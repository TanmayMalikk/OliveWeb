package com.olive.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

@Composable internal fun SafetyPlanScreen(session:SessionViewModel,ownerUid:String=session.coverAccountId.orEmpty(),
    ownerName:String=session.coverUsername,onBack:()->Unit=session::back) {
    val controller=session.safetyPlans
    val context=LocalContext.current
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    fun leaveEditor() { focus.clearFocus(force=true);keyboard?.hide() }
    val lease=remember(ownerUid) { Any() }
    val defaults=remember(context) { safetyDefaults(context) }
    DisposableEffect(lease) { onDispose { controller.close(lease) } }
    LaunchedEffect(lease) {
        controller.open(ownerUid,ownerName,defaults,lease)
        while(true) { delay(5.seconds);controller.refresh() }
    }
    var backup by rememberSaveable(ownerUid) { mutableStateOf(false) }
    val plan=controller.plan
    val staff=session.accountRole==AccountRole.STAFF
    val state=rememberLazyListState()
    val scope=rememberCoroutineScope()
    val expandedDescription=stringResource(R.string.support_expanded)
    val collapsedDescription=stringResource(R.string.support_collapsed)
    val prefix=if(backup) "backup" else "primary"
    var olderOpen by rememberSaveable(ownerUid,backup) { mutableStateOf(false) }
    val older=plan?.fields.orEmpty().filter { (key,field) ->
        key.startsWith("${prefix}_") && key.substringAfter('_') !in SAFETY_CHECKLIST_SECTIONS &&
            (readSafetyChecklist(field.value).isNotEmpty() || field.conflictRevision!=null)
    }
    LaunchedEffect(backup) { state.scrollToItem(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val headerMaxHeight=maxHeight*.5f
        Column(Modifier.fillMaxSize().padding(horizontal=16.dp)) {
            // Keep navigation available; on compact screens/with the keyboard, leave at least half the space for the note.
            Column(Modifier.fillMaxWidth().heightIn(max=headerMaxHeight).verticalScroll(rememberScrollState())
                .padding(top=8.dp,bottom=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    SafetyBackButton(onClick={leaveEditor();onBack()},tag="safety_back")
                    Text(stringResource(R.string.support_safety_plan),style=MaterialTheme.typography.headlineSmall,
                        modifier=Modifier.weight(1f).semantics { heading() })
                    if(!staff) IconButton(onClick={leaveEditor();scope.launch {
                        val consultationIndex=1+SAFETY_CHECKLIST_SECTIONS.size+(if(older.isNotEmpty()) 1 else 0)+(if(olderOpen) older.size else 0)
                        state.animateScrollToItem(consultationIndex)
                    }},enabled=plan!=null,modifier=Modifier.testTag("safety_jump_consultation")) {
                        Icon(Icons.Outlined.SupportAgent,stringResource(R.string.safety_consult))
                    }
                }
                if(staff) Text(ownerName,style=MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=!backup,onClick={leaveEditor();backup=false},label={Text(stringResource(R.string.safety_primary))},modifier=Modifier.weight(1f).testTag("safety_primary"))
                    FilterChip(selected=backup,onClick={leaveEditor();backup=true},label={Text(stringResource(R.string.safety_backup))},modifier=Modifier.weight(1f).testTag("safety_backup"))
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    SAFETY_CHECKLIST_SECTIONS.forEachIndexed { index,section ->
                        val label=when(section) {
                            "documents" -> R.string.safety_documents_jump
                            "grab" -> R.string.safety_grab_jump
                            else -> safetySectionLabel(section)
                        }
                        OutlinedButton(onClick={leaveEditor();scope.launch { state.animateScrollToItem(index+1) }},enabled=plan!=null,
                            modifier=Modifier.testTag("safety_jump_$section")) { Text(stringResource(label)) }
                    }
                }
            }
            Surface(Modifier.weight(1f).fillMaxWidth().testTag("safety_checklist_note"),shape=MaterialTheme.shapes.medium,
                color=WarmWhite,contentColor=Ink,border=BorderStroke(1.dp,LightSage)) {
                LazyColumn(Modifier.fillMaxSize().oliveScrollbar(state).testTag("safety_plan_page"),state=state,
                    contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    item {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.safety_checklist_hint),style=MaterialTheme.typography.bodySmall,color=Muted)
                            val status=if(controller.saving>0) R.string.safety_saving else R.string.safety_saved
                            if(plan!=null && controller.error!=R.string.safety_storage_error) Text(stringResource(status),style=MaterialTheme.typography.bodySmall,modifier=Modifier.testTag("safety_save_status"))
                            controller.error?.let { error ->
                                AccessibleMessage(stringResource(error),modifier=Modifier.testTag("safety_error"))
                                if(error==R.string.safety_storage_error) Button(onClick=controller::retrySave) { Text(stringResource(R.string.safety_retry_save)) }
                            }
                        }
                    }
                    if(plan!=null) {
                        SAFETY_CHECKLIST_SECTIONS.forEach { section -> item(key="${prefix}_$section") {
                            SafetyChecklistSection(plan,"${prefix}_$section",
                                onEdit={key,value -> if(controller.plan?.ownerUid==ownerUid) controller.edit(key,value)},
                                onResolve={key,keepMine -> if(controller.plan?.ownerUid==ownerUid) controller.resolve(key,keepMine)},
                                contacts=if(!staff && section=="contacts") session.profile?.contacts.orEmpty() else emptyList(),
                                contactsEditable=!staff && section=="contacts",
                                onContactsChange=if(!staff && section=="contacts") session::saveContacts else null)
                            HorizontalDivider(Modifier.padding(horizontal=16.dp),color=LightSage)
                        } }
                        if(older.isNotEmpty()) item {
                            TextButton(onClick={olderOpen=!olderOpen},modifier=Modifier.testTag("safety_previous_notes")
                                .semantics { stateDescription=if(olderOpen) expandedDescription else collapsedDescription }) {
                                Text(stringResource(R.string.safety_previous_notes))
                            }
                        }
                        if(olderOpen) older.keys.forEach { key -> item(key=key) {
                            SafetyChecklistSection(plan,key,
                                onEdit={key,value -> if(controller.plan?.ownerUid==ownerUid) controller.edit(key,value)},
                                onResolve={key,keepMine -> if(controller.plan?.ownerUid==ownerUid) controller.resolve(key,keepMine)})
                        } }
                        if(!staff) item { Box(Modifier.padding(16.dp)) { SafetyConsultationControls(session,plan) } }
                    }
                    item {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick={session.navigate(SafePage.Documents)},enabled=!staff,modifier=Modifier.fillMaxWidth().testTag("safety_documents")) { Text(stringResource(R.string.support_documents)) }
                            Text(stringResource(R.string.safety_source),style=MaterialTheme.typography.bodySmall,color=Muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SafetyConsultationControls(session:SessionViewModel,plan:SafetyPlan) {
    val controller=session.safetyPlans
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        val message=when {
            plan.shareAction=="stop" -> stringResource(R.string.safety_share_queued)
            plan.staffUid.isNotBlank() -> stringResource(R.string.safety_shared,plan.staffName)
            plan.shareAction=="request" && !plan.consultationRequested -> stringResource(R.string.safety_share_queued)
            plan.consultationRequested -> stringResource(R.string.safety_waiting)
            else -> null
        }
        if(message!=null) Text(message,style=MaterialTheme.typography.bodySmall,
            modifier=Modifier.testTag("safety_sharing_status").semantics { liveRegion=LiveRegionMode.Polite })
        if(plan.staffUid.isBlank() && plan.shareAction!="request" && !plan.consultationRequested) {
            OutlinedButton(onClick={controller.consult()},enabled=!plan.hasConflicts,modifier=Modifier.testTag("safety_request_consultation")) {
                Text(stringResource(R.string.safety_request))
            }
        } else OutlinedButton(onClick={controller.consult(stop=true)},enabled=plan.shareAction!="stop",modifier=Modifier.testTag("safety_stop_sharing")) {
            Text(stringResource(R.string.safety_stop))
        }
        Text(stringResource(R.string.safety_consult_description),style=MaterialTheme.typography.bodySmall,color=Muted)
    }
}

@Composable internal fun SafetyDocumentsScreen(session:SessionViewModel) {
    FormColumn {
        SafetyBackButton(onClick=session::back,tag="safe_home")
        Heading(stringResource(R.string.support_documents),stringResource(R.string.safety_documents_intro))
        listOf("photos" to R.string.safety_storage_photos,"videos" to R.string.safety_storage_videos,
            "documents" to R.string.safety_storage_documents).forEach { (category,label) ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(label),style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics { heading() })
                    Text(stringResource(R.string.safety_upload_soon),style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick={},enabled=false,modifier=Modifier.testTag("safety_upload_$category")) {
                        Text(stringResource(R.string.safety_upload))
                    }
                }
            }
        }
    }
}

// Match the arrow-only navigation used by Notes and Messages while retaining a localized TalkBack label.
@Composable private fun SafetyBackButton(onClick:()->Unit,tag:String) {
    IconButton(onClick=onClick,modifier=Modifier.testTag(tag)) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack,stringResource(R.string.ui_previous_safe_page))
    }
}
