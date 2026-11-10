// Staff: shows assigned users and support tickets.
package com.olive.app.ui.users.staff

import com.olive.app.ui.shared.*
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.shared.organization.OrganizationSettings
import com.olive.app.ui.users.shared.organization.OrganizationDashboardCard
import com.olive.app.ui.users.shared.organization.OrganizationMap
import com.olive.app.ui.users.shared.messaging.MessagesScreen
import com.olive.app.ui.users.safecontact.SharedEntryViewer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.R
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.users.shared.safetyplan.SafetyPlanScreen
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import com.olive.app.data.SafetyConsultation

private enum class StaffPage { Dashboard, Settings, SafetyPlans, Messages, Map }

// Shows the staff workspace with assigned users, tickets, map, and settings.
@Composable
internal fun StaffPortal(session: SessionViewModel) {
    LaunchedEffect(session.coverAccountId) { session.messages.ensureMessagingObservation() }
    var page by remember { mutableStateOf(StaffPage.Dashboard) }
    BackHandler(enabled = page != StaffPage.Dashboard) { page = StaffPage.Dashboard }
    // Opens the selected staff dashboard section.
    when (page) {
        StaffPage.Dashboard -> StaffDashboard(
            session,
            onSettings = { page = StaffPage.Settings },
            onSafetyPlans = { page = StaffPage.SafetyPlans },
            onMessages = { page = StaffPage.Messages },
            onMap = { page = StaffPage.Map },
        )
        StaffPage.Settings -> OrganizationSettings(
            session,
            onBack = { page = StaffPage.Dashboard },
        )
        StaffPage.SafetyPlans -> SafetyConsultations(
            session,
            onBack = { page = StaffPage.Dashboard },
        )
        StaffPage.Messages -> {
            LaunchedEffect(Unit) { session.messages.ensureMessagingObservation() }
            MessagesScreen(session, exit = { page = StaffPage.Dashboard })
        }
        StaffPage.Map -> OrganizationMap(session, onBack = { page = StaffPage.Dashboard })
    }
    if (session.viewedSharedEntry != null) SharedEntryViewer(session)
}

// Draws the staff workspace navigation and selected section.
@Composable
private fun StaffDashboard(
    session: SessionViewModel,
    onSettings: () -> Unit,
    onSafetyPlans: () -> Unit,
    onMessages: () -> Unit,
    onMap: () -> Unit,
) {
    val uiText = rememberResourceText()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(end = 56.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.olive_tree),
                    fontFamily = FontFamily.Serif,
                    fontSize = 30.sp,
                    color = OliveGreen,
                )
                Text(
                    uiText(R.string.staff_panel),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(uiText(R.string.ui_signed_in_as_value, session.coverUsername), color = Muted)
            }
            IconButton(onClick = onSettings, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Outlined.Settings, uiText(R.string.account_settings))
            }
        }
        OrganizationDashboardCard(
            Icons.Outlined.HealthAndSafety,
            stringResource(R.string.safety_inbox),
            stringResource(R.string.staff_safety_plans_description),
            onSafetyPlans,
        )
        OrganizationDashboardCard(
            Icons.Outlined.Forum,
            stringResource(R.string.support_conversations),
            stringResource(R.string.staff_conversations_assigned_help),
            onMessages,
            session.messages.pendingActionCount,
        )
        OrganizationDashboardCard(
            Icons.Outlined.LocationOn,
            stringResource(R.string.organization_live_map),
            stringResource(R.string.organization_live_map_staff_description),
            onMap,
        )
        Status(session)
        OutlinedButton(
            onClick = session::signOut,
            enabled = !session.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.sign_out)) }
    }
}

// Staff: shows safety-plan consultation requests to staff.
// Lists the plan-consult tickets assigned to this staff member.
@Composable
internal fun SafetyConsultations(session: SessionViewModel, onBack: () -> Unit) {
    var selected by remember { mutableStateOf<SafetyConsultation?>(null) }
    var completionTarget by remember { mutableStateOf<SafetyConsultation?>(null) }
    var assignedExpanded by rememberSaveable { mutableStateOf(true) }
    var waitingExpanded by rememberSaveable { mutableStateOf(true) }
    val controller = session.safetyPlans
    DisposableEffect(Unit) { onDispose { controller.close() } }
    BackHandler { if (selected != null) selected = null else onBack() }
    val plan = selected
    if (plan != null) {
        val ticketLabel = if (plan.ticketNumber >= 0L) "Ticket #${plan.ticketNumber}" else "Support ticket"
        SafetyPlanScreen(
            session = session,
            ownerUid = plan.ownerUid,
            ownerName = plan.ownerName,
            displayOwnerName = ticketLabel,
            onCompleteConsultation = if (
                session.accountRole == com.olive.app.data.AccountRole.STAFF &&
                plan.conversationId.isNotBlank()
            ) ({ completionTarget = plan }) else null,
            onBack = { selected = null },
        )
        completionTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { completionTarget = null },
                title = { Text(stringResource(R.string.safety_complete_consultation_title)) },
                text = { Text(stringResource(R.string.safety_complete_consultation_description)) },
                confirmButton = {
                    Button(
                        onClick = {
                            completionTarget = null
                            session.messages.closeSupportChat(target.conversationId) {
                                selected = null
                                controller.close()
                            }
                        },
                        enabled = session.staffSupport.saving == 0,
                    ) { Text(stringResource(R.string.safety_mark_complete)) }
                },
                dismissButton = {
                    TextButton(onClick = { completionTarget = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
        return
    }
    // Load once on entry; account invalidations refresh the encrypted consultation index afterward.
    LaunchedEffect(Unit) {
        controller.refreshConsultations()
    }
    FormColumn {
        TextButton(onClick = onBack) { Text(stringResource(R.string.safety_back)) }
        Heading(
            stringResource(R.string.safety_inbox),
            stringResource(if (session.accountRole == com.olive.app.data.AccountRole.ADMIN) R.string.safety_admin_intro else R.string.safety_staff_intro),
            stringResource(R.string.olive_tree),
        )
        if (session.accountRole == com.olive.app.data.AccountRole.STAFF) {
            val availabilityLabel = stringResource(R.string.safety_available)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(availabilityLabel, Modifier.weight(1f))
                Switch(
                    checked = controller.available,
                    onCheckedChange = controller::changeAvailability,
                    modifier = Modifier.testTag("safety_staff_available")
                        .semantics { contentDescription = availabilityLabel },
                )
            }
        }
        controller.error?.let { AccessibleMessage(stringResource(it)) }
        if (controller.consultations.isEmpty()) Text(stringResource(R.string.safety_inbox_empty))
        val assigned = controller.consultations.filter { it.staffUid.isNotBlank() }
        val waiting = controller.consultations.filter { it.staffUid.isBlank() }
        ConsultationGroup(
            title = stringResource(
                if (session.accountRole == com.olive.app.data.AccountRole.ADMIN)
                    R.string.safety_assigned_to_staff else R.string.safety_assigned_to_you
            ),
            consultations = assigned,
            expanded = assignedExpanded,
            onExpandedChange = { assignedExpanded = it },
            showStaffName = session.accountRole == com.olive.app.data.AccountRole.ADMIN,
            onSelected = { selected = it },
        )
        if (session.accountRole == com.olive.app.data.AccountRole.ADMIN) {
            ConsultationGroup(
                title = stringResource(R.string.safety_waiting_for_staff),
                consultations = waiting,
                expanded = waitingExpanded,
                onExpandedChange = { waitingExpanded = it },
                showStaffName = false,
                onSelected = { selected = it },
            )
        }
    }
}

// Staff and admins use the same collapsible consultation list so shared plan work looks consistent.
@Composable
private fun ConsultationGroup(
    title: String,
    consultations: List<SafetyConsultation>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    showStaffName: Boolean,
    onSelected: (SafetyConsultation) -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column {
            TextButton(
                onClick = { onExpandedChange(!expanded) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "$title (${consultations.size})",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                )
            }
            if (expanded) {
                HorizontalDivider()
                if (consultations.isEmpty()) {
                    Text(
                        stringResource(R.string.safety_no_plans_in_category),
                        modifier = Modifier.padding(16.dp),
                        color = Muted,
                    )
                }
                consultations.forEach { consultation ->
                    val ticketLabel = if (consultation.ticketNumber >= 0L) {
                        "Ticket #${consultation.ticketNumber}"
                    } else {
                        "Support ticket"
                    }
                    ElevatedCard(
                        onClick = { onSelected(consultation) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("safety_consultation_${consultation.ownerUid}"),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(ticketLabel, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.support_filter_plan_consults), color = OliveGreen)
                            if (showStaffName && consultation.staffName.isNotBlank()) {
                                Text(
                                    stringResource(R.string.safety_assigned_staff_value, consultation.staffName),
                                    color = Muted,
                                )
                            }
                            Text(stringResource(R.string.safety_open_anonymous_plan), color = Muted)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}
