// Signed-in accounts: broadcasts that an account-owned Storage file changed.
package com.olive.app.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.olive.app.data.messaging.ChatSubscription
import kotlinx.coroutines.tasks.await

// Only a revision counter is stored here; profile and history contents stay in their protected Storage files.
internal class AccountSyncRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    // Watches one account marker and lets the session reload the matching Storage files.
    fun observe(uid: String, onChange: () -> Unit, onError: (Exception) -> Unit): ChatSubscription {
        val listener = firestore.collection("accountSync").document(uid)
            .addSnapshotListener { _, error ->
                if (error != null) onError(error) else onChange()
            }
        return ChatSubscription { listener.remove() }
    }

    // Raises the marker after a successful profile or history upload.
    suspend fun notifyChanged(uid: String) {
        firestore.collection("accountSync").document(uid).set(
            mapOf("revision" to FieldValue.increment(1), "updatedAt" to FieldValue.serverTimestamp()),
            com.google.firebase.firestore.SetOptions.merge(),
        ).await()
    }
}
