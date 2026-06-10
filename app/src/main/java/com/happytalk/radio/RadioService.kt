package com.happytalk.radio

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.content.Context
import androidx.core.app.NotificationCompat
import io.appwrite.models.RealtimeSubscription
import kotlinx.coroutines.*

class RadioService : Service() {

    private val DATABASE_ID = "happytalk_db"
    private val CHANNEL_ID  = "HappyTalkRadioServiceChannel"
    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var audioSub:          RealtimeSubscription? = null
    private var player:            MediaPlayer?          = null
    private var lastPlayedTimestamp = 0L
    private var currentChannelName  = ""
    private var deviceId            = ""
    private var isMuted             = false
    private var isOfflineMode       = false

    private val udpListener = UdpAudioListener()

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    companion object {
        const val ACTION_SET_MUTE = "com.happytalk.radio.ACTION_SET_MUTE"
        const val EXTRA_MUTED     = "isMuted"
        
        const val ACTION_SET_OFFLINE_MODE = "com.happytalk.radio.ACTION_SET_OFFLINE_MODE"
        const val EXTRA_OFFLINE_MODE      = "isOfflineMode"
    }

    override fun onCreate() {
        super.onCreate()
        lastPlayedTimestamp = System.currentTimeMillis()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HappyTalkRadio::ServiceWakeLock")
        wakeLock?.acquire()

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "HappyTalkRadio::ServiceWifiLock")
        wifiLock?.acquire()
        
        multicastLock = wifiManager.createMulticastLock("HappyTalkRadio::MulticastLock")
        multicastLock?.setReferenceCounted(true)
        multicastLock?.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_PLAY_FCM_AUDIO") {
            val audioUrl = intent.getStringExtra("audioUrl")
            if (!audioUrl.isNullOrEmpty()) {
                currentChannelName = intent.getStringExtra("channelName") ?: currentChannelName
                CoroutineScope(Dispatchers.IO).launch {
                    downloadAndPlay(audioUrl)
                }
            }
            return START_STICKY
        }
        if (intent?.action == ACTION_SET_MUTE) {
            isMuted = intent.getBooleanExtra(EXTRA_MUTED, false)
            Log.i("RadioService", "Mute state updated: $isMuted")
            return START_STICKY
        }
        
        if (intent?.action == ACTION_SET_OFFLINE_MODE) {
            isOfflineMode = intent.getBooleanExtra(EXTRA_OFFLINE_MODE, false)
            Log.i("RadioService", "Offline Mode updated: $isOfflineMode")
            toggleOfflineMode()
            return START_STICKY
        }

        val prefs = getSharedPreferences("ThemePrefs", Context.MODE_PRIVATE)
        currentChannelName = intent?.getStringExtra("channelName") ?: prefs.getString("currentChannelName", "family_roadtrip") ?: "family_roadtrip"
        deviceId           = intent?.getStringExtra("deviceId") ?: prefs.getString("deviceId", "") ?: ""
        if (deviceId.isEmpty()) return START_NOT_STICKY

        isMuted            = intent?.getBooleanExtra(EXTRA_MUTED, prefs.getBoolean("isMuted", false)) ?: prefs.getBoolean("isMuted", false)
        isOfflineMode      = intent?.getBooleanExtra(EXTRA_OFFLINE_MODE, prefs.getBoolean("isOfflineMode", false)) ?: prefs.getBoolean("isOfflineMode", false)

        AppwriteManager.init(this)
        createNotificationChannel()

        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HappyTalk Radio")
            .setContentText("Listening to $currentChannelName")
            .setSmallIcon(R.drawable.ptt_ring)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else
            startForeground(1, notification)

        toggleOfflineMode()
        return START_STICKY
    }
    
    private fun toggleOfflineMode() {
        if (isOfflineMode) {
            Log.i("RadioService", "Switching to Offline Mode (LAN UDP)")
            audioSub?.close(); audioSub = null
            udpListener.startListening()
        } else {
            Log.i("RadioService", "Switching to Online Mode (Appwrite Realtime)")
            udpListener.stopListening()
            subscribeToAudioMessages()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "HappyTalk Radio", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun subscribeToAudioMessages() {
        audioSub = AppwriteManager.realtime.subscribe(
            "databases.$DATABASE_ID.collections.audio_messages.documents"
        ) { event ->
            val doc  = event.payload as? Map<*, *> ?: return@subscribe
            val chan  = doc["channelName"] as? String ?: return@subscribe
            val sender  = doc["senderId"]  as? String ?: return@subscribe
            val url     = doc["audioUrl"]  as? String ?: return@subscribe
            val ts      = (doc["timestamp"] as? Number)?.toLong() ?: 0L

            if (chan == currentChannelName && sender != deviceId && ts > lastPlayedTimestamp && url.isNotEmpty()) {
                lastPlayedTimestamp = ts
                if (!isMuted) {
                    Handler(Looper.getMainLooper()).post { playAudio(url) }
                } else {
                    Log.i("RadioService", "Incoming audio suppressed — muted")
                }
            }
        }
        Log.i("RadioService", "Subscribed to audio for channel: $currentChannelName")
    }

    private fun playAudio(url: String) {
        player?.runCatching { if (isPlaying) stop(); release() }
        player = null
        try {
            player = MediaPlayer().apply {
                setDataSource(url)
                prepareAsync()
                setOnPreparedListener { it.start() }
                setOnCompletionListener { 
                    it.release()
                    player = null
                }
                setOnErrorListener { mp, w, e -> 
                    Log.e("RadioService", "Player error $w/$e")
                    mp.release()
                    player = null
                    true 
                }
            }
        } catch (e: Exception) { 
            Log.e("RadioService", "Failed to play audio", e)
        }
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        multicastLock?.let { if (it.isHeld) it.release() }

        udpListener.stopListening()
        audioSub?.close(); audioSub = null
        player?.runCatching { if (isPlaying) stop(); release() }; player = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
