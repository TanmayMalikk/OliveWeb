// Users and safe contacts: keeps active voice calls running and registers device push tokens for incoming call alerts.
package com.olive.app.data.messaging

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.network.persistentInstallationId
import com.olive.app.notification.OliveNotificationManager
import java.io.IOException
import kotlinx.coroutines.tasks.await
import androidx.core.content.edit

// Keeps an active voice call alive when the user leaves Olive Branch or turns off the screen.
class VoipCallForegroundService : Service() {
    /**
     * Initializes the component and sets up its state or UI when created by Android.
     */
    override fun onCreate() {
        super.onCreate()
        OliveNotificationManager.createNotificationChannels(this)
    }

    /**
     * Android requires the foreground notification immediately after the call service starts.
     *
     * @param intent Android intent containing the action and optional extras for this component.
     * @param flags Flags supplied to [onStartCommand].
     * @param startId Start ID supplied to [onStartCommand].
     * @return The computed on start command numeric value.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callId = intent?.getStringExtra(EXTRA_CALL_ID)
        if (callId.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val returnToApp = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = returnToApp?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(this, OliveNotificationManager.CHANNEL_ACTIVE_CALLS)
            .setSmallIcon(R.drawable.ic_notification_olive_branch)
            .setContentTitle(getString(R.string.ui_voice_call))
            .setContentText(getString(R.string.ui_call_running_in_background))
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.ui_end_call),
                callAction(ACTION_END_CALL, callId),
            )
            .apply {
                if (intent.getBooleanExtra(EXTRA_QUICK_EXIT, false)) addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    getString(R.string.quick_exit),
                    callAction(ACTION_QUICK_EXIT, callId),
                )
            }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    /**
     * Invokes action.
     *
     * @param action Action supplied to [callAction].
     * @param callId Unique identifier of the VoIP call session.
     * @return The resulting [PendingIntent] for call action.
     */
    private fun callAction(action: String, callId: String): PendingIntent {
        val intent = Intent(action)
            .setPackage(packageName)
            .setData(Uri.Builder().scheme(CALL_ACTION_SCHEME).authority("call").appendPath(callId).build())
            .addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
        return PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Returns the binder interface for clients connecting to this service.
     *
     * @param intent Android intent containing the action and optional extras for this component.
     * @return The resulting [IBinder], or `null` if unavailable.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val ACTION_END_CALL = "com.olive.app.voip.END_CALL"
        internal const val ACTION_QUICK_EXIT = "com.olive.app.voip.QUICK_EXIT"
        internal const val CALL_ACTION_SCHEME = "olive-call-action"
        private const val EXTRA_CALL_ID = "call_id"
        private const val EXTRA_QUICK_EXIT = "quick_exit"
        private const val NOTIFICATION_ID = 8_107

        /**
         * Starts the notification while the call room is connecting or connected.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @param callId Unique identifier of the VoIP call session.
         * @param quickExit Whether to apply quick exit.
         */
        fun start(context: Context, callId: String, quickExit: Boolean) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VoipCallForegroundService::class.java)
                    .putExtra(EXTRA_CALL_ID, callId).putExtra(EXTRA_QUICK_EXIT, quickExit),
            )
        }

        /**
         * Removes the call notification after the room has closed or failed.
         *
         * @param context Android context used to access system services, resources, or storage.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, VoipCallForegroundService::class.java))
        }
    }
}

// Registers or removes this device's call-invitation push token with the VoIP server.
object VoipPushTokenRegistration {
    private const val PREFERENCES = "voip_push_token"
    private const val TOKEN_KEY = "token"
    private const val DEVICE_ID_KEY = "device_id"
    private const val ENABLED_KEY = "enabled_for_account"
    private const val WORK_NAME = "voip-push-token-registration"
    private const val INPUT_TOKEN = "token"
    private const val INPUT_DEVICE_ID = "device_id"

    /**
     * Subscribes to Firebase messaging updates and uploads the latest push token.
     *
     * @param context Android context used to access system services, resources, or storage.
     */
    suspend fun registerForTokenUpdates(context: Context) {
        FirebaseMessaging.getInstance().register().await()
        onTokenRefreshed(context, FirebaseInstallations.getInstance().id.await())
    }

    /**
     * Personal accounts can receive calls; organization accounts remove any token left by a previous user.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param role Account role determining workspace permissions.
     */
    suspend fun configureForRole(context: Context, role: AccountRole) {
        val enabled = role in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT) && isVoipApiConfigured()
        preferences(context).edit { putBoolean(ENABLED_KEY, enabled)}
        if (enabled) {
            registerCurrentToken(context)
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            if (FirebaseAuth.getInstance().currentUser != null) unregisterCurrentDevice(context)
        }
    }

    /**
     * Registers the current device token once the user is signed in.
     *
     * @param context Android context used to access system services, resources, or storage.
     */
    suspend fun registerCurrentToken(context: Context) {
        check(FirebaseAuth.getInstance().currentUser != null) {
            "Sign in before registering this device for call invitations"
        }
        check(isEnabled(context)) { "Voice calls are not enabled for this account type" }
        registerForTokenUpdates(context)
    }

    /**
     * Removes this device's call-invitation token from the server on sign-out.
     *
     * @param context Android context used to access system services, resources, or storage.
     */
    suspend fun unregisterCurrentDevice(context: Context) {
        check(FirebaseAuth.getInstance().currentUser != null) {
            "Sign in before unregistering this device's call-invitation token"
        }
        val deviceId = deviceId(context)
        VoipManager().unregisterPushToken(deviceId)
        Log.i(TAG, "Unregistered this device's call-invitation token")
    }

    /**
     * Replaces the existing push token with a fresh installation ID.
     *
     * @param context Android context used to access system services, resources, or storage.
     */
    suspend fun rotateCurrentToken(context: Context) {
        check(FirebaseAuth.getInstance().currentUser != null) {
            "Sign in before rotating this device's call-invitation token"
        }
        val messaging = FirebaseMessaging.getInstance()
        val installations = FirebaseInstallations.getInstance()
        val installationId = deviceId(context)
        VoipManager().unregisterPushToken(installationId)
        messaging.unregister().await()
        installations.delete().await()
        preferences(context).edit {remove(TOKEN_KEY)}
        messaging.register().await()
        val token = installations.id.await()
        saveToken(context, token)
        VoipManager().registerPushToken(installationId, token)
        Log.i(TAG, "Rotated and registered this device's call-invitation token")
    }

    /**
     * Saves a refreshed Firebase token and schedules background registration.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param token Authentication, push notification, or session token.
     */
    internal fun onTokenRefreshed(context: Context, token: String) {
        require(token.isNotBlank()) { "Firebase returned a blank push token" }
        saveToken(context, token)
        if (FirebaseAuth.getInstance().currentUser != null && isEnabled(context)) {
            enqueueRegistration(context, token)
        }
    }

    /**
     * Checks whether enabled.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @return `true` if is enabled, or `false` otherwise.
     */
    internal fun isEnabled(context: Context): Boolean =
        isVoipApiConfigured() && preferences(context).getBoolean(ENABLED_KEY, false)

    /**
     * Saves token.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param token Authentication, push notification, or session token.
     */
    private fun saveToken(context: Context, token: String) {
        require(token.isNotBlank()) { "Firebase returned a blank push token" }
        preferences(context).edit { putString(TOKEN_KEY, token)}
    }

    /**
     * Computes device ID.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @return The resulting device ID string.
     */
    private fun deviceId(context: Context): String =
        persistentInstallationId(context, PREFERENCES, DEVICE_ID_KEY)

    /**
     * Computes preferences.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @return The resulting preferences.
     */
    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /**
     * Enqueues registration.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param token Authentication, push notification, or session token.
     */
    private fun enqueueRegistration(context: Context, token: String) {
        val input = Data.Builder()
            .putString(INPUT_TOKEN, token)
            .putString(INPUT_DEVICE_ID, deviceId(context))
            .build()
        val request = OneTimeWorkRequestBuilder<VoipPushTokenRegistrationWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(input)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    internal const val TAG = "VoipPushToken"
    internal const val INPUT_TOKEN_KEY = INPUT_TOKEN
    internal const val INPUT_DEVICE_ID_KEY = INPUT_DEVICE_ID
}

// Uploads the stored VoIP push token to the call server when network connectivity is available.
class VoipPushTokenRegistrationWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    /**
     * Executes the scheduled WorkManager background task and returns the resulting work status.
     *
     * @return WorkManager [Result] indicating success, retry, or failure.
     */
    override suspend fun doWork(): Result {
        val token = inputData.getString(VoipPushTokenRegistration.INPUT_TOKEN_KEY) ?: return Result.failure()
        val deviceId = inputData.getString(VoipPushTokenRegistration.INPUT_DEVICE_ID_KEY) ?: return Result.failure()
        if (FirebaseAuth.getInstance().currentUser == null) return Result.success()
        if (!VoipPushTokenRegistration.isEnabled(applicationContext)) return Result.success()

        return try {
            VoipManager().registerPushToken(deviceId, token)
            Result.success()
        } catch (error: VoipApiException) {
            if (error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500) Result.retry()
            else Result.failure()
        } catch (error: IOException) {
            Result.retry()
        } catch (error: IllegalArgumentException) {
            Result.failure()
        }
    }
}
