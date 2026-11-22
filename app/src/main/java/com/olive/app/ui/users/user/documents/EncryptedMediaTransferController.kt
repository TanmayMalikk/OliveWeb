// Safe-app users: starts encrypted document uploads and opens downloaded media.
package com.olive.app.ui.users.user.documents

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.olive.app.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.safety.DocumentOcrMetadata
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.data.network.hasValidatedInternetConnection
import com.olive.app.data.media.DocumentDownloadGate
import com.olive.app.security.AccountKeysetWrappedKey
import com.olive.app.data.media.MAX_MEDIA_CIPHERTEXT_BYTES
import com.olive.app.data.media.PendingVoiceMessage
import com.olive.app.data.media.MediaKind
import com.olive.app.data.media.copyAndVerifyEncryptedDownload
import com.olive.app.data.media.MediaPreprocessor
import com.olive.app.data.media.MediaPreparationMemoryException
import com.olive.app.data.media.MediaTicketDataSource
import com.olive.app.data.media.MediaTransferState
import com.olive.app.data.media.MediaTransferStatus
import com.olive.app.data.media.MediaTransferStore
import com.olive.app.data.media.MediaUploadJob
import com.olive.app.data.media.MediaUploadPresentation
import com.olive.app.data.media.MediaUploadWorker
import com.olive.app.data.media.OcrReview
import com.olive.app.data.media.pickerMediaKind
import com.olive.app.data.media.OfflineMediaAvailability
import com.olive.app.data.media.OfflineMediaCache
import com.olive.app.data.media.MediaAccessError
import com.olive.app.data.media.MediaAccessException
import com.olive.app.data.media.OnDeviceMediaPreprocessor
import com.olive.app.data.media.PreparedEncryptedMedia
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.AccountKeysetEnvelope
import com.olive.app.security.UnlockedAccountKeyset
import com.olive.app.security.KeyManager
import com.olive.app.security.MediaCipher
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.updateAndGet
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal interface MediaTransferOperations {
    /**
     * Watches pending uploads for this account and reports progress to the screen.
     *
     * @param ownerUid Firebase UID of the resource owner.
     */
    fun observeUploads(ownerUid: String)
    /**
     * Marks an uploaded encrypted file as published after the server accepts it.
     *
     * @param mediaIds Collection of media IDs values to process.
     */
    fun acknowledgePublished(mediaIds: Set<String>)
    /**
     * Adds document metadata to the user's encrypted document list.
     *
     * @param uri Content or file URI identifying the selected local item.
     * @param owner Owner supplied to [queueDocument].
     * @param ocrReview OCR review supplied to [queueDocument].
     * @param tags Collection of tags values to process.
     * @param onQueued Callback invoked when queued changes or occurs.
     * @param onFailure Callback invoked when failure changes or occurs.
     */
    fun queueDocument(
        uri: Uri, owner: AccountIdentity, ocrReview: OcrReview,
        tags: Set<String> = emptySet(),
        onQueued: (String) -> Unit, onFailure: (String) -> Unit,
    )
    /**
     * Encrypts the selected file and queues its upload.
     *
     * @param file Local file to read, hash, process, or write.
     * @param name Field, key, or display name.
     * @param mimeType MIME content type of the payload.
     * @param owner Owner supplied to [queueFile].
     * @param kind Kind supplied to [queueFile].
     * @param ocrReview OCR review supplied to [queueFile].
     * @param ocr Optional OCR supplied to [queueFile].
     * @param tags Collection of tags values to process.
     * @param onQueued Callback invoked when queued changes or occurs.
     * @param onFailure Callback invoked when failure changes or occurs.
     */
    fun queueFile(
        file: File, name: String, mimeType: String, owner: AccountIdentity, kind: MediaKind,
        ocrReview: OcrReview, ocr: DocumentOcrMetadata?,
        tags: Set<String> = emptySet(),
        onQueued: (String) -> Unit, onFailure: (String) -> Unit,
    )
    /**
     * Downloads, checks, and decrypts one allowed media file.
     *
     * @param document Safety document metadata record.
     * @param viewerUid Firebase UID of the viewing account.
     * @return The resulting [File] for download file.
     */
    suspend fun downloadFile(document: SafetyDocument, viewerUid: String): File
    /**
     * Builds available offline.
     *
     * @param document Safety document metadata record.
     * @param viewerUid Firebase UID of the viewing account.
     */
    suspend fun makeAvailableOffline(document: SafetyDocument, viewerUid: String)
    /**
     * Removes offline copy.
     *
     * @param document Safety document metadata record.
     * @param viewerUid Firebase UID of the viewing account.
     */
    suspend fun removeOfflineCopy(document: SafetyDocument, viewerUid: String)
    /**
     * Opens a local preview of a pending upload before the server confirms it.
     *
     * @param jobId Unique identifier of the durable background job.
     * @param viewerUid Firebase UID of the viewing account.
     * @return The resulting pair of document and decrypted preview file, or `null`.
     */
    fun openPendingUpload(jobId: String, viewerUid: String): Pair<SafetyDocument, File>? = null
    /**
     * Speculatively prefetches remote ciphertext into the offline cache so opening is instant.
     *
     * @param documents Collection of documents values to process.
     * @param viewerUid Firebase UID of the viewing account.
     */
    suspend fun prefetchRecentCiphertext(documents: List<SafetyDocument>, viewerUid: String) {}
    /**
     * Handles the document snapshot event.
     *
     * @param viewerUid Firebase UID of the viewing account.
     * @param documents Collection of documents values to process.
     * @param serverConfirmed Whether to apply server confirmed.
     */
    fun onDocumentSnapshot(viewerUid: String, documents: List<SafetyDocument>, serverConfirmed: Boolean)
    /**
     * Clears upload progress and errors when the document screen closes.
     */
    fun clearState()
}

internal data class MediaTransferUiState(
    val state: MediaTransferState = MediaTransferState.Idle,
    val uploads: List<MediaUploadPresentation> = emptyList(),
    val offlineCopies: Map<String, OfflineMediaAvailability> = emptyMap(),
    val recentCachingEnabled: Boolean = false,
)

// Session-scoped coordinator for media preparation, observable state, and encrypted downloads.
internal class MediaTransferController(
    context:Context,
    private val scope:CoroutineScope,
    private val repository:MediaTicketDataSource,
    private val store:MediaTransferStore=MediaTransferStore(context),
    private val offlineCache:OfflineMediaCache=OfflineMediaCache(context),
    private val client:OkHttpClient=OkHttpClient(),
    private val preprocessor:MediaPreprocessor=OnDeviceMediaPreprocessor(context.applicationContext),
    private val accountKeysetProvider:suspend (String)->AccountKeysetEnvelope?={ null },
    private val accountKeysetUnlocker:suspend (String)->UnlockedAccountKeyset?={ null },
    private val preparationFailureResolver:(Context,Throwable,MediaKind)->String=::preparationFailureMessage,
):MediaTransferOperations {
    private val applicationContext=context.applicationContext
    private var observedOwnerUid:String?=null
    private var publishedMediaIds:Set<String> = emptySet()
    private val observationJobs=mutableMapOf<String,Job>()
    private val workProgress=mutableMapOf<String,Pair<Long,Long>>()
    private val downloadGate=DocumentDownloadGate()
    private val sessionDecryptedCache = java.util.concurrent.ConcurrentHashMap<String, File>()
    private val speculativeCiphertextFiles = java.util.concurrent.ConcurrentHashMap<String, File>()
    private val uiStateFlow = MutableStateFlow(MediaTransferUiState())
    val uiState: StateFlow<MediaTransferUiState> = uiStateFlow.asStateFlow()
    private var uiStateSnapshot by mutableStateOf(uiStateFlow.value)

    /**
     * Atomically updates the UI state flow and updates the local state snapshot.
     *
     * @param transform State reducer transforming current state.
     */
    private inline fun updateUiState(transform: (MediaTransferUiState) -> MediaTransferUiState) {
        uiStateSnapshot = uiStateFlow.updateAndGet(transform)
    }

    var state: MediaTransferState
        get() = uiStateSnapshot.state
        private set(value) = updateUiState { it.copy(state = value) }
    var uploads: List<MediaUploadPresentation>
        get() = uiStateSnapshot.uploads
        private set(value) = updateUiState { it.copy(uploads = value) }
    var offlineCopies: Map<String, OfflineMediaAvailability>
        get() = uiStateSnapshot.offlineCopies
        private set(value) = updateUiState { it.copy(offlineCopies = value) }
    var recentCachingEnabled: Boolean
        get() = uiStateSnapshot.recentCachingEnabled
        private set(value) = updateUiState { it.copy(recentCachingEnabled = value) }
    private var cachePreferenceOwnerUid: String? = null

    /**
     * Restores and observes every durable upload belonging to the visible account.
     *
     * @param ownerUid Firebase UID of the resource owner.
     */
    override fun observeUploads(ownerUid: String) {
        if (observedOwnerUid != ownerUid) {
            observationJobs.values.forEach(Job::cancel)
            observationJobs.clear()
            workProgress.clear()
            observedOwnerUid = ownerUid
            publishedMediaIds = emptySet()
            uploads = emptyList()
            offlineCopies = emptyMap()
        }
        loadRecentCachingPreference(ownerUid)
        scope.launch {
            refreshUploads(ownerUid)
                .filterNot { it.status in setOf(MediaTransferStatus.COMPLETE, MediaTransferStatus.FAILED, MediaTransferStatus.CANCELLED) }
                .forEach { observe(it.jobId) }
        }
    }

    /**
     * Retries a durable job without repeating OCR, compression, or encryption.
     *
     * @param jobId Unique identifier of the durable background job.
     */
    fun retryUpload(jobId: String) {
        retry(jobId)
        observedOwnerUid?.let { owner -> scope.launch { refreshUploads(owner) } }
    }

    /**
     * Removes completed local bookkeeping only after Firestore publishes the matching media record.
     *
     * @param mediaIds Collection of media IDs values to process.
     */
    override fun acknowledgePublished(mediaIds: Set<String>) {
        val owner = observedOwnerUid ?: return
        publishedMediaIds = mediaIds
        scope.launch {
            val completed = withContext(Dispatchers.IO) {
                store.all().filter {
                    it.ownerUid == owner && it.status == MediaTransferStatus.COMPLETE && it.mediaId in mediaIds
                }
            }
            withContext(Dispatchers.IO) { completed.forEach { store.remove(it.id) } }
            completed.forEach {
                observationJobs.remove(it.id)?.cancel()
                workProgress.remove(it.id)
            }
            refreshUploads(owner)
        }
    }

    /**
     * Entry point for picker-selected safety documents after OCR review.
     *
     * @param uri Content or file URI identifying the selected local item.
     * @param owner Owner supplied to [queueDocument].
     * @param ocrReview OCR review supplied to [queueDocument].
     * @param tags Collection of tags values to process.
     * @param onQueued Callback invoked when queued changes or occurs.
     * @param onFailure Callback invoked when failure changes or occurs.
     */
    override fun queueDocument(
        uri:Uri,owner:AccountIdentity,ocrReview:OcrReview,
        tags:Set<String>,
        onQueued:(String)->Unit,onFailure:(String)->Unit,
    ) {
        // Do not upload a low-confidence scan until the user chooses Retake or Save anyway.
        if(ocrReview==OcrReview.RETAKE_RECOMMENDED) {
            // OCR is advisory: pause for a user decision instead of silently rejecting the document.
            state=MediaTransferState.Failed(null,"Document quality review recommends another capture",false)
            onFailure("Document quality review recommends another capture")
            return
        }
        val kind=pickerMediaKind(applicationContext.contentResolver.getType(uri).orEmpty())
        scope.launch {
            state=MediaTransferState.Preparing("encrypting")
            runCatching { withContext(Dispatchers.IO) { prepareUri(uri,owner,ocrReview,tags) } }
                .onSuccess { job ->
                    store.write(job)
                    MediaUploadWorker.enqueue(applicationContext,job.id)
                    observe(job.id)
                    refreshUploads(owner.uid)
                    state = MediaTransferState.Queued(job.id)
                    onQueued(job.id)
                }.onFailure { failure ->
                    val message=preparationFailureResolver(applicationContext,failure,kind)
                    state = MediaTransferState.Failed(null, message, false)
                    onFailure(message)
                }
        }
    }

    /**
     * Queues a camera/capture file through the same preprocessing and encryption pipeline.
     *
     * @param file Local file to read, hash, process, or write.
     * @param name Field, key, or display name.
     * @param mimeType MIME content type of the payload.
     * @param owner Owner supplied to [queueFile].
     * @param kind Kind supplied to [queueFile].
     * @param ocrReview OCR review supplied to [queueFile].
     * @param ocr Optional OCR supplied to [queueFile].
     * @param tags Collection of tags values to process.
     * @param onQueued Callback invoked when queued changes or occurs.
     * @param onFailure Callback invoked when failure changes or occurs.
     */
    override fun queueFile(
        file:File,name:String,mimeType:String,owner:AccountIdentity,kind:MediaKind,
        ocrReview:OcrReview,ocr:DocumentOcrMetadata?,
        tags:Set<String>,
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
                prepare(file.inputStream(),name,mimeType,owner,kind,ocrReview,ocr,tags)
            } }.onSuccess { job ->
                store.write(job)
                MediaUploadWorker.enqueue(applicationContext,job.id)
                observe(job.id)
                refreshUploads(owner.uid)
                state = MediaTransferState.Queued(job.id)
                onQueued(job.id)
            }.onFailure { failure ->
                val message=preparationFailureResolver(applicationContext,failure,kind)
                state = MediaTransferState.Failed(null, message, false)
                onFailure(message)
            }
        }
    }

    /**
     * Computes queue voice message.
     *
     * @param file Local file to read, hash, process, or write.
     * @param owner Owner supplied to [queueVoiceMessage].
     * @param voice Voice supplied to [queueVoiceMessage].
     */
    suspend fun queueVoiceMessage(file: File, owner: AccountIdentity, voice: PendingVoiceMessage) {
        try {
            val job = withContext(Dispatchers.IO) {
                val job = prepare(file.inputStream(), "Voice message.m4a", "audio/mp4", owner,
                    MediaKind.AUDIO, OcrReview.NOT_APPLICABLE).copy(voiceDraft = voice)
                store.write(job)
                MediaUploadWorker.enqueue(applicationContext, job.id)
                job
            }
            if (observedOwnerUid == owner.uid) {
                observe(job.id)
                refreshUploads(owner.uid)
            }
        } finally {
            file.delete()
        }
    }

    /**
     * Re-enqueues an existing durable job without repeating OCR, compression, or encryption.
     *
     * @param jobId Unique identifier of the durable background job.
     */
    fun retry(jobId:String) {
        val job=store.get(jobId) ?: return
        store.write(job.copy(status=MediaTransferStatus.QUEUED,error=null))
        // A manual retry replaces a worker that may still be waiting in exponential backoff.
        MediaUploadWorker.enqueue(applicationContext,jobId,replaceDelayedWork=true)
        observe(jobId)
        state = MediaTransferState.Queued(jobId)
    }

    /**
     * Downloads and decrypts an R2-backed document for the authenticated viewer.
     *
     * @param document Safety document metadata record.
     * @param viewerUid Firebase UID of the viewing account.
     * @return The resulting [File] for download file.
     */
    override suspend fun downloadFile(document:SafetyDocument,viewerUid:String):File {
        require(document.objectKey.isNotBlank()) { "Legacy media downloads use SafetyDocumentRepository" }
        sessionDecryptedCache[document.id]?.takeIf { it.isFile && it.length() > 0 }?.let { return it }
        state=MediaTransferState.Preparing("downloading")
        return try {
            val opened=withContext(Dispatchers.IO) {
                downloadGate.withDocumentLock(document.id) {
                    sessionDecryptedCache[document.id]?.takeIf { it.isFile && it.length() > 0 }?.let { return@withDocumentLock it }
                    downloadEncrypted(document,viewerUid)
                }
            }
            refreshOfflineCopies(viewerUid,listOf(document))
            opened.also { state=MediaTransferState.Idle }
        } catch(failure:Exception) {
            if (failure is MediaAccessException && failure.reason == MediaAccessError.ACCESS_REVOKED) {
                offlineCache.remove(viewerUid, document.id)
                sessionDecryptedCache.remove(document.id)
            }
            val message=failure.localizedMessage ?: "Unable to download encrypted media"
            state=MediaTransferState.Failed(null,message,true)
            throw failure
        }
    }

    override fun openPendingUpload(jobId: String, viewerUid: String): Pair<SafetyDocument, File>? {
        val job = store.get(jobId) ?: return null
        val target = File(job.source.filePath)
        if (!target.isFile) return null
        val metadata = runCatching {
            val wrapped = job.source.wrappedKeys[viewerUid] ?: return null
            JSONObject(EncryptionUtils.decryptText(job.source.encryptedMetadata,
                KeyManager.unwrapKey(wrapped, job.source.encryptionVersion)))
        }.getOrNull() ?: return null
        val doc = SafetyDocument(
            id = job.id,
            ownerUid = job.ownerUid,
            storagePath = "",
            name = metadata.optString("name", "Document"),
            mimeType = metadata.optString("mimeType", "application/octet-stream"),
            allowedUsers = job.source.allowedUsers,
            createdAt = job.createdAt,
            objectKey = "pending/${job.id}",
            ciphertextSize = job.source.ciphertextSize,
            ciphertextSha256 = job.source.ciphertextSha256,
            wrappedKeys = job.source.wrappedKeys,
            deviceWrappedKeys = job.source.deviceWrappedKeys,
            encryptionVersion = job.source.encryptionVersion,
        )
        val file = sessionDecryptedCache[job.id]?.takeIf { it.isFile && it.length() > 0 } ?: run {
            val wrapped = listOfNotNull(doc.deviceWrappedKeys["$viewerUid--${KeyManager.deviceId()}"], doc.wrappedKeys[viewerUid])
            val key = runCatching { KeyManager.unwrapFirst(wrapped, doc.encryptionVersion) }.getOrNull() ?: return null
            val opened = File(applicationContext.cacheDir, "opened-documents").apply { mkdirs() }
            val extension = doc.name.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
            val partial = File.createTempFile("preview-", ".part", opened)
            val decryptedTarget = File(opened, partial.nameWithoutExtension + (extension?.let { ".$it" } ?: ".preview"))
            try {
                target.inputStream().buffered().use { input ->
                    partial.outputStream().buffered().use { output ->
                        MediaCipher.decrypt(input, output, key)
                    }
                }
                check(partial.renameTo(decryptedTarget))
                sessionDecryptedCache[job.id] = decryptedTarget
                decryptedTarget
            } catch (_: Exception) {
                partial.delete()
                return null
            }
        }
        return doc to file
    }

    /**
     * Builds available offline.
     *
     * @param document Safety document metadata record.
     * @param viewerUid Firebase UID of the viewing account.
     */
    override suspend fun makeAvailableOffline(document:SafetyDocument,viewerUid:String) {
        require(document.objectKey.isNotBlank())
        offlineCopies = offlineCopies + (document.id to OfflineMediaAvailability.SAVING)
        try {
            withContext(Dispatchers.IO) { downloadGate.withDocumentLock(document.id) {
                when (offlineCache.availability(viewerUid, document)) {
                    OfflineMediaAvailability.AVAILABLE -> Unit
                    OfflineMediaAvailability.RECENT_AVAILABLE -> offlineCache.pin(viewerUid,document.id)
                    OfflineMediaAvailability.REVALIDATION_REQUIRED,
                    OfflineMediaAvailability.RECENT_REVALIDATION_REQUIRED -> {
                        requireNetwork(MediaAccessError.LEASE_EXPIRED)
                        repository.downloadTicket(document.id)
                        offlineCache.markRevalidated(viewerUid, document.id)
                        offlineCache.pin(viewerUid,document.id)
                    }
                    else -> if (!offlineCache.promoteUploadCandidate(viewerUid, document)) {
                        requireNetwork(MediaAccessError.NETWORK_REQUIRED)
                        val encrypted = downloadRemoteCiphertext(document)
                        try { offlineCache.retainDownloaded(viewerUid, document, encrypted) }
                        finally { encrypted.delete() }
                    }
                }
            } }
        } finally { refreshOfflineCopies(viewerUid, listOf(document)) }
    }

    /**
     * Removes offline copy.
     *
     * @param document Safety document metadata record.
     * @param viewerUid Firebase UID of the viewing account.
     */
    override suspend fun removeOfflineCopy(document:SafetyDocument,viewerUid:String) {
        withContext(Dispatchers.IO) { downloadGate.withDocumentLock(document.id) {
            offlineCache.remove(viewerUid, document.id)
            sessionDecryptedCache.remove(document.id)
        } }
        offlineCopies = offlineCopies + (document.id to OfflineMediaAvailability.NOT_CACHED)
    }

    /**
     * Handles the document snapshot event.
     *
     * @param viewerUid Firebase UID of the viewing account.
     * @param documents Collection of documents values to process.
     * @param serverConfirmed Whether to apply server confirmed.
     */
    override fun onDocumentSnapshot(
        viewerUid:String,
        documents:List<SafetyDocument>,
        serverConfirmed:Boolean,
    ) {
        scope.launch {
            val values = withContext(Dispatchers.IO) {
                if (serverConfirmed) offlineCache.reconcile(viewerUid, documents)
                documents.associate { it.id to offlineCache.availability(viewerUid, it) }
            }
            if (observedOwnerUid == null || observedOwnerUid == viewerUid) offlineCopies = values
            if (serverConfirmed) {
                prefetchRecentCiphertext(documents, viewerUid)
            }
        }
    }

    /**
     * Cancels active upload listeners and removes their visible state.
     */
    override fun clearState() {
        observationJobs.values.forEach(Job::cancel)
        observationJobs.clear()
        workProgress.clear()
        observedOwnerUid = null
        publishedMediaIds = emptySet()
        uploads = emptyList()
        offlineCopies = emptyMap()
        cachePreferenceOwnerUid = null
        recentCachingEnabled = false
        speculativeCiphertextFiles.values.forEach(File::delete)
        speculativeCiphertextFiles.clear()
        state = MediaTransferState.Idle
    }

    /**
     * Loads recent caching preference.
     *
     * @param ownerUid Firebase UID of the resource owner.
     */
    fun loadRecentCachingPreference(ownerUid:String) {
        cachePreferenceOwnerUid=ownerUid
        scope.launch {
            val enabled=withContext(Dispatchers.IO) { offlineCache.recentCachingEnabled(ownerUid) }
            if(cachePreferenceOwnerUid==ownerUid) recentCachingEnabled=enabled
        }
    }

    /**
     * Sets recent caching preference.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @param enabled Whether this feature or control is currently enabled.
     */
    fun setRecentCachingPreference(ownerUid:String,enabled:Boolean) {
        cachePreferenceOwnerUid=ownerUid
        recentCachingEnabled=enabled
        scope.launch {
            runCatching { withContext(Dispatchers.IO) {
                offlineCache.setRecentCachingEnabled(ownerUid,enabled)
            } }.onFailure {
                if(cachePreferenceOwnerUid==ownerUid) recentCachingEnabled=
                    withContext(Dispatchers.IO) { offlineCache.recentCachingEnabled(ownerUid) }
            }
            if(cachePreferenceOwnerUid==ownerUid && !enabled) {
                offlineCopies=offlineCopies.mapValues { (_,availability) ->
                    if(availability in setOf(OfflineMediaAvailability.RECENT_AVAILABLE,
                            OfflineMediaAvailability.RECENT_REVALIDATION_REQUIRED))
                        OfflineMediaAvailability.NOT_CACHED else availability
                }
            }
        }
    }

    /**
     * Projects durable WorkManager progress into Compose-observable session state.
     *
     * @param jobId Unique identifier of the durable background job.
     */
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

    /**
     * Reads encrypted durable jobs off the main thread and publishes a key-free UI projection.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @return List of resulting refresh uploads items.
     */
    private suspend fun refreshUploads(ownerUid:String):List<MediaUploadPresentation> {
        val progressSnapshot=workProgress.toMap()
        val publishedSnapshot=publishedMediaIds
        val jobs=withContext(Dispatchers.IO) {
            val owned=store.all().filter { it.ownerUid==ownerUid }
            val retired=owned.filter { it.status==MediaTransferStatus.COMPLETE && it.mediaId in publishedSnapshot }
            // Refresh each pending upload card with its newest background-worker state.
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
                    job.mediaId,job.error,job.createdAt,
                    job.voiceDraft?.conversationId ?: job.voiceMessage?.conversationId)
            }
        }
        if(observedOwnerUid==ownerUid) uploads=jobs
        return jobs
    }

    /**
     * Reconstructs multipart progress from persisted ETags when live WorkInfo is unavailable.
     *
     * @param job Job supplied to [completedBytes].
     * @return The computed completed bytes numeric value.
     */
    private fun completedBytes(job:MediaUploadJob):Long {
        if(job.status==MediaTransferStatus.COMPLETE) return job.source.ciphertextSize
        if(job.partSize<=0 || job.completedParts.isEmpty()) return 0
        return job.completedParts.keys.sumOf { part ->
            val offset=(part-1)*job.partSize
            minOf(job.partSize,(job.source.ciphertextSize-offset).coerceAtLeast(0))
        }.coerceAtMost(job.source.ciphertextSize)
    }

    /**
     * Resolves picker metadata, then hands the stream to the shared preparation pipeline.
     *
     * @param uri Content or file URI identifying the selected local item.
     * @param owner Owner supplied to [prepareUri].
     * @param ocrReview OCR review supplied to [prepareUri].
     * @param tags Collection of tags values to process.
     * @return The resulting [MediaUploadJob] for prepare URI.
     */
    private suspend fun prepareUri(
        uri:Uri, owner:AccountIdentity, ocrReview:OcrReview, tags:Set<String>
    ):MediaUploadJob {
        val resolver=applicationContext.contentResolver
        val mime=resolver.getType(uri)?.take(120)?.ifBlank { null } ?: "application/octet-stream"
        val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
            if(cursor.moveToFirst()) cursor.getString(0) else null
        }?.trim()?.take(160).orEmpty().ifBlank { "Document" }
        // Images selected here remain document scans for OCR, while videos use the video pipeline.
        val kind=pickerMediaKind(mime)
        return prepare(
            resolver.openInputStream(uri) ?: error("Unable to read the selected file"),
            name,mime,owner,kind,ocrReview,tags=tags
        )
    }

    /**
     * Security-critical preparation boundary. Plaintext is consumed here, optionally compressed,
     * and immediately encrypted into the app-private outbox before a durable/network job exists.
     *
     * @param input Input stream, matrix, or value to process.
     * @param name Field, key, or display name.
     * @param mimeType MIME content type of the payload.
     * @param owner Owner supplied to [prepare].
     * @param kind Kind supplied to [prepare].
     * @param ocrReview OCR review supplied to [prepare].
     * @param ocr Optional OCR supplied to [prepare].
     * @param tags Collection of tags values to process.
     * @return The resulting [MediaUploadJob] instance.
     */
    private suspend fun prepare(
        input:InputStream,name:String,mimeType:String,owner:AccountIdentity,kind:MediaKind,ocrReview:OcrReview,
        ocr:DocumentOcrMetadata?=null,
        tags:Set<String> = emptySet(),
    ):MediaUploadJob {
        val accountKeyset=accountKeysetProvider(owner.uid)
            ?: error("Set up account recovery before uploading encrypted media")
        val jobId=UUID.randomUUID().toString()
        val folder=File(applicationContext.noBackupFilesDir,"media-outbox").apply { mkdirs() }
        val target=File(folder,"$jobId.olive")
        val key=EncryptionUtils.generateSymmetricKey()
        // Turn an unexpected prepare failure into a readable message.
        try {
            var processedMimeType=mimeType
            var processedName=name
            // The preprocessor owns compression only. Its output is immediately consumed by the
            // cipher, and ProcessedMedia.close() guarantees adapter-specific plaintext cleanup.
            input.use { source -> preprocessor.process(source,mimeType,kind).use { processed ->
                processedMimeType=processed.mimeType
                processedName=processed.displayName(name)
                target.outputStream().buffered().use { MediaCipher.encrypt(processed.input,it,key) }
            } }
            require(target.length() in 1..MAX_MEDIA_CIPHERTEXT_BYTES) {
                "Encrypted media must be smaller than 250 MB"
            }
            val metadata=JSONObject().apply {
                put("name", processedName.trim().take(160).ifBlank { "Document" })
                put("mimeType", processedMimeType.take(120))
                put("kind", kind.name)
                put("ocrReview", ocrReview.name)
                put("tags", JSONArray(
                    tags.asSequence().map(String::trim).filter(String::isNotBlank)
                        .map { it.take(80) }.distinct().take(20).toList()
                ))
                ocr?.let {
                    put("ocrText",it.text.take(MAX_OCR_TEXT))
                    put("ocrConfidence",it.confidence.coerceIn(0f,1f).toDouble())
                    put("ocrSuccessful",it.successful)
                }
            }.toString()
            // Device and account wrappers are parallel encrypted copies of this AES key. Firebase
            // receives neither the raw key nor a wrapper it can decrypt.
            val publicKey=KeyManager.getOrCreatePublicKey()
            val ownerWrapped=KeyManager.wrapKey(key,publicKey)
            val wrapped=mapOf(owner.uid to ownerWrapped)
            val deviceWrapped=mapOf("${owner.uid}--${KeyManager.deviceId(publicKey)}" to ownerWrapped)
            val accountWrapped=mapOf(owner.uid to AccountKeysetWrappedKey(
                accountKeyset.keyId,accountKeyset.epoch,"RSA-OAEP-256",
                KeyManager.wrapForAccountKeyset(
                    key,KeyManager.decodePublicKey(accountKeyset.publicKeyBase64),
                ),
            ))
            val prepared=PreparedEncryptedMedia(target.absolutePath,target.length(),offlineCache.sha256(target),
                EncryptionUtils.encryptText(metadata,key),wrapped,listOf(owner.uid),
                KeyManager.CURRENT_KEY_WRAP_VERSION,kind,ocrReview,deviceWrapped,accountWrapped)
            return MediaUploadJob(jobId,owner.uid,prepared,
                attachToSafetyPlan = tags.any { it.startsWith("safety-plan:") })
        } catch (failure: Throwable) {
            target.delete()
            throw failure
        }
    }

    /**
     * Executes the encrypted download workflow without exposing plaintext to Firebase or R2.
     *
     * @param document Safety document metadata record.
     * @param viewerUid Firebase UID of the viewing account.
     * @return The resulting [File] for download encrypted.
     */
    private suspend fun downloadEncrypted(document:SafetyDocument,viewerUid:String):File {
        offlineCache.verifiedCiphertext(viewerUid, document)?.let {
            return decryptCiphertext(it, document, viewerUid)
        }
        speculativeCiphertextFiles[document.id]?.takeIf { it.isFile && it.length() == document.ciphertextSize }?.let { cachedFile ->
            val opened = decryptCiphertext(cachedFile, document, viewerUid)
            runCatching { offlineCache.retainRecentDownloaded(viewerUid, document, cachedFile) }
            cachedFile.delete()
            speculativeCiphertextFiles.remove(document.id)
            return opened
        }
        if (offlineCache.promoteUploadCandidateAsRecent(viewerUid, document)) {
            offlineCache.verifiedCiphertext(viewerUid, document)?.let {
                return decryptCiphertext(it, document, viewerUid)
            }
        }
        if (offlineCache.availability(viewerUid, document) in setOf(
                OfflineMediaAvailability.REVALIDATION_REQUIRED,
                OfflineMediaAvailability.RECENT_REVALIDATION_REQUIRED,
            )) {
            requireNetwork(MediaAccessError.LEASE_EXPIRED)
            repository.downloadTicket(document.id)
            offlineCache.markRevalidated(viewerUid, document.id)
            refreshOfflineCopies(viewerUid, listOf(document))
            offlineCache.verifiedCiphertext(viewerUid, document)?.let {
                return decryptCiphertext(it, document, viewerUid)
            }
        }
        requireNetwork(MediaAccessError.NETWORK_REQUIRED)
        val encrypted = downloadRemoteCiphertext(document)
        return try {
            val opened=decryptCiphertext(encrypted, document, viewerUid)
            runCatching { offlineCache.retainRecentDownloaded(viewerUid,document,encrypted) }
            opened
        }
        finally { encrypted.delete() }
    }

    /**
     * Prefetches ciphertext for recent documents into offline media cache or private storage.
     *
     * @param documents Collection of documents values to process.
     * @param viewerUid Firebase UID of the viewing account.
     */
    override suspend fun prefetchRecentCiphertext(documents: List<SafetyDocument>, viewerUid: String) {
        val candidates = documents
            .filter { it.objectKey.isNotBlank() && viewerUid in it.allowedUsers }
            .take(3)
        if (candidates.isEmpty()) return
        withContext(Dispatchers.IO) {
            for (doc in candidates) {
                if (offlineCache.verifiedCiphertext(viewerUid, doc) != null) continue
                if (speculativeCiphertextFiles[doc.id]?.exists() == true) continue
                downloadGate.withDocumentLock(doc.id) {
                    if (offlineCache.verifiedCiphertext(viewerUid, doc) != null) return@withDocumentLock
                    if (speculativeCiphertextFiles[doc.id]?.exists() == true) return@withDocumentLock
                    runCatching {
                        val encrypted = downloadRemoteCiphertext(doc)
                        val retained = offlineCache.retainRecentDownloaded(viewerUid, doc, encrypted)
                        if (!retained) {
                            speculativeCiphertextFiles[doc.id] = encrypted
                        } else {
                            encrypted.delete()
                        }
                    }
                }
            }
        }
    }

    /**
     * Downloads remote ciphertext.
     *
     * @param document Safety document metadata record.
     * @return The resulting [File] for download remote ciphertext.
     */
    private suspend fun downloadRemoteCiphertext(document:SafetyDocument):File {
        val ticket=repository.downloadTicket(document.id)
        val builder = Request.Builder().url(ticket.url).get()
        ticket.headers.forEach(builder::header)
        val encryptedFolder=File(applicationContext.cacheDir,"encrypted-media").apply { mkdirs() }
        val encrypted= withContext(Dispatchers.IO) {
            File.createTempFile("${document.id}.", ".olive.part", encryptedFolder)
        }
        // Turn an unexpected download encrypted failure into a readable message.
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
            return encrypted
        } catch (failure:Exception) {
            encrypted.delete()
            throw failure
        }
    }

    /**
     * Decrypts ciphertext.
     *
     * @param encrypted Encrypted supplied to [decryptCiphertext].
     * @param document Safety document metadata record.
     * @param viewerUid Firebase UID of the viewing account.
     * @return The resulting [File] for decrypt ciphertext.
     */
    private suspend fun decryptCiphertext(encrypted:File,document:SafetyDocument,viewerUid:String):File = withContext(Dispatchers.IO) {
        val deviceKey="$viewerUid--${KeyManager.deviceId()}"
        val wrapped=listOfNotNull(document.deviceWrappedKeys[deviceKey],document.wrappedKeys[viewerUid])
        val key=runCatching { KeyManager.unwrapFirst(wrapped,document.encryptionVersion) }
            .getOrElse { deviceFailure ->
                val accountWrapper=document.accountWrappedKeys[viewerUid]
                    ?: throw MediaAccessException(MediaAccessError.KEY_UNAVAILABLE,deviceFailure)
                val keyset=runCatching { accountKeysetUnlocker(viewerUid) }.getOrNull()
                    ?: throw MediaAccessException(MediaAccessError.KEY_UNAVAILABLE,deviceFailure)
                runCatching { accountWrapper.unwrap(keyset) }
                    .getOrElse { throw MediaAccessException(MediaAccessError.KEY_UNAVAILABLE,it) }
            }
        val opened=File(applicationContext.cacheDir,"opened-documents").apply { mkdirs() }
        val extension=document.name.substringAfterLast('.',"").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
        val partial = File.createTempFile("preview-", ".part", opened)
        val target = File(opened, partial.nameWithoutExtension + (extension?.let { ".$it" } ?: ".preview"))
        try {
            encrypted.inputStream().buffered().use { input -> partial.outputStream().buffered().use { output ->
                MediaCipher.decrypt(input,output,key)
            } }
            check(partial.renameTo(target))
            sessionDecryptedCache[document.id] = target
            return@withContext target
        } catch(failure:Exception) {
            partial.delete()
            throw failure
        }
    }

    /**
     * Refreshes offline copies.
     *
     * @param viewerUid Firebase UID of the viewing account.
     * @param documents Collection of documents values to process.
     */
    private suspend fun refreshOfflineCopies(viewerUid:String,documents:List<SafetyDocument>) {
        val values=withContext(Dispatchers.IO) {
            documents.associate { it.id to offlineCache.availability(viewerUid,it) }
        }
        offlineCopies=offlineCopies+values
    }

    /**
     * Validates required network.
     *
     * @param reason Reason supplied to [requireNetwork].
     */
    private fun requireNetwork(reason:MediaAccessError) {
        if (!hasValidatedInternetConnection(applicationContext)) {
            throw MediaAccessException(reason)
        }
    }

    private companion object {
        const val MAX_OCR_TEXT=50_000
        /**
         * Rejects a download when its encrypted byte count differs from metadata.
         *
         * @param expected Expected supplied to [sizeMismatch].
         * @param received Received supplied to [sizeMismatch].
         * @return The resulting size mismatch.
         */
        fun sizeMismatch(expected:Long,received:Long)=
            "Encrypted download size does not match (expected $expected bytes, received $received bytes)"
    }
}

/**
 * Computes preparation failure resource.
 *
 * @param failure Exception or failure reason encountered during execution.
 * @param kind Kind supplied to [preparationFailureResource].
 * @return The computed preparation failure resource number, or `null` if unavailable.
 */
internal fun preparationFailureResource(failure:Throwable,kind:MediaKind):Int?=
    if(failure is OutOfMemoryError || failure is MediaPreparationMemoryException) {
        if(kind==MediaKind.VIDEO) R.string.documents_video_memory_failed
        else R.string.documents_media_memory_failed
    } else null

/**
 * Computes preparation failure message.
 *
 * @param context Android context used to access system services, resources, or storage.
 * @param failure Exception or failure reason encountered during execution.
 * @param kind Kind supplied to [preparationFailureMessage].
 * @return The resulting preparation failure message string.
 */
private fun preparationFailureMessage(
    context:Context,
    failure:Throwable,
    kind:MediaKind,
):String {
    val resource=preparationFailureResource(failure,kind)
    return resource?.let(context::getString)
        ?: failure.localizedMessage
        ?: context.getString(R.string.documents_upload_failed)
}
