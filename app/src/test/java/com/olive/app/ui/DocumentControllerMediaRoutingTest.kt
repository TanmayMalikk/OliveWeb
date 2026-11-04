// Tests how a user document opens through the correct media viewer.
package com.olive.app.ui

import android.content.Context
import android.net.Uri
import com.olive.app.data.AccountIdentity
import com.olive.app.data.ChatSubscription
import com.olive.app.data.DocumentOcrMetadata
import com.olive.app.data.DocumentListSnapshot
import com.olive.app.data.DocumentListSource
import com.olive.app.data.SafetyDocument
import com.olive.app.data.SafetyDocumentDataSource
import com.olive.app.data.media.MediaAccessError
import com.olive.app.data.media.MediaAccessException
import com.olive.app.data.media.MediaKind
import com.olive.app.data.media.MediaDeviceVerificationException
import com.olive.app.data.media.OcrReview
import com.olive.app.ui.users.user.documents.MediaTransferOperations
import com.olive.app.ui.users.user.documents.SafetyDocumentsController
import com.olive.app.ui.users.user.documents.mediaFailureResource
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

    @Test
    fun `App Check failure reports localized device verification guidance`() {
        assertEquals(
            com.olive.app.R.string.documents_device_verification_failed,
            mediaFailureResource(
                MediaDeviceVerificationException("internal detail", IllegalStateException()),
            ),
        )
    }

    // Checks that repository access failures reach the UI as the revoked-access message.
    @Test
    fun `domain access failure reports revoked document guidance`() {
        assertEquals(
            com.olive.app.R.string.documents_access_revoked,
            mediaFailureResource(MediaAccessException(MediaAccessError.ACCESS_REVOKED)),
        )
    }

    @Test
    fun `evidence change updates the visible document and persists it`() {
        val original = document(objectKey = "users/owner/media/r2-document")
        val repository = FakeDocumentRepository(original, File(context.cacheDir, "legacy"))
        val controller = SafetyDocumentsController(context, scope, repository, null)
        controller.prepare("owner")
        controller.load("owner", "owner_name", false)

        controller.setEvidence(original, "owner", true)

        assertTrue(repository.document.evidence)
        assertTrue(controller.state.files.single().evidence)
        assertTrue(controller.state.evidenceUpdates.isEmpty())
    }

    // Builds a sample document for this test.
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
        document: SafetyDocument,
        private val file: File,
    ) : SafetyDocumentDataSource {
        var document = document
            private set
        var downloadCalled = false

        // Checks the list for step with the test's sample data.
        override suspend fun listFor(uid: String) =
            DocumentListSnapshot(listOf(document), DocumentListSource.SERVER, 1)
        // Checks the observe for step with the test's sample data.
        override fun observeFor(
            uid: String,
            onUpdate: (DocumentListSnapshot) -> Unit,
            onError: (String) -> Unit,
        ): ChatSubscription {
            onUpdate(DocumentListSnapshot(listOf(document), DocumentListSource.SERVER, 1))
            return ChatSubscription {}
        }
        // Checks the get for step with the test's sample data.
        override suspend fun getFor(uid: String, id: String) = document
        override fun cachedFor(uid: String, id: String) = document
        // Checks the share step with the test's sample data.
        override suspend fun share(document: SafetyDocument, ownerUid: String, recipients: Map<String, String>) = Unit
        // Checks the delete step with the test's sample data.
        override suspend fun delete(document: SafetyDocument, ownerUid: String) = Unit
        override suspend fun setEvidence(
            document: SafetyDocument,
            ownerUid: String,
            evidence: Boolean,
        ) {
            this.document = this.document.copy(evidence = evidence)
        }
        // Checks the download step with the test's sample data.
        override suspend fun download(context: Context, document: SafetyDocument): File {
            downloadCalled = true
            return file
        }
        // Checks the approve current account devices step with the test's sample data.
        override suspend fun approveCurrentAccountDevices(
            document: SafetyDocument,
            currentUid: String,
            username: String,
        ) = Unit
    }

    private class FakeMediaTransfers(
        private val file: File,
        private val failure: Exception? = null,
    ) : MediaTransferOperations {
        var downloadedDocumentId: String? = null
        var viewerUid: String? = null

        // Checks the observe uploads step with the test's sample data.
        override fun observeUploads(ownerUid: String) = Unit
        // Checks the acknowledge published step with the test's sample data.
        override fun acknowledgePublished(mediaIds: Set<String>) = Unit
        // Checks the queue document step with the test's sample data.
        override fun queueDocument(
            uri: Uri,
            owner: AccountIdentity,
            ocrReview: OcrReview,
            tags: Set<String>,
            onQueued: (String) -> Unit,
            onFailure: (String) -> Unit,
        ) = Unit
        // Checks the queue file step with the test's sample data.
        override fun queueFile(
            file: File,
            name: String,
            mimeType: String,
            owner: AccountIdentity,
            kind: MediaKind,
            ocrReview: OcrReview,
            ocr: DocumentOcrMetadata?,
            tags: Set<String>,
            onQueued: (String) -> Unit,
            onFailure: (String) -> Unit,
        ) = Unit
        // Checks the download file step with the test's sample data.
        override suspend fun downloadFile(document: SafetyDocument, viewerUid: String): File {
            downloadedDocumentId = document.id
            this.viewerUid = viewerUid
            failure?.let { throw it }
            return file
        }
        override suspend fun makeAvailableOffline(document: SafetyDocument, viewerUid: String) = Unit
        override suspend fun removeOfflineCopy(document: SafetyDocument, viewerUid: String) = Unit
        override fun onDocumentSnapshot(
            viewerUid: String,
            documents: List<SafetyDocument>,
            serverConfirmed: Boolean,
        ) = Unit
        // Checks the clear state step with the test's sample data.
        override fun clearState() = Unit
    }

    private companion object {
        const val VIEWER_UID = "viewer"
    }
}
