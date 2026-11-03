// Signed-in users: handles background safe update notification work.
package com.olive.app.notification

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit
import kotlin.random.Random

// Safe updates wait a random amount of time, then appear as a normal notification from the chosen cover.
class SafeUpdateNotificationWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {
    // Processes the queued background work for safe update notification worker. It waits for the set time and cancels the timer when the feature closes.
    override fun doWork(): Result {
        if (FirebaseAuth.getInstance().currentUser == null) return Result.success()
        AppNotifications.showRandomSafeUpdate(
            applicationContext,
            LocalNotificationEventHandler.currentCoverType(applicationContext),
            LocalNotificationEventHandler.currentRole(applicationContext),
        )
        return Result.success()
    }
}

object SafeUpdateNotificationScheduler {
    private const val WORK_NAME = "olive_pending_safe_update_notification"

    // TESTING: 5–10 seconds, use a longer window here with TimeUnit.MINUTES.
    internal const val MIN_DELAY_SECONDS = 5L
    internal const val MAX_DELAY_SECONDS = 10L

    // Schedules the background task for safe update notification worker.
    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<SafeUpdateNotificationWorker>()
            .setInitialDelay(randomDelaySeconds(), TimeUnit.SECONDS)
            .build()
        // Several updates arriving together become one discreet notification instead of a burst.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    // Safe update notification worker: Cancels the active task. It asks WorkManager to cancel the uniquely named background task.
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    // Safe update notification worker: Returns the random delay seconds. It selects a random delay inside the allowed range.
    internal fun randomDelaySeconds(random: Random = Random.Default): Long =
        random.nextLong(MIN_DELAY_SECONDS, MAX_DELAY_SECONDS + 1)
}
