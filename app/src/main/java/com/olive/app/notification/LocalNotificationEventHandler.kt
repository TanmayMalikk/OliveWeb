// Signed-in users: handles account and support alerts on the device.
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
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.olive.app.OliveActivity
import com.olive.app.R
import com.olive.app.data.AccountRole
import com.olive.app.data.CoverType
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.tasks.await


// The phone checks conversation markers itself, so sending notifications needs no Cloud Function.
class LocalSafeUpdateCheckWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    // Waits until an alert is due and cancels the timer when its feature closes.
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
        // Keeps the saved message key name in one place for app notifications.
        internal fun messageKey(uid: String, conversationId: String) =
            "last_message_${uid}_$conversationId"

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

    // Background Firestore polling is disabled. Live listeners still detect updates while the app process is running.
    internal const val BACKGROUND_CHECKS_ENABLED = false

    // Keep this value for future testing only; production periodic work must use a 15+ minute interval.
    internal const val BACKGROUND_CHECK_DELAY_SECONDS = 5L
    private var activeUid: String? = null
    private var conversationIndexListener: ListenerRegistration? = null

    // Starts listening for account events that should create local notifications.
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

    // Schedules the background check that refreshes safe update notifications.
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

    // Cancels the scheduled notification check when the signed-in account changes.
    fun cancel(context: Context) {
        stopForegroundListeners()
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(BACKGROUND_WORK)
        SafeUpdateNotificationScheduler.cancel(context)
    }

    // The unlocked messaging UI has its own listeners; pause these to avoid reading the same documents twice.
    fun pauseForegroundListeners() = stopForegroundListeners()

    // Reads which cover icon and title should be used for this notification.
    internal fun currentCoverType(context: Context) = CoverType.fromStored(
        context.getSharedPreferences(LocalSafeUpdateCheckWorker.STATE_FILE, Context.MODE_PRIVATE)
            .getString("cover_type", null).orEmpty(),
    )

    // Reads the account role attached to this notification event.
    internal fun currentRole(context: Context) = AccountRole.fromStored(
        context.getSharedPreferences(LocalSafeUpdateCheckWorker.STATE_FILE, Context.MODE_PRIVATE)
            .getString("account_role", null).orEmpty(),
    )

    // Live listeners detect updates while the process runs; background polling stays off unless explicitly enabled above.
    private fun startForegroundListeners(context: Context) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        if (activeUid == uid && conversationIndexListener != null) return
        stopForegroundListeners()
        activeUid = uid
        val firestore = FirebaseFirestore.getInstance()
        // Watch every conversation containing this account. Unlike the friend-list-only listener,
        // this also covers staff/admin support assignments and their three support inbox tabs.
        conversationIndexListener = firestore.collection("conversations")
            .whereArrayContains("participantIds", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || activeUid != uid) return@addSnapshotListener
                snapshot.documents.forEach { conversation ->
                    recordConversationMarker(
                        context,
                        uid,
                        conversation.id,
                        conversation.getString("lastMessageId").orEmpty(),
                        conversation.getString("lastSenderUid").orEmpty(),
                    )
                }
            }
    }

    // Saves the last notified message ID so the same message is not announced twice.
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

    // Removes account and message listeners when notification monitoring stops.
    private fun stopForegroundListeners() {
        conversationIndexListener?.remove()
        conversationIndexListener = null
        activeUid = null
    }
}

// Signed-in users: posts app alerts on Android.

object OliveNotificationManager {
    const val CHANNEL_NEWS = "olive_news_channel"
    const val CHANNEL_FITNESS = "olive_fitness_channel"
    const val CHANNEL_GAME = "olive_game_channel"
    const val CHANNEL_BRANCH = "olive_branch_channel"
    const val CHANNEL_TREE = "olive_tree_channel"
    const val CHANNEL_CALLS = "olive_calls_channel"

    // Each cover gets a matching public-facing notification category in Android settings.
    fun createNotificationChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_NEWS,
                    context.getString(R.string.ui_daily_digest_briefings),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description =
                        context.getString(R.string.ui_daily_headlines_and_news_digest_notifications)
                },
                NotificationChannel(
                    CHANNEL_FITNESS,
                    context.getString(R.string.fitness_updates),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.fitness_reminder)
                },
                NotificationChannel(
                    CHANNEL_GAME,
                    context.getString(R.string.ui_app_updates),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = context.getString(R.string.ui_general_app_activity_notifications)
                },
                NotificationChannel(
                    CHANNEL_BRANCH,
                    context.getString(R.string.ui_olive_branch_updates),
                    NotificationManager.IMPORTANCE_DEFAULT
                ),
                NotificationChannel(
                    CHANNEL_TREE,
                    context.getString(R.string.ui_olive_tree_updates),
                    NotificationManager.IMPORTANCE_DEFAULT
                ),
                NotificationChannel(
                    CHANNEL_CALLS,
                    context.getString(R.string.ui_call_invitation_notifications),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = context.getString(R.string.ui_call_invitation_notifications)
                }
            )
        )
    }

    fun showIncomingCall(
        context: Context,
        callId: String,
        callerId: String,
        callName: String
    ) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        createNotificationChannels(context)
        val intent = Intent(context, OliveActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_INCOMING_CALL_ID, callId)
            putExtra(EXTRA_INCOMING_CALL_CALLER_ID, callerId)
            putExtra(EXTRA_INCOMING_CALL_NAME, callName)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            callId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_CALLS)
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

    const val EXTRA_INCOMING_CALL_ID = "com.olive.app.INCOMING_CALL_ID"
    const val EXTRA_INCOMING_CALL_CALLER_ID = "com.olive.app.INCOMING_CALL_CALLER_ID"
    const val EXTRA_INCOMING_CALL_NAME = "com.olive.app.INCOMING_CALL_NAME"

    // Builds the title and message shown in this app notification.
    internal fun contentFor(
        title: String?,
        body: String?,
        coverType: CoverType,
        text: com.olive.app.ui.shared.ResourceText
    ) =
        AppNotifications.contentFor(title, body, coverType, text = text)

    // Posts role- and cover-safe notification text without revealing private app activity.
    fun showDiscreetNotification(
        context: Context,
        title: String?,
        body: String?,
        coverType: CoverType = CoverType.FITNESS,
        role: AccountRole = AccountRole.USER,
        notificationId: Int = (System.currentTimeMillis() % 100000).toInt()
    ) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        createNotificationChannels(context)
        val content = AppNotifications.contentFor(
            title,
            body,
            coverType,
            role,
            com.olive.app.ui.shared.ResourceText(context.resources)
        )
        val smallIcon = iconFor(coverType, role)
        val intent = Intent(context, OliveActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, content.channelId)
            .setSmallIcon(smallIcon)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, notification) }
    }

    // Chooses the notification icon that matches this event.
    internal fun iconFor(coverType: CoverType, role: AccountRole = AccountRole.USER) = when {
        role == AccountRole.SAFE_CONTACT -> R.drawable.ic_notification_olive
        role == AccountRole.STAFF || role == AccountRole.ADMIN -> R.drawable.ic_notification_olive_tree
        coverType == CoverType.NEWS -> R.drawable.ic_notification_daily_digest
        coverType == CoverType.FITNESS -> R.drawable.ic_notification_fitness
        // Game branding is still in development, so it temporarily uses the generic Olive status icon.
        coverType == CoverType.GAME -> R.drawable.ic_notification_olive
        else -> R.drawable.ic_notification_olive
    }
}

// Cover users: chooses notification text that does not reveal private activity.

internal data class CoverNotificationContent(
    val channelId: String,
    val title: String,
    val body: String
)

// Select cover-specific resource IDs here; notification wording lives in Android language resources.
object AppNotifications {
    // DEFAULT COVER NOTIFICATIONS: these are used whenever a local title or body is left blank.
    private val NEWS_DEFAULT_TITLE = R.string.ui_daily_digest
    private val NEWS_DEFAULT_BODY = R.string.ui_new_headlines_are_available
    private val FITNESS_DEFAULT_TITLE = R.string.fitness_tracker
    private val FITNESS_DEFAULT_BODY = R.string.fitness_reminder
    private val GAME_DEFAULT_TITLE = R.string.app_name
    private val GAME_DEFAULT_BODY = R.string.ui_a_new_update_is_available
    private val BRANCH_DEFAULT_TITLE = R.string.olive_branch
    private val BRANCH_DEFAULT_BODY = R.string.ui_a_new_update_is_available
    private val TREE_DEFAULT_TITLE = R.string.olive_tree
    private val TREE_DEFAULT_BODY = R.string.ui_an_account_update_is_available

    //these stay cover-safe and never include a sender or private message text.
    private val NEWS_SAFE_UPDATES = listOf(
        R.string.ui_new_headlines_are_available,
        R.string.ui_your_latest_digest_is_ready,
        R.string.ui_there_are_new_stories_to_browse,
    )
    private val FITNESS_SAFE_UPDATES = listOf(R.string.fitness_reminder)
    private val GAME_SAFE_UPDATES = listOf(
        R.string.ui_a_new_update_is_available,
        R.string.ui_open_the_app_to_see_what_s_new,
        R.string.ui_new_activity_is_waiting,
    )

    // Chooses a harmless update message that matches the active cover and posts it locally.
    fun showRandomSafeUpdate(
        context: Context, coverType: CoverType, role: AccountRole = AccountRole.USER,
        random: Random = Random.Default
    ) {
        val choices = when (coverType) {
            CoverType.NEWS -> NEWS_SAFE_UPDATES
            CoverType.FITNESS -> FITNESS_SAFE_UPDATES
            CoverType.GAME -> GAME_SAFE_UPDATES
        }
        val bodyId = when (role) {
            AccountRole.SAFE_CONTACT -> BRANCH_DEFAULT_BODY
            AccountRole.STAFF, AccountRole.ADMIN -> TREE_DEFAULT_BODY
            AccountRole.USER -> choices[random.nextInt(choices.size)]
        }
        OliveNotificationManager.showDiscreetNotification(
            context,
            "",
            context.getString(bodyId),
            coverType,
            role
        )
    }

    // Builds the title and message shown in this app notification.
    internal fun contentFor(
        title: String?,
        body: String?,
        coverType: CoverType,
        role: AccountRole = AccountRole.USER,
        text: com.olive.app.ui.shared.ResourceText
    ) = when {
        role == AccountRole.SAFE_CONTACT -> CoverNotificationContent(
            OliveNotificationManager.CHANNEL_BRANCH,
            title.orDefault(text(BRANCH_DEFAULT_TITLE)),
            body.orDefault(text(BRANCH_DEFAULT_BODY)),
        )

        role == AccountRole.STAFF || role == AccountRole.ADMIN -> CoverNotificationContent(
            OliveNotificationManager.CHANNEL_TREE,
            title.orDefault(text(TREE_DEFAULT_TITLE)),
            body.orDefault(text(TREE_DEFAULT_BODY)),
        )

        coverType == CoverType.NEWS -> CoverNotificationContent(
            OliveNotificationManager.CHANNEL_NEWS,
            title.orDefault(text(NEWS_DEFAULT_TITLE)),
            body.orDefault(text(NEWS_DEFAULT_BODY)),
        )

        coverType == CoverType.FITNESS -> CoverNotificationContent(
            OliveNotificationManager.CHANNEL_FITNESS,
            title.orDefault(text(FITNESS_DEFAULT_TITLE)),
            body.orDefault(text(FITNESS_DEFAULT_BODY)),
        )

        else -> CoverNotificationContent(
            OliveNotificationManager.CHANNEL_GAME,
            title.orDefault(text(GAME_DEFAULT_TITLE)),
            body.orDefault(text(GAME_DEFAULT_BODY)),
        )
    }

    // Reads a notification field and uses its safe default when it is missing.
    private fun String?.orDefault(fallback: String) = takeUnless { it.isNullOrBlank() } ?: fallback
}

// Safe updates wait a random amount of time, then appear as a normal notification from the chosen cover.
class SafeUpdateNotificationWorker(context: Context, parameters: WorkerParameters) :
    Worker(context, parameters) {
    // Checks for a safe update in the background and posts a local notification when needed.
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

// Schedules discreet cover-themed notifications with a random delay to prevent bursts.
object SafeUpdateNotificationScheduler {
    private const val WORK_NAME = "olive_pending_safe_update_notification"
    internal const val MIN_DELAY_SECONDS = 5L
    internal const val MAX_DELAY_SECONDS = 10L

    // Schedules the background task for safe update notification worker.
    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<SafeUpdateNotificationWorker>()
            .setInitialDelay(randomDelaySeconds(), TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    // Cancels the active background notification timer.
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    // Spreads background checks across a small random delay to avoid bursts.
    internal fun randomDelaySeconds(random: Random = Random.Default): Long =
        random.nextLong(MIN_DELAY_SECONDS, MAX_DELAY_SECONDS + 1)
}
