package com.olive.app.data.media

import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import androidx.work.impl.WorkManagerImpl
import androidx.work.ProgressUpdater
import com.google.common.util.concurrent.Futures
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.functions.FirebaseFunctionsException
import com.olive.app.data.messaging.MessageOutboxStore
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import com.olive.app.support.TestAndroidKeyStore
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class MediaUploadWorkerExecutionTest {
    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun resumedMultipartUploadSkipsCompletedBytesAndCommitsReceiptsInOrder() {
        val accepted = mutableListOf<Pair<String, ByteArray>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { request ->
            accepted.add(request.requestURI.path to request.requestBody.readBytes())
            request.responseHeaders.add("ETag", 34.toChar() + "etag-${request.requestURI.path.substringAfterLast('/')}" + 34.toChar())
            request.sendResponseHeaders(200, -1)
            request.close()
        }
        server.start()
        try {
            val file = File(context.cacheDir, "multipart.olive").apply { writeBytes(ByteArray(13) { it.toByte() }) }
            val initial = job(file).copy(
                mediaId = "media", objectKey = "encrypted/media", uploadId = "multipart",
                mode = MediaUploadMode.MULTIPART, partSize = 5, completedParts = mapOf(1 to "already-confirmed"),
            )
            withWorker(initial, url = "http://127.0.0.1:${server.address.port}") { worker, current, api, states ->
                assertEquals(ListenableWorker.Result.success(Data.Builder().putString("mediaId", "media").build()), runBlocking { worker.doWork() })
                assertEquals(listOf("/2", "/3"), accepted.map { it.first })
                assertArrayEquals(byteArrayOf(5, 6, 7, 8, 9), accepted[0].second)
                assertArrayEquals(byteArrayOf(10, 11, 12), accepted[1].second)
                assertEquals(MediaTransferStatus.COMPLETE, current().status)
                assertEquals(mapOf(1 to "already-confirmed", 2 to "etag-2", 3 to "etag-3"), current().completedParts)
                assertTrue(states.contains(MediaTransferStatus.FINALIZING))
                runBlocking {
                    verify(api).completeUpload("job", listOf(
                        MediaCompletedPart(1, "already-confirmed"), MediaCompletedPart(2, "etag-2"), MediaCompletedPart(3, "etag-3"),
                    ))
                }
            }
        } finally { server.stop(0) }
    }

    @Test
    fun anotherAccountCannotContinueOrFinalizeTheOwnersUpload() {
        val file = File(context.cacheDir, "pending.olive").apply { writeBytes(byteArrayOf(1, 2)) }
        withWorker(job(file), signedIn = "different-account") { worker, current, api, _ ->
            assertEquals(ListenableWorker.Result.retry(), runBlocking { worker.doWork() })
            assertEquals(MediaTransferStatus.QUEUED, current().status)
            assertEquals("Waiting for the account that queued this upload", current().error)
            assertTrue(mockingDetails(api).invocations.isEmpty())
        }
    }

    @Test
    fun missingStagingFileIsPermanentAndNeverStartsANetworkTransfer() {
        val absent = File(context.cacheDir, "absent.olive")
        absent.delete()
        withWorker(job(absent)) { worker, current, api, _ ->
            val result = runBlocking { worker.doWork() }
            assertTrue(result is ListenableWorker.Result.Failure)
            assertEquals(MediaTransferStatus.FAILED, current().status)
            assertEquals("Encrypted upload staging file is missing", current().error)
            assertTrue(mockingDetails(api).invocations.isEmpty())
        }
    }

    @Test
    fun recoverableDeviceVerificationRetriesButDeniedVerificationFails() {
        val file = File(context.cacheDir, "verified.olive").apply { writeBytes(byteArrayOf(1, 2)) }
        val failures = listOf(
            MediaDeviceVerificationRetryableException("Temporarily unavailable", IllegalStateException()),
            MediaDeviceVerificationException("Installation rejected", IllegalStateException()),
            functionsFailure("Quota unavailable", FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED),
            functionsFailure("Upload rejected", FirebaseFunctionsException.Code.PERMISSION_DENIED),
        )
        for ((index, failure) in failures.withIndex()) {
            withWorker(job(file), failure = failure) { worker, current, _, _ ->
                val result = runBlocking { worker.doWork() }
                val retryable = index % 2 == 0
                assertEquals(if (retryable) MediaTransferStatus.QUEUED else MediaTransferStatus.FAILED, current().status)
                if (retryable) assertEquals(ListenableWorker.Result.retry(), result)
                else assertTrue(result is ListenableWorker.Result.Failure)
                assertEquals(failure.message, current().error)
            }
        }
    }

    @Test
    fun voiceRecordingIsQueuedWithItsEncryptedAttachmentOnlyAfterUploadCompletes() {
        val ciphertext = byteArrayOf(5, 31, 76)
        val uploads = mutableListOf<ByteArray>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { request ->
            uploads += request.requestBody.readBytes()
            request.sendResponseHeaders(200, -1)
            request.close()
        }
        server.start()
        val file = File(context.cacheDir, "synthetic-voice.olive").apply { writeBytes(ciphertext) }
        WorkManagerImpl.setDelegate(mock(WorkManagerImpl::class.java))
        try {
            TestAndroidKeyStore.install().use {
                val roomKey = EncryptionUtils.generateSymmetricKey()
                val draft = PendingVoiceMessage("chat", "fixture_owner",
                    KeyManager.wrapKey(roomKey, KeyManager.getOrCreatePublicKey()), "5000,123")
                val initial = job(file).copy(voiceDraft = draft)
                val outbox = MessageOutboxStore(context)
                assertTrue(outbox.all("owner").isEmpty())
                withWorker(initial, url = "http://127.0.0.1:${server.address.port}") { worker, current, api, states ->
                    val result = runBlocking { worker.doWork() }
                    assertEquals(ListenableWorker.Result.success(Data.Builder().putString("mediaId", "media").build()), result)
                    assertArrayEquals(ciphertext, uploads.single())
                    assertTrue(states.containsAll(listOf(MediaTransferStatus.CREATING_SESSION, MediaTransferStatus.FINALIZING)))
                    assertNull(current().voiceDraft)
                    assertNull(current().voiceMessage)
                    val queued = outbox.all("owner").single()
                    assertEquals("job", queued.id)
                    assertEquals("chat", queued.conversationId)
                    assertEquals("media", queued.attachment?.mediaId)
                    assertEquals("SHARED_VOICE:media:5000,123", EncryptionUtils.decryptText(queued.ciphertext, roomKey))
                    runBlocking { verify(api).completeUpload("job", listOf(MediaCompletedPart(1, "single"))) }
                }
            }
        } finally {
            WorkManagerImpl.setDelegate(null)
            server.stop(0)
            file.delete()
        }
    }

    private fun functionsFailure(message: String, code: FirebaseFunctionsException.Code) =
        FirebaseFunctionsException::class.java.getDeclaredConstructor(
            String::class.java, FirebaseFunctionsException.Code::class.java, Any::class.java,
        ).apply { isAccessible = true }.newInstance(message, code, null)

    private fun job(file: File) = MediaUploadJob("job", "owner", PreparedEncryptedMedia(
        file.absolutePath, if (file.exists()) file.length() else 2, "checksum", "encrypted-metadata",
        emptyMap(), listOf("owner"), kind = MediaKind.DOCUMENT, ocrReview = OcrReview.NOT_RUN,
    ))

    private fun withWorker(
        initial: MediaUploadJob,
        signedIn: String = "owner",
        url: String = "http://127.0.0.1:1",
        failure: Exception? = null,
        verify: (MediaUploadWorker, () -> MediaUploadJob, MediaRepository, List<MediaTransferStatus>) -> Unit,
    ) {
        var current = initial
        val states = mutableListOf<MediaTransferStatus>()
        val auth = mock(FirebaseAuth::class.java)
        val user = mock(FirebaseUser::class.java)
        `when`(user.uid).thenReturn(signedIn)
        `when`(auth.currentUser).thenReturn(user)
        val parameters = mock(WorkerParameters::class.java)
        `when`(parameters.id).thenReturn(UUID.randomUUID())
        `when`(parameters.inputData).thenReturn(Data.Builder().putString("jobId", "job").build())
        `when`(parameters.tags).thenReturn(emptySet())
        val executor = Executors.newSingleThreadExecutor()
        `when`(parameters.backgroundExecutor).thenReturn(executor)
        `when`(parameters.taskExecutor).thenReturn(mock(TaskExecutor::class.java))
        val updater = mock(ProgressUpdater::class.java)
        `when`(parameters.progressUpdater).thenReturn(updater)
        `when`(updater.updateProgress(eq(context) ?: context, any(UUID::class.java) ?: UUID.randomUUID(),
            any(Data::class.java) ?: Data.EMPTY)).thenReturn(Futures.immediateVoidFuture())
        try {
            mockStatic(FirebaseAuth::class.java).use { authApi ->
                authApi.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
                mockConstruction(MediaTransferStore::class.java) { store, _ ->
                    `when`(store.get("job")).thenAnswer { current }
                    doAnswer { invocation ->
                        current = invocation.getArgument(0)
                        states.add(current.status)
                        null
                    }.`when`(store).write(any(MediaUploadJob::class.java) ?: initial)
                    `when`(store.update(eq("job") ?: "", any<(MediaUploadJob) -> MediaUploadJob>() ?: { it }))
                        .thenAnswer { invocation ->
                            val change = invocation.getArgument<(MediaUploadJob) -> MediaUploadJob>(1)
                            current = change(current)
                            states.add(current.status)
                            current
                        }
                }.use {
                    mockConstruction(OfflineMediaCache::class.java).use {
                        mockConstruction(MediaRepository::class.java) { api, _ ->
                            runBlocking {
                                `when`(api.createUpload(any(MediaUploadJob::class.java) ?: initial)).thenAnswer {
                                    failure?.let { throw it }
                                    MediaUploadSession("job", "media", "encrypted/media", MediaUploadMode.SINGLE, "", initial.source.ciphertextSize)
                                }
                                for (number in 1..3) {
                                    `when`(api.uploadTicket("job", number)).thenReturn(MediaUploadTicket("$url/$number", emptyMap(), Long.MAX_VALUE, number))
                                }
                                `when`(api.completeUpload(eq("job") ?: "", anyList())).thenReturn(
                                    CloudMediaRecord("media", "owner", "encrypted/media", "", emptyMap(), listOf("owner"), initial.source.ciphertextSize, "", 1, 0),
                                )
                            }
                        }.use { api ->
                            val worker = MediaUploadWorker(context, parameters)
                            verify(worker, { current }, api.constructed().single(), states)
                        }
                    }
                }
            }
        } finally { executor.shutdownNow() }
    }
}
