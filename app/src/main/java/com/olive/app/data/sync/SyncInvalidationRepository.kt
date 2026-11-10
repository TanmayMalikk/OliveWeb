// Signed-in accounts: reads the server-owned, payload-free reconciliation marker.
package com.olive.app.data.sync

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import com.olive.app.data.ChatSubscription
import kotlinx.coroutines.tasks.await

internal class SyncInvalidationRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    suspend fun get(uid: String): SyncRevisions? {
        val snapshot = firestore.collection(COLLECTION).document(uid).get(Source.SERVER).await()
        if (!snapshot.exists()) return null
        return syncRevisionsFrom(uid, snapshot.data)
            ?: error("Invalid reconciliation marker")
    }

    fun observe(
        uid: String,
        onUpdate: (SyncRevisions) -> Unit,
        onError: (Exception) -> Unit,
    ): ChatSubscription {
        val listener = firestore.collection(COLLECTION).document(uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, failure ->
                if (failure != null) {
                    onError(failure)
                    return@addSnapshotListener
                }
                if (snapshot?.exists() != true) return@addSnapshotListener
                val revisions = syncRevisionsFrom(uid, snapshot.data)
                if (revisions == null) onError(IllegalStateException("Invalid reconciliation marker"))
                else onUpdate(revisions)
            }
        return ChatSubscription { listener.remove() }
    }

    private companion object { const val COLLECTION = "syncInvalidations" }
}

