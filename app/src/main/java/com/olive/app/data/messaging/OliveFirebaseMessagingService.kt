// Signed-in accounts: routes call invitations, update alerts, and sync wake-ups from one FCM service.
package com.olive.app.data.messaging

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.olive.app.BuildConfig
import com.olive.app.data.sync.PriorityReconciliationWorker
import com.olive.app.data.sync.syncInvalidationFrom
import com.olive.app.data.update.AppUpdateScheduler
import com.olive.app.notification.LocalNotificationEventHandler
import com.olive.app.notification.OliveNotificationManager

class OliveFirebaseMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        super.onRegistered(installationId)
        runCatching { VoipPushTokenRegistration.onTokenRefreshed(this, installationId) }
            .onFailure { error ->
                Log.e(VoipPushTokenRegistration.TAG, "Could not persist the Firebase messaging registration", error)
            }
        runCatching { LocalNotificationEventHandler.onFcmTokenRefreshed(this, installationId) }
        AppUpdateScheduler.registerForUpdates(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val invalidation = syncInvalidationFrom(data)
        if (invalidation != null) {
            FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
                PriorityReconciliationWorker.enqueue(
                    this,
                    uid,
                    revision = invalidation.revision,
                    expedited = invalidation.urgent,
                )
            }
            return
        }
        if (data["type"] == CALL_INVITATION) {
            if (VoipPushTokenRegistration.isEnabled(this)) showCall(data)
            return
        }
        if (data["type"] == APP_UPDATE) {
            val remoteVersion = data["versionCode"]?.toLongOrNull() ?: Long.MAX_VALUE
            if (remoteVersion > BuildConfig.VERSION_CODE) {
                AppUpdateScheduler.announceRelease(this, data["versionName"].orEmpty())
                runCatching { AppUpdateScheduler.triggerNow(this) }
            }
            return
        }
        runCatching {
            LocalNotificationEventHandler.onFcmMessageReceived(
                this,
                data,
                message.notification?.title,
                message.notification?.body,
            )
        }
    }

    private fun showCall(data: Map<String, String>) {
        val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val recipientId = data["recipientId"] ?: return
        val callId = data["callId"] ?: return
        val callerId = data["callerId"] ?: return
        if (recipientId != currentUid || callId.isBlank() || callerId.isBlank()) return
        runCatching {
            OliveNotificationManager.showIncomingCall(
                this,
                callId,
                callerId,
                data["callName"].orEmpty(),
            )
        }.onFailure { error ->
            Log.e(VoipPushTokenRegistration.TAG, "Could not show the incoming call notification", error)
        }
    }

    private companion object {
        const val CALL_INVITATION = "call_invitation"
        const val APP_UPDATE = "app_update"
    }
}

