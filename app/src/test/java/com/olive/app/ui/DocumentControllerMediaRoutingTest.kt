// Tests how a user document opens through the correct media viewer.
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

    // Inputs: the values shown below. Expected: shared R2 document uses authenticated media download.
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

    // Inputs: the values shown below. Expected: shared legacy document keeps Firebase Storage compatibility path.
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

    // returns the document for document controller media routing test. The test gives the code a clear input and checks the expected result.
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

        // Lists for for document controller media routing test. The test gives the code a clear input and checks the expected result.
        override suspend fun listFor(uid: String) = listOf(document)
        // Document controller media routing test: Observes for. The test gives the code a clear input and checks the expected result.
        override fun observeFor(
            uid: String,
            onUpdate: (List<SafetyDocument>) -> Unit,
            onError: (String) -> Unit,
        ) = ChatSubscription {}
        // Loads for for document controller media routing test. The test gives the code a clear input and checks the expected result.
        override suspend fun getFor(uid: String, id: String) = document
        // Document controller media routing test: Shares the selected item. The test gives the code a clear input and checks the expected result.
        override suspend fun share(document: SafetyDocument, ownerUid: String, recipients: Map<String, String>) = Unit
        // Deletes the selected item for document controller media routing test. The test gives the code a clear input and checks the expected result.
        override suspend fun delete(document: SafetyDocument, ownerUid: String) = Unit
        // Document controller media routing test: Downloads the selected file. The test gives the code a clear input and checks the expected result.
        override suspend fun download(context: Context, document: SafetyDocument): File {
            downloadCalled = true
            return file
        }
        // returns the approve current account devices for document controller media routing test. The test gives the code a clear input and checks the expected result.
        override suspend fun approveCurrentAccountDevices(
            document: SafetyDocument,
            currentUid: String,
            username: String,
        ) = Unit
    }

    private class FakeMediaTransfers(private val file: File) : MediaTransferOperations {
        var downloadedDocumentId: String? = null
        var viewerUid: String? = null

        // Observes uploads for document controller media routing test. The test gives the code a clear input and checks the expected result.
        override fun observeUploads(ownerUid: String) = Unit
        // Document controller media routing test: returns the acknowledge published. The test gives the code a clear input and checks the expected result.
        override fun acknowledgePublished(mediaIds: Set<String>) = Unit
        // returns the queue document for document controller media routing test. The test gives the code a clear input and checks the expected result.
        override fun queueDocument(
            uri: Uri,
            owner: AccountIdentity,
            ocrReview: OcrReview,
            onQueued: (String) -> Unit,
            onFailure: (String) -> Unit,
        ) = Unit
        // returns the queue file for document controller media routing test. The test gives the code a clear input and checks the expected result.
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
        // Downloads file for document controller media routing test. The test gives the code a clear input and checks the expected result.
        override suspend fun downloadFile(document: SafetyDocument, viewerUid: String): File {
            downloadedDocumentId = document.id
            this.viewerUid = viewerUid
            return file
        }
        // Document controller media routing test: Removes state. The test gives the code a clear input and checks the expected result.
        override fun clearState() = Unit
    }

    private companion object {
        const val VIEWER_UID = "viewer"
    }
}
