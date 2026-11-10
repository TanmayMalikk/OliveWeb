// Signed-in accounts: routes call invitations and content-free sync wake-ups from one FCM service.
package com.olive.app.data.messaging

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.olive.app.data.sync.PriorityReconciliationWorker
import com.olive.app.data.sync.syncInvalidationFrom
import com.olive.app.notification.OliveNotificationManager

class OliveFirebaseMessagingService : FirebaseMessagingService() {
    override fun onRegistered(installationId: String) {
        super.onRegistered(installationId)
        runCatching { VoipPushTokenRegistration.onTokenRefreshed(this, installationId) }
            .onFailure { error ->
                Log.e(VoipPushTokenRegistration.TAG, "Could not persist the Firebase messaging registration", error)
            }
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
        if (data["type"] != CALL_INVITATION) return
        showCall(data)
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

    private companion object { const val CALL_INVITATION = "call_invitation" }
}

