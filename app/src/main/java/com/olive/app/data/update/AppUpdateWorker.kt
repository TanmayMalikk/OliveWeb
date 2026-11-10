// All app roles: checks for a release update and prepares it without interrupting the current screen.
package com.olive.app.data.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.google.firebase.messaging.FirebaseMessaging
import com.olive.app.BuildConfig
import com.olive.app.R
import com.olive.app.notification.OliveNotificationManager
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

// Checks for a release update in the background and prepares it without interrupting the current screen.
internal class AppUpdateWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    // A failed network check is retried later; a malformed or untrusted release is never installed.
    override suspend fun doWork(): Result = runCatching {
        val client = AppUpdateClient(applicationContext)
        val release = client.fetchLatest()
        if (release.versionCode <= BuildConfig.VERSION_CODE) return Result.success()
        client.download(release)
        notifyReady(release)
        Result.success()
    }.getOrElse { error ->
        if (runAttemptCount < 3 && error is java.io.IOException) Result.retry() else Result.failure()
    }

    // The notification opens a small installer screen; Android owns the final confirmation.
    private fun notifyReady(release: AppReleaseManifest) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        OliveNotificationManager.createNotificationChannels(applicationContext)
        val intent = Intent(applicationContext, AppUpdateInstallerActivity::class.java)
            .putExtra(AppUpdateInstallerActivity.EXTRA_RELEASE_JSON, release.toJson())
        val pending = PendingIntent.getActivity(
            applicationContext,
            release.versionCode.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            NOTIFICATION,
            NotificationCompat.Builder(applicationContext, OliveNotificationManager.CHANNEL_APP_UPDATES)
                .setSmallIcon(R.drawable.ic_notification_olive)
                .setContentTitle(applicationContext.getString(R.string.ui_a_new_update_is_available))
                .setContentText(release.versionName)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun AppReleaseManifest.toJson() = JSONObject().apply {
        put("packageName", packageName)
        put("versionCode", versionCode)
        put("versionName", versionName)
        put("minimumSupportedVersionCode", minimumSupportedVersionCode)
        put("apkUrl", apkUrl)
        put("sha256", sha256)
        put("sizeBytes", sizeBytes)
        put("publishedAt", publishedAt)
        put("mandatory", mandatory)
        put("releaseNotes", JSONArray(releaseNotes))
    }.toString()

    companion object {
        private const val NOTIFICATION = 7401
    }
}

// Schedules periodic background update checks over unmetered Wi-Fi and handles FCM release broadcasts.
internal object AppUpdateScheduler {
    const val TOPIC_APP_UPDATES = "app_updates"

    // Checks once after startup and then once per day on an unmetered connection because the APK is large.
    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniqueWork(
            "olive-update-startup",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AppUpdateWorker>().setConstraints(constraints).build(),
        )
        manager.enqueueUniquePeriodicWork(
            "olive-update-daily",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AppUpdateWorker>(24, TimeUnit.HOURS).setConstraints(constraints).build(),
        )
        if (BuildConfig.FIREBASE_CONFIGURED) {
            runCatching { FirebaseMessaging.getInstance().subscribeToTopic(TOPIC_APP_UPDATES) }
        }
    }

    // Immediately enqueues an update check and download when an FCM release alert is received.
    fun triggerNow(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "olive-update-fcm",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AppUpdateWorker>().setConstraints(constraints).build(),
        )
    }
}

