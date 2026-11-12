package com.olive.app.ui.users.user.documents

import android.content.Context
import com.olive.app.R
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.media.CloudMediaRecord
import com.olive.app.data.media.MediaCompletedPart
import com.olive.app.data.media.MediaDownloadTicket
import com.olive.app.data.media.MediaKind
import com.olive.app.data.media.MediaPreprocessor
import com.olive.app.data.media.MediaPreparationMemoryException
import com.olive.app.data.media.MediaTicketDataSource
import com.olive.app.data.media.MediaUploadJob
import com.olive.app.data.media.MediaUploadSession
import com.olive.app.data.media.MediaUploadTicket
import com.olive.app.data.media.OcrReview
import com.olive.app.data.media.ProcessedMedia
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MediaPreparationMemoryTest {
    @Test
    fun memoryFailuresMapToLocalizedGuidanceResources() {
        assertEquals(
            R.string.documents_video_memory_failed,
            preparationFailureResource(OutOfMemoryError("private allocation detail"),MediaKind.VIDEO),
        )
        listOf(MediaKind.DOCUMENT,MediaKind.IMAGE,MediaKind.AUDIO,MediaKind.OTHER).forEach { kind ->
            assertEquals(
                R.string.documents_media_memory_failed,
                preparationFailureResource(MediaPreparationMemoryException(),kind),
            )
        }
        assertEquals(null,preparationFailureResource(IllegalArgumentException("invalid"),MediaKind.VIDEO))
    }

    @Test
    fun memoryFailureUsesGuidanceAndDeletesPartialCiphertext() {
        val context:Context=RuntimeEnvironment.getApplication()
        val outbox=File(context.noBackupFilesDir,"media-outbox")
        val filesBefore=outbox.listFiles()?.map(File::getName)?.toSet().orEmpty()
        val latch=CountDownLatch(1)
        var message:String?=null
        val controller=MediaTransferController(
            context=context,
            scope=CoroutineScope(Dispatchers.Unconfined),
            repository=UnusedMediaRepository,
            preprocessor=MediaPreprocessor { _,_,_ ->
                ProcessedMedia(FailingAfterFirstChunkInput(),"video/mp4","mp4")
            },
            preparationFailureResolver={ _,failure,kind ->
                if(failure is OutOfMemoryError && kind==MediaKind.VIDEO) EXPECTED_VIDEO_GUIDANCE
                else error("Unexpected preparation failure: $failure")
            },
        )
        val source=File.createTempFile("memory-test-",".mp4",context.cacheDir).apply {
            writeBytes(byteArrayOf(1))
        }

        try {
            controller.queueFile(
                file=source,
                name="large-video.mp4",
                mimeType="video/mp4",
                owner=AccountIdentity("owner","owner"),
                kind=MediaKind.VIDEO,
                ocrReview=OcrReview.NOT_APPLICABLE,
                ocr=null,
                tags=emptySet(),
                onQueued={ error("OOM media must not be queued") },
                onFailure={ message=it;latch.countDown() },
            )

            assertTrue("Timed out waiting for the preparation failure",latch.await(5,TimeUnit.SECONDS))
            assertEquals(EXPECTED_VIDEO_GUIDANCE,message)
            assertEquals(filesBefore,outbox.listFiles()?.map(File::getName)?.toSet().orEmpty())
        } finally {
            source.delete()
        }
    }

    private class FailingAfterFirstChunkInput:InputStream() {
        private var delivered=false

        override fun read():Int=throw UnsupportedOperationException()

        override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
            if(delivered) throw OutOfMemoryError("internal allocation detail")
            delivered=true
            val count=minOf(length,32)
            buffer.fill(7,offset,offset+count)
            return count
        }
    }

    private object UnusedMediaRepository:MediaTicketDataSource {
        override suspend fun createUpload(job:MediaUploadJob):MediaUploadSession=error("unused")
        override suspend fun uploadTicket(jobId:String,partNumber:Int):MediaUploadTicket=error("unused")
        override suspend fun completeUpload(jobId:String,parts:List<MediaCompletedPart>):CloudMediaRecord=error("unused")
        override suspend fun abortUpload(jobId:String)=Unit
        override suspend fun downloadTicket(mediaId:String):MediaDownloadTicket=error("unused")
        override suspend fun updateAccess(
            mediaId:String,
            wrappedKeys:Map<String,String>,
            deviceWrappedKeys:Map<String,String>,
        ):CloudMediaRecord=error("unused")
        override suspend fun updateDeviceKeys(
            mediaId:String,
            deviceWrappedKeys:Map<String,String>,
        ):CloudMediaRecord=error("unused")
        override suspend fun updateEvidence(mediaId:String,evidence:Boolean)=Unit
        override suspend fun deleteMedia(mediaId:String)=Unit
    }

    private companion object {
        const val EXPECTED_VIDEO_GUIDANCE=
            "This video requires more memory than this device can safely use. " +
                "Try recording at 1080p/30 FPS or choose a shorter video."
    }
}
