package com.olive.app.data

import android.content.Context
import java.io.File

/** Emits the encrypted local index immediately and mutates it only from server-confirmed results. */
class IndexedSafetyDocumentRepository(
    private val remote: SafetyDocumentDataSource,
    private val index: DocumentIndexDataSource,
    private val clock: () -> Long = System::currentTimeMillis,
) : SafetyDocumentDataSource {
    override suspend fun listFor(uid: String): DocumentListSnapshot = try {
        persist(uid, remote.listFor(uid))
    } catch (failure: Exception) {
        val local = index.read(uid)
        if (local.documents.isEmpty()) throw failure
        local.snapshot()
    }

    override fun observeFor(
        uid: String,
        onUpdate: (DocumentListSnapshot) -> Unit,
        onError: (String) -> Unit,
    ): ChatSubscription {
        onUpdate(index.read(uid).snapshot())
        return remote.observeFor(uid, { incoming ->
            when (incoming.source) {
                DocumentListSource.SERVER -> onUpdate(persist(uid, incoming))
                DocumentListSource.FIRESTORE_CACHE -> {
                    val durable = index.read(uid)
                    val merged = (durable.documents + incoming.documents)
                        .associateBy(SafetyDocument::id).values.sortedByDescending(SafetyDocument::createdAt)
                    onUpdate(DocumentListSnapshot(merged, DocumentListSource.FIRESTORE_CACHE, durable.lastSyncedAt))
                }
                DocumentListSource.LOCAL_INDEX -> onUpdate(incoming)
            }
        }, { message ->
            val local = index.read(uid)
            if (local.documents.isEmpty()) onError(message) else onUpdate(local.snapshot())
        })
    }

    /** Server reads never silently fall back after an authorization failure. */
    override suspend fun getFor(uid: String, id: String): SafetyDocument = remote.getFor(uid, id)

    override fun cachedFor(uid: String, id: String): SafetyDocument? =
        index.read(uid).documents.firstOrNull { it.id == id && uid in it.allowedUsers }

    override suspend fun share(document: SafetyDocument, ownerUid: String, recipients: Map<String, String>) =
        remote.share(document, ownerUid, recipients)

    override suspend fun grantAccess(
        document: SafetyDocument,
        ownerUid: String,
        recipientUid: String,
        recipientUsername: String,
    ) = remote.grantAccess(document, ownerUid, recipientUid, recipientUsername)

    override suspend fun revokeAccess(documentId: String, ownerUid: String, recipientUid: String) =
        remote.revokeAccess(documentId, ownerUid, recipientUid)

    override suspend fun delete(document: SafetyDocument, ownerUid: String) {
        remote.delete(document, ownerUid)
        index.remove(ownerUid, document.id)
    }

    override suspend fun download(context: Context, document: SafetyDocument): File = remote.download(context, document)

    override suspend fun approveCurrentAccountDevices(document: SafetyDocument, currentUid: String, username: String) =
        remote.approveCurrentAccountDevices(document, currentUid, username)

    private fun persist(uid: String, snapshot: DocumentListSnapshot): DocumentListSnapshot {
        if (snapshot.source != DocumentListSource.SERVER) return snapshot
        val saved = index.replace(uid, snapshot.documents, snapshot.lastSyncedAt ?: clock())
        return DocumentListSnapshot(saved.documents, DocumentListSource.SERVER, saved.lastSyncedAt)
    }
}

private fun IndexedDocuments.snapshot() =
    DocumentListSnapshot(documents, DocumentListSource.LOCAL_INDEX, lastSyncedAt)
