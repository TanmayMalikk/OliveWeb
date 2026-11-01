// Safe-app users: starts encrypted document uploads and opens downloaded media.
package com.olive.app.ui.users.user.documents

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.olive.app.data.AccountIdentity
import com.olive.app.data.DocumentOcrMetadata
import com.olive.app.data.SafetyDocument
import com.olive.app.data.media.DocumentDownloadGate
import com.olive.app.data.media.MediaKind
import com.olive.app.data.media.copyAndVerifyEncryptedDownload
import com.olive.app.data.media.MediaPreprocessor
import com.olive.app.data.media.MediaTicketDataSource
import com.olive.app.data.media.MediaTransferState
import com.olive.app.data.media.MediaTransferStatus
import com.olive.app.data.media.MediaTransferStore
import com.olive.app.data.media.MediaUploadJob
import com.olive.app.data.media.MediaUploadPresentation
import com.olive.app.data.media.MediaUploadWorker
import com.olive.app.data.media.OcrReview
import com.olive.app.data.media.PassThroughMediaPreprocessor
import com.olive.app.data.media.PreparedEncryptedMedia
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import com.olive.app.security.MediaCipher
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal interface MediaTransferOperations {
    fun observeUploads(ownerUid:String)
    fun acknowledgePublished(mediaIds:Set<String>)
    fun queueDocument(
        uri:Uri,owner:AccountIdentity,ocrReview:OcrReview,
        onQueued:(String)->Unit,onFailure:(String)->Unit,
    )
    fun queueFile(
        file:File,name:String,mimeType:String,owner:AccountIdentity,kind:MediaKind,
        ocrReview:OcrReview,ocr:DocumentOcrMetadata?,
        onQueued:(String)->Unit,onFailure:(String)->Unit,
    )
    suspend fun downloadFile(document:SafetyDocument,viewerUid:String):File
    fun clearState()
}

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
    private val repository:MediaTicketDataSource,
    private val store:MediaTransferStore=MediaTransferStore(context),
    private val client:OkHttpClient=OkHttpClient(),
    private val preprocessor:MediaPreprocessor=PassThroughMediaPreprocessor,
):MediaTransferOperations {
    private val applicationContext=context.applicationContext
    private var observedOwnerUid:String?=null
    private var publishedMediaIds:Set<String> = emptySet()
    private val observationJobs=mutableMapOf<String,Job>()
    private val workProgress=mutableMapOf<String,Pair<Long,Long>>()
    private val downloadGate=DocumentDownloadGate()
    var state by mutableStateOf<MediaTransferState>(MediaTransferState.Idle)
        private set
    var uploads by mutableStateOf<List<MediaUploadPresentation>>(emptyList())
        private set

    /**
     * Restores and observes every durable upload belonging to the visible account.
     *
     * WorkManager owns execution, while this controller owns only the device-local presentation.
     * Re-entering the screen after process death reconstructs pending cards from the encrypted
     * [MediaTransferStore] and reconnects to the already scheduled unique work chains.
     */
    override fun observeUploads(ownerUid:String) {
        if(observedOwnerUid!=ownerUid) {
            observationJobs.values.forEach(Job::cancel)
            observationJobs.clear()
            workProgress.clear()
            observedOwnerUid = ownerUid
            publishedMediaIds = emptySet()
            uploads = emptyList()
        }
        scope.launch { refreshUploads(ownerUid)
            .filterNot { it.status in setOf(MediaTransferStatus.COMPLETE,MediaTransferStatus.FAILED,MediaTransferStatus.CANCELLED) }
            .forEach { observe(it.jobId) } }
    }

    /** Retries a durable job without repeating OCR, compression, or encryption. */
    fun retryUpload(jobId:String) {
        retry(jobId)
        observedOwnerUid?.let { owner -> scope.launch { refreshUploads(owner) } }
    }

    /**
     * Removes completed local bookkeeping only after Firestore publishes the matching media record.
     * This prevents a blank gap: the pending card remains until the finalized document can replace it.
     */
    override fun acknowledgePublished(mediaIds:Set<String>) {
        val owner=observedOwnerUid ?: return
        publishedMediaIds=mediaIds
        scope.launch {
            val completed=withContext(Dispatchers.IO) { store.all().filter {
                it.ownerUid==owner && it.status==MediaTransferStatus.COMPLETE && it.mediaId in mediaIds
            } }
            withContext(Dispatchers.IO) { completed.forEach { store.remove(it.id) } }
            completed.forEach {
                observationJobs.remove(it.id)?.cancel()
                workProgress.remove(it.id)
            }
            refreshUploads(owner)
        }
    }

    /**
     * Entry point for picker-selected safety documents. The caller supplies the OCR recommendation.
     * [OcrReview.RETAKE_RECOMMENDED] pauses before any plaintext processing; declining is represented
     * by calling again with [OcrReview.RETAKE_DECLINED].
     */
    override fun queueDocument(
        uri:Uri,owner:AccountIdentity,ocrReview:OcrReview,
        onQueued:(String)->Unit,onFailure:(String)->Unit,
    ) {
        if(ocrReview==OcrReview.RETAKE_RECOMMENDED) {
            // OCR is advisory: pause for a user decision instead of silently rejecting the document.
            state=MediaTransferState.Failed(null,"Document quality review recommends another capture",false)
            onFailure("Document quality review recommends another capture")
            return
        }
        scope.launch {
            state=MediaTransferState.Preparing("encrypting")
            runCatching { withContext(Dispatchers.IO) { prepareUri(uri,owner,ocrReview) } }
                .onSuccess { job ->
                    store.write(job)
                    MediaUploadWorker.enqueue(applicationContext,job.id)
                    observe(job.id)
                    refreshUploads(owner.uid)
                    state = MediaTransferState.Queued(job.id)
                    onQueued(job.id)
                }.onFailure { failure ->
                    val message=failure.localizedMessage ?: "Unable to prepare encrypted media"
                    state = MediaTransferState.Failed(null, message, false)
                    onFailure(message)
                }
        }
    }

    /** Queues a camera/capture file through the same preprocessing and encryption pipeline. */
    override fun queueFile(
        file:File,name:String,mimeType:String,owner:AccountIdentity,kind:MediaKind,
        ocrReview:OcrReview,ocr:DocumentOcrMetadata?,
        onQueued:(String)->Unit,onFailure:(String)->Unit,
    ) {
        if(ocrReview==OcrReview.RETAKE_RECOMMENDED) {
            val message="Document quality review recommends another capture"
            state = MediaTransferState.Failed(null, message, false)
            onFailure(message)
            return
        }
        scope.launch {
            state=MediaTransferState.Preparing("encrypting")
            runCatching { withContext(Dispatchers.IO) {
                require(file.isFile && file.length()>0) { "Media source is empty" }
                prepare(file.inputStream(),name,mimeType,owner,kind,ocrReview,ocr)
            } }.onSuccess { job ->
                store.write(job)
                MediaUploadWorker.enqueue(applicationContext,job.id)
                observe(job.id)
                refreshUploads(owner.uid)
                state = MediaTransferState.Queued(job.id)
                onQueued(job.id)
            }.onFailure { failure ->
                val message=failure.localizedMessage ?: "Unable to prepare encrypted media"
                state = MediaTransferState.Failed(null, message, false)
                onFailure(message)
            }
        }
    }

    /** Re-enqueues an existing durable job without repeating OCR, compression, or encryption. */
    fun retry(jobId:String) {
        val job=store.get(jobId) ?: return
        store.write(job.copy(status=MediaTransferStatus.QUEUED,error=null))
        // A manual retry replaces a worker that may still be waiting in exponential backoff.
        MediaUploadWorker.enqueue(applicationContext,jobId,replaceDelayedWork=true)
        observe(jobId)
        state = MediaTransferState.Queued(jobId)
    }

    /** Downloads and decrypts an R2-backed document for the authenticated viewer. */
    override suspend fun downloadFile(document:SafetyDocument,viewerUid:String):File {
        require(document.objectKey.isNotBlank()) { "Legacy media downloads use SafetyDocumentRepository" }
        state=MediaTransferState.Preparing("downloading")
        return try {
            withContext(Dispatchers.IO) {
                downloadGate.withDocumentLock(document.id) {
                    downloadEncrypted(document,viewerUid)
                }
            }
                .also { state=MediaTransferState.Idle }
        } catch(failure:Exception) {
            val message=failure.localizedMessage ?: "Unable to download encrypted media"
            state=MediaTransferState.Failed(null,message,true)
            throw failure
        }
    }

    override fun clearState() {
        observationJobs.values.forEach(Job::cancel)
        observationJobs.clear()
        workProgress.clear()
        observedOwnerUid = null
        publishedMediaIds = emptySet()
        uploads = emptyList()
        state = MediaTransferState.Idle
    }

    /** Projects durable WorkManager progress into Compose-observable session state. */
    private fun observe(jobId:String) {
        if(observationJobs.containsKey(jobId)) return
        observationJobs[jobId]=scope.launch {
            WorkManager.getInstance(applicationContext)
                .getWorkInfosForUniqueWorkFlow(MediaUploadWorker.workName(jobId))
                .collectLatest { work ->
                    val info=work.firstOrNull { !it.state.isFinished } ?: work.maxByOrNull { it.runAttemptCount }
                        ?: return@collectLatest
                    state=when(info.state) {
                        WorkInfo.State.RUNNING -> {
                            val sent=info.progress.getLong(MediaUploadWorker.BYTES_SENT,0)
                            val total=info.progress.getLong(MediaUploadWorker.TOTAL_BYTES,0)
                            workProgress[jobId]=sent to total
                            MediaTransferState.Uploading(jobId,sent,total)
                        }
                        WorkInfo.State.SUCCEEDED -> MediaTransferState.Completed(jobId,
                            info.outputData.getString(MediaUploadWorker.MEDIA_ID).orEmpty())
                        WorkInfo.State.FAILED -> MediaTransferState.Failed(jobId,
                            info.outputData.getString(MediaUploadWorker.ERROR) ?: "Media upload failed",false)
                        WorkInfo.State.CANCELLED -> MediaTransferState.Failed(jobId,"Media upload was cancelled",true)
                        else -> MediaTransferState.Queued(jobId)
                    }
                    observedOwnerUid?.let { refreshUploads(it) }
                }
        }
    }

    /** Reads encrypted durable jobs off the main thread and publishes a key-free UI projection. */
    private suspend fun refreshUploads(ownerUid:String):List<MediaUploadPresentation> {
        val progressSnapshot=workProgress.toMap()
        val publishedSnapshot=publishedMediaIds
        val jobs=withContext(Dispatchers.IO) {
            val owned=store.all().filter { it.ownerUid==ownerUid }
            val retired=owned.filter { it.status==MediaTransferStatus.COMPLETE && it.mediaId in publishedSnapshot }
            retired.forEach { store.remove(it.id) }
            owned.filterNot { it in retired }.sortedByDescending { it.createdAt }.map { job ->
                val metadata=runCatching {
                    val wrapped=job.source.wrappedKeys[ownerUid] ?: error("Encryption key is unavailable")
                    JSONObject(EncryptionUtils.decryptText(job.source.encryptedMetadata,
                        KeyManager.unwrapKey(wrapped,job.source.encryptionVersion)))
                }.getOrNull()
                val progress=progressSnapshot[job.id]
                MediaUploadPresentation(job.id,job.ownerUid,
                    metadata?.optString("name")?.takeIf(String::isNotBlank) ?: "Encrypted document",
                    metadata?.optString("mimeType")?.takeIf(String::isNotBlank) ?: "application/octet-stream",
                    job.status,progress?.first ?: completedBytes(job),progress?.second ?: job.source.ciphertextSize,
                    job.mediaId,job.error,job.createdAt)
            }
        }
        if(observedOwnerUid==ownerUid) uploads=jobs
        return jobs
    }

    /** Reconstructs multipart progress from persisted ETags when live WorkInfo is unavailable. */
    private fun completedBytes(job:MediaUploadJob):Long {
        if(job.status==MediaTransferStatus.COMPLETE) return job.source.ciphertextSize
        if(job.partSize<=0 || job.completedParts.isEmpty()) return 0
        return job.completedParts.keys.sumOf { part ->
            val offset=(part-1)*job.partSize
            minOf(job.partSize,(job.source.ciphertextSize-offset).coerceAtLeast(0))
        }.coerceAtMost(job.source.ciphertextSize)
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
        ocr:DocumentOcrMetadata?=null,
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
                put("name", name.trim().take(160).ifBlank { "Document" })
                put("mimeType", processedMimeType.take(120))
                put("kind", kind.name)
                put("ocrReview", ocrReview.name)
                ocr?.let {
                    put("ocrText",it.text.take(MAX_OCR_TEXT))
                    put("ocrConfidence",it.confidence.coerceIn(0f,1f).toDouble())
                    put("ocrSuccessful",it.successful)
                }
            }.toString()
            // The server stores only wrapped key material; it never receives the raw AES key.
            val wrapped=mapOf(owner.uid to KeyManager.wrapKey(key,KeyManager.getOrCreatePublicKey()))
            val prepared=PreparedEncryptedMedia(target.absolutePath,target.length(),sha256(target),
                EncryptionUtils.encryptText(metadata,key),wrapped,listOf(owner.uid),
                KeyManager.CURRENT_KEY_WRAP_VERSION,kind,ocrReview)
            return MediaUploadJob(jobId,owner.uid,prepared)
        } catch (failure: Exception) {
            target.delete()
            throw failure
        }
    }

    /** Computes the full ciphertext checksum used to reject truncated/corrupted downloads. */
    private fun sha256(file:File):String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        try { file.inputStream().use { input -> while(true) {
            val count = input.read(buffer)
            if (count < 0) {
                break
            }
            if (count > 0) {
                digest.update(buffer, 0, count)
            }
        } } } finally { buffer.fill(0) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Executes the encrypted download workflow without exposing plaintext to Firebase or R2. */
    private suspend fun downloadEncrypted(document:SafetyDocument,viewerUid:String):File {
        val ticket=repository.downloadTicket(document.id)
        val builder = Request.Builder().url(ticket.url).get()
        ticket.headers.forEach(builder::header)
        val encryptedFolder=File(applicationContext.cacheDir,"encrypted-media").apply { mkdirs() }
        val encrypted=File.createTempFile("${document.id}.",".olive.part",encryptedFolder)
        try {
            client.newCall(builder.build()).execute().use { response ->
                if(!response.isSuccessful) error("Cloud download failed with HTTP ${response.code}")
                val body=response.body
                val declaredSize=body.contentLength()
                require(declaredSize<0 || declaredSize==document.ciphertextSize) {
                    sizeMismatch(document.ciphertextSize,declaredSize)
                }
                encrypted.outputStream().buffered().use { output -> body.byteStream().use { input ->
                    copyAndVerifyEncryptedDownload(input,output,document.ciphertextSize,
                        document.ciphertextSha256)
                } }
            }
            // Verify the complete encrypted object before attempting key use or plaintext output.
            require(encrypted.length()==document.ciphertextSize) {
                sizeMismatch(document.ciphertextSize,encrypted.length())
            }
            val key=document.wrappedKeys[viewerUid]?.let {
                KeyManager.unwrapKey(it,document.encryptionVersion)
            } ?: error("Encryption key is unavailable")
            val opened=File(applicationContext.cacheDir,"opened-documents").apply { mkdirs() }
            val extension=document.name.substringAfterLast('.',"").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
            val target = File(opened, document.id + (extension?.let { ".$it" } ?: ""))
            val partial = File(target.path + ".part")
            try {
                // Decrypt into a non-published file. A bad GCM tag deletes this partial plaintext;
                // only authenticated completion is atomically renamed to the consumer-visible path.
                encrypted.inputStream().buffered().use { input -> partial.outputStream().buffered().use { output ->
                    MediaCipher.decrypt(input,output,key)
                } }
                if(target.exists()) target.delete()
                check(partial.renameTo(target)) { "Unable to publish decrypted media" }
                return target
            } catch (failure: Exception) {
                partial.delete()
                throw failure
            }
        } finally { encrypted.delete() }
    }

    private companion object {
        const val MAX_CIPHERTEXT_BYTES=250L*1024L*1024L
        const val MAX_OCR_TEXT=50_000
        fun sizeMismatch(expected:Long,received:Long)=
            "Encrypted download size does not match (expected $expected bytes, received $received bytes)"
    }
}
