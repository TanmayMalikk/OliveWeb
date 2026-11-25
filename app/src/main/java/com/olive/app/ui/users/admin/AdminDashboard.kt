// Admins: shows admin tools, dashboard portal, and account totals.
package com.olive.app.ui.users.admin

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveOutlinedCard as OutlinedCard
import com.olive.app.ui.shared.OliveTextButton as TextButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.R
import com.olive.app.data.account.AccountCounts
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.AdminAccountSummary
import com.olive.app.data.messaging.SUPPORT_TAG_NEEDS_ADMIN
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.AccessibleMessage
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.shared.SkeletonLayout
import com.olive.app.ui.users.safecontact.SharedEntryViewer
import com.olive.app.ui.users.shared.messaging.MessagesScreen
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.users.shared.organization.*
import com.olive.app.ui.users.staff.SafetyConsultations

private enum class AdminPage { Dashboard, Accounts, StaffManagement, Settings, Map, SafetyPlans, Messages, Emergencies }

/**
 * Admin dashboard shows this admin portal view so the user can view its details and use its actions.
 *
 * @param session Shared session view model owning application state and actions.
 */
@Composable
internal fun AdminPortal(session: SessionViewModel) {
    val uiText = rememberResourceText()
    LaunchedEffect(session.coverAccountId) { session.messages.ensureMessagingObservation() }
    var page by remember { mutableStateOf(AdminPage.Dashboard) }
    var createOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<AdminAccountSummary?>(null) }
    val (emergencies, emergencyError) = observeEmergencyAlerts(session)
    val context = LocalContext.current
    val alertPreferences = remember { context.getSharedPreferences("emergency_alerts", android.content.Context.MODE_PRIVATE) }
    val acknowledgementKey = "acknowledged_${session.coverAccountId}"
    var acknowledged by remember(session.coverAccountId) {
        mutableStateOf(alertPreferences.getStringSet(acknowledgementKey, emptySet())!!.toSet())
    }
    /**
     * Acknowledges the target.
     *
     * @param id ID supplied to [acknowledge].
     */
    fun acknowledge(id: String) {
        acknowledged = acknowledged + id
        alertPreferences.edit().putStringSet(acknowledgementKey, acknowledged).apply()
    }
    var trackingUid by remember { mutableStateOf<String?>(null) }
    val alert = emergencies.orEmpty().firstOrNull { it.resolvedAt == 0L && it.id !in acknowledged }
    val back = rememberOliveBack(enabled = page != AdminPage.Dashboard) { page = AdminPage.Dashboard }
    OliveRouteEntrance(page, back) {
        // Opens the selected admin dashboard section.
        when (page) {
            AdminPage.Dashboard -> AdminDashboard(
                session,
                onAccounts = { page = AdminPage.Accounts },
                onStaffManagement = { page = AdminPage.StaffManagement },
                onSettings = { page = AdminPage.Settings },
                onMap = { page = AdminPage.Map },
                onSafetyPlans = { page = AdminPage.SafetyPlans },
                onMessages = { page = AdminPage.Messages },
                onEmergencies = { page = AdminPage.Emergencies },
            )
            AdminPage.Accounts -> AdminAccounts(
                session,
                onBack = { page = AdminPage.Dashboard },
                onCreate = { createOpen = true },
                onDelete = { deleteTarget = it },
            )
            AdminPage.StaffManagement -> StaffManagement(
                session = session,
                onBack = { page = AdminPage.Dashboard },
            )
            AdminPage.Settings -> OrganizationSettings(
                session,
                onBack = { page = AdminPage.Dashboard },
            )
            AdminPage.Map -> OrganizationMap(session, onBack = { page = AdminPage.Dashboard; trackingUid = null }, initialFollowUid = trackingUid)
            AdminPage.Emergencies -> EmergencyResponseScreen(
                session, emergencies, emergencyError,
                onBack = { page = AdminPage.Dashboard },
                onTrack = { trackingUid = it; page = AdminPage.Map },
            )
            AdminPage.SafetyPlans -> SafetyConsultations(session, onBack = { page = AdminPage.Dashboard })
            AdminPage.Messages -> MessagesScreen(
                session,
                exit = { page = AdminPage.Dashboard },
                initialSupportFilter = SUPPORT_TAG_NEEDS_ADMIN,
            )
        }
    }
    if (alert != null) EmergencyAlertDialog(
        session = session,
        alert = alert,
        onDismiss = { acknowledge(alert.id) },
        onTrack = {
            acknowledge(alert.id)
            trackingUid = alert.ownerUid
            page = AdminPage.Map
        },
        onOpenResponse = {
            acknowledge(alert.id)
            page = AdminPage.Emergencies
        },
    )
    if (session.viewedSharedEntry != null) SharedEntryViewer(session)
    // Open the protected-account form and close it when the admin dismisses it.
    if (createOpen) CreateAccountDialog(session, onDismiss = { createOpen = false })
    deleteTarget?.let { account ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(uiText(R.string.ui_delete_value, account.username)) },
            text = { Text(uiText(R.string.delete_managed_account_warning, account.role.localizedTitle(uiText))) },
            confirmButton = {
                Button(
                    onClick = {
                        session.deleteManagedAccount(account)
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.delete_account)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * Draws the admin workspace navigation and selected section.
 *
 * @param session Shared session view model owning application state and actions.
 * @param onAccounts Callback invoked when accounts changes or occurs.
 * @param onStaffManagement Callback invoked when staff management changes or occurs.
 * @param onSettings Callback invoked when settings changes or occurs.
 * @param onMap Callback invoked when map changes or occurs.
 * @param onSafetyPlans Callback invoked when safety plans changes or occurs.
 * @param onMessages Callback invoked when messages changes or occurs.
 * @param onEmergencies Callback invoked when emergencies changes or occurs.
 */
@Composable
private fun AdminDashboard(
    session: SessionViewModel,
    onAccounts: () -> Unit,
    onStaffManagement: () -> Unit,
    onSettings: () -> Unit,
    onMap: () -> Unit,
    onSafetyPlans: () -> Unit,
    onMessages: () -> Unit,
    onEmergencies: () -> Unit,
) {
    val uiText = rememberResourceText()
    val dashboard by session.dashboardState.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(end = 56.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.olive_tree),
                    fontFamily = FontFamily.Serif,
                    fontSize = 30.sp,
                    color = OliveGreen,
                )
                Text(
                    uiText(R.string.admin_panel),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(uiText(R.string.ui_signed_in_as_value, session.coverUsername), color = Muted)
            }
            IconButton(onClick = onSettings, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Outlined.Settings, uiText(R.string.account_settings))
            }
        }
        SafeAppUpdateReminder()
        if (dashboard.adminSummaryOwner == session.coverAccountId && dashboard.adminSummaryOwner != null || session.localMode) {
            AccountCountGrid(dashboard.adminCounts)
            TicketCountGrid(dashboard.adminCounts)
        } else if (session.busy || session.message == null) {
            LoadingSkeleton(SkeletonLayout.DashboardCounts)
        } else Status(session)
        val emergencyDescription = uiText(R.string.admin_emergency_response) + ". " + uiText(R.string.admin_emergency_response_help)
        OutlinedCard(onClick = onEmergencies, modifier = Modifier.fillMaxWidth()
            .semantics { contentDescription = emergencyDescription }) {
            Column(Modifier.padding(16.dp).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    uiText(R.string.admin_emergency_response),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(uiText(R.string.admin_emergency_response_help))
                Button(onClick = onEmergencies) { Text(stringResource(R.string.emergency_response_title)) }
            }
        }
        OrganizationDashboardCard(
            Icons.Outlined.HealthAndSafety,
            stringResource(R.string.safety_inbox),
            stringResource(R.string.admin_safety_plans_description),
            onSafetyPlans,
        )
        OrganizationDashboardCard(
            Icons.Outlined.ManageAccounts,
            stringResource(R.string.accounts),
            stringResource(R.string.admin_accounts_description),
            onAccounts,
        )
        OrganizationDashboardCard(
            Icons.Outlined.Badge,
            stringResource(R.string.admin_staff_management),
            stringResource(R.string.admin_staff_management_description),
            onStaffManagement,
        )
        OrganizationDashboardCard(
            Icons.Outlined.Forum,
            stringResource(R.string.admin_escalation_chats),
            stringResource(R.string.admin_escalation_chats_description),
            onMessages,
            session.messages.pendingActionCount,
        )
        OrganizationDashboardCard(
            Icons.Outlined.LocationOn,
            stringResource(R.string.organization_live_map),
            stringResource(R.string.organization_live_map_admin_description),
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
 * Admin dashboard keeps open and closed support totals visible without opening the chat list.
 *
 * @param counts Counts supplied to [TicketCountGrid].
 */
@Composable
private fun TicketCountGrid(counts: AccountCounts) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TicketCountCard(
            label = stringResource(R.string.admin_open_tickets),
            count = counts.openTickets,
            modifier = Modifier.weight(1f),
        )
        TicketCountCard(
            label = stringResource(R.string.admin_closed_tickets),
            count = counts.closedTickets,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * One ticket card reads as a single TalkBack item instead of separate count and label items.
 *
 * @param label Accessible label or display caption.
 * @param count Count supplied to [TicketCountCard].
 * @param modifier Compose modifier applied to the root layout.
 */
@Composable
private fun TicketCountCard(label: String, count: Int, modifier: Modifier = Modifier) {
    ElevatedCard(modifier.clearAndSetSemantics { contentDescription = "$count $label" }) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(count.toString(), style = MaterialTheme.typography.headlineSmall, color = OliveGreen)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
    }
}

/**
 * Admin staff management shows each staff member's active and completed plan and support work.
 *
 * @param session Shared session view model owning application state and actions.
 * @param onBack Callback invoked when the user navigates back.
 */
@Composable
private fun StaffManagement(session: SessionViewModel, onBack: () -> Unit) {
    val uiText = rememberResourceText()
    LaunchedEffect(session.coverAccountId) { session.ensureAdminStaff() }
    val staff = session.adminStaffAccounts.filter { it.role == AccountRole.STAFF }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiText(R.string.ui_back))
            }
            Column {
                Text(
                    stringResource(R.string.admin_staff_management),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.admin_staff_management_description), color = Muted)
            }
        }
        OutlinedButton(
            onClick = session::refreshAdminStaff,
            enabled = !session.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.refresh)) }
        if (session.showAdminStaffSkeleton) LoadingSkeleton(SkeletonLayout.StaffWorkload)
        else if (staff.isEmpty() && session.adminStaffError == null) {
            Text(stringResource(R.string.admin_no_staff_accounts), color = Muted)
        }
        else staff.forEach { account -> StaffWorkloadCard(account) }
        session.adminStaffError?.let { AccessibleMessage(stringResource(it)) }
        Status(session)
    }
}

/**
 * A staff workload card summarizes plans and chats with completed counts kept beside their totals.
 *
 * @param account Account supplied to [StaffWorkloadCard].
 */
@Composable
private fun StaffWorkloadCard(account: AdminAccountSummary) {
    val summary = pluralStringResource(
        R.plurals.admin_staff_workload_accessibility,
        account.safetyPlans,
        account.username,
        account.safetyPlans,
        account.completedSafetyPlans,
        account.supportChats,
        account.closedSupportChats,
    )
    OutlinedCard(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = summary }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(account.username, style = MaterialTheme.typography.titleLarge)
            account.fullName.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted) }
            HorizontalDivider()
            WorkloadRow(
                stringResource(R.string.admin_safety_plan_tasks),
                account.safetyPlans,
                account.completedSafetyPlans,
                stringResource(R.string.admin_completed),
            )
            WorkloadRow(
                stringResource(R.string.admin_support_chat_tasks),
                account.supportChats,
                account.closedSupportChats,
                stringResource(R.string.admin_closed),
            )
        }
    }
}

/**
 * Each workload row keeps its total and finished count together for quick comparison.
 *
 * @param label Accessible label or display caption.
 * @param total Total supplied to [WorkloadRow].
 * @param finished Finished supplied to [WorkloadRow].
 * @param finishedLabel Finished label supplied to [WorkloadRow].
 */
@Composable
private fun WorkloadRow(label: String, total: Int, finished: Int, finishedLabel: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text("$total ($finished $finishedLabel)", color = OliveGreen)
    }
}
