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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

internal data class SafetyDocumentsState(
    val ownerUid:String?=null,
    val files:List<SafetyDocument> = emptyList(),
    val loading:Boolean=false,
    val error:String?=null,
)

/** Owns document-storage workflows so the Compose screen only renders state and dispatches actions. */
internal class SafetyDocumentsController(
    context:Context,
    private val scope:CoroutineScope,
    private val repository:SafetyDocumentDataSource?,
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
        if(subscription!=null) return
        val token=generation
        state=state.copy(loading=true,error=null)
        subscription=repository.observeFor(ownerUid,{ files ->
            if(token==generation && state.ownerUid==ownerUid) state=state.copy(files=files,loading=false,error=null)
        },{ error ->
            if(token==generation && state.ownerUid==ownerUid) state=state.copy(loading=false,error=error)
        })
    }

    fun upload(uri:Uri,identity:AccountIdentity,fallbackError:String) = run(refreshAfter=true,fallbackError=fallbackError) {
        checkNotNull(repository).upload(applicationContext,uri,identity)
    }

    fun uploadCaptured(file:File,name:String,mimeType:String,identity:AccountIdentity,fallbackError:String,onComplete:()->Unit) =
        run(refreshAfter=true,fallbackError=fallbackError,onComplete={ file.delete();onComplete() }) {
            val timeout=if(mimeType.startsWith("image/")) PHOTO_UPLOAD_TIMEOUT else VIDEO_UPLOAD_TIMEOUT
            checkNotNull(withTimeoutOrNull(timeout.milliseconds) {
                checkNotNull(repository).uploadFile(file,name,mimeType,identity)
            }) { fallbackError }
        }

    fun download(document:SafetyDocument,onReady:(File)->Unit) = run {
        onReady(checkNotNull(repository).download(applicationContext,document))
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
    fun clear() { subscription?.close();subscription=null;generation++;state=SafetyDocumentsState() }

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

    private companion object {
        const val PHOTO_UPLOAD_TIMEOUT=45_000L
        const val VIDEO_UPLOAD_TIMEOUT=5L*60L*1000L
    }
}
