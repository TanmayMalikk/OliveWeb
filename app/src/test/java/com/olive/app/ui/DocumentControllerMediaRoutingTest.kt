package com.olive.app.ui

import android.content.Context
import android.net.Uri
import com.olive.app.data.AccountIdentity
import com.olive.app.data.ChatSubscription
import com.olive.app.data.DocumentOcrMetadata
import com.olive.app.data.SafetyDocument
import com.olive.app.data.SafetyDocumentDataSource
import com.olive.app.data.media.MediaKind
import com.olive.app.data.media.OcrReview
import com.olive.app.ui.users.user.documents.MediaTransferOperations
import com.olive.app.ui.users.user.documents.SafetyDocumentsController
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DocumentControllerMediaRoutingTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `shared R2 document uses authenticated media download`() {
        val document = document(objectKey = "users/owner/media/r2-document")
        val repository = FakeDocumentRepository(document, File(context.cacheDir, "legacy"))
        val mediaFile = File(context.cacheDir, "r2")
        val media = FakeMediaTransfers(mediaFile)
        val controller = SafetyDocumentsController(context, scope, repository, media)
        controller.prepare(VIEWER_UID)

        var opened: Pair<SafetyDocument, File>? = null
        var finished = false
        controller.openShared(VIEWER_UID, document.id, { source, file -> opened = source to file }) {
            finished = true
        }

        assertSame(document, opened?.first)
        assertSame(mediaFile, opened?.second)
        assertEquals(document.id, media.downloadedDocumentId)
        assertEquals(VIEWER_UID, media.viewerUid)
        assertFalse(repository.downloadCalled)
        assertTrue(finished)
    }

    @Test
    fun `shared legacy document keeps Firebase Storage compatibility path`() {
        val document = document(objectKey = "")
        val legacyFile = File(context.cacheDir, "legacy")
        val repository = FakeDocumentRepository(document, legacyFile)
        val media = FakeMediaTransfers(File(context.cacheDir, "r2"))
        val controller = SafetyDocumentsController(context, scope, repository, media)
        controller.prepare(VIEWER_UID)

        var opened: Pair<SafetyDocument, File>? = null
        controller.openShared(VIEWER_UID, document.id, { source, file -> opened = source to file }) {}

        assertSame(document, opened?.first)
        assertSame(legacyFile, opened?.second)
        assertTrue(repository.downloadCalled)
        assertEquals(null, media.downloadedDocumentId)
    }

    private fun document(objectKey: String) = SafetyDocument(
        id = "document-id",
        ownerUid = "owner",
        storagePath = "users/owner/media/document-id",
        name = "Evidence.pdf",
        mimeType = "application/pdf",
        allowedUsers = listOf("owner", VIEWER_UID),
        objectKey = objectKey,
    )

    private class FakeDocumentRepository(
        private val document: SafetyDocument,
        private val file: File,
    ) : SafetyDocumentDataSource {
        var downloadCalled = false

        override suspend fun listFor(uid: String) = listOf(document)
        override fun observeFor(
            uid: String,
            onUpdate: (List<SafetyDocument>) -> Unit,
            onError: (String) -> Unit,
        ) = ChatSubscription {}
        override suspend fun getFor(uid: String, id: String) = document
        override suspend fun share(document: SafetyDocument, ownerUid: String, recipientUids: List<String>) = Unit
        override suspend fun delete(document: SafetyDocument, ownerUid: String) = Unit
        override suspend fun download(context: Context, document: SafetyDocument): File {
            downloadCalled = true
            return file
        }
    }

    private class FakeMediaTransfers(private val file: File) : MediaTransferOperations {
        var downloadedDocumentId: String? = null
        var viewerUid: String? = null

        override fun observeUploads(ownerUid: String) = Unit
        override fun acknowledgePublished(mediaIds: Set<String>) = Unit
        override fun queueDocument(
            uri: Uri,
            owner: AccountIdentity,
            ocrReview: OcrReview,
            onQueued: (String) -> Unit,
            onFailure: (String) -> Unit,
        ) = Unit
        override fun queueFile(
            file: File,
            name: String,
            mimeType: String,
            owner: AccountIdentity,
            kind: MediaKind,
            ocrReview: OcrReview,
            ocr: DocumentOcrMetadata?,
            onQueued: (String) -> Unit,
            onFailure: (String) -> Unit,
        ) = Unit
        override suspend fun downloadFile(document: SafetyDocument, viewerUid: String): File {
            downloadedDocumentId = document.id
            this.viewerUid = viewerUid
            return file
        }
        override fun clearState() = Unit
    }

    private companion object {
        const val VIEWER_UID = "viewer"
    }
}
