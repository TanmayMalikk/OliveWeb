// Signed-in users: handles FCM registration and account/support alerts on the device.
package com.olive.app.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.olive.app.OliveActivity
import com.olive.app.R
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.tasks.await

// Syncs the device's FCM token to Firestore and checks conversation markers when requested.
class LocalSafeUpdateCheckWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.success()
        if (inputData.getBoolean(KEY_SYNC_FCM_ONLY, false)) {
            return try {
                val token = inputData.getString(KEY_FCM_TOKEN)?.takeIf { it.isNotBlank() } ?: run {
                    FirebaseMessaging.getInstance().register().await()
                    FirebaseInstallations.getInstance().id.await()
                }
                LocalNotificationEventHandler.saveFcmTokenToFirestore(applicationContext, uid, token)
                Result.success()
            } catch (_: Exception) {
                Result.retry()
            }
        }
        val preferences = applicationContext.getSharedPreferences(STATE_FILE, Context.MODE_PRIVATE)
        val result = try {
            val firestore = FirebaseFirestore.getInstance()
            val friendships = firestore.collection("friendLists").document(uid)
                .collection("friends").get().await()
            val baselineComplete = preferences.getBoolean(initializedKey(uid), false)
            var incomingUpdate = false
            friendships.documents.forEach { friend ->
                val conversationId = friend.getString("friendshipId") ?: return@forEach
                val conversation = firestore.collection("conversations")
                    .document(conversationId).get().await()
                val currentMessageId = conversation.getString("lastMessageId").orEmpty()
                val previousMessageId = preferences.getString(messageKey(uid, conversationId), null)
                val senderUid = conversation.getString("lastSenderUid").orEmpty()
                if (shouldScheduleNotification(previousMessageId, currentMessageId, senderUid, uid, baselineComplete)) {
                    incomingUpdate = true
                }
                preferences.edit { putString(messageKey(uid, conversationId), currentMessageId) }
            }
            preferences.edit { putBoolean(initializedKey(uid), true) }
            if (incomingUpdate) SafeUpdateNotificationScheduler.schedule(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.success()
        }
        if (FirebaseAuth.getInstance().currentUser != null)
            LocalNotificationEventHandler.scheduleBackgroundCheck(applicationContext)
        return result
    }

    companion object {
        internal const val STATE_FILE = "olive_local_notification_events"
        internal const val KEY_SYNC_FCM_ONLY = "sync_fcm_only"
        internal const val KEY_FCM_TOKEN = "fcm_token"

        // Keeps the saved message key name in one place for app notifications.
        internal fun messageKey(uid: String, conversationId: String) = "last_message_${uid}_$conversationId"

        // Keeps the saved initialized key name in one place for app notifications.
        internal fun initializedKey(uid: String) = "message_baseline_ready_$uid"

        // Sends the alert only when its account and feature are still active.
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
    private const val FCM_TOKEN_WORK = "olive_fcm_notification_token_sync"
    private const val KEY_DEVICE_ID = "fcm_device_id"
    private const val KEY_LAST_FCM_TOKEN = "fcm_last_token"
    private const val KEY_ACTIVE_UID = "fcm_active_uid"

    // Background Firestore polling is disabled because FCM delivers background push updates.
    internal const val BACKGROUND_CHECKS_ENABLED = false
    internal const val BACKGROUND_CHECK_DELAY_SECONDS = 5L
    private var activeUid: String? = null
    private var conversationIndexListener: ListenerRegistration? = null

    // Starts FCM registration and foreground listeners for account events that create local notifications.
    fun start(context: Context) {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        context.getSharedPreferences(LocalSafeUpdateCheckWorker.STATE_FILE, Context.MODE_PRIVATE).edit {
            if (!currentUid.isNullOrBlank()) putString(KEY_ACTIVE_UID, currentUid)
        }
        enqueueFcmTokenSync(context.applicationContext, null)
        startForegroundListeners(context.applicationContext)
        if (!BACKGROUND_CHECKS_ENABLED) {
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK)
            WorkManager.getInstance(context).cancelUniqueWork(BACKGROUND_WORK)
            return
        }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val immediate = OneTimeWorkRequestBuilder<LocalSafeUpdateCheckWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, immediate)
    }

    // Schedules a background worker to register or refresh this device's FCM token in Firestore.
    internal fun enqueueFcmTokenSync(context: Context, token: String?) {
        if (FirebaseAuth.getInstance().currentUser == null) return
        val input = Data.Builder()
            .putBoolean(LocalSafeUpdateCheckWorker.KEY_SYNC_FCM_ONLY, true)
            .apply { if (!token.isNullOrBlank()) putString(LocalSafeUpdateCheckWorker.KEY_FCM_TOKEN, token) }
            .build()
        val request = OneTimeWorkRequestBuilder<LocalSafeUpdateCheckWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(input)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(FCM_TOKEN_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    // Saves a refreshed FCM installation token and syncs it to Firestore for the signed-in account.
    internal fun onFcmTokenRefreshed(context: Context, token: String) {
        if (token.isBlank()) return
        context.getSharedPreferences(LocalSafeUpdateCheckWorker.STATE_FILE, Context.MODE_PRIVATE)
            .edit { putString(KEY_LAST_FCM_TOKEN, token) }
        enqueueFcmTokenSync(context.applicationContext, token)
    }

    // Writes the device's FCM token to Firestore so Cloud Functions can push update alerts.
    internal suspend fun saveFcmTokenToFirestore(context: Context, uid: String, token: String) {
        if (uid.isBlank() || token.isBlank()) return
        val devId = deviceId(context)
        context.getSharedPreferences(LocalSafeUpdateCheckWorker.STATE_FILE, Context.MODE_PRIVATE).edit {
            putString(KEY_LAST_FCM_TOKEN, token)
            putString(KEY_ACTIVE_UID, uid)
        }
        FirebaseFirestore.getInstance()
            .collection("notificationTokens").document(uid)
            .collection("devices").document(devId)
            .set(mapOf("uid" to uid, "deviceId" to devId, "token" to token, "updatedAt" to FieldValue.serverTimestamp()))
            .await()
        if (FirebaseAuth.getInstance().currentUser?.uid != uid) {
            FirebaseFirestore.getInstance().collection("notificationTokens").document(uid)
                .collection("devices").document(devId).delete().await()
        }
    }

    // Evaluates whether an incoming FCM data payload should trigger a Safe App update notification.
    internal fun shouldHandleFcmNotification(
        data: Map<String, String>, currentUid: String?, previousMessageId: String? = null,
        hasNotificationPayload: Boolean = false,
    ): Boolean {
        if (currentUid.isNullOrBlank()) return false
        val type = data["type"].orEmpty()
        if (type == "call_invitation") return false
        val recipientId = data["recipientId"].orEmpty()
        if (recipientId.isNotBlank() && recipientId != currentUid) return false
        val senderUid = data["senderUid"].orEmpty()
        if (senderUid.isNotBlank() && senderUid == currentUid) return false
        val messageId = data["messageId"].orEmpty()
        if (messageId.isNotBlank() && messageId == previousMessageId) return false
        return type in setOf("safe_update", "message", "notification") ||
            messageId.isNotBlank() || data["conversationId"].orEmpty().isNotBlank() || hasNotificationPayload
    }

    // Handles an incoming FCM message by deduplicating its conversation marker and scheduling an alert.
    internal fun onFcmMessageReceived(
        context: Context, data: Map<String, String>, title: String? = null, body: String? = null,
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val conversationId = data["conversationId"].orEmpty()
        if (data["eventId"] == "ticket-assigned") {
            if (data["recipientId"] == uid && conversationId.isNotBlank()) {
                OliveNotificationManager.showSafeUpdate(
                    context, R.string.notification_ticket_assigned, "ticket-assigned:$uid:$conversationId",
                )
            }
            return
        }
        if (data["type"] == "safety_plans_completed" && data["recipientId"] == uid) {
            OliveNotificationManager.showSafeUpdate(context, R.string.safety_plans_completed)
            return
        }
        val preferences = context.getSharedPreferences(LocalSafeUpdateCheckWorker.STATE_FILE, Context.MODE_PRIVATE)
        val messageId = data["messageId"].orEmpty()
        val markerKey = if (conversationId.isNotBlank()) LocalSafeUpdateCheckWorker.messageKey(uid, conversationId) else null
        val previousMessageId = markerKey?.let { preferences.getString(it, null) }
        if (!shouldHandleFcmNotification(data, uid, previousMessageId, !title.isNullOrBlank() || !body.isNullOrBlank())) return
        if (markerKey != null && messageId.isNotBlank()) {
            preferences.edit {
                putString(markerKey, messageId)
                putBoolean(LocalSafeUpdateCheckWorker.initializedKey(uid), true)
            }
        }
        SafeUpdateNotificationScheduler.schedule(context.applicationContext)
    }

    @Synchronized
    private fun deviceId(context: Context): String {
        val preferences = context.getSharedPreferences(LocalSafeUpdateCheckWorker.STATE_FILE, Context.MODE_PRIVATE)
        return preferences.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also { preferences.edit { putString(KEY_DEVICE_ID, it) } }
    }

    // Schedules the background check that refreshes safe update notifications.
    internal fun scheduleBackgroundCheck(context: Context) {
        if (!BACKGROUND_CHECKS_ENABLED) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<LocalSafeUpdateCheckWorker>()
            .setInitialDelay(BACKGROUND_CHECK_DELAY_SECONDS, TimeUnit.SECONDS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(BACKGROUND_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    // Cancels the scheduled notification check and removes the device's FCM token when signing out.
    fun cancel(context: Context) {
        stopForegroundListeners()
        val preferences = context.getSharedPreferences(LocalSafeUpdateCheckWorker.STATE_FILE, Context.MODE_PRIVATE)
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: preferences.getString(KEY_ACTIVE_UID, null)
        val devId = preferences.getString(KEY_DEVICE_ID, null)
        if (!uid.isNullOrBlank() && !devId.isNullOrBlank()) {
            runCatching {
                FirebaseFirestore.getInstance()
                    .collection("notificationTokens").document(uid)
                    .collection("devices").document(devId).delete()
            }
        }
        WorkManager.getInstance(context).cancelUniqueWork(FCM_TOKEN_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(BACKGROUND_WORK)
        SafeUpdateNotificationScheduler.cancel(context)
    }

    // The unlocked messaging UI has its own listeners; pause these to avoid reading the same documents twice.
    fun pauseForegroundListeners() = stopForegroundListeners()

    // Live listeners detect updates while the process runs; FCM delivers push updates in the background.
    private fun startForegroundListeners(context: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (activeUid == uid && conversationIndexListener != null) return
        stopForegroundListeners()
        activeUid = uid
        conversationIndexListener = FirebaseFirestore.getInstance().collection("conversations")
            .whereArrayContains("participantIds", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || activeUid != uid) return@addSnapshotListener
                snapshot.documents.forEach { conversation ->
                    recordConversationMarker(
                        context, uid, conversation.id,
                        conversation.getString("lastMessageId").orEmpty(),
                        conversation.getString("lastSenderUid").orEmpty(),
                    )
                }
            }
    }

    // Saves the last notified message ID so the same message is not announced twice.
    private fun recordConversationMarker(
        context: Context, uid: String, conversationId: String, currentMessageId: String, senderUid: String
    ) {
        val preferences = context.getSharedPreferences(LocalSafeUpdateCheckWorker.STATE_FILE, Context.MODE_PRIVATE)
        val key = LocalSafeUpdateCheckWorker.messageKey(uid, conversationId)
        val previousMessageId = preferences.getString(key, null)
        val baselineComplete = preferences.getBoolean(LocalSafeUpdateCheckWorker.initializedKey(uid), false)
        if (LocalSafeUpdateCheckWorker.shouldScheduleNotification(
                previousMessageId, currentMessageId, senderUid, uid, baselineComplete
            )
        ) SafeUpdateNotificationScheduler.schedule(context)
        preferences.edit { putString(key, currentMessageId) }
    }

    // Removes account and message listeners when notification monitoring stops.
    private fun stopForegroundListeners() {
        conversationIndexListener?.remove()
        conversationIndexListener = null
        activeUid = null
    }
}

// Signed-in users: posts app alerts on Android.
object OliveNotificationManager {
    internal const val CHANNEL_CALL_INVITES = "olive_calls_channel"
    internal const val CHANNEL_ACTIVE_CALLS = "olive_voice_call"
    internal const val CHANNEL_SAFE_UPDATES = "olive_safe_app_updates"
    internal const val CHANNEL_LOCATION = "olive_admin_location_v1"
    internal const val CHANNEL_APP_UPDATES = "app-updates"
    private val legacyChannels = setOf(
        "olive_news_channel", "olive_fitness_channel", "olive_game_channel",
        "olive_branch_channel", "olive_tree_channel", "olive_live_location_v3",
    )
    const val EXTRA_INCOMING_CALL_ID = "com.olive.app.INCOMING_CALL_ID"
    const val EXTRA_INCOMING_CALL_CALLER_ID = "com.olive.app.INCOMING_CALL_CALLER_ID"
    const val EXTRA_INCOMING_CALL_NAME = "com.olive.app.INCOMING_CALL_NAME"

    fun createNotificationChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_CALL_INVITES, context.getString(R.string.ui_call_invitation_notifications), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_ACTIVE_CALLS, context.getString(R.string.notification_active_voice_calls), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_SAFE_UPDATES, context.getString(R.string.notification_safe_app_updates), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_LOCATION, context.getString(R.string.location_notification_channel), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_APP_UPDATES, context.getString(R.string.notification_new_app_update), NotificationManager.IMPORTANCE_DEFAULT),
            )
        )
        manager.notificationChannels.filter { it.id in legacyChannels }.forEach {
            // Android prevents deletion while an upgraded location service is using this channel.
            runCatching { manager.deleteNotificationChannel(it.id) }
        }
    }

    // Displays a high-priority incoming call notification with a 20-second timeout.
    fun showIncomingCall(context: Context, callId: String, callerId: String, callName: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        createNotificationChannels(context)
        val intent = Intent(context, OliveActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_INCOMING_CALL_ID, callId)
            putExtra(EXTRA_INCOMING_CALL_CALLER_ID, callerId)
            putExtra(EXTRA_INCOMING_CALL_NAME, callName)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, callId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_CALL_INVITES)
            .setSmallIcon(R.drawable.ic_notification_olive)
            .setContentTitle(context.getString(R.string.ui_incoming_call))
            .setContentText(context.getString(R.string.ui_open_olive_to_respond_to_this_call))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setTimeoutAfter(20_000)
            .build()
        NotificationManagerCompat.from(context).notify(callId.hashCode(), notification)
    }

    fun showSafeUpdate(context: Context, body: Int = R.string.notification_safe_app_activity, tag: String? = null) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        createNotificationChannels(context)
        val intent = Intent(context, OliveActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_SAFE_UPDATES)
            .setSmallIcon(R.drawable.ic_notification_olive_branch)
            .setContentTitle(context.getString(R.string.notification_safe_app_updates))
            .setContentText(context.getString(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        val notificationId = if (tag != null) 0 else (System.currentTimeMillis() % 100000).toInt()
        runCatching { NotificationManagerCompat.from(context).notify(tag ?: "safe-app-update", notificationId, notification) }
    }

}
