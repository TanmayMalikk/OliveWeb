package com.olive.app.data.media

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Deletes expired, unpinned upload-reuse ciphertext even if no document screen is opened. */
class OfflineMediaCleanupWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = runCatching {
        OfflineMediaCache(applicationContext).cleanupExpiredCandidates()
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        fun enqueue(context: Context, candidateId: String) {
            val request = OneTimeWorkRequestBuilder<OfflineMediaCleanupWorker>()
                .setInitialDelay(OfflineMediaCache.UPLOAD_REUSE_WINDOW_MS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "offline_media_cleanup_$candidateId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
