package com.olive.app.ui.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.olive.app.data.AccountIdentity
import com.olive.app.data.MediaKind
import com.olive.app.data.MediaPreprocessor
import com.olive.app.data.MediaRepository
import com.olive.app.data.MediaTicketDataSource
import com.olive.app.data.MediaTransferState
import com.olive.app.data.MediaTransferStore
import com.olive.app.data.MediaUploadJob
import com.olive.app.data.MediaUploadWorker
import com.olive.app.data.OcrReview
import com.olive.app.data.PassThroughMediaPreprocessor
import com.olive.app.data.PreparedEncryptedMedia
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import com.olive.app.security.MediaCipher
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import com.olive.app.data.SafetyDocument

/**
 * Session-scoped coordinator for media preparation, observable state, and encrypted downloads.
 *
 * Ownership and dependency graph:
 * - `SessionViewModel` owns one controller for the signed-in session;
 * - `SafetyDocumentsController` delegates document upload/download actions here;
 * - [MediaPreprocessor] supplies the replaceable on-device compression stage;
 * - [MediaTransferStore] persists restartable jobs;
 * - [MediaUploadWorker] owns background upload execution;
 * - [MediaTicketDataSource] supplies authorized R2 download capabilities;
 * - [MediaCipher] and [KeyManager] enforce client-side encryption and per-user key wrapping.
 *
 * Upload workflow:
 * `OCR decision -> preprocess/compress -> AES-GCM encrypt -> checksum/wrap key -> persist job ->
 * WorkManager -> Firebase ticket -> direct R2 PUT -> Firebase finalization`.
 *
 * Download workflow:
 * `Firebase authorization ticket -> direct R2 GET -> ciphertext size/hash verification -> unwrap key
 * -> AES-GCM decrypt into .part -> atomic rename`.
 *
 * This is deliberately a controller rather than another AndroidX ViewModel: WorkManager/store provide
 * durable state, while `SessionViewModel` remains the single lifecycle owner for session features.
 */
internal class MediaTransferController(
    context:Context,
    private val scope:CoroutineScope,
    private val store:MediaTransferStore=MediaTransferStore(context),
    private val repository:MediaTicketDataSource=MediaRepository(),
    private val client:OkHttpClient=OkHttpClient(),
    private val preprocessor:MediaPreprocessor=PassThroughMediaPreprocessor,
) {
    private val applicationContext=context.applicationContext
    var state by mutableStateOf<MediaTransferState>(MediaTransferState.Idle)
        private set

    /**
     * Entry point for picker-selected safety documents. The caller supplies the OCR recommendation.
     * [OcrReview.RETAKE_RECOMMENDED] pauses before any plaintext processing; declining is represented
     * by calling again with [OcrReview.RETAKE_DECLINED].
     */
    fun queueDocument(
        uri:Uri,owner:AccountIdentity,ocrReview:OcrReview=OcrReview.NOT_RUN,
        onQueued:(String)->Unit={},onFailure:(String)->Unit={},
    ) {
        if(ocrReview==OcrReview.RETAKE_RECOMMENDED) {
            // OCR is advisory: pause for a user decision instead of silently rejecting the document.
            state=MediaTransferState.Failed(null,"Document quality review recommends another capture",false)
            onFailure("Document quality review recommends another capture");return
        }
        scope.launch {
            state=MediaTransferState.Preparing("encrypting")
            runCatching { withContext(Dispatchers.IO) { prepareUri(uri,owner,ocrReview) } }
                .onSuccess { job ->
                    store.write(job);MediaUploadWorker.enqueue(applicationContext,job.id)
                    observe(job.id)
                    state=MediaTransferState.Queued(job.id);onQueued(job.id)
                }.onFailure { failure ->
                    val message=failure.localizedMessage ?: "Unable to prepare encrypted media"
                    state=MediaTransferState.Failed(null,message,false);onFailure(message)
                }
        }
    }

    /** Queues a camera/capture file through the same preprocessing and encryption pipeline. */
    fun queueFile(
        file:File,name:String,mimeType:String,owner:AccountIdentity,kind:MediaKind,
        ocrReview:OcrReview=if(kind==MediaKind.DOCUMENT) OcrReview.NOT_RUN else OcrReview.NOT_APPLICABLE,
        onQueued:(String)->Unit={},onFailure:(String)->Unit={},
    ) {
        if(ocrReview==OcrReview.RETAKE_RECOMMENDED) {
            val message="Document quality review recommends another capture"
            state=MediaTransferState.Failed(null,message,false);onFailure(message);return
        }
        scope.launch {
            state=MediaTransferState.Preparing("encrypting")
            runCatching { withContext(Dispatchers.IO) {
                require(file.isFile && file.length()>0) { "Media source is empty" }
                prepare(file.inputStream(),name,mimeType,owner,kind,ocrReview)
            } }.onSuccess { job ->
                store.write(job);MediaUploadWorker.enqueue(applicationContext,job.id)
                observe(job.id)
                state=MediaTransferState.Queued(job.id);onQueued(job.id)
            }.onFailure { failure ->
                val message=failure.localizedMessage ?: "Unable to prepare encrypted media"
                state=MediaTransferState.Failed(null,message,false);onFailure(message)
            }
        }
    }

    /** Re-enqueues an existing durable job without repeating OCR, compression, or encryption. */
    fun retry(jobId:String) {
        val job=store.get(jobId) ?: return
        store.write(job.copy(status=com.olive.app.data.MediaTransferStatus.QUEUED,error=null))
        MediaUploadWorker.enqueue(applicationContext,jobId);observe(jobId);state=MediaTransferState.Queued(jobId)
    }

    /** Downloads and decrypts a new R2-backed document; legacy Firebase Storage uses its repository. */
    fun download(document:SafetyDocument,viewerUid:String,onReady:(File)->Unit,onFailure:(String)->Unit={}) {
        require(document.objectKey.isNotBlank()) { "Legacy media downloads use SafetyDocumentRepository" }
        scope.launch {
            state=MediaTransferState.Preparing("downloading")
            runCatching { withContext(Dispatchers.IO) { downloadEncrypted(document,viewerUid) } }
                .onSuccess { file -> state=MediaTransferState.Idle;onReady(file) }
                .onFailure { failure ->
                    val message=failure.localizedMessage ?: "Unable to download encrypted media"
                    state=MediaTransferState.Failed(null,message,true);onFailure(message)
                }
        }
    }

    fun clearState() { state=MediaTransferState.Idle }

    /** Projects durable WorkManager progress into Compose-observable session state. */
    private fun observe(jobId:String) {
        scope.launch {
            WorkManager.getInstance(applicationContext)
                .getWorkInfosForUniqueWorkFlow(MediaUploadWorker.workName(jobId))
                .collectLatest { work ->
                    val info=work.firstOrNull { !it.state.isFinished } ?: work.maxByOrNull { it.runAttemptCount }
                        ?: return@collectLatest
                    state=when(info.state) {
                        WorkInfo.State.RUNNING -> MediaTransferState.Uploading(jobId,
                            info.progress.getLong(MediaUploadWorker.BYTES_SENT,0),
                            info.progress.getLong(MediaUploadWorker.TOTAL_BYTES,0))
                        WorkInfo.State.SUCCEEDED -> MediaTransferState.Completed(jobId,
                            info.outputData.getString(MediaUploadWorker.MEDIA_ID).orEmpty())
                        WorkInfo.State.FAILED -> MediaTransferState.Failed(jobId,
                            info.outputData.getString(MediaUploadWorker.ERROR) ?: "Media upload failed",false)
                        WorkInfo.State.CANCELLED -> MediaTransferState.Failed(jobId,"Media upload was cancelled",true)
                        else -> MediaTransferState.Queued(jobId)
                    }
                }
        }
    }

    /** Resolves picker metadata, then hands the stream to the shared preparation pipeline. */
    private fun prepareUri(uri:Uri,owner:AccountIdentity,ocrReview:OcrReview):MediaUploadJob {
        val resolver=applicationContext.contentResolver
        val mime=resolver.getType(uri)?.take(120)?.ifBlank { null } ?: "application/octet-stream"
        val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
            if(cursor.moveToFirst()) cursor.getString(0) else null
        }?.trim()?.take(160).orEmpty().ifBlank { "Document" }
        // This entry point is domain-specific: an image selected here represents a
        // photographed/scanned document and therefore participates in OCR review.
        val kind=MediaKind.DOCUMENT
        return prepare(resolver.openInputStream(uri) ?: error("Unable to read the selected file"),name,mime,owner,kind,ocrReview)
    }

    /**
     * Security-critical preparation boundary. Plaintext is consumed here, optionally compressed,
     * and immediately encrypted into the app-private outbox before a durable/network job exists.
     */
    private fun prepare(
        input:InputStream,name:String,mimeType:String,owner:AccountIdentity,kind:MediaKind,ocrReview:OcrReview,
    ):MediaUploadJob {
        val jobId=UUID.randomUUID().toString()
        val folder=File(applicationContext.noBackupFilesDir,"media-outbox").apply { mkdirs() }
        val target=File(folder,"$jobId.olive")
        val key=EncryptionUtils.generateSymmetricKey()
        try {
            var processedMimeType=mimeType
            // The preprocessor owns compression only. Its output is immediately consumed by the
            // cipher, and ProcessedMedia.close() guarantees adapter-specific plaintext cleanup.
            input.use { source -> preprocessor.process(source,mimeType,kind).use { processed ->
                processedMimeType=processed.mimeType
                target.outputStream().buffered().use { MediaCipher.encrypt(processed.input,it,key) }
            } }
            require(target.length() in 1..MAX_CIPHERTEXT_BYTES) { "Encrypted media must be smaller than 250 MB" }
            val metadata=JSONObject().apply {
                put("name",name.trim().take(160).ifBlank { "Document" });put("mimeType",processedMimeType.take(120))
                put("kind",kind.name);put("ocrReview",ocrReview.name)
            }.toString()
            // The server stores only wrapped key material; it never receives the raw AES key.
            val wrapped=mapOf(owner.uid to KeyManager.wrapKey(key,KeyManager.getOrCreatePublicKey()))
            val prepared=PreparedEncryptedMedia(target.absolutePath,target.length(),sha256(target),
                EncryptionUtils.encryptText(metadata,key),wrapped,listOf(owner.uid),1,kind,ocrReview)
            return MediaUploadJob(jobId,owner.uid,prepared)
        } catch(failure:Exception) { target.delete();throw failure }
    }

    /** Computes the full ciphertext checksum used to reject truncated/corrupted downloads. */
    private fun sha256(file:File):String {
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024)
        try { file.inputStream().use { input -> while(true) {
            val count=input.read(buffer);if(count<0) break;if(count>0) digest.update(buffer,0,count)
        } } } finally { buffer.fill(0) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Executes the encrypted download workflow without exposing plaintext to Firebase or R2. */
    private suspend fun downloadEncrypted(document:SafetyDocument,viewerUid:String):File {
        val ticket=repository.downloadTicket(document.id)
        val builder=Request.Builder().url(ticket.url).get();ticket.headers.forEach(builder::header)
        val encryptedFolder=File(applicationContext.cacheDir,"encrypted-media").apply { mkdirs() }
        val encrypted=File(encryptedFolder,"${document.id}.olive.part")
        val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024)
        try {
            client.newCall(builder.build()).execute().use { response ->
                if(!response.isSuccessful) error("Cloud download failed with HTTP ${response.code}")
                val body=response.body ?: error("Cloud download was empty")
                encrypted.outputStream().buffered().use { output -> body.byteStream().use { input ->
                    while(true) {
                        coroutineContext.ensureActive()
                        val count=input.read(buffer);if(count<0) break;if(count==0) continue
                        digest.update(buffer,0,count);output.write(buffer,0,count)
                    }
                } }
            }
            // Verify the complete encrypted object before attempting key use or plaintext output.
            require(encrypted.length()==document.ciphertextSize) { "Encrypted download size does not match" }
            val checksum=digest.digest().joinToString("") { "%02x".format(it) }
            require(checksum.equals(document.ciphertextSha256,true)) { "Encrypted download checksum does not match" }
            val key=document.wrappedKeys[viewerUid]?.let(KeyManager::unwrapKey) ?: error("Encryption key is unavailable")
            val opened=File(applicationContext.cacheDir,"opened-documents").apply { mkdirs() }
            val extension=document.name.substringAfterLast('.',"").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
            val target=File(opened,document.id+(extension?.let { ".$it" } ?: ""));val partial=File(target.path+".part")
            try {
                // Decrypt into a non-published file. A bad GCM tag deletes this partial plaintext;
                // only authenticated completion is atomically renamed to the consumer-visible path.
                encrypted.inputStream().buffered().use { input -> partial.outputStream().buffered().use { output ->
                    MediaCipher.decrypt(input,output,key)
                } }
                if(target.exists()) target.delete()
                check(partial.renameTo(target)) { "Unable to publish decrypted media" }
                return target
            } catch(failure:Exception) { partial.delete();throw failure }
        } finally { buffer.fill(0);encrypted.delete() }
    }

    private companion object { const val MAX_CIPHERTEXT_BYTES=250L*1024L*1024L }
}
