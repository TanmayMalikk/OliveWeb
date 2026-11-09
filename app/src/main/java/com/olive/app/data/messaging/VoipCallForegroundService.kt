package com.olive.app.data.messaging

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.olive.app.R

// Keeps an active voice call alive when the user leaves Olive Branch or turns off the screen.
class VoipCallForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.ui_voice_call),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = getString(R.string.ui_call_running_in_background)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // Android requires the foreground notification immediately after the call service starts.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val returnToApp = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = returnToApp?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_olive_branch)
            .setContentTitle(getString(R.string.ui_voice_call))
            .setContentText(getString(R.string.ui_call_running_in_background))
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "olive_voice_call"
        private const val NOTIFICATION_ID = 8_107

        // Starts the notification while the call room is connecting or connected.
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VoipCallForegroundService::class.java),
            )
        }

        // Removes the call notification after the room has closed or failed.
        fun stop(context: Context) {
            context.stopService(Intent(context, VoipCallForegroundService::class.java))
        }
    }
}
