package com.olive.app.ui.documents

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olive.app.data.AccountIdentity
import com.olive.app.data.SafetyDocument
import com.olive.app.data.SafetyDocumentDataSource
import com.olive.app.data.ChatSubscription
import com.olive.app.data.MediaKind
import com.olive.app.data.OcrReview
import com.olive.app.ui.media.MediaTransferController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/** Compose-facing state owned by [SafetyDocumentsController]. */
internal data class SafetyDocumentsState(
    val ownerUid:String?=null,
    val files:List<SafetyDocument> = emptyList(),
    val loading:Boolean=false,
    val error:String?=null,
)

/**
 * Domain controller that keeps safety-document policy out of Compose screens.
 *
 * It decides whether an operation belongs to legacy [SafetyDocumentDataSource] storage or the new
 * encrypted [MediaTransferController] pipeline. It also translates OCR/user decisions into media-core
 * inputs and guards asynchronous callbacks with [generation] so results from a previous account/page
 * cannot overwrite current state.
 */
internal class SafetyDocumentsController(
    context:Context,
    private val scope:CoroutineScope,
    private val repository:SafetyDocumentDataSource?,
    private val media:MediaTransferController?,
) {
    private val applicationContext=context.applicationContext
    private var generation=0
    private var subscription:ChatSubscription?=null
    var state by mutableStateOf(SafetyDocumentsState())
        private set

    fun prepare(ownerUid:String) {
        subscription?.close();subscription=null
        generation++
        state=SafetyDocumentsState(ownerUid=ownerUid)
    }

    fun load(ownerUid:String,localMode:Boolean) {
        if(state.ownerUid!=ownerUid) {
            subscription?.close();subscription=null
            generation++;state=SafetyDocumentsState(ownerUid=ownerUid)
        }
        if(localMode || repository==null) return
        // Pending encrypted jobs are restored independently of Firestore. This keeps queued/offline
        // work visible before the trusted callable publishes its finalized `files` record.
        media?.observeUploads(ownerUid)
        if(subscription!=null) return
        val token=generation
        state=state.copy(loading=true,error=null)
        subscription=repository.observeFor(ownerUid,{ files ->
            if(token==generation && state.ownerUid==ownerUid) {
                state=state.copy(files=files,loading=false,error=null)
                // Retire a completed pending card only after its Firestore-backed document is ready
                // to replace it, preventing an empty-state flash at the finalization boundary.
                media?.acknowledgePublished(files.mapTo(mutableSetOf()) { it.id })
            }
        },{ error ->
            if(token==generation && state.ownerUid==ownerUid) {
                // Firestore stops a listener after terminal failures such as PERMISSION_DENIED. Keeping
                // the old handle made later load() calls believe observation was still active, so the
                // Documents screen could remain permanently empty even after rules/connectivity were
                // corrected. Remove the dead registration before exposing the error so re-entering the
                // screen—or queueing an upload below—can establish a fresh listener.
                subscription?.close();subscription=null
                state=state.copy(loading=false,error=error.ifBlank { "Unable to load documents" })
            }
        })
    }

    /** Starts picker-document preparation after the UI/OCR layer supplies its recommendation. */
    fun upload(uri:Uri,identity:AccountIdentity,fallbackError:String,ocrReview:OcrReview=OcrReview.NOT_RUN) {
        val transfers=media ?: return reportError(fallbackError)
        state=state.copy(loading=true,error=null)
        transfers.queueDocument(uri,identity,ocrReview,onQueued={
            state=state.copy(loading=false,error=null)
            // Upload execution is durable and may finish after the picker/activity transition. Ensure a
            // listener that previously failed is reattached now; the server-published `files` record will
            // then flow into state without polling or coupling the UI to the WorkManager implementation.
            if(subscription==null) load(identity.uid,false)
        },
            onFailure={ state=state.copy(loading=false,error=it.ifBlank { fallbackError }) })
    }

    /** Routes camera output into the media core; captured images remain document/OCR candidates. */
    fun uploadCaptured(file:File,name:String,mimeType:String,identity:AccountIdentity,fallbackError:String,onComplete:()->Unit) =
        media?.let { transfers ->
            state=state.copy(loading=true,error=null)
            // Images captured for the safety-document workflow are still documents.
            // Keeping that domain classification ensures the OCR recommendation is offered.
            val kind=if(mimeType.startsWith("video/")) MediaKind.VIDEO else MediaKind.DOCUMENT
            transfers.queueFile(file,name,mimeType,identity,kind,
                if(kind==MediaKind.DOCUMENT) OcrReview.NOT_RUN else OcrReview.NOT_APPLICABLE,
                onQueued={ file.delete();state=state.copy(loading=false,error=null);onComplete() },
                onFailure={ state=state.copy(loading=false,error=it.ifBlank { fallbackError }) })
        } ?: reportError(fallbackError)

    /** Selects authenticated R2 decryption for new records and repository download for legacy ones. */
    fun download(document:SafetyDocument,onReady:(File)->Unit) {
        if(document.objectKey.isNotBlank()) {
            val uid=state.ownerUid ?: return
            state=state.copy(loading=true,error=null)
            media?.download(document,uid,{ state=state.copy(loading=false,error=null);onReady(it) },
                { state=state.copy(loading=false,error=it) }) ?: reportError("Encrypted media is unavailable")
            return
        }
        run { onReady(checkNotNull(repository).download(applicationContext,document)) }
    }

    fun openShared(ownerUid:String,documentId:String,onReady:(SafetyDocument,File)->Unit,onFinished:()->Unit) {
        if(state.ownerUid!=ownerUid) { generation++;state=SafetyDocumentsState(ownerUid=ownerUid) }
        val token=generation
        scope.launch {
            state=state.copy(loading=true,error=null)
            runCatching {
                val source=checkNotNull(repository)
                val document=source.getFor(ownerUid,documentId)
                val file=source.download(applicationContext,document)
                if(token==generation && state.ownerUid==ownerUid) onReady(document,file)
            }.onFailure { if(token==generation) state=state.copy(error=it.localizedMessage) }
            if(token==generation) { state=state.copy(loading=false);onFinished() }
        }
    }

    fun delete(document:SafetyDocument,ownerUid:String) = run(refreshAfter=true) {
        checkNotNull(repository).delete(document,ownerUid)
    }

    fun share(document:SafetyDocument,ownerUid:String,recipientUids:List<String>,onComplete:()->Unit) = run(refreshAfter=true,onComplete=onComplete) {
        checkNotNull(repository).share(document,ownerUid,recipientUids)
    }

    fun reportError(message:String?) { state=state.copy(error=message) }
    fun clear() { subscription?.close();subscription=null;generation++;media?.clearState();state=SafetyDocumentsState() }

    private fun run(refreshAfter:Boolean=false,fallbackError:String?=null,onComplete:()->Unit={},operation:suspend ()->Unit) {
        val ownerUid=state.ownerUid ?: return
        val token=generation
        scope.launch {
            state=state.copy(loading=true,error=null)
            runCatching { operation() }
                .onSuccess {
                    if(refreshAfter) refresh(ownerUid)
                    if(token==generation && state.ownerUid==ownerUid) onComplete()
                }
                .onFailure { if(token==generation) state=state.copy(error=it.localizedMessage ?: fallbackError) }
            if(token==generation) state=state.copy(loading=false)
        }
    }

    private suspend fun refresh(ownerUid:String) {
        val files=checkNotNull(repository).listFor(ownerUid)
        if(state.ownerUid==ownerUid) state=state.copy(files=files,error=null)
    }
}
