package com.olive.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.*
import java.io.File

private enum class DocumentFilter { All, Photos, Videos, Documents }

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun SafetyDocumentsScreen(session:SessionViewModel) {
    val uid=session.coverAccountId ?: return
    val controller=session.documents
    val state=controller.state
    var filter by rememberSaveable { mutableStateOf(DocumentFilter.All) };var sharing by remember { mutableStateOf<SafetyDocument?>(null) }
    var opened by remember { mutableStateOf<Pair<SafetyDocument,File>?>(null) }
    var choosingUpload by rememberSaveable { mutableStateOf(false) }
    val unableUpload=stringResource(R.string.documents_upload_failed)
    fun finishSelection(uri:android.net.Uri?) {
        session.endExternalFilePicker()
        if(uri!=null) controller.upload(uri,AccountIdentity(uid,session.coverUsername),unableUpload)
    }
    val mediaPicker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia(),::finishSelection)
    val documentPicker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(),::finishSelection)
    fun launchProtectedPicker(action:()->Unit) {
        choosingUpload=false;session.beginExternalFilePicker()
        runCatching(action).onFailure { session.endExternalFilePicker();controller.reportError(unableUpload) }
    }
    LaunchedEffect(uid,session.localMode) { controller.load(uid,session.localMode) }
    opened?.let { (document,localFile) ->
        SafetyDocumentViewer(document,localFile) {
            localFile.delete()
            opened=null
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(top=8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=session::back,modifier=Modifier.testTag("documents_back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack,stringResource(R.string.ui_previous_safe_page))
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.support_documents),style=MaterialTheme.typography.headlineSmall,
                    modifier=Modifier.semantics { heading() })
                Text(stringResource(R.string.documents_storage_help),style=MaterialTheme.typography.bodySmall,color=Muted)
            }
        }
        Button(onClick={choosingUpload=true},
            enabled=!state.loading && !session.localMode,modifier=Modifier.fillMaxWidth().testTag("documents_upload")) {
            Icon(Icons.Outlined.UploadFile,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.documents_add))
        }
        if(session.localMode) Text(stringResource(R.string.documents_account_required),color=Muted)
        Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            DocumentFilter.entries.toList().chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    row.forEach { option ->
                        FilterChip(selected=filter==option,onClick={filter=option},label={Text(stringResource(when(option) {
                            DocumentFilter.All -> R.string.documents_all
                            DocumentFilter.Photos -> R.string.safety_storage_photos
                            DocumentFilter.Videos -> R.string.safety_storage_videos
                            DocumentFilter.Documents -> R.string.safety_storage_documents
                        }),maxLines=1)},modifier=Modifier.weight(1f))
                    }
                    if(row.size==1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if(state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { AccessibleMessage(it) }
        val shown=state.files.filter { file -> when(filter) {
            DocumentFilter.All -> true
            DocumentFilter.Photos -> file.mimeType.startsWith("image/")
            DocumentFilter.Videos -> file.mimeType.startsWith("video/")
            DocumentFilter.Documents -> !file.mimeType.startsWith("image/") && !file.mimeType.startsWith("video/")
        } }
        if(!state.loading && shown.isEmpty()) Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,color=LightSage) {
            Column(Modifier.padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally,
                verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.FolderOpen,null,Modifier.size(36.dp),tint=OliveGreen)
                Text(stringResource(R.string.documents_empty),style=MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.documents_empty_help),style=MaterialTheme.typography.bodySmall,color=Muted)
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(),contentPadding=PaddingValues(bottom=24.dp),
            verticalArrangement=Arrangement.spacedBy(10.dp)) {
            items(shown,key={it.id}) { file ->
                SafetyDocumentCard(file,uid,session.friends,onOpen={controller.download(file) { local ->
                    opened=file to local
                }},onShare={sharing=file},onDelete={controller.delete(file,uid)})
            }
        }
    }
    sharing?.let { file -> DocumentSharingDialog(file,uid,session.friends,session.profile?.contacts.orEmpty(),onDismiss={sharing=null}) { recipients ->
        controller.share(file,uid,recipients) { sharing=null }
    } }
    if(choosingUpload) ModalBottomSheet(onDismissRequest={choosingUpload=false}) {
        Column(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(bottom=32.dp),
            verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.documents_choose_source),style=MaterialTheme.typography.headlineSmall,
                modifier=Modifier.semantics { heading() })
            Text(stringResource(R.string.documents_choose_source_help),style=MaterialTheme.typography.bodyMedium,color=Muted)
            Button(onClick={launchProtectedPicker {
                mediaPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            }},modifier=Modifier.fillMaxWidth().testTag("documents_choose_media")) {
                Icon(Icons.Outlined.PermMedia,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.documents_choose_media))
            }
            OutlinedButton(onClick={launchProtectedPicker {
                documentPicker.launch(arrayOf("application/pdf","text/plain","application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            }},modifier=Modifier.fillMaxWidth().testTag("documents_choose_document")) {
                Icon(Icons.Outlined.Description,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.documents_choose_document))
            }
            TextButton(onClick={choosingUpload=false},modifier=Modifier.align(Alignment.End)) { Text(stringResource(R.string.cancel)) }
        }
    }
}

@Composable private fun SafetyDocumentCard(file:SafetyDocument,currentUid:String,friends:List<Friend>,onOpen:()->Unit,
    onShare:()->Unit,onDelete:()->Unit) {
    val own=file.ownerUid==currentUid
    val owner=friends.firstOrNull { it.uid==file.ownerUid }?.username
    OutlinedCard(Modifier.fillMaxWidth().testTag("document_${file.id}")) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(when { file.mimeType.startsWith("image/")->Icons.Outlined.Image
                    file.mimeType.startsWith("video/")->Icons.Outlined.Videocam else->Icons.Outlined.Description },null,
                    Modifier.size(30.dp),tint=OliveGreen)
                Column(Modifier.weight(1f)) {
                    Text(file.name,style=MaterialTheme.typography.titleMedium,maxLines=2)
                    Text(if(own) stringResource(R.string.documents_yours) else stringResource(R.string.documents_shared_by,
                        owner ?: stringResource(R.string.safe_contact_shared_user)),
                        style=MaterialTheme.typography.bodySmall,color=Muted)
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick=onOpen,modifier=Modifier.weight(1f)) { Icon(Icons.Outlined.Visibility,null);Spacer(Modifier.width(6.dp));Text(stringResource(R.string.open)) }
                if(own) OutlinedButton(onClick=onShare,modifier=Modifier.weight(1f)) { Icon(Icons.Outlined.Share,null);Spacer(Modifier.width(6.dp));Text(stringResource(R.string.share)) }
                if(own) IconButton(onClick=onDelete) { Icon(Icons.Outlined.Delete,stringResource(R.string.remove)) }
            }
        }
    }
}

@Composable private fun DocumentSharingDialog(file:SafetyDocument,currentUid:String,friends:List<Friend>,contacts:List<SafeContact>,onDismiss:()->Unit,onSave:(List<String>)->Unit) {
    var selected by remember(file.id) { mutableStateOf(file.allowedUsers.filterNot { it==currentUid }.toSet()) }
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.documents_share_title))},
        text={Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.documents_share_help),style=MaterialTheme.typography.bodySmall)
            if(friends.isEmpty()) Text(stringResource(R.string.location_no_friends),color=Muted)
            friends.forEach { friend -> Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                val trusted=contacts.firstOrNull { it.linkedUid==friend.uid }
                Checkbox(friend.uid in selected,{checked->selected=if(checked) selected+friend.uid else selected-friend.uid},
                    enabled=friend.uid in selected || selected.size<10)
                Column {
                    Text(trusted?.name ?: friend.username)
                    if(trusted!=null) Text("@${friend.username}",style=MaterialTheme.typography.bodySmall,color=Muted)
                }
            } }
        }},confirmButton={Button(onClick={onSave(selected.toList())}) { Text(stringResource(R.string.save)) }},
        dismissButton={TextButton(onClick=onDismiss) { Text(stringResource(R.string.cancel)) }})
}
