package com.happytalk.radio

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.app.DownloadManager
import android.content.res.Configuration
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton
import android.view.animation.AnimationUtils
import android.view.animation.ScaleAnimation
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import io.appwrite.ID
import io.appwrite.Query
import io.appwrite.exceptions.AppwriteException
import io.appwrite.models.RealtimeSubscription
import io.appwrite.models.InputFile
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // ─── Appwrite ─────────────────────────────────────────────────────────────
    private val DATABASE_ID    = "happytalk_db"
    private val STORAGE_BUCKET = "audio_messages"
    private val AUTO_DELETE_MS = 20_000L
    private val scope          = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var channelStateSub: RealtimeSubscription? = null
    private var presenceSub:     RealtimeSubscription? = null

    // ─── UI ───────────────────────────────────────────────────────────────────
    private lateinit var btnPtt:         MaterialButton
    private lateinit var btnThemeToggle: ImageButton
    private lateinit var tvInstruction:  TextView
    private lateinit var tvStatus:       TextView
    private lateinit var tvUserCount:    TextView
    private lateinit var ivPttRing:      android.widget.ImageView
    private lateinit var etChannelName:  EditText
    private lateinit var btnJoinChannel: android.view.View
    private lateinit var switchMute:     com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var switchOffline:  com.google.android.material.switchmaterial.SwitchMaterial

    // ─── State ────────────────────────────────────────────────────────────────
    private lateinit var prefs:    SharedPreferences
    private lateinit var deviceId: String
    private var isDarkMode         = false
    private var isMuted            = false
    private var isOfflineMode      = false
    private var currentChannelName = "family_roadtrip"
    private var currentAudioFile   = ""
    private var lastPlayedTimestamp = 0L
    private var isChannelBusy      = false
    private var isRecordingState   = false
    private var recorder: MediaRecorder? = null
    
    private var chunkingJob: Job? = null
    private var currentChunkPath: String? = null
    
    private val udpBroadcaster = UdpAudioBroadcaster()

    private val mainHandler     = Handler(Looper.getMainLooper())
    private val presenceHandler = Handler(Looper.getMainLooper())
    private var presenceRunnable: Runnable? = null
    private var pulseAnimator: AnimatorSet? = null

    // Auto Updater receiver
    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (downloadId != -1L) {
                    // Assuming installApk logic exists elsewhere or handled via intent
                    val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = dm.query(query)
                    if (cursor.moveToFirst()) {
                        val uri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                        if (uri != null) {
                            val installIntent = Intent(Intent.ACTION_VIEW)
                            installIntent.setDataAndType(Uri.parse(uri), "application/vnd.android.package-archive")
                            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            startActivity(installIntent)
                        }
                    }
                    cursor.close()
                }
            }
        }
    }

    companion object { private const val PERM_REQ = 200 }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences("ThemePrefs", MODE_PRIVATE)
        isDarkMode = if (prefs.contains("isDarkMode")) prefs.getBoolean("isDarkMode", false)
        else (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        AppCompatDelegate.setDefaultNightMode(
            if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceId = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString()
            .replace("-", "_").also {
                prefs.edit().putString("deviceId", it).apply()
            }

        currentChannelName = prefs.getString("currentChannelName", "family_roadtrip") ?: "family_roadtrip"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }

        lastPlayedTimestamp = System.currentTimeMillis()
        currentAudioFile    = externalCacheDir!!.absolutePath + "/happytalk_audio.3gp"

        btnPtt         = findViewById(R.id.btnPtt)
        btnThemeToggle = findViewById(R.id.btnThemeToggle)
        tvInstruction  = findViewById(R.id.tvInstruction)
        tvStatus       = findViewById(R.id.tvStatus)
        tvUserCount    = findViewById(R.id.tvUserCount)
        ivPttRing      = findViewById(R.id.ivPttRing)
        etChannelName  = findViewById(R.id.etChannelName)
        btnJoinChannel = findViewById(R.id.btnJoinChannel)
        switchMute     = findViewById(R.id.switchMute)
        switchOffline  = findViewById(R.id.switchOffline)

        etChannelName.setText(currentChannelName)

        // Register Download Receiver for Updater
        registerReceiver(downloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED)
        
        // Check for updates
        scope.launch {
            try {
                val pInfo = packageManager.getPackageInfo(packageName, 0)
                val version = pInfo.versionName ?: "1.0.0"
                GitHubUpdater.checkForUpdates(this@MainActivity, version)
            } catch (e: Exception) {}
        }

        // Restore offline mode state
        isOfflineMode = prefs.getBoolean("isOfflineMode", false)
        switchOffline.isChecked = isOfflineMode
        switchOffline.setOnCheckedChangeListener { _, checked ->
            isOfflineMode = checked
            prefs.edit().putBoolean("isOfflineMode", isOfflineMode).apply()
            
            // Notify RadioService of offline mode change
            val modeIntent = Intent(this, RadioService::class.java).apply {
                action = RadioService.ACTION_SET_OFFLINE_MODE
                putExtra(RadioService.EXTRA_OFFLINE_MODE, isOfflineMode)
            }
            startService(modeIntent)
            Toast.makeText(this,
                if (isOfflineMode) "📡 Offline Mode Enabled (LAN)"
                else "🌍 Online Mode Enabled (Appwrite)",
                Toast.LENGTH_SHORT).show()
        }

        // Restore mute state
        isMuted = prefs.getBoolean("isMuted", false)
        switchMute.isChecked = isMuted
        switchMute.setOnCheckedChangeListener { _, checked ->
            isMuted = checked
            prefs.edit().putBoolean("isMuted", isMuted).apply()
            // Notify RadioService of mute state change
            val muteIntent = Intent(this, RadioService::class.java).apply {
                action = RadioService.ACTION_SET_MUTE
                putExtra(RadioService.EXTRA_MUTED, isMuted)
            }
            startService(muteIntent)
            Toast.makeText(this,
                if (isMuted) "🔇 Muted — incoming audio silenced"
                else "🔊 Unmuted — incoming audio enabled",
                Toast.LENGTH_SHORT).show()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS), PERM_REQ)
        else
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), PERM_REQ)

        AppwriteManager.init(this)
        joinChannel(currentChannelName)

        btnJoinChannel.setOnClickListener {
            val ch = etChannelName.text.toString().trim()
            if (ch.isNotEmpty()) joinChannel(ch)
        }
        
        etChannelName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO || actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND || actionId == android.view.inputmethod.EditorInfo.IME_NULL) {
                val ch = etChannelName.text.toString().trim()
                if (ch.isNotEmpty()) joinChannel(ch)
                true
            } else {
                false
            }
        }

        btnThemeToggle.setImageResource(if (isDarkMode) R.drawable.ic_light_mode else R.drawable.ic_dark_mode)
        btnThemeToggle.setOnClickListener {
            isDarkMode = !isDarkMode
            prefs.edit().putBoolean("isDarkMode", isDarkMode).apply()
            AppCompatDelegate.setDefaultNightMode(
                if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        }

        btnPtt.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (isChannelBusy) { Toast.makeText(this, "Channel is busy!", Toast.LENGTH_SHORT).show(); return@setOnTouchListener false }
                    isRecordingState = true
                    
                    if (!isOfflineMode) {
                        updateChannelState(mapOf("isTransmitting" to true, "activeSenderId" to deviceId))
                    }
                    
                    // ─── Press: scale down for tactile feel ───
                    btnPtt.animate().scaleX(0.92f).scaleY(0.92f).setDuration(120).start()
                    // ─── Start pulsing ring ───
                    startRingPulse()
                    setPttColor(getColor(R.color.ptt_active))
                    btnPtt.text = "កំពុងផ្សាយ\nនិយាយឥឡូវនេះ"; btnPtt.textSize = 30f
                    ivPttRing.setImageResource(R.drawable.ptt_ring_active)
                    tvInstruction.text = if (isOfflineMode) "Broadcasting locally..." else "Broadcasting to $currentChannelName..."
                    tvStatus.text = "🔴 LIVE"
                    
                    if (isOfflineMode) {
                        udpBroadcaster.startBroadcasting(getBroadcastAddress())
                    } else {
                        startMicroChunking()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!isRecordingState) return@setOnTouchListener false
                    isRecordingState = false
                    
                    if (!isOfflineMode) {
                        clearChannelBusy()
                    }
                    
                    // ─── Release: spring back with stronger overshoot ───
                    btnPtt.animate().scaleX(1f).scaleY(1f).setDuration(300)
                        .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
                    // ─── Stop pulsing ring ───
                    stopRingPulse()
                    btnPtt.isEnabled = true
                    setPttColor(getColor(R.color.ptt_idle))
                    btnPtt.text = "ចុច ដើម្បីនិយាយ"; btnPtt.textSize = 36f
                    ivPttRing.setImageResource(R.drawable.ptt_ring)
                    tvInstruction.text = "Safe driving! Keep eyes on the road."
                    tvStatus.text = "🩷 $currentChannelName"
                    if (isOfflineMode) {
                        udpBroadcaster.stopBroadcasting()
                    } else {
                        stopMicroChunking()
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(downloadReceiver)
        } catch (e: Exception) {}
        unsubscribeAll()
        presenceRunnable?.let { presenceHandler.removeCallbacks(it) }
        scope.launch {
            runCatching {
                val id = (currentChannelName + deviceId).take(36).replace("-", "_")
                AppwriteManager.databases.deleteDocument(DATABASE_ID, "presence", id)
            }
        }
        recorder?.runCatching { stop(); release() }
        recorder = null
        scope.cancel()
    }

    // ─── Channel management ───────────────────────────────────────────────────

    private fun playChannelSetSound() {
        Thread {
            try {
                val toneGen = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)
                toneGen.startTone(android.media.ToneGenerator.TONE_DTMF_A, 50)
                Thread.sleep(60)
                toneGen.startTone(android.media.ToneGenerator.TONE_DTMF_B, 50)
                Thread.sleep(60)
                toneGen.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun joinChannel(channelName: String) {
        if (channelName.isBlank()) return
        
        prefs.edit().putString("currentChannelName", channelName).apply()

        playChannelSetSound()
        currentChannelName = channelName
        tvStatus.text      = "🩷 $currentChannelName"
        ivPttRing.setImageResource(R.drawable.ptt_ring)
        etChannelName.clearFocus()

        unsubscribeAll()
        presenceRunnable?.let { presenceHandler.removeCallbacks(it) }

        scope.launch { runCatching { cleanupStaleBusy() } }
        subscribeToChannelState()
        subscribeToPresence()
        startPresenceHeartbeat()

        val svc = Intent(this, RadioService::class.java).apply {
            putExtra("channelName", currentChannelName)
            putExtra("deviceId", deviceId)
            putExtra(RadioService.EXTRA_MUTED, isMuted)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
        Toast.makeText(this, "Joined: $channelName", Toast.LENGTH_SHORT).show()
    }

    private suspend fun cleanupStaleBusy() {
        try {
            val doc = AppwriteManager.databases.getDocument(DATABASE_ID, "channel_states", currentChannelName)
            val transmitting = doc.data["isTransmitting"] as? Boolean ?: false
            if (!transmitting) return
            val sender = doc.data["activeSenderId"] as? String ?: ""
            val ts = (doc.data["timestamp"] as? Number)?.toLong() ?: 0L
            val now = System.currentTimeMillis()
            if (deviceId == sender || (ts > 0 && now - ts > 120_000)) {
                AppwriteManager.databases.updateDocument(DATABASE_ID, "channel_states", currentChannelName,
                    mapOf("isTransmitting" to false, "activeSenderId" to "", "timestamp" to now, "channelName" to currentChannelName))
                Log.i("Appwrite", "Cleared stale lock")
            }
        } catch (e: AppwriteException) {
            if (e.code != 404) Log.e("Appwrite", "cleanupStaleBusy", e)
        }
    }

    private fun startPresenceHeartbeat() {
        presenceRunnable = object : Runnable {
            override fun run() {
                scope.launch {
                    val id = (currentChannelName + deviceId).take(36).replace("-", "_")
                    val data = mapOf("channelName" to currentChannelName, "deviceId" to deviceId,
                        "lastSeen" to System.currentTimeMillis())
                    try {
                        AppwriteManager.databases.updateDocument(DATABASE_ID, "presence", id, data)
                    } catch (e: AppwriteException) {
                        if (e.code == 404)
                            AppwriteManager.databases.createDocument(DATABASE_ID, "presence", id, data,
                                listOf("read(\"any\")", "write(\"any\")"))
                        else Log.e("Appwrite", "Heartbeat", e)
                    }
                }
                presenceHandler.postDelayed(this, 15_000)
            }
        }
        presenceHandler.post(presenceRunnable!!)
    }

    // ─── Realtime subscriptions ───────────────────────────────────────────────

    private fun subscribeToChannelState() {
        channelStateSub = AppwriteManager.realtime.subscribe(
            "databases.$DATABASE_ID.collections.channel_states.documents"
        ) { event ->
            val doc = event.payload as? Map<*, *> ?: return@subscribe
            if (doc["channelName"] != currentChannelName) return@subscribe
            val json = JSONObject().apply {
                put("isTransmitting", doc["isTransmitting"])
                put("activeSenderId", doc["activeSenderId"])
                put("timestamp",      doc["timestamp"])
            }
            mainHandler.post { processChannelState(json) }
        }
    }

    private fun subscribeToPresence() {
        presenceSub = AppwriteManager.realtime.subscribe(
            "databases.$DATABASE_ID.collections.presence.documents"
        ) { _ ->
            scope.launch {
                try {
                    val docs = AppwriteManager.databases.listDocuments(DATABASE_ID, "presence",
                        listOf(Query.equal("channelName", currentChannelName)))
                    val now = System.currentTimeMillis()
                    val count = docs.documents.count { d ->
                        ((d.data["lastSeen"] as? Number)?.toLong() ?: 0L).let { now - it < 30_000 }
                    }.coerceAtLeast(1)
                    val displayCount = if (count > 99) "99+" else count.toString()
                    mainHandler.post { tvUserCount.text = "👥 $displayCount" }
                } catch (e: Exception) { Log.e("Appwrite", "Presence count", e) }
            }
        }
    }

    private fun unsubscribeAll() {
        channelStateSub?.close(); channelStateSub = null
        presenceSub?.close();     presenceSub     = null
    }

    // ─── Database writes ──────────────────────────────────────────────────────

    private fun updateChannelState(extra: Map<String, Any>, onDone: (() -> Unit)? = null) {
        scope.launch {
            val data = extra + mapOf("channelName" to currentChannelName, "timestamp" to System.currentTimeMillis())
            try {
                try { AppwriteManager.databases.updateDocument(DATABASE_ID, "channel_states", currentChannelName, data) }
                catch (e: AppwriteException) {
                    if (e.code == 404)
                        AppwriteManager.databases.createDocument(DATABASE_ID, "channel_states", currentChannelName, data,
                            listOf("read(\"any\")", "write(\"any\")"))
                    else throw e
                }
                onDone?.let { mainHandler.post(it) }
            } catch (e: Exception) {
                Log.e("Appwrite", "updateChannelState", e)
                if (e is AppwriteException && (e.code == 503 || e.code == 504))
                    mainHandler.post { Toast.makeText(this@MainActivity, "Network error.", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun clearChannelBusy() = updateChannelState(mapOf("isTransmitting" to false, "activeSenderId" to ""))

    // ─── UI ───────────────────────────────────────────────────────────────────

    private fun processChannelState(s: JSONObject) {
        val transmitting = s.optBoolean("isTransmitting", false)
        val sender       = s.optString("activeSenderId", "")
        if (transmitting && sender.isNotEmpty() && sender != deviceId) {
            isChannelBusy = true
            btnPtt.isEnabled = false
            setPttColor(android.graphics.Color.GRAY)
            btnPtt.text = "CHANNEL\nBUSY"
            ivPttRing.setImageResource(R.drawable.ptt_ring_active)
            tvStatus.text = "🔴 Busy"
        } else {
            isChannelBusy = false
            btnPtt.isEnabled = true
            if (!isRecordingState) {
                setPttColor(getColor(R.color.ptt_idle))
                btnPtt.text = "ចុច ដើម្បីនិយាយ"; btnPtt.textSize = 36f
                ivPttRing.setImageResource(R.drawable.ptt_ring)
                tvStatus.text = "🩷 $currentChannelName"
            }
        }
    }

    private fun setPttColor(color: Int) { btnPtt.backgroundTintList = ColorStateList.valueOf(color) }

    // ─── PTT ring pulse animation ─────────────────────────────────────────────

    private fun startRingPulse() {
        stopRingPulse() // Cancel any existing animation first

        // Ring: outward radiating ripple effect
        val ringScaleX = ObjectAnimator.ofFloat(ivPttRing, "scaleX", 0.9f, 1.5f).apply {
            duration = 1200
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
        }
        val ringScaleY = ObjectAnimator.ofFloat(ivPttRing, "scaleY", 0.9f, 1.5f).apply {
            duration = 1200
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
        }
        val ringFade = ObjectAnimator.ofFloat(ivPttRing, "alpha", 0.8f, 0f).apply {
            duration = 1200
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
        }

        // Button: heartbeat pulse (independent from ring)
        val btnScaleX = ObjectAnimator.ofFloat(btnPtt, "scaleX", 0.92f, 0.97f).apply {
            duration = 500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        val btnScaleY = ObjectAnimator.ofFloat(btnPtt, "scaleY", 0.92f, 0.97f).apply {
            duration = 500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }

        pulseAnimator = AnimatorSet().apply {
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            playTogether(ringScaleX, ringScaleY, ringFade, btnScaleX, btnScaleY)
            start()
        }
    }

    private fun stopRingPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        // Reset ring to default state
        ivPttRing.scaleX = 1f
        ivPttRing.scaleY = 1f
        ivPttRing.alpha  = 1f
        // Reset button to default state
        btnPtt.scaleX = 1f
        btnPtt.scaleY = 1f
    }

    // ─── Audio recording ──────────────────────────────────────────────────────

    private fun startMicroChunking() {
        var chunkIndex = 0
        chunkingJob = scope.launch(Dispatchers.Main) {
            while (isActive) {
                currentChunkPath = externalCacheDir!!.absolutePath + "/chunk_${chunkIndex++}.3gp"
                
                recorder = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                    setOutputFile(currentChunkPath)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                    try { prepare(); start() }
                    catch (e: Exception) { Log.e("AudioRecord", "prepare chunk failed", e) }
                }
                
                delay(1500)
                
                val pathToUpload = currentChunkPath!!
                val r = recorder
                try { r?.stop() } catch (_: Exception) {}
                try { r?.release() } catch (_: Exception) {}
                recorder = null
                
                uploadAudio(pathToUpload)
            }
        }
    }

    private fun stopMicroChunking() {
        chunkingJob?.cancel()
        chunkingJob = null
        
        val r = recorder
        val pathToUpload = currentChunkPath
        try { r?.stop() } catch (_: Exception) {}
        try { r?.release() } catch (_: Exception) {}
        recorder = null
        
        if (pathToUpload != null) {
            uploadAudio(pathToUpload)
        }
        mainHandler.post { tvStatus.text = "🩷 $currentChannelName" }
    }

    // ─── Appwrite Storage upload ──────────────────────────────────────────────

    private fun uploadAudio(filePath: String) {
        mainHandler.post { tvStatus.text = "📡 Uploading..." }
        scope.launch {
            try {
                val file = File(filePath)
                if (!file.exists()) { mainHandler.post { tvStatus.text = "❌ Audio file missing" }; return@launch }

                val uploaded = AppwriteManager.storage.createFile(
                    bucketId   = STORAGE_BUCKET,
                    fileId     = ID.unique(),
                    file       = InputFile.fromFile(file),
                    permissions = listOf("read(\"any\")")
                )

                val audioUrl = "${AppwriteManager.ENDPOINT}/storage/buckets/$STORAGE_BUCKET" +
                        "/files/${uploaded.id}/view?project=${AppwriteManager.PROJECT_ID}"

                val docId = ID.unique()
                AppwriteManager.databases.createDocument(
                    databaseId   = DATABASE_ID,
                    collectionId = "audio_messages",
                    documentId   = docId,
                    data = mapOf(
                        "channelName" to currentChannelName,
                        "senderId"    to deviceId,
                        "audioUrl"    to audioUrl,
                        "fileId"      to uploaded.id,
                        "timestamp"   to System.currentTimeMillis()
                    ),
                    permissions = listOf("read(\"any\")", "write(\"any\")")
                )

                Log.i("Appwrite", "Audio uploaded: $audioUrl")
                mainHandler.post { tvStatus.text = "🩷 $currentChannelName" }

                // Schedule reliable auto-delete after 60s via WorkManager (survives app close)
                val deleteWork = OneTimeWorkRequestBuilder<AudioDeleteWorker>()
                    .setInitialDelay(AUTO_DELETE_MS, TimeUnit.MILLISECONDS)
                    .setInputData(
                        Data.Builder()
                            .putString(AudioDeleteWorker.KEY_FILE_ID, uploaded.id)
                            .putString(AudioDeleteWorker.KEY_DOC_ID,  docId)
                            .putString(AudioDeleteWorker.KEY_BUCKET,  STORAGE_BUCKET)
                            .putString(AudioDeleteWorker.KEY_DB_ID,   DATABASE_ID)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(this@MainActivity).enqueue(deleteWork)
                Log.i("Appwrite", "Auto-delete scheduled in 60s for file=${uploaded.id}")

            } catch (e: Exception) {
                Log.e("Appwrite", "Upload failed", e)
                mainHandler.post { tvStatus.text = "⚠️ Upload failed: ${e.message}" }
            }
        }
    }

    // ─── Permissions ──────────────────────────────────────────────────────────

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(req, perms, results)
        if (req == PERM_REQ && (results.isEmpty() || results[0] != PackageManager.PERMISSION_GRANTED))
            Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
    }
    
    private fun getBroadcastAddress(): InetAddress {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null) {
                        return broadcast
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to calculate broadcast address via NetworkInterface", e)
        }
        return InetAddress.getByName("255.255.255.255")
    }
    private fun installApk(downloadId: Long) {
        try {
            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = downloadManager.getUriForDownloadedFile(downloadId)
            if (uri != null) {
                val install = Intent(Intent.ACTION_VIEW)
                install.setDataAndType(uri, "application/vnd.android.package-archive")
                install.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                startActivity(install)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to install APK", e)
        }
    }
}
