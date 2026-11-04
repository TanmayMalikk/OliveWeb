// Admins: shows admin tools and account totals.
package com.olive.app.ui.users.admin

import com.olive.app.ui.shared.*


import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.ui.users.shared.organization.OrganizationSettings
import com.olive.app.ui.users.shared.organization.OrganizationMap
import com.olive.app.ui.users.shared.organization.OrganizationDashboardCard
import com.olive.app.ui.users.staff.SafetyConsultations
import com.olive.app.ui.users.shared.messaging.MessagesScreen
import com.olive.app.ui.users.safecontact.SharedEntryViewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.R
import com.olive.app.data.AdminAccountSummary
import com.olive.app.ui.shared.LocalizedIcon as Icon

private enum class AdminPage { Dashboard, Accounts, Settings, Map, SafetyPlans, Messages }

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
        AdminPage.Settings -> OrganizationSettings(
            session,
            onBack = { page = AdminPage.Dashboard },
        )
        AdminPage.Map -> OrganizationMap(session, onBack = { page = AdminPage.Dashboard })
        AdminPage.SafetyPlans -> SafetyConsultations(session, onBack = { page = AdminPage.Dashboard })
        AdminPage.Messages -> MessagesScreen(session, exit = { page = AdminPage.Dashboard })
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
            Icons.Outlined.Forum,
            stringResource(R.string.support_conversations),
            stringResource(R.string.staff_conversations_assigned_help),
            onMessages,
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
