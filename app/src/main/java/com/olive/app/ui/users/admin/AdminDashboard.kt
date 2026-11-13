// Admins: shows admin tools, dashboard portal, and account totals.
package com.olive.app.ui.users.admin

import androidx.activity.compose.BackHandler
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
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.users.safecontact.SharedEntryViewer
import com.olive.app.ui.users.shared.messaging.MessagesScreen
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.shared.organization.*
import com.olive.app.ui.users.staff.SafetyConsultations

private enum class AdminPage { Dashboard, Accounts, StaffManagement, Settings, Map, SafetyPlans, Messages }

// admin dashboard shows this admin portal view so the user can view its details and use its actions.
@Composable
internal fun AdminPortal(session: SessionViewModel) {
    val uiText = rememberResourceText()
    LaunchedEffect(session.coverAccountId) { session.messages.ensureMessagingObservation() }
    var page by remember { mutableStateOf(AdminPage.Dashboard) }
    var createOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<AdminAccountSummary?>(null) }
    BackHandler(enabled = page != AdminPage.Dashboard) { page = AdminPage.Dashboard }
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
        AdminPage.Map -> OrganizationMap(session, onBack = { page = AdminPage.Dashboard })
        AdminPage.SafetyPlans -> SafetyConsultations(session, onBack = { page = AdminPage.Dashboard })
        AdminPage.Messages -> MessagesScreen(
            session,
            exit = { page = AdminPage.Dashboard },
            initialSupportFilter = SUPPORT_TAG_NEEDS_ADMIN,
        )
    }
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

// Draws the admin workspace navigation and selected section.
@Composable
private fun AdminDashboard(
    session: SessionViewModel,
    onAccounts: () -> Unit,
    onStaffManagement: () -> Unit,
    onSettings: () -> Unit,
    onMap: () -> Unit,
    onSafetyPlans: () -> Unit,
    onMessages: () -> Unit,
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
        AccountCountGrid(session.adminCounts)
        TicketCountGrid(session.adminCounts)
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
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.sign_out)) }
    }
}

// Admin dashboard keeps open and closed support totals visible without opening the chat list.
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

// One ticket card reads as a single TalkBack item instead of separate count and label items.
@Composable
private fun TicketCountCard(label: String, count: Int, modifier: Modifier = Modifier) {
    ElevatedCard(modifier.clearAndSetSemantics { contentDescription = "$count $label" }) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(count.toString(), style = MaterialTheme.typography.headlineSmall, color = OliveGreen)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
    }
}

// Admin staff management shows each staff member's active and completed plan and support work.
@Composable
private fun StaffManagement(session: SessionViewModel, onBack: () -> Unit) {
    val uiText = rememberResourceText()
    LaunchedEffect(Unit) { session.refreshAdminStaff() }
    val staff = session.adminAccounts.filter { it.role == AccountRole.STAFF }
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
        if (staff.isEmpty() && !session.busy) {
            Text(stringResource(R.string.admin_no_staff_accounts), color = Muted)
        }
        staff.forEach { account -> StaffWorkloadCard(account) }
        Status(session)
    }
}

// A staff workload card summarizes plans and chats with completed counts kept beside their totals.
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

// Each workload row keeps its total and finished count together for quick comparison.
@Composable
private fun WorkloadRow(label: String, total: Int, finished: Int, finishedLabel: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text("$total ($finished $finishedLabel)", color = OliveGreen)
    }
}


