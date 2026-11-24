// All safe-app roles: shows safety-plan content to permitted roles.
package com.olive.app.ui.users.shared.safetyplan

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveOutlinedCard as OutlinedCard
import com.olive.app.ui.shared.OliveTextButton as TextButton
import com.olive.app.ui.shared.*


import com.olive.app.ui.users.shared.navigation.*


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.SafetyPlanIntake
import com.olive.app.data.safety.SAFETY_CHECKLIST_SECTIONS
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.data.safety.SafetyPlan
import com.olive.app.data.safety.SharedSafetyPlan
import com.olive.app.data.safety.readSafetyChecklist
import com.olive.app.data.safety.safetyChecklistAttachmentTag
import com.olive.app.data.safety.safetyChecklistConflictPreview
import com.olive.app.data.safety.safetyChecklistPreview
import com.olive.app.data.safety.writeSafetyChecklist
import com.olive.app.ui.users.shared.onboarding.SafetyPlanQuestionnaireDialog
import com.olive.app.ui.users.user.documents.SafetyDocumentUploadFlow
import com.olive.app.ui.users.user.documents.SafetyDocumentViewer
import java.io.File
import kotlinx.coroutines.launch

private data class SafetyAttachmentTarget(
    val fieldKey: String,
    val itemId: String,
    val uploadTag: String,
)

private data class ChecklistCompletion(val prepared: Int, val total: Int) {
    val complete get() = total > 0 && prepared == total
}

/**
 * Composes the safety plan tab label UI element.
 *
 * @param label Accessible label or display caption.
 * @param complete Whether the plan checklist is fully complete.
 */
@Composable
private fun SafetyPlanTabLabel(label: String, complete: Boolean) {
    val completeLabel = stringResource(R.string.safety_conflict_complete)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = if (complete) Modifier.semantics { stateDescription = completeLabel } else Modifier,
    ) {
        Text(label)
        if (complete) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Shared safe app safety plan screen shows this safety plan screen so the user can view its details and use its actions.
 *
 * @param session Shared session view model owning application state and actions.
 * @param ownerUid Firebase UID of the resource owner.
 * @param ownerName Owner name supplied to [SafetyPlanScreen].
 * @param displayOwnerName Display owner name supplied to [SafetyPlanScreen].
 * @param onCompleteConsultation Callback invoked when complete consultation changes or occurs.
 * @param onBack Callback invoked when the user navigates back.
 */
@Composable
internal fun SafetyPlanScreen(
    session: SessionViewModel, ownerUid: String = session.coverAccountId.orEmpty(),
    ownerName: String = session.coverUsername,
    displayOwnerName: String = ownerName,
    onCompleteConsultation: (() -> Unit)? = null,
    onBack: () -> Unit = session.navigation::back,
) {
    val controller = session.safetyPlans
    val documentController = session.documents
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var pendingDocumentAttachment by remember { mutableStateOf<SafetyAttachmentTarget?>(null) }
    var openedDocument by remember { mutableStateOf<Pair<SafetyDocument, File>?>(null) }
    /**
     * Hides the keyboard before leaving or switching safety-plan sections.
     */
    fun leaveEditor() {
        focus.clearFocus(force = true)
        keyboard?.hide()
        controller.flushEdits()
    }

    val lease = remember(ownerUid) { Any() }
    val hasAffectedPeople = ownerUid == session.coverAccountId && !session.profile?.affectedNames.isNullOrBlank()
    val intake = if (ownerUid == session.coverAccountId) session.profile?.safetyIntake ?: SafetyPlanIntake() else SafetyPlanIntake()
    val defaults = remember(context, hasAffectedPeople, intake) { safetyDefaults(context, hasAffectedPeople, intake) }
    var questionnaireOpen by rememberSaveable(ownerUid) { mutableStateOf(false) }
    // Stop this screen's plan listener when it leaves composition.
    DisposableEffect(lease) { onDispose { controller.close(lease) } }
    // Open once; its Firestore listener and durable reconciliation worker deliver later changes.
    LaunchedEffect(lease) {
        controller.open(ownerUid, ownerName, defaults, lease)
    }
    val organizationEditor = session.accountRole in setOf(AccountRole.STAFF, AccountRole.ADMIN)
    val viewerUid = session.coverAccountId.orEmpty()
    LaunchedEffect(ownerUid, viewerUid, session.coverUsername, session.localMode, organizationEditor) {
        if (viewerUid.isNotBlank()) {
            documentController.load(viewerUid, session.coverUsername, session.localMode)
        }
    }
    var backup by rememberSaveable(ownerUid) { mutableStateOf(false) }
    val plan = controller.plan?.takeIf { !controller.loadingPlan && it.ownerUid == ownerUid }
    val safeContact = session.accountRole == AccountRole.SAFE_CONTACT
    val attachedDocumentIds = remember(plan?.fields) {
        plan?.fields.orEmpty().values.filter { it.mutationId == null }.flatMapTo(linkedSetOf()) { field ->
            readSafetyChecklist(field.value).flatMap { item -> item.attachmentIds - item.excludedAttachmentIds.toSet() }
        }
    }
    val attachmentAccessSnapshot = documentController.state.files.map { document ->
        document.id to document.allowedUsers.sorted()
    }
    var recipients by remember(ownerUid, viewerUid) { mutableStateOf(emptyMap<String, String>()) }
    var recipientDevicesVersion by remember(ownerUid, viewerUid) { mutableIntStateOf(0) }
    LaunchedEffect(ownerUid, viewerUid, plan?.staffUid, plan?.adminUid, plan?.safeContactUids) {
        recipients = runCatching { controller.mediaRecipients() }.getOrDefault(emptyMap())
    }
    DisposableEffect(recipients) {
        val listeners = if (recipients.isEmpty()) emptyList()
            else controller.observeRecipientDevices(recipients.values) { recipientDevicesVersion++ }
        onDispose { listeners.forEach { it.remove() } }
    }
    LaunchedEffect(ownerUid, viewerUid, plan?.fields, documentController.state.files) {
        if (session.accountRole == AccountRole.USER && ownerUid == viewerUid) {
            controller.attachPublishedDocuments(documentController.state.files.filter { it.ownerUid == ownerUid })
        }
    }
    // Wrap attached file keys for the current readers and any newly registered devices.
    LaunchedEffect(ownerUid, viewerUid, attachedDocumentIds, attachmentAccessSnapshot, recipients, recipientDevicesVersion) {
        if (session.accountRole == AccountRole.USER && ownerUid == viewerUid) {
            if (attachedDocumentIds.isEmpty()) return@LaunchedEffect
            val pending = documentController.state.files.filter { document ->
                document.id in attachedDocumentIds
            }
            if (pending.isNotEmpty() && recipients.isNotEmpty()) {
                documentController.grantPlanAccess(pending, ownerUid, recipients)
            }
        }
    }
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val expandedDescription = stringResource(R.string.support_expanded)
    val collapsedDescription = stringResource(R.string.support_collapsed)
    val prefix = if (backup) "backup" else "primary"
    val sectionsByPlan = remember(plan?.fields) {
        listOf("primary", "backup").associateWith { planPrefix ->
            SAFETY_CHECKLIST_SECTIONS.filterNot { it == "contacts" }.associateWith { section ->
                val items = plan?.fields?.get("${planPrefix}_$section")
                    ?.let { readSafetyChecklist(it.value) }.orEmpty()
                ChecklistCompletion(items.count { it.checked }, items.size)
            }
        }
    }
    val completionByPlan = sectionsByPlan.mapValues { (_, sections) ->
        ChecklistCompletion(sections.values.sumOf { it.prepared }, sections.values.sumOf { it.total })
    }
    val selectedCompletion = completionByPlan.getValue(prefix)
    val completeLabel = stringResource(R.string.safety_conflict_complete)
    if (session.accountRole == AccountRole.USER && plan != null) {
        val conflict = plan.fields.entries.firstOrNull {
            it.value.conflictRevision != null || it.key in plan.pendingConflicts
        }
        if (conflict != null) {
            val (key, field) = conflict
            val incoming = field.conflictValue ?: plan.pendingConflicts[key]?.value.orEmpty()
            SafetyConflictDialog(key, field.value, incoming, controller.saving == 0) { keepMine ->
                controller.resolve(key, keepMine)
            }
        }
    }
    var olderOpen by rememberSaveable(ownerUid, backup) { mutableStateOf(false) }
    val older = plan?.fields.orEmpty().filter { (key, field) ->
        key.startsWith("${prefix}_") && key.substringAfter('_') !in SAFETY_CHECKLIST_SECTIONS &&
                (readSafetyChecklist(field.value).isNotEmpty() || field.conflictRevision != null)
    }
    // Return to the top when switching between primary and backup plans.
    LaunchedEffect(backup) { state.scrollToItem(0) }
    openedDocument?.let { (document, file) ->
        LaunchedEffect(plan, documentController.state.files) {
            if (plan == null || documentController.state.files.none { it.id == document.id }) {
                file.delete()
                openedDocument = null
            }
        }
        DisposableEffect(file) { onDispose { file.delete() } }
        SafetyDocumentViewer(document, file) {
            file.delete()
            openedDocument = null
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val headerMaxHeight = maxHeight * .4f
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            // Allow the tabs to scroll vertically in compact windows while the progress stays fixed.
            Column(
                Modifier.fillMaxWidth().heightIn(max = headerMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    SafetyBackButton(onClick = { leaveEditor(); onBack() }, tag = "safety_back")
                    Text(
                        stringResource(R.string.support_safety_plan),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f).semantics { heading() }
                    )
                    if (!organizationEditor && !safeContact) IconButton(
                        onClick = {
                            leaveEditor()
                            scope.launch {
                                val index = 1 + SAFETY_CHECKLIST_SECTIONS.size +
                                    (if (older.isNotEmpty()) 1 else 0) + (if (olderOpen) older.size else 0)
                                state.animateScrollToItem(index)
                            }
                        },
                        enabled = plan != null,
                        modifier = Modifier.testTag("safety_jump_consultation")
                    ) { Icon(Icons.Outlined.SupportAgent, stringResource(R.string.safety_consult)) }
                }
                if (organizationEditor || safeContact) Text(displayOwnerName, style = MaterialTheme.typography.labelLarge)
                PrimaryTabRow(
                    selectedTabIndex = if (backup) 1 else 0,
                    modifier = Modifier.fillMaxWidth().walkthroughTarget("safety_plan_tabs").testTag("safety_plan_tabs"),
                ) {
                    Tab(
                        selected = !backup,
                        onClick = { leaveEditor(); backup = false },
                        text = {
                            SafetyPlanTabLabel(
                                stringResource(R.string.safety_primary),
                                completionByPlan.getValue("primary").complete
                            )
                        },
                        modifier = Modifier.testTag("safety_primary"),
                    )
                    Tab(
                        selected = backup,
                        onClick = { leaveEditor(); backup = true },
                        text = {
                            SafetyPlanTabLabel(
                                stringResource(R.string.safety_backup),
                                completionByPlan.getValue("backup").complete
                            )
                        },
                        modifier = Modifier.testTag("safety_backup"),
                    )
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SAFETY_CHECKLIST_SECTIONS.forEachIndexed { index, section ->
                        val complete = sectionsByPlan.getValue(prefix)[section]?.complete == true
                        val label = when (section) {
                            "documents" -> R.string.safety_documents_jump
                            "grab" -> R.string.safety_grab_jump
                            else -> safetySectionLabel(section)
                        }
                        OutlinedButton(
                            onClick = { leaveEditor(); scope.launch { state.animateScrollToItem(index + 1) } },
                            enabled = plan != null,
                            colors = if (complete) ButtonDefaults.outlinedButtonColors(
                                containerColor = SageGreen, contentColor = Ink
                            ) else ButtonDefaults.outlinedButtonColors(),
                            modifier = Modifier.testTag("safety_jump_$section")
                                .semantics { if (complete) stateDescription = completeLabel }
                        ) {
                            if (complete) {
                                Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(stringResource(label))
                        }
                    }
                }
            }
            if (controller.loadingPlan) LoadingSkeleton(SkeletonLayout.Checklist)
            if (plan != null) {
                SafetyChecklistProgress(
                    prepared = selectedCompletion.prepared,
                    total = selectedCompletion.total,
                    summary = true,
                )
                Spacer(Modifier.height(8.dp))
            }
            Surface(
                Modifier.weight(1f).fillMaxWidth().testTag("safety_checklist_note"),
                shape = MaterialTheme.shapes.medium,
                color = WarmWhite,
                contentColor = Ink,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                LazyColumn(
                    Modifier.fillMaxSize().oliveScrollbar(state).testTag("safety_plan_page"),
                    state = state,
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Column(
                            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val status = when {
                                controller.saving > 0 -> R.string.safety_saving
                                controller.syncing -> R.string.safety_syncing
                                plan?.pending == true -> R.string.safety_saved_waiting
                                else -> R.string.safety_saved
                            }
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
                                    onSyncNow = controller::flushEdits,
                                    contacts = if (!organizationEditor && !safeContact && section == "contacts") session.profile?.contacts.orEmpty() else emptyList(),
                                    contactsEditable = !organizationEditor && !safeContact && section == "contacts",
                                    onContactsChange = if (!organizationEditor && !safeContact && section == "contacts") session::saveContacts else null,
                                    editable = !safeContact,
                                    attachmentsEditable = !organizationEditor && !safeContact,
                                    documents = documentController.state.files.filter { it.ownerUid == ownerUid },
                                    onOpenDocument = { document ->
                                        documentController.download(document) { file ->
                                            openedDocument = document to file
                                        }
                                    },
                                    onUploadDocument = if (!organizationEditor && !safeContact && section == "documents") {
                                        { itemId ->
                                            val fieldKey = "${prefix}_documents"
                                            pendingDocumentAttachment = SafetyAttachmentTarget(
                                                fieldKey = fieldKey,
                                                itemId = itemId,
                                                uploadTag = safetyChecklistAttachmentTag(
                                                    fieldKey,
                                                    itemId
                                                ),
                                            )
                                        }
                                    } else null,
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
                                    onSyncNow = controller::flushEdits,
                                    editable = !safeContact
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
                        // Personal users can edit this section; safe contacts can only read it.
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
                            if (!organizationEditor) OutlinedButton(
                                onClick = { session.navigation.openSafePage(SafePage.Documents) },
                                modifier = Modifier.fillMaxWidth().testTag("safety_documents")
                            ) { Text(stringResource(R.string.support_documents)) }
                            if (!organizationEditor && !safeContact && ownerUid == viewerUid) OutlinedButton(
                                onClick = { questionnaireOpen = true },
                                enabled = plan != null && !session.busy,
                                modifier = Modifier.fillMaxWidth().testTag("safety_tailor_questionnaire"),
                            ) { Text(stringResource(R.string.safety_intake_update_plan)) }
                            if (session.accountRole == AccountRole.STAFF && onCompleteConsultation != null) {
                                OutlinedButton(
                                    onClick = onCompleteConsultation,
                                    enabled = controller.saving == 0 && session.staffSupport.saving == 0,
                                    modifier = Modifier.fillMaxWidth().testTag("complete_safety_consultation"),
                                ) { Text(stringResource(R.string.safety_mark_consultation_complete)) }
                            }
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
    SafetyDocumentUploadFlow(
        session = session,
        visible = pendingDocumentAttachment != null,
        onDismiss = { pendingDocumentAttachment = null },
        tags = setOfNotNull("Safety Plan", pendingDocumentAttachment?.uploadTag),
        onSelectExisting = { document ->
            val target = pendingDocumentAttachment ?: return@SafetyDocumentUploadFlow
            val field = controller.plan?.fields?.get(target.fieldKey) ?: return@SafetyDocumentUploadFlow
            val changed = readSafetyChecklist(field.value).map { item ->
                if (item.id == target.itemId) item.copy(
                    attachmentIds = (item.attachmentIds + document.id).distinct().takeLast(80),
                    excludedAttachmentIds = item.excludedAttachmentIds - document.id,
                ) else item
            }
            controller.edit(target.fieldKey, writeSafetyChecklist(changed))
        },
    )
    if (questionnaireOpen) {
        SafetyPlanQuestionnaireDialog(
            initialIntake = intake,
            enabled = !session.busy && controller.saving == 0,
            onDismiss = { questionnaireOpen = false },
            onSave = { updated ->
                session.saveSafetyIntake(updated)
                applySafetyIntakeToPlan(context, controller, updated, hasAffectedPeople)
                questionnaireOpen = false
            },
        )
    }
}

/**
 * Lets a trusted contact view the safety plans shared with them.
 *
 * @param session Shared session view model owning application state and actions.
 */
@Composable
internal fun SafeContactSafetyPlansScreen(session: SessionViewModel) {
    val controller = session.safetyPlans
    var selected by rememberSaveable { mutableStateOf<SharedSafetyPlan?>(null) }
    LaunchedEffect(session.coverAccountId) {
        controller.observeAccess()
        controller.refreshSharedPlans()
    }
    if (selected != null) SafetyPlanScreen(
        session,
        checkNotNull(selected).ownerUid,
        checkNotNull(selected).ownerName,
        onBack = {
            controller.close()
            selected = null
            controller.refreshSharedPlans()
        }) else SharedSafetyPlanList(
        controller.sharedPlans, controller.loadingAccess, session.localMode, controller.error,
        onBack = session.navigation::back, onOpen = { selected = it },
    )
}

/**
 * Composes the shared safety plan list UI element.
 *
 * @param plans Collection of plans values to process.
 * @param loading Whether an asynchronous operation is currently in progress.
 * @param localMode Whether to apply local mode.
 * @param error Throwable or error message describing a failure, or null when not provided.
 * @param onBack Callback invoked when the user navigates back.
 * @param onOpen Callback invoked when open changes or occurs.
 */
@Composable
internal fun SharedSafetyPlanList(
    plans: List<SharedSafetyPlan>,
    loading: Boolean,
    localMode: Boolean,
    error: Int?,
    onBack: () -> Unit,
    onOpen: (SharedSafetyPlan) -> Unit,
) {
    FormColumn {
        SafetyBackButton(onClick = onBack, tag = "safe_contact_safety_back")
        Heading(
            stringResource(R.string.support_safety_plan),
            stringResource(R.string.safe_contact_shared_plans_intro)
        )
        if (localMode) Text(stringResource(R.string.safety_account_required), color = Muted)
        if (loading && !localMode) LoadingSkeleton(SkeletonLayout.SharedPlans)
        error?.let { AccessibleMessage(stringResource(it)) }
        val visiblePlans = if (loading) emptyList() else plans
        if (visiblePlans.isEmpty() && (!loading || localMode) && error == null) Surface(
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
        visiblePlans.forEach { shared ->
            OutlinedCard(
                onClick = { onOpen(shared) },
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
        error?.let { AccessibleMessage(stringResource(it)) }
    }
}

/**
 * Draws the safety contact sharing controls for the safety plan.
 *
 * @param session Shared session view model owning application state and actions.
 * @param plan Safety plan data model.
 */
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
        if (session.messages.friends.isEmpty()) Text(
            stringResource(R.string.location_no_friends),
            color = Muted
        )
        session.messages.friends.forEach { friend ->
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
                    enabled = friend.uid in selected || selected.size < 10,
                    modifier = Modifier.testTag("safety_share_contact_${friend.uid}"),
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
        val controller = session.safetyPlans
        val uiState by controller.uiState.collectAsState()
        val inlineError = uiState.sharingError ?: controller.error?.let { stringResource(it) }
        if (inlineError != null) {
            AccessibleMessage(inlineError)
        } else if (uiState.sharingSaved && selected == plan.safeContactUids.toSet()) {
            Text(stringResource(R.string.safety_sharing_saved), style = MaterialTheme.typography.bodySmall, color = OliveGreen)
        }
        Button(
            onClick = { controller.shareWithContacts(session.messages.friends.filter { it.uid in selected }) },
            enabled = !session.localMode && controller.saving == 0 && selected != plan.safeContactUids.toSet(),
            modifier = Modifier.fillMaxWidth().testTag("share_safety_plan")
        ) {
            Text(stringResource(if (controller.saving > 0) R.string.safety_saving else R.string.safety_save_sharing))
        }
    }
}

/**
 * Lets a user request or cancel a separate plan-consult ticket.
 *
 * @param session Shared session view model owning application state and actions.
 * @param plan Safety plan data model.
 */
@Composable
private fun SafetyConsultationControls(session: SessionViewModel, plan: SafetyPlan) {
    val controller = session.safetyPlans
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val message = when {
            plan.shareAction == "stop" -> stringResource(R.string.safety_share_queued)
            plan.consultationRequested && plan.staffUid.isNotBlank() -> stringResource(R.string.safety_staff_assigned)
            plan.shareAction == "request" && !plan.consultationRequested -> stringResource(R.string.safety_share_queued)
            plan.consultationRequested -> stringResource(R.string.safety_waiting)
            plan.plansCompletedAt > 0 -> stringResource(R.string.safety_plans_completed)
            else -> null
        }
        if (message != null) Text(
            message, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("safety_sharing_status").semantics { liveRegion = LiveRegionMode.Polite })
        if (plan.consultationRequested && plan.consultationConversationId.isNotBlank()) {
            OutlinedButton(onClick = { controller.openConsultation(plan.consultationConversationId) }) {
                Text(stringResource(R.string.safety_open_consultation))
            }
        }
        if (plan.shareAction != "request" && !plan.consultationRequested) {
            OutlinedButton(
                onClick = { controller.consult() }, enabled = !plan.hasConflicts && controller.saving == 0,
                modifier = Modifier.testTag("safety_request_consultation")
            ) { Text(stringResource(R.string.safety_request)) }
        } else OutlinedButton(
            onClick = { controller.consult(stop = true) }, enabled = plan.shareAction != "stop" && controller.saving == 0,
            modifier = Modifier.testTag("safety_stop_sharing")
        ) { Text(stringResource(R.string.safety_stop)) }
        // Show sharing choices only when the plan is not already shared with staff or a safe contact.
        if (plan.staffUid.isNotBlank() && plan.safeContactUids.isNotEmpty()) {
            Text(stringResource(R.string.support_chat_contact), style = MaterialTheme.typography.titleSmall)
            plan.safeContactUids.forEach { uid ->
                val selected = plan.supportSafeContactUid == uid
                FilterChip(
                    selected = selected, onClick = { controller.setSupportContact(if (selected) "" else uid) },
                    label = { Text(plan.safeContactNames[uid] ?: stringResource(R.string.safe_contact)) },
                    enabled = controller.saving == 0,
                )
            }
            Text(stringResource(R.string.support_chat_contact_help), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Text(stringResource(R.string.safety_consult_description), style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}

/**
 * Composes the safety conflict property label UI element.
 *
 * @param property Property supplied to [safetyConflictPropertyLabel].
 */
@Composable
private fun safetyConflictPropertyLabel(property: String) = stringResource(
    when (property) {
        "Step" -> R.string.safety_conflict_step
        "Completion" -> R.string.safety_conflict_completion
        "Attachments" -> R.string.safety_conflict_attachments
        "Excluded attachments" -> R.string.safety_conflict_excluded_attachments
        else -> R.string.safety_conflict_text
    },
)

/**
 * Composes the safety conflict value label UI element.
 *
 * @param value Input value to store, parse, or format.
 * @return The resulting safety conflict value label string.
 */
@Composable
private fun safetyConflictValueLabel(value: String): String {
    val resource = when (value) {
        "Present" -> R.string.safety_conflict_present
        "Removed" -> R.string.safety_conflict_removed
        "Complete" -> R.string.safety_conflict_complete
        "Not complete" -> R.string.safety_conflict_not_complete
        "Changed" -> R.string.safety_conflict_changed
        else -> return value
    }
    return stringResource(resource)
}

/**
 * Displays the safety conflict dialog overlay.
 *
 * @param key Lookup key or cryptographic key.
 * @param mine Mine supplied to [SafetyConflictDialog].
 * @param incoming Incoming supplied to [SafetyConflictDialog].
 * @param enabled Whether this feature or control is currently enabled.
 * @param resolve Action or callback executed for resolve.
 */
@Composable
private fun SafetyConflictDialog(key: String, mine: String, incoming: String, enabled: Boolean, resolve: (Boolean) -> Unit) {
    var keepMine by remember(key, mine, incoming) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.safety_conflicts_title)) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                listOf(true to mine, false to incoming).forEach { (isMine, value) ->
                    Row {
                        RadioButton(selected = keepMine == isMine, onClick = { keepMine = isMine })
                        Column {
                            Text(stringResource(if (isMine) R.string.safety_your_change else R.string.safety_incoming_change))
                            val conflicts = remember(mine, incoming, isMine) {
                                safetyChecklistConflictPreview(mine, incoming, isMine)
                            }
                            if (conflicts == null) {
                                Text(safetyChecklistPreview(value).ifBlank { stringResource(R.string.safety_empty_value) })
                            } else {
                                conflicts.forEach { conflict ->
                                    Text(conflict.item, style = MaterialTheme.typography.labelLarge)
                                    Text("${safetyConflictPropertyLabel(conflict.property)}: ${safetyConflictValueLabel(conflict.value)}")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { resolve(keepMine) }, enabled = enabled) {
                Text(stringResource(if (keepMine) R.string.safety_keep_mine else R.string.safety_use_synced))
            }
        },
    )
}

/**
 * Match the arrow-only navigation used by Notes and Messages while retaining a localized TalkBack label.
 *
 * @param onClick Callback invoked when the control is activated.
 * @param tag Tag supplied to [SafetyBackButton].
 */
@Composable
private fun SafetyBackButton(onClick: () -> Unit, tag: String) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_previous_safe_page))
    }
}
