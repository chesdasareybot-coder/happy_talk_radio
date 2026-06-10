package com.happytalk.radio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FcmListenerService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Store token in prefs to be uploaded later when joining a channel
        getSharedPreferences("RadioPrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("fcmToken", token)
            .apply()
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        
        val type = message.data["type"]
        if (type == "WAKE_UP") {
            val channelName = message.data["channelName"] ?: "Unknown Channel"
            val senderName = message.data["senderName"] ?: "Someone"
            val audioUrl = message.data["audioUrl"]

            // Wake up the device CPU temporarily
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "HappyTalk:FcmWakeLock"
            )
            wakeLock.acquire(10 * 1000L) // 10 seconds max

            // We can show a high-priority notification to wake the screen
            showWakeUpNotification(channelName, senderName)

            // If we have an audio URL, we could potentially pass it to RadioService to play.
            // But since RadioService might already be running, we can just broadcast an intent,
            // or start RadioService if it's not running.
            val serviceIntent = Intent(this, RadioService::class.java).apply {
                action = "ACTION_PLAY_FCM_AUDIO"
                putExtra("audioUrl", audioUrl)
                putExtra("channelName", channelName)
            }
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (e: Exception) {
                // If we are in background and Android blocks startForegroundService
                e.printStackTrace()
            }
        }
    }

    private fun showWakeUpNotification(channelName: String, senderName: String) {
        val channelId = "happytalk_wakeup_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "HappyTalk Wake-Up Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when someone is talking in your channel"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, SplashActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingIntentFlags)

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.logo)
            .setContentTitle("Incoming Radio Transmission")
            .setContentText("🔴 $senderName is speaking on $channelName")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setFullScreenIntent(pendingIntent, true) // Wakes the screen
            .build()

        notificationManager.notify(2001, notification)
    }
}
