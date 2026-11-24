package com.olive.app.ui.users.user.documents

import android.net.Uri
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.media.*
import com.olive.app.data.safety.*
import com.olive.app.support.SessionRuntimeFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SafetyDocumentActionsTest {
    private class Fixture:AutoCloseable {
        val f=SessionRuntimeFixture()
        val repo=mock(SafetyDocumentDataSource::class.java)
        val media=mock(MediaTransferOperations::class.java)
        val doc=SafetyDocument("doc","owner","","Synthetic.pdf","application/pdf",listOf("owner"),objectKey="objects/doc")
        lateinit var snapshot:(DocumentListSnapshot)->Unit
        lateinit var error:(String)->Unit
        var closed=false
        val controller=SafetyDocumentsController(f.application, f.scope, repo, media, failureMessageResolver = { "Access denied" })
        init {
            doAnswer { call -> snapshot=call.getArgument(1);error=call.getArgument(2);ChatSubscription { closed=true } }.`when`(repo)
                .observeFor(anyString(),any<(DocumentListSnapshot)->Unit>() ?: {},any<(String)->Unit>() ?: {})
            controller.load("owner","fixture_owner",false)
            snapshot(DocumentListSnapshot(listOf(doc),DocumentListSource.SERVER,100))
            runBlocking { `when`(repo.listFor("owner")).thenReturn(DocumentListSnapshot(listOf(doc),DocumentListSource.SERVER,101)) }
        }
        override fun close() { controller.clear();f.close() }
    }
    @Test
    fun serverSnapshotsAcknowledgePublishedFilesAndOldAccountCallbacksAreIgnored() {
        Fixture().use { t ->
            assertEquals(100L,t.controller.state.lastSyncedAt)
            verify(t.media).acknowledgePublished(setOf("doc"))
            t.error("Temporary failure")
            assertNull(t.controller.state.error)
            t.controller.prepare("another")
            t.snapshot(DocumentListSnapshot(listOf(t.doc),DocumentListSource.SERVER))
            assertTrue(t.controller.state.files.isEmpty())
            assertEquals("another",t.controller.state.ownerUid)
            assertTrue(t.closed)
        }
    }
    @Test
    fun shareDeleteAndOfflineActionsUseTheActiveOwnerAndRefreshAfterWrites()=runBlocking<Unit> {
        Fixture().use { t ->
            var completed=false
            t.controller.share(t.doc,"owner",mapOf("friend" to "fixture_friend")) { completed=true }
            assertTrue(completed)
            verify(t.repo).share(t.doc,"owner",mapOf("friend" to "fixture_friend"))
            t.controller.makeAvailableOffline(t.doc)
            verify(t.media).makeAvailableOffline(t.doc,"owner")
            t.controller.removeOfflineCopy(t.doc)
            verify(t.media).removeOfflineCopy(t.doc,"owner")
            t.controller.delete(t.doc,"owner")
            verify(t.repo).delete(t.doc,"owner")
            assertFalse(t.controller.state.loading)
        }
    }
    @Test
    fun planGrantsContinuePastOneRecipientFailureButReportWhenEveryGrantFails()=runBlocking<Unit> {
        Fixture().use { t ->
            `when`(t.repo.grantAccess(t.doc,"owner","bad","bad_fixture",true)).thenAnswer { error("Bad recipient") }
            t.controller.grantPlanAccess(listOf(t.doc),"owner",linkedMapOf("bad" to "bad_fixture","good" to "good_fixture"))
            verify(t.repo).grantAccess(t.doc,"owner","good","good_fixture",true)
            assertNull(t.controller.state.error)
            t.controller.grantPlanAccess(listOf(t.doc),"owner",mapOf("bad" to "bad_fixture"))
            assertEquals("Bad recipient",t.controller.state.error)
        }
    }
    @Test
    fun pickerAndCaptureCallbacksEndProgressAndOnlySuccessfulCaptureDeletesItsSource() {
        Fixture().use { t ->
            t.controller.upload(Uri.parse("content://fixture/document"),AccountIdentity("owner","fixture_owner"),"Upload failed")
            val picker=mockingDetails(t.media).invocations.last { it.method.name=="queueDocument" }
            @Suppress("UNCHECKED_CAST") val failure=picker.arguments[5] as (String)->Unit
            failure("")
            assertFalse(t.controller.state.loading)
            assertEquals("Upload failed",t.controller.state.error)
            val file=File.createTempFile("synthetic-", ".jpg",t.f.application.cacheDir).apply { writeBytes(byteArrayOf(1)) }
            var done=false
            t.controller.uploadCaptured(file,"capture.jpg","image/jpeg",AccountIdentity("owner","fixture_owner"),"Capture failed",MediaKind.IMAGE) { done=true }
            val capture=mockingDetails(t.media).invocations.last { it.method.name=="queueFile" }
            assertEquals(OcrReview.NOT_APPLICABLE,capture.arguments[5])
            @Suppress("UNCHECKED_CAST") val success=capture.arguments[8] as (String)->Unit
            success("queued")
            assertTrue(done)
            assertFalse(file.exists())
            assertFalse(t.controller.state.loading)
        }
    }

    @Test
    fun evidenceSelectionSurvivesAnOlderSnapshotUntilTheServerConfirmsOrRejectsIt() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val write = CompletableDeferred<Unit>()
            doAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                val continuation = invocation.rawArguments.last() as kotlin.coroutines.Continuation<Unit>
                val operation: suspend () -> Unit = { write.await() }
                operation.startCoroutineUninterceptedOrReturn(continuation)
            }.`when`(fixture.repo).setEvidence(fixture.doc, "owner", true)
            fixture.controller.setEvidence(fixture.doc, "owner", true)
            assertTrue(fixture.controller.state.files.single().evidence)
            assertEquals(setOf("doc"), fixture.controller.state.evidenceUpdates)
            fixture.snapshot(DocumentListSnapshot(listOf(fixture.doc), DocumentListSource.SERVER, 101))
            assertTrue(fixture.controller.state.files.single().evidence)
            write.completeExceptionally(IllegalStateException("Synthetic permission denied"))
            fixture.f.await { fixture.controller.state.evidenceUpdates.isEmpty() }
            assertFalse(fixture.controller.state.files.single().evidence)
            assertEquals("Synthetic permission denied", fixture.controller.state.error)
        }
    }

    @Test
    fun openingTheSameDocumentTwiceDownloadsOnceAndAccountSwitchDiscardsTheStalePlaintext() = runBlocking<Unit> {
        Fixture().use { fixture ->
            val download = CompletableDeferred<OpenedMedia>()
            doAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                val continuation = invocation.rawArguments.last() as kotlin.coroutines.Continuation<OpenedMedia>
                val operation: suspend () -> OpenedMedia = { download.await() }
                operation.startCoroutineUninterceptedOrReturn(continuation)
            }.`when`(fixture.media).downloadFile(fixture.doc, "owner")
            val local = File.createTempFile("synthetic-opened-", ".pdf", fixture.f.application.cacheDir)
            try {
                fixture.controller.download(fixture.doc) { _, _ -> fail("A previous account's document must stay closed") }
                fixture.controller.download(fixture.doc) { _, _ -> fail("A duplicate request must stay closed") }
                assertEquals(setOf("doc"), fixture.controller.state.openingDocumentIds)
                verify(fixture.media).downloadFile(fixture.doc, "owner")
                fixture.controller.prepare("another")
                download.complete(OpenedMedia(fixture.doc, local))
                fixture.f.await { !local.exists() }
                assertEquals("another", fixture.controller.state.ownerUid)
                assertTrue(fixture.controller.state.openingDocumentIds.isEmpty())
            } finally {
                local.delete()
            }
        }
    }

}
