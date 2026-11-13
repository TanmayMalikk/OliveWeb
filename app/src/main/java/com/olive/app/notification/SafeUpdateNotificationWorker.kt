// Signed-in users: handles background safe update notification work.
package com.olive.app.notification

import android.content.Context
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.TimeUnit
import kotlin.random.Random

// Coalesces Safe App activity into a notification.
class SafeUpdateNotificationWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {
    override fun doWork(): Result {
        val uid = inputData.getString("uid") ?: return Result.success()
        if (FirebaseAuth.getInstance().currentUser?.uid != uid) return Result.success()
        OliveNotificationManager.showSafeUpdate(applicationContext)
        return Result.success()
    }
}

// Schedules Safe App notifications with a random delay to prevent bursts.
object SafeUpdateNotificationScheduler {
    private const val WORK_NAME = "olive_pending_safe_update_notification"
    internal const val MIN_DELAY_SECONDS = 5L
    internal const val MAX_DELAY_SECONDS = 10L

    // Schedules the background task for safe update notification worker.
    fun schedule(context: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val request = OneTimeWorkRequestBuilder<SafeUpdateNotificationWorker>()
            .setInputData(workDataOf("uid" to uid))
            .setInitialDelay(randomDelaySeconds(), TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    // Cancels the active background notification timer.
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    // Spreads background checks across a small random delay to avoid bursts.
    internal fun randomDelaySeconds(random: Random = Random.Default): Long =
        random.nextLong(MIN_DELAY_SECONDS, MAX_DELAY_SECONDS + 1)
}

