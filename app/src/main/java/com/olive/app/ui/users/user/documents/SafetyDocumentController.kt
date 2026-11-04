// Safe-app users: manages safety document actions.
package com.olive.app.ui.users.user.documents


import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olive.app.data.AccountIdentity
import com.olive.app.data.SafetyDocument
import com.olive.app.data.SafetyDocumentDataSource
import com.olive.app.data.ChatSubscription
import com.olive.app.data.DocumentOcrMetadata
import com.olive.app.data.DocumentListSource
import com.olive.app.data.PrivateEntry
import com.olive.app.data.UserProfile
import com.olive.app.R
import com.olive.app.data.media.MediaAccessError
import com.olive.app.data.media.MediaAccessException
import com.olive.app.data.media.MediaDeviceVerificationException
import com.olive.app.data.media.MediaKind
import com.olive.app.data.media.OcrReview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal data class SafetyDocumentsState(
    val ownerUid: String? = null,
    val files: List<SafetyDocument> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val source: DocumentListSource? = null,
    val lastSyncedAt: Long? = null,
    val attachmentErrors: Map<String, String> = emptyMap(),
)

/** Owns document-storage workflows so the Compose screen only renders state and dispatches actions. */
internal class SafetyDocumentsController(
    context: Context,
    private val scope: CoroutineScope,
    private val repository: SafetyDocumentDataSource?,
    private val media: MediaTransferOperations?,
) {
    private val applicationContext = context.applicationContext
    private var generation = 0
    private var subscription: ChatSubscription? = null
    var state by mutableStateOf(SafetyDocumentsState())
        private set

    // Clears old document state before loading a different account owner.
    fun prepare(ownerUid: String) {
        subscription?.close()
        subscription = null
        generation++
        state = SafetyDocumentsState(ownerUid = ownerUid)
    }

    // Watches the owner's allowed documents and updates the screen as Firebase changes.
    fun load(ownerUid: String, username: String, localMode: Boolean) {
        if (state.ownerUid != ownerUid) {
            subscription?.close()
            subscription = null
            generation++
            state = SafetyDocumentsState(ownerUid = ownerUid)
        }
        if (localMode || repository == null) return
        media?.observeUploads(ownerUid)
        if (subscription != null) return
        val token = generation
        state = state.copy(loading = true, error = null)
        subscription = repository.observeFor(ownerUid, { snapshot ->
            // Apply loaded documents only when the same owner and request are still active.
            if (token == generation && state.ownerUid == ownerUid) {
                state = state.copy(files = snapshot.documents, loading = false, error = null,
                    source = snapshot.source, lastSyncedAt = snapshot.lastSyncedAt)
                media?.acknowledgePublished(snapshot.documents.mapTo(mutableSetOf()) { it.id })
                media?.onDocumentSnapshot(ownerUid, snapshot.documents,
                    snapshot.source == DocumentListSource.SERVER)
                if (snapshot.source == DocumentListSource.SERVER) snapshot.documents.forEach { document ->
                    scope.launch {
                        runCatching { repository.approveCurrentAccountDevices(document, ownerUid, username) }
                    }
                }
            }
        }, { error ->
            if (token == generation && state.ownerUid == ownerUid) {
                subscription?.close()
                subscription = null
                state = state.copy(loading = false,
                    error = error.ifBlank { applicationContext.getString(R.string.documents_load_failed) }
                        .takeIf { state.files.isEmpty() })
            }
        })
    }

    // Queues a file selected from Android storage for encrypted upload.
    fun upload(
        uri: Uri,
        identity: AccountIdentity,
        fallbackError: String,
        ocrReview: OcrReview = OcrReview.NOT_RUN,
        tags: Set<String> = emptySet(),
    ) {
        val transfers = media ?: return reportError(fallbackError)
        state = state.copy(loading = true, error = null)
        transfers.queueDocument(uri, identity, ocrReview, tags, onQueued = {
            state = state.copy(loading = false, error = null)
            if (subscription == null) load(identity.uid, identity.username, false)
        }, onFailure = { state = state.copy(loading = false, error = it.ifBlank { fallbackError }) })
    }

    // Queues a camera photo, video, or OCR document for encrypted upload.
    fun uploadCaptured(
        file: File,
        name: String,
        mimeType: String,
        identity: AccountIdentity,
        fallbackError: String,
        kind: MediaKind,
        ocr: DocumentOcrMetadata? = null,
        onComplete: () -> Unit
    ) {
        val transfers = media ?: return reportError(fallbackError)
        state = state.copy(loading = true, error = null)
        val review = when {
            kind != MediaKind.DOCUMENT -> OcrReview.NOT_APPLICABLE
            ocr == null -> OcrReview.NOT_RUN
            ocr.successful -> OcrReview.PASSED
            else -> OcrReview.RETAKE_DECLINED
        }
        transfers.queueFile(file, name, mimeType, identity, kind, review, ocr, emptySet(),
            onQueued = {
                file.delete()
                state = state.copy(loading = false, error = null)
                if (subscription == null) load(identity.uid, identity.username, false)
                onComplete()
            },
            onFailure = { state = state.copy(loading = false, error = it.ifBlank { fallbackError }) })
    }

    // Downloads and decrypts an allowed document, then opens its in-app viewer.
    fun download(document: SafetyDocument, onReady: (File) -> Unit) {
        val viewerUid = state.ownerUid ?: return
        val token = generation
        scope.launch {
            state = state.copy(loading = true, error = null)
            runCatching { openDocument(document, viewerUid) }
                .onSuccess { file ->
                    if (token == generation && state.ownerUid == viewerUid) onReady(file)
                }
                .onFailure { failure ->
                    if (token == generation) {
                        state = state.copy(error = localizedFailure(failure),
                            attachmentErrors = state.attachmentErrors + (document.id to localizedFailure(failure)))
                    }
                }
            if (token == generation) state = state.copy(loading = false)
        }
    }

    // Opens a document shared through an encrypted conversation.
    fun openShared(
        viewerUid: String,
        documentId: String,
        onReady: (SafetyDocument, File) -> Unit,
        onFinished: () -> Unit
    ) {
        if (state.ownerUid != viewerUid) {
            generation++
            state = SafetyDocumentsState(ownerUid = viewerUid)
        }
        val token = generation
        scope.launch {
            state = state.copy(loading = true, error = null)
            runCatching {
                val source = checkNotNull(repository)
                val document = if (hasValidatedNetwork(applicationContext)) {
                    source.getFor(viewerUid, documentId)
                } else source.cachedFor(viewerUid, documentId)
                    ?: throw MediaAccessException(MediaAccessError.NOT_CACHED)
                val file = openDocument(document, viewerUid)
                if (token == generation && state.ownerUid == viewerUid) {
                    state = state.copy(attachmentErrors = state.attachmentErrors - documentId)
                    onReady(document, file)
                }
            }.onFailure { if (token == generation) {
                val message = localizedFailure(it)
                state = state.copy(error = message,
                    attachmentErrors = state.attachmentErrors + (documentId to message))
            } }
            if (token == generation) {
                state = state.copy(loading = false)
                onFinished()
            }
        }
    }

    // User safety document controller: Deletes the selected item. It requires the active feature controller before continuing.
    fun delete(document: SafetyDocument, ownerUid: String) = run(refreshAfter = true) {
        checkNotNull(repository).delete(document, ownerUid)
    }

    fun makeAvailableOffline(document: SafetyDocument) {
        val viewerUid = state.ownerUid ?: return
        run { checkNotNull(media).makeAvailableOffline(document, viewerUid) }
    }

    fun removeOfflineCopy(document: SafetyDocument) {
        val viewerUid = state.ownerUid ?: return
        run { checkNotNull(media).removeOfflineCopy(document, viewerUid) }
    }

    // Saves the Evidence toggle and refreshes the document card after the cloud accepts it.
    fun setEvidence(document: SafetyDocument, ownerUid: String, evidence: Boolean) = run(refreshAfter = true) {
        checkNotNull(repository).setEvidence(document, ownerUid, evidence)
    }

    // The emergency button builds a PDF, encrypts it, and adds it to the user's Documents list.
    fun createEmergencyReport(
        identity: AccountIdentity,
        profile: UserProfile,
        entries: List<PrivateEntry>,
        location: EmergencyReportLocation?,
        onFinished: (Result<Unit>) -> Unit,
    ) {
        val ownerUid = identity.uid
        if (ownerUid.isBlank()) return onFinished(
            Result.failure(IllegalStateException("The account is not ready for an emergency export.")),
        )
        val token = generation
        scope.launch {
            state = state.copy(loading = true, error = null)
            val result = runCatching {
                val incidents = emergencyIncidents(entries)
                val available = repository?.listFor(ownerUid)?.documents ?: state.files
                val relevantIds = incidents.flatMapTo(linkedSetOf()) { it.attachmentIds }.apply {
                    addAll(available.filter(SafetyDocument::evidence).map(SafetyDocument::id))
                }
                val relevant = available.filter { it.id in relevantIds }
                val files = linkedMapOf<String, EmergencyReportMedia>()
                relevant.forEach { document ->
                    val file = runCatching { openDocument(document, ownerUid) }.getOrNull()
                    files[document.id] = EmergencyReportMedia(document, file)
                }
                withContext(Dispatchers.IO) {
                    EmergencyReportPdf.create(applicationContext, profile, incidents, location, files)
                }
            }
            result.onFailure { error ->
                if (token == generation) {
                    state = state.copy(loading = false, error = error.localizedMessage)
                    onFinished(Result.failure(error))
                }
            }.onSuccess { report ->
                val transfers = media
                if (transfers == null) {
                    report.delete()
                    val error = IllegalStateException("Secure document storage is not ready.")
                    if (token == generation) {
                        state = state.copy(loading = false, error = error.message)
                        onFinished(Result.failure(error))
                    }
                    return@onSuccess
                }
                transfers.queueFile(
                    file = report,
                    name = report.name,
                    mimeType = "application/pdf",
                    owner = identity,
                    kind = MediaKind.DOCUMENT,
                    ocrReview = OcrReview.NOT_APPLICABLE,
                    ocr = null,
                    onQueued = {
                        report.delete()
                        if (token == generation) {
                            state = state.copy(loading = false, error = null)
                            if (subscription == null) load(identity.uid, identity.username, false)
                            onFinished(Result.success(Unit))
                        }
                    },
                    onFailure = { message ->
                        report.delete()
                        val error = IllegalStateException(message)
                        if (token == generation) {
                            state = state.copy(loading = false, error = message)
                            onFinished(Result.failure(error))
                        }
                    },
                )
            }
        }
    }

    // User safety document controller: Shares the selected item. It requires the active feature controller before continuing.
    fun share(
        document: SafetyDocument,
        ownerUid: String,
        recipients: Map<String, String>,
        onComplete: () -> Unit
    ) = run(refreshAfter = true, onComplete = onComplete) {
        checkNotNull(repository).share(document, ownerUid, recipients)
    }

    // Changes a document failure into a clear message shown on the Safe App screen.
    fun reportError(message: String?) {
        state = state.copy(error = message)
    }

    // Clears the current document viewer and its error message.
    fun clear() {
        subscription?.close()
        subscription = null
        generation++
        media?.clearState()
        File(applicationContext.cacheDir, "opened-documents").listFiles()?.forEach(File::delete)
        state = SafetyDocumentsState()
    }

    // Runs one document task, reports its error, and refreshes the list when needed.
    private fun run(
        refreshAfter: Boolean = false,
        fallbackError: String? = null,
        onComplete: () -> Unit = {},
        operation: suspend () -> Unit
    ) {
        val ownerUid = state.ownerUid ?: return
        val token = generation
        scope.launch {
            state = state.copy(loading = true, error = null)
            runCatching { operation() }
                .onSuccess {
                    if (refreshAfter) refresh(ownerUid)
                    if (token == generation && state.ownerUid == ownerUid) onComplete()
                }
                .onFailure {
                    if (token == generation) state =
                        state.copy(error = if (it is MediaAccessException) localizedFailure(it)
                        else it.localizedMessage ?: fallbackError)
                }
            if (token == generation) state = state.copy(loading = false)
        }
    }

    // Reloads the owner's document list after a write finishes.
    private suspend fun refresh(ownerUid: String) {
        val snapshot = checkNotNull(repository).listFor(ownerUid)
        if (state.ownerUid == ownerUid) {
            state = state.copy(files = snapshot.documents, source = snapshot.source,
                lastSyncedAt = snapshot.lastSyncedAt, error = null)
            media?.onDocumentSnapshot(ownerUid, snapshot.documents,
                snapshot.source == DocumentListSource.SERVER)
        }
    }

    // Opens a saved document after its encrypted content is ready.
    private suspend fun openDocument(document: SafetyDocument, viewerUid: String): File =
        if (document.objectKey.isNotBlank()) {
            checkNotNull(media) { "Encrypted media is unavailable" }.downloadFile(document, viewerUid)
        } else {
            checkNotNull(repository).download(applicationContext, document)
        }

    private fun localizedFailure(failure: Throwable): String {
        return applicationContext.getString(mediaFailureResource(failure))
    }
}

internal fun mediaFailureResource(failure: Throwable): Int {
    if (failure is MediaDeviceVerificationException) {
        return R.string.documents_device_verification_failed
    }
    val reason = (failure as? MediaAccessException)?.reason
    return when (reason) {
        MediaAccessError.NOT_CACHED -> R.string.documents_not_available_offline
        MediaAccessError.NETWORK_REQUIRED -> R.string.documents_network_required
        MediaAccessError.LEASE_EXPIRED -> R.string.documents_offline_revalidation_required
        MediaAccessError.ACCESS_REVOKED -> R.string.documents_access_revoked
        MediaAccessError.CORRUPT_CIPHERTEXT -> R.string.documents_offline_copy_corrupt
        MediaAccessError.KEY_UNAVAILABLE, MediaAccessError.DEVICE_KEY_CHANGED -> R.string.documents_key_unavailable
        MediaAccessError.ACCOUNT_MISMATCH -> R.string.documents_account_mismatch
        MediaAccessError.INSUFFICIENT_STORAGE -> R.string.documents_storage_full
        MediaAccessError.DOWNLOAD_FAILED -> R.string.documents_download_failed
        null -> R.string.documents_open_failed
    }
}

private fun hasValidatedNetwork(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
