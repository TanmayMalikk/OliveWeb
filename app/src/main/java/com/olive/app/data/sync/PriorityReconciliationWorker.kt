// Schedules priority reconciliation and persists or reports its progress.
package com.olive.app.data.sync

import android.content.Context
import android.os.Build
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
import com.olive.app.data.SafetyAccessIndexStore
import com.olive.app.data.SafetyPlanStore
import com.olive.app.data.media.OfflineMediaCache
import com.olive.app.security.DeviceStore
import com.olive.app.security.EncryptedVault
import com.olive.app.security.JsonVault
import com.olive.app.security.child
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject

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
            if (runner.reconcile(uid, target) == ReconciliationOutcome.COMPLETE) {
                SyncCompletionEvents.publish(uid)
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: FirebaseAuthInvalidUserException) {
            revoke(uid, checkpoints, auth)
        } catch (failure: FirebaseFirestoreException) {
            when (failure.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
                FirebaseFirestoreException.Code.UNAUTHENTICATED -> try {
                    AccountAccessReconciler(auth).reconcile(uid)
                    Result.failure()
                } catch (_: FirebaseAuthInvalidUserException) {
                    revoke(uid, checkpoints, auth)
                } catch (_: Exception) {
                    Result.retry()
                }
                else -> Result.retry()
            }
        } catch (_: FirebaseNetworkException) {
            Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun revoke(
        uid: String,
        checkpoints: SyncCheckpointDataSource,
        auth: FirebaseAuth,
    ): Result {
        if (auth.currentUser?.uid?.let { it != uid } == true) return Result.success()
        purgeRevokedAccount(uid, checkpoints)
        if (auth.currentUser?.uid == uid) auth.signOut()
        AccountRevocationEvents.publish(uid)
        return Result.success()
    }

    private fun purgeRevokedAccount(uid: String, checkpoints: SyncCheckpointDataSource) {
        MessageOutboxStore(applicationContext).purge(uid)
        ChatCacheStore(applicationContext).purge(uid)
        DocumentIndexStore(applicationContext).purge(uid)
        OfflineMediaCache(applicationContext).purge(uid)
        SafetyPlanStore(applicationContext, false).purge(uid)
        SafetyAccessIndexStore(applicationContext).purge(uid)
        checkpoints.purge(uid)
        DeviceStore(
            EncryptedVault(applicationContext, "firebase"),
            EncryptedVault(applicationContext, "firebase-activity"),
        ).forgetDevice(expectedUid = uid)
    }

    companion object {
        private const val INPUT_UID = "uid"

        private val scheduledRevisions = ConcurrentHashMap<String, Long>()

        fun enqueue(
            context: Context,
            uid: String,
            revision: Long? = null,
            expedited: Boolean = false,
        ) {
            if (!BuildConfig.FIREBASE_CONFIGURED || uid.isBlank()) return
            if (revision != null) {
                var accepted = false
                scheduledRevisions.compute(uid) { _, previous ->
                    if (previous == null || revision > previous) {
                        accepted = true
                        revision
                    } else previous
                }
                if (!accepted) return
            }
            val builder = OneTimeWorkRequestBuilder<PriorityReconciliationWorker>()
                .setInputData(Data.Builder().putString(INPUT_UID, uid).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            // Older Android versions require a foreground service for expedited work.
            if (expedited && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            val request = builder.build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "olive-priority-reconciliation-$uid",
                // A running worker may already have read the previous server marker.
                if (revision != null) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context, uid: String) {
            scheduledRevisions.remove(uid)
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork("olive-priority-reconciliation-$uid")
        }
    }
}

// Signed-in accounts: persists account-scoped reconciliation checkpoints in Keystore-protected storage.
internal class SyncCheckpointStore(
    private val vault: JsonVault,
) : SyncCheckpointDataSource {
    constructor(context: Context) : this(EncryptedVault(context.applicationContext, "priority-sync"))

    override fun read(uid: String): SyncCheckpoint {
        require(validUid(uid))
        val value = runCatching { vault.read().optJSONObject(ACCOUNTS)?.optJSONObject(uid) }
            .getOrNull() ?: return SyncCheckpoint()
        return runCatching {
            SyncCheckpoint(
                initialized = value.optBoolean(INITIALIZED, false),
                revisions = SyncRevisions(
                    revision = value.nonNegative(REVISION),
                    account = value.nonNegative(ACCOUNT),
                    safety = value.nonNegative(SAFETY),
                    media = value.nonNegative(MEDIA),
                    conversation = value.nonNegative(CONVERSATION),
                ),
            )
        }.getOrElse {
            purge(uid)
            SyncCheckpoint()
        }
    }

    override fun write(uid: String, checkpoint: SyncCheckpoint) {
        require(validUid(uid))
        val revisions = checkpoint.revisions
        vault.update { root ->
            root.child(ACCOUNTS).put(uid, JSONObject()
                .put(INITIALIZED, checkpoint.initialized)
                .put(REVISION, revisions.revision)
                .put(ACCOUNT, revisions.account)
                .put(SAFETY, revisions.safety)
                .put(MEDIA, revisions.media)
                .put(CONVERSATION, revisions.conversation))
        }
    }

    override fun purge(uid: String) {
        require(validUid(uid))
        runCatching { vault.update { it.optJSONObject(ACCOUNTS)?.remove(uid) } }
    }

    private fun JSONObject.nonNegative(name: String): Long = getLong(name).also { require(it >= 0) }
    private fun validUid(uid: String) = uid.isNotBlank() && uid.length <= 128

    private companion object {
        const val ACCOUNTS = "accounts"
        // Older versions could mark the baseline initialized before all domains completed.
        const val INITIALIZED = "baselineComplete"
        const val REVISION = "revision"
        const val ACCOUNT = "accountRevision"
        const val SAFETY = "safetyRevision"
        const val MEDIA = "mediaRevision"
        const val CONVERSATION = "conversationRevision"
    }
}

// Signed-in accounts: lets active screens reload encrypted local results without adding backend listeners.
internal object SyncCompletionEvents {
    private val mutableEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events = mutableEvents.asSharedFlow()
    fun publish(uid: String) { mutableEvents.tryEmit(uid) }
}

// Signed-in accounts: closes an active private session after authoritative account revocation.
internal object AccountRevocationEvents {
    private val mutableEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val events = mutableEvents.asSharedFlow()
    fun publish(uid: String) { mutableEvents.tryEmit(uid) }
}
