package com.olive.app.ui.users.user.documents

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.media.MediaKind
import com.olive.app.data.media.MediaTicketDataSource
import com.olive.app.data.media.MediaTransferState
import com.olive.app.data.media.MediaTransferStore
import com.olive.app.data.media.OcrReview
import com.olive.app.data.media.OfflineMediaAvailability
import com.olive.app.data.media.OfflineMediaCache
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.security.AccountKeysetEnvelope
import com.olive.app.security.AccountRecoveryKey
import com.olive.app.support.TestAndroidKeyStore
import java.io.File
import java.security.Security
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class EncryptedMediaTransferControllerDeepTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before
    fun cleanKeyStore() {
        Security.removeProvider("AndroidKeyStore")
        if (runCatching { WorkManager.getInstance(context) }.isFailure) {
            WorkManager.initialize(context, Configuration.Builder().build())
        }
    }

    @Test
    fun queueFileOpenPendingUploadRetryAcknowledgeAndOfflineCopyCoverPaths() = runBlocking {
        TestAndroidKeyStore.install().use {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val repo = mock(MediaTicketDataSource::class.java)
            val store = MediaTransferStore(context)
            val cache = OfflineMediaCache(context)
            val keyset = AccountKeysetEnvelope.create("owner-1", AccountRecoveryKey.generate())
            val controller = MediaTransferController(
                context = context,
                scope = scope,
                repository = repo,
                store = store,
                offlineCache = cache,
                accountKeysetProvider = { keyset },
            )
            val owner = AccountIdentity("owner-1", "alex")

            // Advisory OCR retake branch fails immediately without queueing
            val src = File.createTempFile("note", ".txt").apply { writeText("Hello safety world") }
            var retakeErr = ""
            controller.queueFile(
                file = src,
                name = "note.txt",
                mimeType = "text/plain",
                owner = owner,
                kind = MediaKind.DOCUMENT,
                ocrReview = OcrReview.RETAKE_RECOMMENDED,
                ocr = null,
                onQueued = {},
                onFailure = { retakeErr = it },
            )
            assertTrue(retakeErr.contains("another capture"))
            assertTrue(controller.state is MediaTransferState.Failed)

            // Observe uploads and enqueue a real encrypted document
            controller.observeUploads("owner-1")
            var queuedJobId = ""
            var failureMsg = ""
            controller.queueFile(
                file = src,
                name = "note.txt",
                mimeType = "text/plain",
                owner = owner,
                kind = MediaKind.DOCUMENT,
                ocrReview = OcrReview.NOT_APPLICABLE,
                ocr = null,
                onQueued = { queuedJobId = it },
                onFailure = { failureMsg = it },
            )
            val deadline = System.nanoTime() + 4_000_000_000L
            while (queuedJobId.isEmpty() && failureMsg.isEmpty() && System.nanoTime() < deadline) {
                delay(15)
            }
            assertTrue("Expected queued job, got failure: $failureMsg", queuedJobId.isNotEmpty())

            // Open pending upload decrypts local preview file cleanly
            val opened = controller.openPendingUpload(queuedJobId, "owner-1")
            assertNotNull(opened)
            assertEquals("note.txt", opened!!.first.name)
            assertEquals("Hello safety world", opened.second.readText())

            // Retry & acknowledgePublished & removeOfflineCopy branches
            controller.retry(queuedJobId)
            assertTrue(controller.state is MediaTransferState.Queued)
            controller.acknowledgePublished(setOf("missing-id"))

            val legacyDoc = SafetyDocument("d-leg", "owner-1", "p", "old.txt", "text/plain", listOf("owner-1"), 100L)
            assertNull(controller.downloadPreviewFile(legacyDoc, "owner-1"))
            val legacyRejected = runCatching { controller.downloadFile(legacyDoc, "owner-1") }.isFailure
            assertTrue(legacyRejected)

            controller.removeOfflineCopy(legacyDoc.copy(objectKey = "k-1"), "owner-1")
            assertEquals(OfflineMediaAvailability.NOT_CACHED, controller.offlineCopies["d-leg"])

            controller.clearState()
            scope.cancel()
        }
    }
}
