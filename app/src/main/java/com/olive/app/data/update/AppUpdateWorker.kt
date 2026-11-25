// All app roles: checks for a release update and prepares it without interrupting the current screen.
package com.olive.app.data.update

import android.app.NotificationManager
import android.content.Context
import androidx.work.*
import com.google.firebase.messaging.FirebaseMessaging
import com.olive.app.BuildConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

// Topic membership belongs to the installation and applies to every signed-in role.
class AppUpdateRegistrationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    /**
     * Executes the scheduled WorkManager background task and returns the resulting work status.
     *
     * @return WorkManager [Result] indicating success, retry, or failure.
     */
    override suspend fun doWork(): Result = try {
        val messaging = FirebaseMessaging.getInstance()
        messaging.register().await()
        messaging.subscribeToTopic(AppUpdateScheduler.TOPIC_APP_UPDATES).await()
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        Result.retry()
    }
}

// Checks for a release update in the background and prepares it without interrupting the current screen.
internal class AppUpdateWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    /**
     * A failed network check is retried later; a malformed or untrusted release is never installed.
     *
     * @return WorkManager [Result] indicating success, retry, or failure.
     */
    override suspend fun doWork(): Result = runCatching {
        val client = AppUpdateClient(applicationContext)
        val release = AppUpdateStore(applicationContext).refresh(client)
        if (release.versionCode <= BuildConfig.VERSION_CODE) return Result.success()
        if (inputData.getBoolean(CHECK_ONLY, false)) return Result.success()
        client.prepare(release)
        Result.success()
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        if (runAttemptCount < 3 && error is java.io.IOException) Result.retry() else Result.failure()
    }

    companion object {
        const val CHECK_ONLY = "check_only"
    }
}

// Schedules periodic background update checks over unmetered Wi-Fi and handles FCM release broadcasts.
internal object AppUpdateScheduler {
    const val TOPIC_APP_UPDATES = "app_updates"
    private const val LEGACY_UPDATE_NOTIFICATION = 7401

    /**
     * Checks once after startup and then once per day on an unmetered connection because the APK is large.
     *
     * @param context Android context used to access system services, resources, or storage.
     */
    fun schedule(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(LEGACY_UPDATE_NOTIFICATION)
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
            registerForUpdates(context)
        }
    }

    /**
     * Registers for updates.
     *
     * @param context Android context used to access system services, resources, or storage.
     */
    fun registerForUpdates(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "olive-update-registration", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AppUpdateRegistrationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    /**
     * Immediately enqueues an update check and download when an FCM release signal is received.
     *
     * @param context Android context used to access system services, resources, or storage.
     */
    fun triggerNow(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniqueWork(
            "olive-update-manifest",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AppUpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(AppUpdateWorker.CHECK_ONLY to true)).build(),
        )
        manager.enqueueUniqueWork(
            "olive-update-fcm",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AppUpdateWorker>().setConstraints(constraints).build(),
        )
    }
}
