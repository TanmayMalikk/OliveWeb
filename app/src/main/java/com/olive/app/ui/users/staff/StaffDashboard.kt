// Staff: shows assigned users and support tickets.
package com.olive.app.ui.users.staff

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveOutlinedCard as OutlinedCard
import com.olive.app.ui.shared.OliveTextButton as TextButton
import com.olive.app.ui.shared.*
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.shared.organization.OrganizationSettings
import com.olive.app.ui.users.shared.organization.OrganizationDashboardCard
import com.olive.app.ui.users.shared.organization.OrganizationMap
import com.olive.app.ui.users.shared.messaging.MessagesScreen
import com.olive.app.ui.users.safecontact.SharedEntryViewer
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.olive.app.data.account.AccountRole
import com.olive.app.data.safety.SafetyConsultation

private enum class StaffPage { Dashboard, Settings, SafetyPlans, Messages, Map }

/**
 * Shows the staff workspace with assigned users, tickets, map, and settings.
 *
 * @param session Shared session view model owning application state and actions.
 */
@Composable
internal fun StaffPortal(session: SessionViewModel) {
    LaunchedEffect(session.coverAccountId) { session.messages.ensureMessagingObservation() }
    var page by remember { mutableStateOf(StaffPage.Dashboard) }
    val back = rememberOliveBack(enabled = page != StaffPage.Dashboard) { page = StaffPage.Dashboard }
    OliveRouteEntrance(page, back) {
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
    }
    if (session.viewedSharedEntry != null) SharedEntryViewer(session)
}

/**
 * Draws the staff workspace navigation and selected section.
 *
 * @param session Shared session view model owning application state and actions.
 * @param onSettings Callback invoked when settings changes or occurs.
 * @param onSafetyPlans Callback invoked when safety plans changes or occurs.
 * @param onMessages Callback invoked when messages changes or occurs.
 * @param onMap Callback invoked when map changes or occurs.
 */
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
            Column(Modifier.padding(end = if (LocalSafeUpdateReminder.current == null) 56.dp else 104.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
            Row(Modifier.align(Alignment.TopEnd)) {
                SafeAppUpdateReminder()
                IconButton(onClick = onSettings) {
                    Icon(Icons.Outlined.Settings, uiText(R.string.account_settings))
                }
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
            modifier = Modifier.fillMaxWidth().testTag("sign_out"),
        ) { Text(stringResource(R.string.sign_out)) }
    }
}

/**
 * Staff: shows safety-plan consultation requests to staff. Lists the plan-consult tickets assigned to this staff member.
 *
 * @param session Shared session view model owning application state and actions.
 * @param onBack Callback invoked when the user navigates back.
 */
@Composable
internal fun SafetyConsultations(session: SessionViewModel, onBack: () -> Unit) {
    var selected by remember { mutableStateOf<SafetyConsultation?>(null) }
    var completionTarget by remember { mutableStateOf<SafetyConsultation?>(null) }
    var assignedExpanded by rememberSaveable { mutableStateOf(true) }
    var waitingExpanded by rememberSaveable { mutableStateOf(true) }
    val controller = session.safetyPlans
    DisposableEffect(session.coverAccountId) {
        onDispose { controller.close(preserveAdminIndex = session.accountRole == AccountRole.ADMIN) }
    }
    val back = rememberOliveBack(enabled = selected != null) { selected = null }
    val plan = selected
    if (plan != null) {
        val ticketLabel = if (plan.ticketNumber >= 0L) "Consult Ticket #${plan.ticketNumber}" else "Consult Ticket"
        OliveRouteEntrance(plan.ownerUid, back) {
            SafetyPlanScreen(
                session = session,
                ownerUid = plan.ownerUid,
                ownerName = plan.ownerName,
                displayOwnerName = ticketLabel,
                onCompleteConsultation = if (
                    session.accountRole == AccountRole.STAFF &&
                    plan.conversationId.isNotBlank()
                ) ({ completionTarget = plan }) else null,
                onBack = { selected = null },
            )
        }
        completionTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { completionTarget = null },
                title = { Text(stringResource(R.string.safety_complete_consultation_title)) },
                text = { Text(stringResource(R.string.safety_complete_consultation_description)) },
                confirmButton = {
                    Button(
                        onClick = {
                            completionTarget = null
                            session.messages.closeSupportChat(target.conversationId, planCompleted = true) {
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
        controller.observeAccess()
        controller.refreshConsultations(force = false)
    }
    FormColumn {
        TextButton(onClick = onBack) { Text(stringResource(R.string.safety_back)) }
        Heading(
            stringResource(R.string.safety_inbox),
            stringResource(if (session.accountRole == AccountRole.ADMIN) R.string.safety_admin_intro else R.string.safety_staff_intro),
            stringResource(R.string.olive_tree),
        )
        if (controller.loadingAccess && !session.localMode) {
            LoadingSkeleton(SkeletonLayout.SharedPlans)
            return@FormColumn
        }
        if (session.accountRole == AccountRole.STAFF) {
            val availabilityLabel = stringResource(R.string.safety_available)
            Row(Modifier.fillMaxWidth().testTag("safety_staff_available")
                .toggleable(
                    value = controller.available,
                    enabled = !controller.savingAvailability,
                    role = Role.Switch,
                    onValueChange = controller::changeAvailability,
                )
                .semantics(mergeDescendants = true) { contentDescription = availabilityLabel },
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(availabilityLabel, Modifier.weight(1f).clearAndSetSemantics { })
                Switch(
                    checked = controller.available,
                    onCheckedChange = null,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
        controller.error?.let { AccessibleMessage(stringResource(it)) }
        if (controller.consultations.isEmpty() && controller.error == null) Text(stringResource(R.string.safety_inbox_empty))
        val assigned = controller.consultations.filter { it.staffUid.isNotBlank() }
        val waiting = controller.consultations.filter { it.staffUid.isBlank() }
        ConsultationGroup(
            title = stringResource(
                if (session.accountRole == AccountRole.ADMIN)
                    R.string.safety_assigned_to_staff else R.string.safety_assigned_to_you
            ),
            consultations = assigned,
            expanded = assignedExpanded,
            onExpandedChange = { assignedExpanded = it },
            showStaffName = session.accountRole == AccountRole.ADMIN,
            onSelected = { selected = it },
        )
        if (session.accountRole == AccountRole.ADMIN) {
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

/**
 * Staff and admins use the same collapsible consultation list so shared plan work looks consistent.
 *
 * @param title Primary display title text.
 * @param consultations Collection of consultations values to process.
 * @param expanded Whether the expandable section is currently open.
 * @param onExpandedChange Callback invoked when expanded change changes or occurs.
 * @param showStaffName Whether to show staff name.
 * @param onSelected Callback invoked when selected changes or occurs.
 */
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
                        "Consult Ticket #${consultation.ticketNumber}"
                    } else {
                        "Consult Ticket"
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
