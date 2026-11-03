// Signed-in users: handles account and support alerts on the device.
package com.olive.app.notification

import android.content.Context
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.olive.app.data.AccountRole
import com.olive.app.data.CoverType
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

// The phone checks conversation markers itself, so sending notifications needs no Cloud Function.
class LocalSafeUpdateCheckWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    // Local notification event handler: Processes the queued background work. It waits for the set time and cancels the timer when the feature closes.
    override suspend fun doWork(): Result {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.success()
        val preferences = applicationContext.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)
        val result = try {
            val firestore = FirebaseFirestore.getInstance()
            val friendships =
                firestore.collection("friendLists").document(uid).collection("friends").get()
                    .await()
            val baselineComplete = preferences.getBoolean(initializedKey(uid), false)
            var incomingUpdate = false
            friendships.documents.forEach { friend ->
                val conversationId = friend.getString("friendshipId") ?: return@forEach
                val conversation =
                    firestore.collection("conversations").document(conversationId).get().await()
                val currentMessageId = conversation.getString("lastMessageId").orEmpty()
                val previousMessageId = preferences.getString(messageKey(uid, conversationId), null)
                val senderUid = conversation.getString("lastSenderUid").orEmpty()
                if (shouldScheduleNotification(
                        previousMessageId,
                        currentMessageId,
                        senderUid,
                        uid,
                        baselineComplete
                    )
                ) incomingUpdate = true
                preferences.edit { putString(messageKey(uid, conversationId), currentMessageId) }
            }
            preferences.edit { putBoolean(initializedKey(uid), true) }
            if (incomingUpdate) SafeUpdateNotificationScheduler.schedule(applicationContext)
            Result.success()
        } catch (_: Exception) { // The next scheduled background check retries this work, so this worker must not add a second backoff loop.
            Result.success()
        }
        if (FirebaseAuth.getInstance().currentUser != null)
            LocalNotificationEventHandler.scheduleBackgroundCheck(applicationContext)
        return result
    }

    companion object {
        internal const val STATE_FILE = "olive_local_notification_events"
        // returns the message key for local notification event handler.
        internal fun messageKey(uid: String, conversationId: String) =
            "last_message_${uid}_$conversationId"

        // returns the initialized key for local notification event handler.
        internal fun initializedKey(uid: String) = "message_baseline_ready_$uid"
        // Local notification event handler: Checks whether schedule notification. It checks the current account and sends the right alert.
        internal fun shouldScheduleNotification(
            previousId: String?, currentId: String, senderUid: String, currentUid: String,
            baselineComplete: Boolean = true
        ) = currentId.isNotBlank() && currentId != previousId && senderUid.isNotBlank() &&
                senderUid != currentUid && (previousId != null || baselineComplete)
    }
}

object LocalNotificationEventHandler {
    private const val IMMEDIATE_WORK = "olive_local_notification_check_now"
    private const val BACKGROUND_WORK = "olive_local_notification_check_background"

    // Background Firestore polling is disabled. Live listeners still detect updates while the app process is running.
    internal const val BACKGROUND_CHECKS_ENABLED = false

    // Keep this value for future testing only; production periodic work must use a 15+ minute interval.
    internal const val BACKGROUND_CHECK_DELAY_SECONDS = 5L
    private var activeUid: String? = null
    private var friendListListener: ListenerRegistration? = null
    private val conversationListeners = mutableMapOf<String, ListenerRegistration>()

    // Starts the feature for local notification event handler. It reads the value saved on this device.
    fun start(context: Context, coverType: CoverType, role: AccountRole) {
        context.getSharedPreferences(LocalSafeUpdateCheckWorker.STATE_FILE, Context.MODE_PRIVATE)
            .edit {
                putString("cover_type", coverType.name)
                putString("account_role", role.name)
            }
        startForegroundListeners(context.applicationContext)
        if (!BACKGROUND_CHECKS_ENABLED) {
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK)
            WorkManager.getInstance(context).cancelUniqueWork(BACKGROUND_WORK)
            return
        }
        val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val immediate =
            OneTimeWorkRequestBuilder<LocalSafeUpdateCheckWorker>().setConstraints(constraints)
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, immediate)
    }

    // Local notification event handler: returns the schedule background check.
    internal fun scheduleBackgroundCheck(context: Context) {
        if (!BACKGROUND_CHECKS_ENABLED) return
        val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<LocalSafeUpdateCheckWorker>()
            .setInitialDelay(BACKGROUND_CHECK_DELAY_SECONDS, TimeUnit.SECONDS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            BACKGROUND_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request,
        )
    }

    // Cancels the active task for local notification event handler. It checks the current account and sends the right alert.
    fun cancel(context: Context) {
        stopForegroundListeners()
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(BACKGROUND_WORK)
        SafeUpdateNotificationScheduler.cancel(context)
    }

    // The unlocked messaging UI has its own listeners; pause these to avoid reading the same documents twice.
    fun pauseForegroundListeners() = stopForegroundListeners()

    // returns the current cover type for local notification event handler. It reads the value saved on this device.
    internal fun currentCoverType(context: Context) = CoverType.fromStored(
        context.getSharedPreferences(LocalSafeUpdateCheckWorker.STATE_FILE, Context.MODE_PRIVATE)
            .getString("cover_type", null).orEmpty(),
    )

    // Local notification event handler: returns the current role. It reads the value saved on this device.
    internal fun currentRole(context: Context) = AccountRole.fromStored(
        context.getSharedPreferences(LocalSafeUpdateCheckWorker.STATE_FILE, Context.MODE_PRIVATE)
            .getString("account_role", null).orEmpty(),
    )

    // Live listeners detect updates while the process runs; background polling stays off unless explicitly enabled above.
    private fun startForegroundListeners(context: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (activeUid == uid && friendListListener != null) return
        stopForegroundListeners()
        activeUid = uid
        val firestore = FirebaseFirestore.getInstance()
        friendListListener = firestore.collection("friendLists").document(uid).collection("friends")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || activeUid != uid) return@addSnapshotListener
                val conversationIds =
                    snapshot.documents.mapNotNull { it.getString("friendshipId") }.toSet()
                conversationListeners.keys.filterNot(conversationIds::contains)
                    .forEach { removedId ->
                        conversationListeners.remove(removedId)?.remove()
                    }
                conversationIds.filterNot(conversationListeners::containsKey)
                    .forEach { conversationId ->
                        conversationListeners[conversationId] =
                            firestore.collection("conversations").document(conversationId)
                                .addSnapshotListener { conversation, conversationError ->
                                    if (conversationError != null || conversation == null || !conversation.exists() || activeUid != uid)
                                        return@addSnapshotListener
                                    recordConversationMarker(
                                        context, uid, conversationId,
                                        conversation.getString("lastMessageId").orEmpty(),
                                        conversation.getString("lastSenderUid").orEmpty()
                                    )
                                }
                    }
            }
    }

    // Local notification event handler: Records conversation marker. It reads the value saved on this device.
    private fun recordConversationMarker(
        context: Context, uid: String, conversationId: String, currentMessageId: String,
        senderUid: String
    ) {
        val preferences = context.getSharedPreferences(
            LocalSafeUpdateCheckWorker.STATE_FILE,
            Context.MODE_PRIVATE
        )
        val key = LocalSafeUpdateCheckWorker.messageKey(uid, conversationId)
        val previousMessageId = preferences.getString(key, null)
        val baselineComplete =
            preferences.getBoolean(LocalSafeUpdateCheckWorker.initializedKey(uid), false)
        if (LocalSafeUpdateCheckWorker.shouldScheduleNotification(
                previousMessageId,
                currentMessageId,
                senderUid,
                uid,
                baselineComplete
            )
        )
            SafeUpdateNotificationScheduler.schedule(context)
        preferences.edit { putString(key, currentMessageId) }
    }

    // Local notification event handler: Stops foreground listeners. It checks the current account and sends the right alert.
    private fun stopForegroundListeners() {
        friendListListener?.remove()
        friendListListener = null
        conversationListeners.values.forEach(ListenerRegistration::remove)
        conversationListeners.clear()
        activeUid = null
    }
}
