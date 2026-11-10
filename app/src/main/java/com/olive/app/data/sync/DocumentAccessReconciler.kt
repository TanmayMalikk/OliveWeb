// Authorized media viewers: refreshes metadata and purges revoked local ciphertext.
package com.olive.app.data.sync

import android.content.Context
import com.olive.app.data.DocumentIndexStore
import com.olive.app.data.DocumentListSource
import com.olive.app.data.SafetyDocumentRepository
import com.olive.app.data.media.MediaRepository
import com.olive.app.data.media.OfflineMediaCache

internal class DocumentAccessReconciler(
    context: Context,
) : PriorityDomainReconciler {
    private val application = context.applicationContext

    override suspend fun reconcile(uid: String) {
        val snapshot = SafetyDocumentRepository(media = MediaRepository()).listFor(uid)
        check(snapshot.source == DocumentListSource.SERVER)
        val syncedAt = snapshot.lastSyncedAt ?: System.currentTimeMillis()
        DocumentIndexStore(application).replace(uid, snapshot.documents, syncedAt)
        OfflineMediaCache(application).reconcile(uid, snapshot.documents)
    }
}

