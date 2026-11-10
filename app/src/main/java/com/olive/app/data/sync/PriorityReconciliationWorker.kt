// Signed-in accounts: performs durable, sequential priority reconciliation after connectivity returns.
package com.olive.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.firestore.FirebaseFirestoreException
import com.olive.app.BuildConfig
import com.olive.app.data.ChatCacheStore
import com.olive.app.data.DocumentIndexStore
import com.olive.app.data.MessageOutboxStore
import com.olive.app.data.SafetyPlanStore
import com.olive.app.data.SafetyAccessIndexStore
import com.olive.app.data.media.OfflineMediaCache
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class PriorityReconciliationWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!BuildConfig.FIREBASE_CONFIGURED) return Result.success()
        val uid = inputData.getString(INPUT_UID)?.takeIf(String::isNotBlank) ?: return Result.failure()
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser?.uid != uid) return Result.success()
        val checkpoints = SyncCheckpointStore(applicationContext)
        return try {
            val target = SyncInvalidationRepository().get(uid) ?: SyncRevisions()
            val runner = PriorityReconciliationRunner(
                checkpoints = checkpoints,
                activeUid = { auth.currentUser?.uid },
                reconcilers = mapOf(
                    SyncDomain.ACCOUNT to AccountAccessReconciler(auth),
                    SyncDomain.SAFETY to SafetyPlanReconciler(applicationContext, auth),
                    SyncDomain.MEDIA to DocumentAccessReconciler(applicationContext),
                    SyncDomain.CONVERSATION to ConversationAccessReconciler(applicationContext),
                ),
            )
            runner.reconcile(uid, target)
            SyncCompletionEvents.publish(uid)
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: FirebaseAuthInvalidUserException) {
            purgeRevokedAccount(uid, checkpoints)
            auth.signOut()
            Result.success()
        } catch (failure: FirebaseFirestoreException) {
            when (failure.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
                FirebaseFirestoreException.Code.UNAUTHENTICATED -> Result.failure()
                else -> Result.retry()
            }
        } catch (_: FirebaseNetworkException) {
            Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun purgeRevokedAccount(uid: String, checkpoints: SyncCheckpointDataSource) {
        MessageOutboxStore(applicationContext).purge(uid)
        ChatCacheStore(applicationContext).purge(uid)
        DocumentIndexStore(applicationContext).purge(uid)
        OfflineMediaCache(applicationContext).purge(uid)
        SafetyPlanStore(applicationContext, false).purge(uid)
        SafetyAccessIndexStore(applicationContext).purge(uid)
        checkpoints.purge(uid)
    }

    companion object {
        private const val INPUT_UID = "uid"

        fun enqueue(context: Context, uid: String, expedited: Boolean = false) {
            if (!BuildConfig.FIREBASE_CONFIGURED || uid.isBlank()) return
            val builder = OneTimeWorkRequestBuilder<PriorityReconciliationWorker>()
                .setInputData(Data.Builder().putString(INPUT_UID, uid).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            if (expedited) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            val request = builder.build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "olive-priority-reconciliation-$uid",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request,
            )
        }

        fun cancel(context: Context, uid: String) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork("olive-priority-reconciliation-$uid")
        }
    }
}
