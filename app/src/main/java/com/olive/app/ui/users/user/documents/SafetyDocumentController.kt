// Safe-app users: manages safety document actions.
package com.olive.app.ui.users.user.documents

import kotlin.time.Duration.Companion.milliseconds


import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.data.safety.SafetyDocumentDataSource
import com.olive.app.data.safety.isChatOnlyMedia
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.safety.DocumentOcrMetadata
import com.olive.app.data.safety.DocumentListSource
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.UserProfile
import com.olive.app.data.network.hasValidatedInternetConnection
import com.olive.app.R
import com.olive.app.data.media.MediaAccessError
import com.olive.app.data.media.MediaAccessException
import com.olive.app.data.media.MediaDeviceVerificationException
import com.olive.app.data.media.MediaKind
import com.olive.app.data.media.OcrReview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.io.File

internal data class SafetyDocumentsState(
    val ownerUid: String? = null,
    val files: List<SafetyDocument> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val source: DocumentListSource? = null,
    val lastSyncedAt: Long? = null,
    val attachmentErrors: Map<String, String> = emptyMap(),
    val evidenceUpdates: Set<String> = emptySet(),
    val openingDocumentIds: Set<String> = emptySet(),
)

/** Owns document-storage workflows so the Compose screen only renders state and dispatches actions. */
internal class SafetyDocumentsController(
    context: Context,
    private val scope: CoroutineScope,
    private val repository: SafetyDocumentDataSource?,
    private val media: MediaTransferOperations?,
    private val failureMessageResolver: (Throwable) -> String = {
        context.getString(mediaFailureResource(it))
    },
    private val emergencyFunctions: () -> com.google.firebase.functions.FirebaseFunctions = {
        com.google.firebase.functions.FirebaseFunctions.getInstance("northamerica-northeast1")
    },
    private val renderEmergencyReport: (Context, UserProfile, List<PrivateEntry>,
        EmergencyReportLocation?, Map<String, EmergencyReportMedia>) -> File = EmergencyReportPdf::create,
) {
    private val applicationContext = context.applicationContext
    private var generation = 0
    private var subscription: ChatSubscription? = null
    private var initialLoadTimeout: Job? = null
    private var allSnapshotDocuments: List<SafetyDocument> = emptyList()
    private val evidenceWrites = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val stateMutableFlow = MutableStateFlow(SafetyDocumentsState())
    val stateFlow: StateFlow<SafetyDocumentsState> = stateMutableFlow.asStateFlow()
    private var stateSnapshot by mutableStateOf(stateMutableFlow.value)
    var state: SafetyDocumentsState
        get() = stateSnapshot
        private set(value) {
            stateMutableFlow.value = value
            stateSnapshot = value
        }

    /**
     * Clears old document state before loading a different account owner.
     *
     * @param ownerUid Firebase UID of the resource owner.
     */
    fun prepare(ownerUid: String) {
        initialLoadTimeout?.cancel()
        subscription?.close()
        subscription = null
        generation++
        allSnapshotDocuments = emptyList()
        evidenceWrites.clear()
        state = SafetyDocumentsState(ownerUid = ownerUid)
    }

    /**
     * Watches the owner's allowed documents and updates the screen as Firebase changes.
     *
     * @param ownerUid Firebase UID of the resource owner.
     * @param username Account username.
     * @param localMode Whether to apply local mode.
     */
    fun load(ownerUid: String, username: String, localMode: Boolean) {
        if (state.ownerUid != ownerUid) {
            initialLoadTimeout?.cancel()
            subscription?.close()
            subscription = null
            generation++
            allSnapshotDocuments = emptyList()
            state = SafetyDocumentsState(ownerUid = ownerUid)
        }
        if (localMode || repository == null) return
        media?.observeUploads(ownerUid)
        if (subscription != null) return
        val token = generation
        var awaitingServer = hasValidatedInternetConnection(applicationContext)
        state = state.copy(loading = true, error = null)
        initialLoadTimeout = scope.launch {
            delay(10_000.milliseconds)
            if (token == generation && awaitingServer) {
                awaitingServer = false
                state = state.copy(loading = false, error = applicationContext.getString(R.string.documents_load_failed))
            }
        }
        subscription = repository.observeFor(ownerUid, { snapshot ->
            // Apply loaded documents only when the same owner and request are still active.
            if (token == generation && state.ownerUid == ownerUid) {
                allSnapshotDocuments = snapshot.documents
                val documents = snapshot.documents
                    .filterNot(SafetyDocument::isChatOnlyMedia)
                    .map { incoming ->
                        if (incoming.id in state.evidenceUpdates) incoming.copy(
                            evidence = state.files.firstOrNull { it.id == incoming.id }?.evidence ?: incoming.evidence,
                        ) else incoming
                    }
                if (snapshot.source == DocumentListSource.SERVER) {
                    awaitingServer = false
                    initialLoadTimeout?.cancel()
                }
                state = state.copy(files = documents, loading = awaitingServer, error = null,
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
                awaitingServer = false
                initialLoadTimeout?.cancel()
                subscription?.close()
                subscription = null
                state = state.copy(loading = false,
                    error = error.ifBlank { applicationContext.getString(R.string.documents_load_failed) }
                        .takeIf { state.files.isEmpty() })
            }
        })
    }

    /**
     * Queues a file selected from Android storage for encrypted upload.
     *
     * @param uri Content or file URI identifying the selected local item.
     * @param identity Authenticated account identity containing UID and username.
     * @param fallbackError Fallback error supplied to [upload].
     * @param ocrReview OCR review supplied to [upload].
     * @param tags Collection of tags values to process.
     */
    fun upload(
        uri: Uri,
        identity: AccountIdentity,
        fallbackError: String,
        ocrReview: OcrReview = OcrReview.NOT_RUN,
        tags: Set<String> = emptySet(),
    ) {
        val transfers = media ?: return reportError(fallbackError)
        state = state.copy(error = null)
        transfers.queueDocument(uri, identity, ocrReview, tags, onQueued = {
            state = state.copy(loading = false, error = null)
            if (subscription == null) load(identity.uid, identity.username, false)
        }, onFailure = { state = state.copy(loading = false, error = it.ifBlank { fallbackError }) })
    }

    /**
     * Queues a camera photo, video, or OCR document for encrypted upload.
     *
     * @param file Local file to read, hash, process, or write.
     * @param name Field, key, or display name.
     * @param mimeType MIME content type of the payload.
     * @param identity Authenticated account identity containing UID and username.
     * @param fallbackError Fallback error supplied to [uploadCaptured].
     * @param kind Kind supplied to [uploadCaptured].
     * @param ocr Optional OCR supplied to [uploadCaptured].
     * @param tags Collection of tags values to process.
     * @param onComplete Callback invoked when complete changes or occurs.
     */
    fun uploadCaptured(
        file: File,
        name: String,
        mimeType: String,
        identity: AccountIdentity,
        fallbackError: String,
        kind: MediaKind,
        ocr: DocumentOcrMetadata? = null,
        tags: Set<String> = emptySet(),
        onComplete: () -> Unit
    ) {
        val transfers = media ?: return reportError(fallbackError)
        state = state.copy(error = null)
        val review = when {
            kind != MediaKind.DOCUMENT -> OcrReview.NOT_APPLICABLE
            ocr == null -> OcrReview.NOT_RUN
            ocr.successful -> OcrReview.PASSED
            else -> OcrReview.RETAKE_DECLINED
        }
        transfers.queueFile(file, name, mimeType, identity, kind, review, ocr, tags,
            onQueued = {
                file.delete()
                state = state.copy(loading = false, error = null)
                if (subscription == null) load(identity.uid, identity.username, false)
                onComplete()
            },
            onFailure = { state = state.copy(loading = false, error = it.ifBlank { fallbackError }) })
    }

    /**
     * Downloads and decrypts an allowed document, then opens its in-app viewer.
     *
     * @param document Safety document metadata record.
     * @param onReady Callback invoked when ready changes or occurs.
     */
    fun download(document: SafetyDocument, onReady: (SafetyDocument, File) -> Unit) {
        val viewerUid = state.ownerUid ?: return
        if (!beginOpening(document.id)) return
        val token = generation
        scope.launch {
            try {
                runCatching { openDocument(document, viewerUid) }
                    .onSuccess { opened ->
                        rememberRecoveredDocument(viewerUid, opened.document)
                        if (token == generation && state.ownerUid == viewerUid) {
                            onReady(opened.document, opened.file)
                        } else opened.file.delete()
                    }
                    .onFailure { failure ->
                        if (failure is CancellationException) throw failure
                        if (token == generation) {
                            val message = localizedFailure(failure)
                            state = state.copy(error = message,
                                attachmentErrors = state.attachmentErrors +
                                    (document.id to message))
                        }
                    }
            } finally {
                finishOpening(document.id, token)
            }
        }
    }

    /**
     * Opens a document shared through an encrypted conversation.
     *
     * @param viewerUid Firebase UID of the viewing account.
     * @param documentId Unique identifier of the safety document.
     * @param onReady Callback invoked when ready changes or occurs.
     */
    fun openShared(
        viewerUid: String,
        documentId: String,
        onReady: (SafetyDocument, File) -> Unit,
    ) {
        if (state.ownerUid != viewerUid) {
            generation++
            state = SafetyDocumentsState(ownerUid = viewerUid)
        }
        if (!beginOpening(documentId)) return
        val token = generation
        scope.launch {
            try {
                runCatching {
                    val source = checkNotNull(repository)
                    val document = if (hasValidatedInternetConnection(applicationContext)) {
                        source.getFor(viewerUid, documentId)
                    } else source.cachedFor(viewerUid, documentId)
                        ?: throw MediaAccessException(MediaAccessError.NOT_CACHED)
                    val opened = openDocument(document, viewerUid)
                    rememberRecoveredDocument(viewerUid, opened.document)
                    if (token == generation && state.ownerUid == viewerUid) {
                        onReady(opened.document, opened.file)
                    } else opened.file.delete()
                }.onFailure { failure ->
                    if (failure is CancellationException) throw failure
                    if (token == generation) {
                        val message = localizedFailure(failure)
                        state = state.copy(error = message,
                            attachmentErrors = state.attachmentErrors + (documentId to message))
                    }
                }
            } finally {
                finishOpening(documentId, token)
            }
        }
    }

    /**
     * User safety document controller: Deletes the selected item. It requires the active feature controller before continuing.
     *
     * @param document Safety document metadata record.
     * @param ownerUid Firebase UID of the resource owner.
     */
    fun delete(document: SafetyDocument, ownerUid: String) {
        if (state.ownerUid != ownerUid) return
        val previous = state.files
        val previousAll = allSnapshotDocuments
        allSnapshotDocuments = previousAll.filterNot { it.id == document.id }
        state = state.copy(files = previous.filterNot { it.id == document.id }, error = null)
        val reconciled = if (previousAll.isNotEmpty()) allSnapshotDocuments else state.files
        media?.onDocumentSnapshot(ownerUid, reconciled, true)
        scope.launch {
            runCatching {
                checkNotNull(repository).delete(document, ownerUid)
            }.onFailure {
                if (state.ownerUid == ownerUid) {
                    allSnapshotDocuments = previousAll
                    state = state.copy(files = previous, error = localizedFailure(it))
                }
            }
        }
    }

    /**
     * Builds available offline.
     *
     * @param document Safety document metadata record.
     */
    fun makeAvailableOffline(document: SafetyDocument) {
        val viewerUid = state.ownerUid ?: return
        run { checkNotNull(media).makeAvailableOffline(document, viewerUid) }
    }

    /**
     * Removes offline copy.
     *
     * @param document Safety document metadata record.
     */
    fun removeOfflineCopy(document: SafetyDocument) {
        val viewerUid = state.ownerUid ?: return
        run { checkNotNull(media).removeOfflineCopy(document, viewerUid) }
    }

    /**
     * Updates the Evidence toggle immediately, then confirms it with the server or rolls it back.
     *
     * @param document Safety document metadata record.
     * @param ownerUid Firebase UID of the resource owner.
     * @param evidence Whether to apply evidence.
     */
    fun setEvidence(document: SafetyDocument, ownerUid: String, evidence: Boolean) {
        if (state.ownerUid != ownerUid || document.ownerUid != ownerUid ||
            document.id in state.evidenceUpdates || document.evidence == evidence) return
        val token = generation
        val write = CompletableDeferred<Unit>()
        evidenceWrites[document.id] = write
        state = state.copy(
            files = state.files.map { if (it.id == document.id) it.copy(evidence = evidence) else it },
            error = null,
            evidenceUpdates = state.evidenceUpdates + document.id,
        )
        scope.launch {
            runCatching {
                checkNotNull(repository).setEvidence(document, ownerUid, evidence)
            }.onSuccess {
                write.complete(Unit)
                if (token == generation && state.ownerUid == ownerUid) {
                    state = state.copy(
                        error = null,
                        evidenceUpdates = state.evidenceUpdates - document.id,
                    )
                }
            }.onFailure { failure ->
                write.completeExceptionally(failure)
                if (token == generation && state.ownerUid == ownerUid) {
                    state = state.copy(
                        files = state.files.map {
                            if (it.id == document.id) it.copy(evidence = document.evidence) else it
                        },
                        error = if (failure is MediaAccessException ||
                            failure is MediaDeviceVerificationException) localizedFailure(failure)
                        else failure.localizedMessage,
                        evidenceUpdates = state.evidenceUpdates - document.id,
                    )
                }
            }
            if (evidenceWrites[document.id] === write) evidenceWrites.remove(document.id)
        }
    }

    /**
     * The emergency button builds a PDF, encrypts it, and adds it to the user's Documents list.
     *
     * @param identity Authenticated account identity containing UID and username.
     * @param profile Account profile details.
     * @param entries Collection of entries values to process.
     * @param location Function used to transform or provide location.
     * @param onAlertSent Callback invoked when alert sent changes or occurs.
     * @param onFinished Callback invoked when finished changes or occurs.
     */
    fun createEmergencyReport(
        identity: AccountIdentity,
        profile: UserProfile,
        entries: List<PrivateEntry>,
        location: suspend () -> EmergencyReportLocation?,
        onAlertSent: () -> Unit = {},
        onFinished: (Result<Unit>) -> Unit,
    ) {
        val ownerUid = identity.uid
        if (ownerUid.isBlank()) return onFinished(
            Result.failure(IllegalStateException("The account is not ready for an emergency export.")),
        )
        val token = generation
        scope.launch {
            state = state.copy(loading = true, error = null)
            val emergencyId = java.util.UUID.randomUUID().toString()
            val result = runCatching {
                check(token == generation) { "The account changed. Try again." }
                val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                check(user?.uid == ownerUid) { "Sign in again before sending an emergency report." }
                user.getIdToken(false).await()
                try {
                    com.google.firebase.appcheck.FirebaseAppCheck.getInstance().getAppCheckToken(false).await()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    throw MediaDeviceVerificationException(
                        applicationContext.getString(R.string.emergency_report_device_verification_failed), error,
                    )
                }
                emergencyFunctions().getHttpsCallable("emergencyStart").call(mapOf("id" to emergencyId)).await()
                if (token == generation && com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid == ownerUid) {
                    onAlertSent()
                }
                val pressedLocation = location()
                check(com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid == ownerUid && token == generation) {
                    "The account changed. Try again."
                }
                val incidents = emergencyIncidents(entries)
                // A just-selected Evidence checkbox must reach the server before the export rereads it.
                evidenceWrites.values.toList().forEach { it.await() }
                val available = (repository?.listFor(ownerUid)?.documents ?: state.files)
                    .filterNot(SafetyDocument::isChatOnlyMedia)
                val relevantIds = incidents.flatMapTo(linkedSetOf()) { it.attachmentIds }.apply {
                    addAll(available.filter(SafetyDocument::evidence).map(SafetyDocument::id))
                }
                val relevant = available.filter { it.id in relevantIds }
                val files = linkedMapOf<String, EmergencyReportMedia>()
                relevant.forEach { document ->
                    val opened = runCatching { openDocument(document, ownerUid) }.getOrNull()
                    files[document.id] = EmergencyReportMedia(
                        opened?.document ?: document,
                        opened?.file,
                    )
                }
                withContext(Dispatchers.IO) {
                    renderEmergencyReport(applicationContext, profile, incidents, pressedLocation, files)
                }
            }
            result.onFailure { error ->
                if (token == generation) {
                    state = state.copy(loading = false, error = error.localizedMessage)
                    onFinished(Result.failure(error))
                }
            }.onSuccess { report ->
                if (token != generation) {
                    report.delete()
                    return@onSuccess
                }
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
                    name = "emergency-report-$emergencyId.pdf",
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

    /**
     * User safety document controller: Shares the selected item. It requires the active feature controller before continuing.
     *
     * @param document Safety document metadata record.
     * @param ownerUid Firebase UID of the resource owner.
     * @param recipients Key-value mapping of recipients entries.
     * @param onComplete Callback invoked when complete changes or occurs.
     */
    fun share(
        document: SafetyDocument,
        ownerUid: String,
        recipients: Map<String, String>,
        onComplete: () -> Unit
    ) = run(refreshAfter = true, onComplete = onComplete) {
        checkNotNull(repository).share(document, ownerUid, recipients)
    }

    /**
     * Gives assigned safety-plan staff and admins encrypted access without exposing upload controls.
     *
     * @param documents Collection of documents values to process.
     * @param ownerUid Firebase UID of the resource owner.
     * @param recipients Key-value mapping of recipients entries.
     */
    fun grantPlanAccess(
        documents: List<SafetyDocument>,
        ownerUid: String,
        recipients: Map<String, String>,
    ) = run(refreshAfter = true) {
        val source = checkNotNull(repository)
        var granted = false
        var lastFailure: Throwable? = null
        documents.forEach { document ->
            recipients.forEach { (uid, username) ->
                runCatching { source.grantAccess(document, ownerUid, uid, username, forPlan = true) }
                    .onSuccess { granted = true }
                    .onFailure { lastFailure = it }
            }
        }
        if (!granted) lastFailure?.let { throw it }
    }

    /**
     * Changes a document failure into a clear message shown on the Safe App screen.
     *
     * @param message Chat message or remote payload, or null when not provided.
     */
    fun reportError(message: String?) {
        state = state.copy(error = message)
    }

    /**
     * Clears the current document viewer and its error message.
     */
    fun clear() {
        subscription?.close()
        subscription = null
        generation++
        allSnapshotDocuments = emptyList()
        media?.clearState()
        File(applicationContext.cacheDir, "opened-documents").listFiles()?.forEach(File::delete)
        state = SafetyDocumentsState()
    }

    /**
     * Runs one document task, reports its error, and refreshes the list when needed.
     *
     * @param refreshAfter Whether to apply refresh after.
     * @param fallbackError Optional fallback error supplied to [run].
     * @param onComplete Callback invoked when complete changes or occurs.
     * @param operation Action or callback executed for operation.
     */
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

    /**
     * Reloads the owner's document list after a write finishes.
     *
     * @param ownerUid Firebase UID of the resource owner.
     */
    private suspend fun refresh(ownerUid: String) {
        val snapshot = checkNotNull(repository).listFor(ownerUid)
        if (state.ownerUid == ownerUid) {
            allSnapshotDocuments = snapshot.documents
            state = state.copy(
                files = snapshot.documents.filterNot(SafetyDocument::isChatOnlyMedia),
                source = snapshot.source,
                lastSyncedAt = snapshot.lastSyncedAt,
                error = null,
            )
            media?.onDocumentSnapshot(ownerUid, snapshot.documents,
                snapshot.source == DocumentListSource.SERVER)
        }
    }

    /**
     * Opens a saved document after its encrypted content is ready.
     *
     * @param document Safety document metadata record.
     * @param viewerUid Firebase UID of the viewing account.
     * @return The resulting [OpenedMedia] for open document.
     */
    private suspend fun openDocument(document: SafetyDocument, viewerUid: String): OpenedMedia =
        if (document.objectKey.isNotBlank()) {
            checkNotNull(media) { "Encrypted media is unavailable" }.downloadFile(document, viewerUid)
        } else {
            OpenedMedia(document, checkNotNull(repository).download(applicationContext, document))
        }

    /**
     * Starts opening.
     *
     * @param documentId Unique identifier of the safety document.
     * @return `true` if begin opening, or `false` otherwise.
     */
    private fun beginOpening(documentId: String): Boolean {
        if (documentId in state.openingDocumentIds) return false
        state = state.copy(
            error = null,
            attachmentErrors = state.attachmentErrors - documentId,
            openingDocumentIds = state.openingDocumentIds + documentId,
        )
        return true
    }

    /**
     * Finishes opening.
     *
     * @param documentId Unique identifier of the safety document.
     * @param token Authentication, push notification, or session token.
     */
    private fun finishOpening(documentId: String, token: Int) {
        if (token == generation) {
            state = state.copy(openingDocumentIds = state.openingDocumentIds - documentId)
        }
    }

    /**
     * Applies restored metadata immediately and commits it to the encrypted account index.
     *
     * @param ownerUid Active account that owns the encrypted index partition.
     * @param documents Locally authenticated records carrying current-device wrappers.
     */
    internal suspend fun rememberRecoveredDocuments(
        ownerUid: String,
        documents: List<SafetyDocument>,
    ) {
        withContext(Dispatchers.IO) {
            documents.forEach { repository?.rememberRecovered(ownerUid, it) }
        }
        if (state.ownerUid == ownerUid) {
            val recovered = documents.associateBy(SafetyDocument::id)
            state = state.copy(files = state.files.map { recovered[it.id] ?: it })
        }
    }

    /**
     * Persists updated metadata for a single recovered document.
     */
    private fun rememberRecoveredDocument(ownerUid: String, document: SafetyDocument) {
        if (state.ownerUid == ownerUid) {
            state = state.copy(files = state.files.map { if (it.id == document.id) document else it })
        }
        scope.launch(Dispatchers.IO) { repository?.rememberRecovered(ownerUid, document) }
    }

    /**
     * Computes localized failure.
     *
     * @param failure Exception or failure reason encountered during execution.
     * @return The resulting localized failure string.
     */
    private fun localizedFailure(failure: Throwable): String {
        return failureMessageResolver(failure)
    }
}

/**
 * Maps bounded media and recovery failures to localized user guidance.
 *
 * @param failure Failure returned by the encrypted media access pipeline.
 * @return String resource describing the safe next action without exposing cryptographic details.
 */
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
        MediaAccessError.ACCOUNT_WRAPPER_MISSING -> R.string.documents_account_wrapper_missing
        MediaAccessError.ACCOUNT_RECOVERY_FAILED -> R.string.documents_account_recovery_failed
        MediaAccessError.ACCOUNT_KEYSET_MISMATCH -> R.string.documents_account_keyset_mismatch
        MediaAccessError.ACCOUNT_WRAPPER_UNWRAP_FAILED -> R.string.documents_account_wrapper_failed
        MediaAccessError.CIPHERTEXT_AUTHENTICATION_FAILED -> R.string.documents_ciphertext_authentication_failed
        MediaAccessError.ACCOUNT_MISMATCH -> R.string.documents_account_mismatch
        MediaAccessError.INSUFFICIENT_STORAGE -> R.string.documents_storage_full
        MediaAccessError.DOWNLOAD_FAILED -> R.string.documents_download_failed
        null -> R.string.documents_open_failed
    }
}
