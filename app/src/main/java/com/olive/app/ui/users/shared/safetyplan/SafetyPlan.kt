// Contains the safety plan code for this part of Olive.
package com.olive.app.ui.users.shared.safetyplan

import com.olive.app.ui.shared.*


import com.olive.app.ui.users.shared.navigation.*


import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.olive.app.data.*
import com.olive.app.data.Friend
import com.olive.app.data.SafetyChecklistItem
import com.olive.app.data.writeSafetyChecklist
import com.olive.app.R
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun SafetyPlanScreen(
    session: SessionViewModel, ownerUid: String = session.coverAccountId.orEmpty(),
    ownerName: String = session.coverUsername, onBack: () -> Unit = session::back
) {
    val controller = session.safetyPlans
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun leaveEditor() {
        focus.clearFocus(force = true)
        keyboard?.hide()
    }

    val lease = remember(ownerUid) { Any() }
    val defaults = remember(context) { safetyDefaults(context) }
    DisposableEffect(lease) { onDispose { controller.close(lease) } }
    LaunchedEffect(lease) {
        controller.open(ownerUid, ownerName, defaults, lease)
        while (true) {
            delay(5.seconds)
            controller.refresh()
        }
    }
    var backup by rememberSaveable(ownerUid) { mutableStateOf(false) }
    val plan = controller.plan
    val organizationEditor = session.accountRole in setOf(AccountRole.STAFF, AccountRole.ADMIN)
    val safeContact = session.accountRole == AccountRole.SAFE_CONTACT
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val expandedDescription = stringResource(R.string.support_expanded)
    val collapsedDescription = stringResource(R.string.support_collapsed)
    val prefix = if (backup) "backup" else "primary"
    var olderOpen by rememberSaveable(ownerUid, backup) { mutableStateOf(false) }
    val older = plan?.fields.orEmpty().filter { (key, field) ->
        key.startsWith("${prefix}_") && key.substringAfter('_') !in SAFETY_CHECKLIST_SECTIONS &&
                (readSafetyChecklist(field.value).isNotEmpty() || field.conflictRevision != null)
    }
    LaunchedEffect(backup) { state.scrollToItem(0) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val headerMaxHeight = maxHeight * .5f
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            // Keep navigation available; on compact screens/with the keyboard, leave at least half the space for the note.
            Column(
                Modifier.fillMaxWidth().heightIn(max = headerMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    SafetyBackButton(onClick = {
                        leaveEditor()
                        onBack()
                    }, tag = "safety_back")
                    Text(
                        stringResource(R.string.support_safety_plan),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    if (!organizationEditor && !safeContact) IconButton(
                        onClick = {
                            leaveEditor()
                            scope.launch {
                                val consultationIndex =
                                    1 + SAFETY_CHECKLIST_SECTIONS.size + (if (older.isNotEmpty()) 1 else 0) + (if (olderOpen) older.size else 0)
                                state.animateScrollToItem(consultationIndex)
                            }
                        },
                        enabled = plan != null,
                        modifier = Modifier.testTag("safety_jump_consultation")
                    ) {
                        Icon(Icons.Outlined.SupportAgent, stringResource(R.string.safety_consult))
                    }
                }
                if (organizationEditor || safeContact) Text(
                    ownerName,
                    style = MaterialTheme.typography.labelLarge
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !backup,
                        onClick = {
                            leaveEditor()
                            backup = false
                        },
                        label = { Text(stringResource(R.string.safety_primary)) },
                        modifier = Modifier.weight(1f).testTag("safety_primary")
                    )
                    FilterChip(
                        selected = backup,
                        onClick = {
                            leaveEditor()
                            backup = true
                        },
                        label = { Text(stringResource(R.string.safety_backup)) },
                        modifier = Modifier.weight(1f).testTag("safety_backup")
                    )
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SAFETY_CHECKLIST_SECTIONS.forEachIndexed { index, section ->
                        val label = when (section) {
                            "documents" -> R.string.safety_documents_jump
                            "grab" -> R.string.safety_grab_jump
                            else -> safetySectionLabel(section)
                        }
                        OutlinedButton(
                            onClick = {
                                leaveEditor()
                                scope.launch {
                                    state.animateScrollToItem(
                                        index + 1
                                    )
                                }
                            }, enabled = plan != null,
                            modifier = Modifier.testTag("safety_jump_$section")
                        ) { Text(stringResource(label)) }
                    }
                }
            }
            Surface(
                Modifier.weight(1f).fillMaxWidth().testTag("safety_checklist_note"),
                shape = MaterialTheme.shapes.medium,
                color = WarmWhite,
                contentColor = Ink,
                border = BorderStroke(1.dp, LightSage)
            ) {
                LazyColumn(
                    Modifier.fillMaxSize().oliveScrollbar(state).testTag("safety_plan_page"),
                    state = state,
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                stringResource(R.string.safety_checklist_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted
                            )
                            val status =
                                if (controller.saving > 0) R.string.safety_saving else R.string.safety_saved
                            if (plan != null && controller.error != R.string.safety_storage_error) Text(
                                stringResource(status),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.testTag("safety_save_status")
                            )
                            controller.error?.let { error ->
                                AccessibleMessage(
                                    stringResource(error),
                                    modifier = Modifier.testTag("safety_error")
                                )
                                if (error == R.string.safety_storage_error) Button(onClick = controller::retrySave) {
                                    Text(
                                        stringResource(R.string.safety_retry_save)
                                    )
                                }
                            }
                        }
                    }
                    if (plan != null) {
                        SAFETY_CHECKLIST_SECTIONS.forEach { section ->
                            item(key = "${prefix}_$section") {
                                SafetyChecklistSection(
                                    plan, "${prefix}_$section",
                                    onEdit = { key, value ->
                                        if (!safeContact && controller.plan?.ownerUid == ownerUid) controller.edit(
                                            key,
                                            value
                                        )
                                    },
                                    onResolve = { key, keepMine ->
                                        if (!safeContact && controller.plan?.ownerUid == ownerUid) controller.resolve(
                                            key,
                                            keepMine
                                        )
                                    },
                                    contacts = if (!organizationEditor && !safeContact && section == "contacts") session.profile?.contacts.orEmpty() else emptyList(),
                                    contactsEditable = !organizationEditor && !safeContact && section == "contacts",
                                    onContactsChange = if (!organizationEditor && !safeContact && section == "contacts") session::saveContacts else null,
                                    editable = !safeContact
                                )
                                HorizontalDivider(
                                    Modifier.padding(horizontal = 16.dp),
                                    color = LightSage
                                )
                            }
                        }
                        if (older.isNotEmpty()) item {
                            TextButton(
                                onClick = { olderOpen = !olderOpen },
                                modifier = Modifier.testTag("safety_previous_notes")
                                    .semantics {
                                        stateDescription =
                                            if (olderOpen) expandedDescription else collapsedDescription
                                    }) {
                                Text(stringResource(R.string.safety_previous_notes))
                            }
                        }
                        if (olderOpen) older.keys.forEach { key ->
                            item(key = key) {
                                SafetyChecklistSection(
                                    plan, key,
                                    onEdit = { key, value ->
                                        if (controller.plan?.ownerUid == ownerUid) controller.edit(
                                            key,
                                            value
                                        )
                                    },
                                    onResolve = { key, keepMine ->
                                        if (controller.plan?.ownerUid == ownerUid) controller.resolve(
                                            key,
                                            keepMine
                                        )
                                    }, editable = !safeContact
                                )
                            }
                        }
                        if (!organizationEditor && !safeContact) item {
                            Box(Modifier.padding(16.dp)) {
                                SafetyConsultationControls(
                                    session,
                                    plan
                                )
                            }
                        }
                        if (!organizationEditor && !safeContact) item {
                            Box(Modifier.padding(horizontal = 16.dp)) {
                                SafetyContactSharingControls(
                                    session,
                                    plan
                                )
                            }
                        }
                    }
                    item {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { session.navigate(SafePage.Documents) },
                                enabled = !organizationEditor,
                                modifier = Modifier.fillMaxWidth().testTag("safety_documents")
                            ) { Text(stringResource(R.string.support_documents)) }
                            Text(
                                stringResource(R.string.safety_source),
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SafeContactSafetyPlansScreen(session: SessionViewModel) {
    val controller = session.safetyPlans
    var selected by rememberSaveable { mutableStateOf<SharedSafetyPlan?>(null) }
    LaunchedEffect(Unit) { controller.refreshSharedPlans() }
    if (selected != null) SafetyPlanScreen(
        session,
        checkNotNull(selected).ownerUid,
        checkNotNull(selected).ownerName,
        onBack = {
            controller.close()
            selected = null
            controller.refreshSharedPlans()
        }) else FormColumn {
        SafetyBackButton(onClick = session::back, tag = "safe_contact_safety_back")
        Heading(
            stringResource(R.string.support_safety_plan),
            stringResource(R.string.safe_contact_shared_plans_intro)
        )
        if (session.localMode) Text(stringResource(R.string.safety_account_required), color = Muted)
        if (controller.sharedPlans.isEmpty()) Surface(
            Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = LightSage
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.safe_contact_no_shared_plans),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(R.string.safe_contact_no_shared_plans_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
            }
        }
        controller.sharedPlans.forEach { shared ->
            OutlinedCard(
                onClick = { selected = shared },
                modifier = Modifier.fillMaxWidth().testTag("shared_plan_${shared.ownerUid}")
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.HealthAndSafety, null, tint = OliveGreen)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            shared.ownerName.ifBlank { stringResource(R.string.safe_contact_shared_user) },
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            stringResource(R.string.safe_contact_view_shared_plan),
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted
                        )
                    }
                }
            }
        }
        controller.error?.let { AccessibleMessage(stringResource(it)) }
    }
}

@Composable
private fun SafetyContactSharingControls(session: SessionViewModel, plan: SafetyPlan) {
    var selected by remember(plan.safeContactUids) { mutableStateOf(plan.safeContactUids.toSet()) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.safety_share_safe_contacts),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        Text(
            stringResource(R.string.safety_share_safe_contacts_help),
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
        if (session.friends.isEmpty()) Text(
            stringResource(R.string.location_no_friends),
            color = Muted
        )
        session.friends.forEach { friend ->
            val trusted = session.profile?.contacts?.firstOrNull { it.linkedUid == friend.uid }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Checkbox(
                    friend.uid in selected,
                    { checked ->
                        selected = if (checked) selected + friend.uid else selected - friend.uid
                    },
                    enabled = friend.uid in selected || selected.size < 10
                )
                Column(Modifier.weight(1f)) {
                    Text(trusted?.name ?: friend.username)
                    if (trusted != null) Text(
                        "@${friend.username}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted
                    )
                }
            }
        }
        Button(
            onClick = { session.safetyPlans.shareWithContacts(session.friends.filter { it.uid in selected }) },
            enabled = !session.localMode && selected != plan.safeContactUids.toSet(),
            modifier = Modifier.fillMaxWidth().testTag("share_safety_plan")
        ) {
            Text(stringResource(R.string.safety_save_sharing))
        }
    }
}

@Composable
private fun SafetyConsultationControls(session: SessionViewModel, plan: SafetyPlan) {
    val controller = session.safetyPlans
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val message = when {
            plan.shareAction == "stop" -> stringResource(R.string.safety_share_queued)
            plan.staffUid.isNotBlank() -> stringResource(R.string.safety_shared, plan.staffName)
            plan.shareAction == "request" && !plan.consultationRequested -> stringResource(R.string.safety_share_queued)
            plan.consultationRequested -> stringResource(R.string.safety_waiting)
            else -> null
        }
        if (message != null) Text(
            message, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("safety_sharing_status")
                .semantics { liveRegion = LiveRegionMode.Polite })
        if (plan.staffUid.isBlank() && plan.shareAction != "request" && !plan.consultationRequested) {
            OutlinedButton(
                onClick = { controller.consult() },
                enabled = !plan.hasConflicts,
                modifier = Modifier.testTag("safety_request_consultation")
            ) {
                Text(stringResource(R.string.safety_request))
            }
        } else OutlinedButton(
            onClick = { controller.consult(stop = true) },
            enabled = plan.shareAction != "stop",
            modifier = Modifier.testTag("safety_stop_sharing")
        ) {
            Text(stringResource(R.string.safety_stop))
        }
        if (plan.staffUid.isNotBlank() && plan.safeContactUids.isNotEmpty()) {
            Text(
                stringResource(R.string.support_chat_contact),
                style = MaterialTheme.typography.titleSmall,
            )
            plan.safeContactUids.forEach { uid ->
                val selected = plan.supportSafeContactUid == uid
                FilterChip(
                    selected = selected,
                    onClick = { controller.setSupportContact(if (selected) "" else uid) },
                    label = { Text(plan.safeContactNames[uid] ?: stringResource(R.string.safe_contact)) },
                    enabled = controller.saving == 0,
                )
            }
            Text(
                stringResource(R.string.support_chat_contact_help),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
        Text(
            stringResource(R.string.safety_consult_description),
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
    }
}

// Match the arrow-only navigation used by Notes and Messages while retaining a localized TalkBack label.
@Composable
private fun SafetyBackButton(onClick: () -> Unit, tag: String) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_previous_safe_page))
    }
}
