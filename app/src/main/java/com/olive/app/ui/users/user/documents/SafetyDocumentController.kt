// Safe-app users: manages safety document actions.
package com.olive.app.ui.users.user.documents


import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olive.app.data.AccountIdentity
import com.olive.app.data.SafetyDocument
import com.olive.app.data.SafetyDocumentDataSource
import com.olive.app.data.ChatSubscription
import com.olive.app.data.DocumentOcrMetadata
import com.olive.app.data.media.MediaKind
import com.olive.app.data.media.OcrReview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

internal data class SafetyDocumentsState(
    val ownerUid: String? = null,
    val files: List<SafetyDocument> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
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

    // Prepares the feature data for user safety document controller. It checks access, protects the file, and then reads or saves it.
    fun prepare(ownerUid: String) {
        subscription?.close()
        subscription = null
        generation++
        state = SafetyDocumentsState(ownerUid = ownerUid)
    }

    // Loads the saved value for user safety document controller. It checks access, protects the file, and then reads or saves it.
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
        subscription = repository.observeFor(ownerUid, { files ->
            // token is generation and state owner uid is owner uid is handled here by load.
            if (token == generation && state.ownerUid == ownerUid) {
                state = state.copy(files = files, loading = false, error = null)
                media?.acknowledgePublished(files.mapTo(mutableSetOf()) { it.id })
                files.forEach { document ->
                    scope.launch {
                        runCatching { repository.approveCurrentAccountDevices(document, ownerUid, username) }
                    }
                }
            }
        }, { error ->
            if (token == generation && state.ownerUid == ownerUid) {
                subscription?.close()
                subscription = null
                state = state.copy(loading = false, error = error.ifBlank { "Unable to load documents" })
            }
        })
    }

    // User safety document controller: Uploads the selected file. It prepares the picture before the app reads or saves it.
    fun upload(uri: Uri, identity: AccountIdentity, fallbackError: String, ocrReview: OcrReview = OcrReview.NOT_RUN) {
        val transfers = media ?: return reportError(fallbackError)
        state = state.copy(loading = true, error = null)
        transfers.queueDocument(uri, identity, ocrReview, onQueued = {
            state = state.copy(loading = false, error = null)
            if (subscription == null) load(identity.uid, identity.username, false)
        }, onFailure = { state = state.copy(loading = false, error = it.ifBlank { fallbackError }) })
    }

    // Sends captured for user safety document controller. It prepares the picture before the app reads or saves it.
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
        transfers.queueFile(file, name, mimeType, identity, kind, review, ocr,
            onQueued = {
                file.delete()
                state = state.copy(loading = false, error = null)
                if (subscription == null) load(identity.uid, identity.username, false)
                onComplete()
            },
            onFailure = { state = state.copy(loading = false, error = it.ifBlank { fallbackError }) })
    }

    // User safety document controller: Downloads the selected file. It checks access, protects the file, and then reads or saves it.
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
                        state = state.copy(error = failure.localizedMessage ?: "Unable to open document")
                    }
                }
            if (token == generation) state = state.copy(loading = false)
        }
    }

    // Starts shared for user safety document controller. It checks access, protects the file, and then reads or saves it.
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
                val document = source.getFor(viewerUid, documentId)
                val file = openDocument(document, viewerUid)
                if (token == generation && state.ownerUid == viewerUid) onReady(document, file)
            }.onFailure { if (token == generation) state = state.copy(error = it.localizedMessage) }
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

    // User safety document controller: Shares the selected item. It requires the active feature controller before continuing.
    fun share(
        document: SafetyDocument,
        ownerUid: String,
        recipients: Map<String, String>,
        onComplete: () -> Unit
    ) = run(refreshAfter = true, onComplete = onComplete) {
        checkNotNull(repository).share(document, ownerUid, recipients)
    }

    // User safety document controller: returns the report error.
    fun reportError(message: String?) {
        state = state.copy(error = message)
    }

    // returns the clear for user safety document controller. It checks access, protects the file, and then reads or saves it.
    fun clear() {
        subscription?.close()
        subscription = null
        generation++
        media?.clearState()
        state = SafetyDocumentsState()
    }

    // Runs the requested task for user safety document controller. It checks access, protects the file, and then reads or saves it.
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
                        state.copy(error = it.localizedMessage ?: fallbackError)
                }
            if (token == generation) state = state.copy(loading = false)
        }
    }

    // Reloads the latest data for user safety document controller. It checks access, protects the file, and then reads or saves it.
    private suspend fun refresh(ownerUid: String) {
        val files = checkNotNull(repository).listFor(ownerUid)
        if (state.ownerUid == ownerUid) state = state.copy(files = files, error = null)
    }

    // User safety document controller: Starts document.
    private suspend fun openDocument(document: SafetyDocument, viewerUid: String): File =
        if (document.objectKey.isNotBlank()) {
            checkNotNull(media) { "Encrypted media is unavailable" }.downloadFile(document, viewerUid)
        } else {
            checkNotNull(repository).download(applicationContext, document)
        }

}
