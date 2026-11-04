package com.olive.app.data.messaging

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage
import com.olive.app.notification.OliveNotificationManager
import kotlinx.coroutines.tasks.await
import java.io.IOException
import java.util.UUID

object VoipPushTokenRegistration {
    private const val PREFERENCES = "voip_push_token"
    private const val TOKEN_KEY = "token"
    private const val DEVICE_ID_KEY = "device_id"
    private const val WORK_NAME = "voip-push-token-registration"
    private const val INPUT_TOKEN = "token"
    private const val INPUT_DEVICE_ID = "device_id"

    suspend fun registerForTokenUpdates(context: Context) {
        FirebaseMessaging.getInstance().register().await()
        val token = FirebaseInstallations.getInstance().id.await()
        saveToken(context, token)
        if (FirebaseAuth.getInstance().currentUser != null) {
            enqueueRegistration(context, token)
        }
    }

    suspend fun registerCurrentToken(context: Context) {
        check(FirebaseAuth.getInstance().currentUser != null) {
            "Sign in before registering this device for call invitations"
        }
        registerForTokenUpdates(context)
    }

    suspend fun unregisterCurrentDevice(context: Context) {
        check(FirebaseAuth.getInstance().currentUser != null) {
            "Sign in before unregistering this device's call-invitation token"
        }
        val deviceId = deviceId(context)
        VoipManager().unregisterPushToken(deviceId)
        Log.i(TAG, "Unregistered this device's call-invitation token")
    }

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
        preferences(context).edit().remove(TOKEN_KEY).apply()
        messaging.register().await()
        val token = installations.id.await()
        saveToken(context, token)
        VoipManager().registerPushToken(installationId, token)
        Log.i(TAG, "Rotated and registered this device's call-invitation token")
    }

    internal fun onTokenRefreshed(context: Context, token: String) {
        require(token.isNotBlank()) { "Firebase returned a blank push token" }
        saveToken(context, token)
        if (FirebaseAuth.getInstance().currentUser != null) {
            enqueueRegistration(context, token)
        }
    }

    private fun saveToken(context: Context, token: String) {
        require(token.isNotBlank()) { "Firebase returned a blank push token" }
        preferences(context).edit()
            .putString(TOKEN_KEY, token)
            .apply()
    }

    private fun storedToken(context: Context): String? =
        preferences(context).getString(TOKEN_KEY, null)

    @Synchronized
    private fun deviceId(context: Context): String {
        val preferences = preferences(context)
        return preferences.getString(DEVICE_ID_KEY, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(DEVICE_ID_KEY, it).apply()
        }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun enqueueRegistration(context: Context, token: String) {
        val input = Data.Builder()
            .putString(INPUT_TOKEN, token)
            .putString(INPUT_DEVICE_ID, deviceId(context))
            .build()
        val request = OneTimeWorkRequestBuilder<VoipPushTokenRegistrationWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(input)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    internal const val TAG = "VoipPushToken"
    internal const val INPUT_TOKEN_KEY = INPUT_TOKEN
    internal const val INPUT_DEVICE_ID_KEY = INPUT_DEVICE_ID
}

class VoipPushTokenRegistrationWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val token = inputData.getString(VoipPushTokenRegistration.INPUT_TOKEN_KEY)
            ?: return Result.failure()
        val deviceId = inputData.getString(VoipPushTokenRegistration.INPUT_DEVICE_ID_KEY)
            ?: return Result.failure()
        if (FirebaseAuth.getInstance().currentUser == null) {
            Log.i(VoipPushTokenRegistration.TAG, "Push token saved locally; registration waits for sign-in")
            return Result.success()
        }

        return try {
            VoipManager().registerPushToken(deviceId, token)
            Result.success()
        } catch (error: VoipApiException) {
            Log.e(VoipPushTokenRegistration.TAG, "Push-token registration failed with HTTP ${error.statusCode}", error)
            if (error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500) {
                Result.retry()
            } else {
                Result.failure()
            }
        } catch (error: IOException) {
            Log.e(VoipPushTokenRegistration.TAG, "Could not reach the VoIP API to register the push token", error)
            Result.retry()
        } catch (error: IllegalArgumentException) {
            Log.e(VoipPushTokenRegistration.TAG, "VoIP API configuration is invalid; token remains stored locally", error)
            Result.failure()
        }
    }
}

class VoipFirebaseMessagingService : com.google.firebase.messaging.FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        super.onRegistered(installationId)
        try {
            VoipPushTokenRegistration.onTokenRefreshed(this, installationId)
        } catch (error: Exception) {
            Log.e(VoipPushTokenRegistration.TAG, "Could not persist the Firebase messaging registration", error)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["type"] != "call_invitation") return

        val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val recipientId = data["recipientId"] ?: return
        val callId = data["callId"] ?: return
        val callerId = data["callerId"] ?: return
        if (recipientId != currentUid || callId.isBlank() || callerId.isBlank()) return

        try {
            OliveNotificationManager.showIncomingCall(
                this,
                callId,
                callerId,
                data["callName"].orEmpty()
            )
        } catch (error: Exception) {
            Log.e(VoipPushTokenRegistration.TAG, "Could not show the incoming call notification", error)
        }
    }
}
