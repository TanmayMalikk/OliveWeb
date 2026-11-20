package com.olive.app.ui.users.user.documents

import android.net.Uri
import androidx.work.WorkInfo
import androidx.work.workDataOf
import androidx.work.ExistingWorkPolicy
import androidx.work.impl.WorkManagerImpl
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.media.*
import com.olive.app.data.safety.DocumentOcrMetadata
import com.olive.app.security.*
import com.olive.app.support.SessionRuntimeFixture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import java.io.*

@RunWith(RobolectricTestRunner::class)
class MediaTransferPreparationTest {
    private class Fixture:AutoCloseable {
        val session=SessionRuntimeFixture()
        val app=session.application
        val store=MediaTransferStore(app)
        val work=mock(WorkManagerImpl::class.java)
        val workerUpdates = MutableStateFlow<List<WorkInfo>>(emptyList())
        val controller:MediaTransferController
        init {
            WorkManagerImpl.setDelegate(work)
            `when`(work.getWorkInfosForUniqueWorkFlow(anyString())).thenReturn(workerUpdates)
            controller=MediaTransferController(app,session.scope,mock(MediaTicketDataSource::class.java),store,
                preprocessor=MediaPreprocessor { input,mime,_ -> ProcessedMedia(input,mime) },
                accountKeysetProvider={ uid -> AccountKeysetEnvelope.create(uid,AccountRecoveryKey.generate()) },
                preparationFailureResolver={ _,failure,_ -> failure.message ?: "Preparation failed" })
        }
        fun source()=File.createTempFile("synthetic-", ".txt",app.cacheDir).apply { writeText("Synthetic private attachment") }
        override fun close() { session.close();WorkManagerImpl.setDelegate(null) }
    }

    @Test
    fun preparationStoresOnlyCiphertextSanitizesTagsAndKeepsOcrInsideEncryptedMetadata()=runBlocking<Unit> {
        Fixture().use { f ->
            val source=f.source()
            var id=""
            f.controller.queueFile(source,"  report.txt  ","text/plain",AccountIdentity("owner","fixture_owner"),MediaKind.DOCUMENT,
                OcrReview.PASSED,DocumentOcrMetadata("Synthetic OCR",.9f,true),setOf(" Evidence ","Evidence","safety-plan:primary"),
                { id=it },{ fail(it) })
            f.session.await { id.isNotEmpty() }
            val job=f.store.get(id)!!
            val encrypted=File(job.source.filePath)
            assertFalse(encrypted.readText().contains("Synthetic private attachment"))
            val key=KeyManager.unwrapKey(job.source.wrappedKeys.getValue("owner"),job.source.encryptionVersion)
            val metadata=JSONObject(EncryptionUtils.decryptText(job.source.encryptedMetadata,key))
            assertEquals("report.txt",metadata.getString("name"))
            assertEquals("Synthetic OCR",metadata.getString("ocrText"))
            assertEquals(2,metadata.getJSONArray("tags").length())
            assertTrue(job.attachToSafetyPlan)
            val plain=ByteArrayOutputStream()
            encrypted.inputStream().use { MediaCipher.decrypt(it,plain,key) }
            assertEquals(source.readText(),plain.toString(Charsets.UTF_8))
            assertEquals(1,job.source.deviceWrappedKeys.size)
            f.controller.observeUploads("owner")
            f.session.await { f.controller.uploads.isNotEmpty() }
            assertEquals("report.txt",f.controller.uploads.single().name)
            f.store.write(job.copy(status=MediaTransferStatus.COMPLETE,mediaId="published"))
            f.controller.acknowledgePublished(setOf("published"))
            f.session.await { f.store.get(id)==null }
            source.delete();encrypted.delete()
        }
    }

    @Test
    fun voicePreparationPersistsTheConversationDraftAndDeletesTheTemporaryRecording()=runBlocking<Unit> {
        Fixture().use { f ->
            val source=f.source()
            val voice=PendingVoiceMessage("chat","fixture_owner","wrapped-room-key","duration=5")
            f.controller.queueVoiceMessage(source,AccountIdentity("owner","fixture_owner"),voice)
            assertFalse(source.exists())
            val job=f.store.all().single()
            assertEquals(voice,job.voiceDraft)
            assertEquals(MediaKind.AUDIO,job.source.kind)
            assertEquals(OcrReview.NOT_APPLICABLE,job.source.ocrReview)
            assertTrue(File(job.source.filePath).isFile)
            File(job.source.filePath).delete()
        }
    }

    @Test
    fun qualityReviewAndMissingFilesCannotCreateDurableUploads()=runBlocking<Unit> {
        Fixture().use { f ->
            val source=f.source()
            var error=""
            f.controller.queueFile(source,"report.txt","text/plain",AccountIdentity("owner","fixture_owner"),MediaKind.DOCUMENT,
                OcrReview.RETAKE_RECOMMENDED,null,emptySet(),{ fail() },{error=it})
            assertTrue(error.contains("quality review"))
            assertTrue(f.store.all().isEmpty())
            source.delete()
            f.controller.queueFile(source,"report.txt","text/plain",AccountIdentity("owner","fixture_owner"),MediaKind.DOCUMENT,
                OcrReview.NOT_APPLICABLE,null,emptySet(),{ fail() },{error=it})
            f.session.await { error.contains("empty") }
            assertTrue(f.store.all().isEmpty())
            assertTrue(f.controller.state is MediaTransferState.Failed)
        }
    }

    @Test
    fun pickerUploadTracksWorkerProgressAndManualRetryReusesTheSameEncryptedPayload() = runBlocking<Unit> {
        Fixture().use { f ->
            val source = f.source()
            try {
                val owner = AccountIdentity("owner", "fixture_owner")
                var id = ""
                f.controller.queueDocument(Uri.fromFile(source), owner, OcrReview.NOT_APPLICABLE,
                    onQueued = { id = it }, onFailure = { fail(it) })
                f.session.await { id.isNotBlank() }
                val initial = checkNotNull(f.store.get(id))
                val cipher = File(initial.source.filePath)
                try {
                    f.controller.observeUploads(owner.uid)
                    f.session.await { f.controller.uploads.isNotEmpty() }
                    val running = mock(WorkInfo::class.java)
                    `when`(running.state).thenReturn(WorkInfo.State.RUNNING)
                    `when`(running.progress).thenReturn(workDataOf(
                        MediaUploadWorker.BYTES_SENT to 12L, MediaUploadWorker.TOTAL_BYTES to 40L,
                    ))
                    f.workerUpdates.value = listOf(running)
                    f.session.await { f.controller.state == MediaTransferState.Uploading(id, 12L, 40L) }
                    val failed = mock(WorkInfo::class.java)
                    `when`(failed.state).thenReturn(WorkInfo.State.FAILED)
                    `when`(failed.outputData).thenReturn(workDataOf(MediaUploadWorker.ERROR to "Synthetic error"))
                    f.workerUpdates.value = listOf(failed)
                    f.session.await { (f.controller.state as? MediaTransferState.Failed)?.message == "Synthetic error" }

                    f.store.write(initial.copy(status = MediaTransferStatus.FAILED, error = "Synthetic error"))
                    f.controller.retryUpload(id)
                    val retried = checkNotNull(f.store.get(id))
                    assertEquals(MediaTransferStatus.QUEUED, retried.status)
                    assertNull(retried.error)
                    assertEquals(initial.source.filePath, retried.source.filePath)
                    assertTrue(cipher.isFile)
                    assertTrue(mockingDetails(f.work).invocations.any {
                        it.method.name == "enqueueUniqueWork" &&
                            it.arguments[0] == MediaUploadWorker.workName(id) &&
                            it.arguments[1] == ExistingWorkPolicy.REPLACE
                    })
                    f.controller.clearState()
                    assertEquals(MediaTransferState.Idle, f.controller.state)
                    assertTrue(f.controller.uploads.isEmpty())
                } finally {
                    cipher.delete()
                }
            } finally {
                source.delete()
            }
        }
    }

}
