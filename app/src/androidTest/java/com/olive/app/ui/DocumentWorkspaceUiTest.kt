package com.olive.app.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import com.olive.app.ui.session.EmergencyAlert
import com.olive.app.data.media.MediaTransferStatus
import com.olive.app.data.media.MediaUploadPresentation
import com.olive.app.ui.users.user.documents.PendingUploadCard
import com.olive.app.ui.users.admin.EmergencyResponseScreen
import com.olive.app.ui.users.shared.messaging.SharedMediaMessage
import com.olive.app.ui.users.shared.messaging.VoiceMessageBubble
import java.nio.ByteBuffer
import java.nio.ByteOrder
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.R
import com.olive.app.data.account.*
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.safety.*
import com.olive.app.security.*
import com.olive.app.ui.session.SessionDataSources
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.user.documents.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class DocumentWorkspaceUiTest {
    @Test
    fun uploadCardAnnouncesProgressAndOnlyOffersRetryForFailureOrBackoff() {
        val upload = mutableStateOf(MediaUploadPresentation(
            "job", "owner", "Fixture attachment.pdf", "application/pdf",
            MediaTransferStatus.QUEUED, 25, 100, "", null, 1,
        ))
        var retries = 0
        compose.setContent { OliveTheme { PendingUploadCard(upload.value) { retries++ } } }
        val retry = compose.onNodeWithText(text(R.string.retry))
        fun verify(status: MediaTransferStatus, label: String, error: String? = null) {
            compose.runOnIdle { upload.value = upload.value.copy(status = status, error = error) }
            val announcement = listOfNotNull(upload.value.name, label, error).joinToString(". ")
            compose.onNodeWithContentDescription(announcement).assertIsDisplayed()
        }
        verify(MediaTransferStatus.QUEUED, text(R.string.documents_upload_queued))
        retry.assertDoesNotExist()
        verify(MediaTransferStatus.CREATING_SESSION, text(R.string.documents_upload_starting))
        verify(MediaTransferStatus.UPLOADING, context.getString(R.string.documents_upload_progress, 25))
        retry.assertDoesNotExist()
        verify(MediaTransferStatus.FINALIZING, text(R.string.documents_upload_finalizing))
        verify(MediaTransferStatus.COMPLETE, text(R.string.documents_upload_complete))
        verify(MediaTransferStatus.CANCELLED, text(R.string.documents_upload_cancelled))
        retry.assertDoesNotExist()
        verify(MediaTransferStatus.FAILED, text(R.string.documents_upload_failed), "Synthetic upload error")
        retry.performClick()
        verify(MediaTransferStatus.QUEUED, text(R.string.documents_upload_waiting_retry), "Synthetic network error")
        retry.performClick()
        compose.runOnIdle { assertEquals(2, retries) }
    }

    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun text(id:Int)=context.getString(id)
    private class Documents:SafetyDocumentDataSource {
        var rows=listOf(
            SafetyDocument("photo","owner","synthetic/photo","Synthetic photo","image/jpeg",listOf("owner")),
            SafetyDocument("video","owner","synthetic/video","Synthetic video","video/mp4",listOf("owner")),
            SafetyDocument("pdf","owner","synthetic/pdf","Synthetic report.pdf","application/pdf",listOf("owner")))
        private var changed:((DocumentListSnapshot)->Unit)?=null
        var shared=false
        var downloaded: File? = null
        override suspend fun listFor(uid:String)=DocumentListSnapshot(rows,DocumentListSource.SERVER,100)
        override fun observeFor(uid:String,onUpdate:(DocumentListSnapshot)->Unit,onError:(String)->Unit):ChatSubscription {
            changed=onUpdate;onUpdate(DocumentListSnapshot(rows,DocumentListSource.SERVER,100));return ChatSubscription {changed=null}
        }
        override suspend fun getFor(uid:String,id:String)=rows.single {it.id==id}
        override fun cachedFor(uid: String, id: String) = rows.firstOrNull { it.id == id && uid in it.allowedUsers }
        override suspend fun share(document:SafetyDocument,ownerUid:String,recipients:Map<String,String>){shared=true}
        override suspend fun delete(document:SafetyDocument,ownerUid:String){rows=rows.filterNot {it.id==document.id};changed?.invoke(DocumentListSnapshot(rows,DocumentListSource.SERVER,101))}
        override suspend fun setEvidence(document:SafetyDocument,ownerUid:String,evidence:Boolean){rows=rows.map {if(it.id==document.id) it.copy(evidence=evidence) else it}}
        override suspend fun approveCurrentAccountDevices(document:SafetyDocument,currentUid:String,username:String){}
        override suspend fun download(context:Context,document:SafetyDocument):File {
            if (document.mimeType == "audio/wav") {
                val waveform = File.createTempFile("synthetic-voice-", ".wav", context.cacheDir)
                val dataBytes = 8_000 * 2 * 6
                val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                    .put("RIFF".toByteArray()).putInt(dataBytes + 36).put("WAVEfmt ".toByteArray())
                    .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
                    .putShort(2).putShort(16).put("data".toByteArray()).putInt(dataBytes).array()
                waveform.outputStream().use { it.write(header); it.write(ByteArray(dataBytes)) }
                downloaded = waveform
                return waveform
            }
            val target=File.createTempFile("synthetic-report-",".pdf",context.cacheDir)
            val pdf=PdfDocument()
            try {
                repeat(3) { index -> val page=pdf.startPage(PdfDocument.PageInfo.Builder(300,400,index+1).create());page.canvas.drawText("Synthetic page ${index+1}",24f,40f,Paint().apply {textSize=14f});pdf.finishPage(page) }
                target.outputStream().use(pdf::writeTo)
            } finally { pdf.close() }
            return target
        }
    }
    private fun session(documents:Documents):SessionViewModel {
        lateinit var session:SessionViewModel
        val app=compose.activity.application
        val sources=SessionDataSources(LocalAccountRepository(EncryptedVault(app,"ui-fixture-accounts")),null,null,null,null,null,documents,null,null,null,null)
        instrumentation.runOnMainSync {
            session=ViewModelProvider(compose.activity,object:ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T=SessionViewModel(app,sources,false) as T
            })[SessionViewModel::class.java]
        }
        compose.waitUntil(15000) {var ready=false;instrumentation.runOnMainSync {ready=!session.busy};ready}
        instrumentation.runOnMainSync {
            session.runtime.enrollment=DeviceEnrollment("owner","")
            session.runtime.state.accountRole=AccountRole.USER
            session.runtime.state.coverUsername="owner_fixture"
            session.runtime.state.profile=UserProfile("owner_fixture","Synthetic owner","","","2000-01-01","English","","Edmonton","")
            session.runtime.pinAuthorizedUid="owner";session.runtime.pinAuthorizedEpoch=session.runtime.epoch
            session.runtime.state.screen=SessionScreen.Safe;session.runtime.state.page=SafePage.Documents
            session.documents.load("owner","owner_fixture",false)
        }
        return session
    }
    @Test
    fun sharedDocumentsOpenPrivatelyAndShowAnErrorWhenAnAttachmentIsUnavailable() {
        val source = Documents().apply {
            rows += SafetyDocument("encrypted", "owner", "", "Protected file", "application/pdf",
                listOf("owner"), objectKey = "synthetic/encrypted")
        }
        val session = session(source)
        var opened: Pair<String, File>? = null
        compose.setContent { OliveTheme {
            androidx.compose.foundation.layout.Column {
                SharedMediaMessage("pdf", "Report", session, false, { document, file ->
                    opened = document.id to file
                }, sender = "Synthetic sender")
                SharedMediaMessage("encrypted", "Protected file", session, false, { _, _ -> })
                SharedMediaMessage("missing", "", session, true, { _, _ -> error("Missing file opened") })
            }
        } }
        compose.onNodeWithText("Synthetic report.pdf").assertIsDisplayed()
        compose.onNodeWithTag("shared_media_offline_encrypted").assertExists()
        compose.onNodeWithTag("shared_media_pdf").performClick()
        compose.waitUntil(10_000) { opened?.first == "pdf" }
        try {
            assertTrue(opened!!.second.isFile)
        } finally {
            opened?.second?.delete()
        }
        compose.onNodeWithTag("shared_media_missing").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("shared_media_error_missing").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { session.runtime.state.accountRole = AccountRole.STAFF }
        compose.onNodeWithTag("shared_media_offline_encrypted").assertDoesNotExist()
    }

    @Test
    fun filtersShowOnlyTheirMediaTypeAndEvidenceAndSharingChangesUseTheOwner() {
        val source=Documents();val session=session(source)
        compose.setContent {OliveTheme {SafetyDocumentsScreen(session)}}
        compose.onNodeWithText(text(R.string.safety_storage_photos)).performClick()
        compose.onNodeWithTag("document_photo").assertExists()
        compose.onNodeWithTag("document_pdf").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.safety_storage_videos)).performClick()
        compose.onNodeWithTag("document_video").assertExists()
        compose.onNodeWithTag("document_photo").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.safety_storage_documents)).performClick()
        compose.onNodeWithTag("document_evidence_pdf").performClick()
        compose.waitUntil(10000) {source.rows.single {it.id=="pdf"}.evidence}
        compose.onNodeWithContentDescription(text(R.string.share)).performClick()
        compose.onNodeWithText(text(R.string.save)).performClick()
        compose.waitUntil(10000) {source.shared}
        compose.onNodeWithContentDescription(text(R.string.remove)).performClick()
        compose.waitUntil(10000) {source.rows.none {it.id=="pdf"}}
        compose.onNodeWithText(text(R.string.documents_empty)).assertExists()
    }
    @Test
    fun reportPreviewCanAdvanceWithArrowsAndSwipeThenCloseWithoutLeavingDocuments() {
        val session=session(Documents())
        compose.setContent {OliveTheme {SafetyDocumentsScreen(session)}}
        compose.onNodeWithText(text(R.string.safety_storage_documents)).performClick()
        compose.onNodeWithTag("document_open_pdf").performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithTag("pdf_page_content").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("pdf_next_page").performClick()
        compose.onNodeWithTag("pdf_page_number").assertTextEquals(context.getString(R.string.documents_pdf_page,2,3))
        compose.onNodeWithTag("pdf_page_content").performTouchInput {swipeLeft()}
        compose.onNodeWithTag("pdf_page_number").assertTextEquals(context.getString(R.string.documents_pdf_page,3,3))
        compose.onNodeWithTag("pdf_previous_page").performClick()
        compose.onNodeWithTag("pdf_page_number").assertTextEquals(context.getString(R.string.documents_pdf_page,2,3))
        compose.onNodeWithTag("media_zoom_in").performClick()
        compose.onNodeWithTag("media_rotate").performClick()
        compose.onNodeWithTag("media_zoom_reset").performClick()
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNodeWithTag("document_open_pdf").assertExists()
    }
    @Test
    fun attachmentChooserListsOnlyOwnedStorageAndCameraSelectionPreservesAttachmentTags() {
        val source=Documents();val session=session(source)
        var selected:SafetyDocument?=null;var dismissed=false
        compose.setContent {OliveTheme {SafetyDocumentUploadFlow(session,true,{dismissed=true},tags=setOf("Evidence"),onSelectExisting={selected=it})}}
        compose.onNodeWithTag("documents_choose_olive_storage").performClick()
        compose.onNodeWithTag("olive_storage_photo").performClick()
        compose.runOnIdle {assertEquals("photo",selected?.id);assertTrue(dismissed)}
        compose.onNodeWithText(text(R.string.back)).performClick()
        compose.onNodeWithTag("documents_choose_camera").performClick()
        compose.runOnIdle {assertEquals(SafePage.Camera,session.page);assertEquals(setOf("Evidence"),session.pendingCameraUploadTags)}
    }

    @Test
    fun emergencyWorkspaceSeparatesResolvedRequestsAndOpensTheSharedPdf() {
        val fixture = session(Documents())
        val alerts = mutableStateOf<List<EmergencyAlert>?>(null)
        val active = EmergencyAlert("active", "tracked", "Synthetic active owner", 1_700_000_000_000, "")
        val resolved = EmergencyAlert("resolved", "completed", "Synthetic resolved owner", 1_700_000_000_000, "pdf", 1_700_000_001_000)
        var tracking: String? = null
        compose.setContent {
            OliveTheme {
                EmergencyResponseScreen(fixture, alerts.value, null, onBack = {}, onTrack = { tracking = it })
            }
        }
        compose.onNodeWithTag("loading_skeleton").assertExists()
        compose.runOnIdle { alerts.value = listOf(active, resolved) }
        compose.onNodeWithText(active.ownerName).assertExists()
        compose.onNodeWithText(resolved.ownerName).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.emergency_response_waiting_pdf)).assertExists()
        compose.onNodeWithText(text(R.string.emergency_response_track)).performClick()
        compose.runOnIdle { assertEquals(active.ownerUid, tracking) }
        compose.onNodeWithText(text(R.string.emergency_response_resolve)).assertExists()
        compose.onNodeWithText(text(R.string.emergency_response_resolved)).performClick()
        compose.onNodeWithText(active.ownerName).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.emergency_response_reopen)).assertExists()
        compose.onNodeWithText(text(R.string.emergency_response_pdf)).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("pdf_page_content").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("pdf_page_number").assertTextEquals(context.getString(R.string.documents_pdf_page, 1, 3))
    }


    @Test
    fun receivedVoiceMessagePlaysFromThePrivateViewerAndClearsItsFileWhenClosed() {
        val source = Documents().apply {
            rows += SafetyDocument("voice", "friend", "synthetic/voice", "Synthetic voice", "audio/wav", listOf("owner"))
        }
        val fixture = session(source)
        val mounted = mutableStateOf(true)
        compose.setContent {
            OliveTheme {
                if (mounted.value) VoiceMessageBubble("voice", "6000,01234543210", fixture, mine = false)
            }
        }
        compose.onNodeWithText("0:06").assertExists()
        compose.onNodeWithContentDescription(text(R.string.voice_play)).performClick()
        val pause = hasContentDescription(text(R.string.voice_pause))
        compose.waitUntil(10_000) { compose.onAllNodes(pause).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("1×").performClick()
        compose.onNodeWithText("1.5×").assertExists()
        compose.onNode(pause).performClick()
        compose.onNodeWithContentDescription(text(R.string.voice_play)).assertExists()
        val opened = checkNotNull(source.downloaded)
        assertTrue(opened.isFile)
        compose.runOnIdle { mounted.value = false }
        compose.waitUntil(10_000) { !opened.exists() }
    }

}
