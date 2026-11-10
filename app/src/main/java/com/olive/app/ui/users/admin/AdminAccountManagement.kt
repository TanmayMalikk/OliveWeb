// Admins: shows account records and controls for admins.
package com.olive.app.ui.users.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.R
import com.olive.app.data.*
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.users.safecontact.SharedEntryViewer
import com.olive.app.ui.users.shared.messaging.MessagesScreen
import com.olive.app.ui.users.shared.messaging.PrivateContactNoteDialog
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.shared.onboarding.BirthdayPicker
import com.olive.app.ui.users.shared.organization.*
import com.olive.app.ui.users.staff.SafetyConsultations



// admin account management shows this admin accounts view so the user can view its details and use its actions.
@Composable
internal fun AdminAccounts(
    session: SessionViewModel,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onDelete: (AdminAccountSummary) -> Unit
) {
    val uiText = rememberResourceText()
    LaunchedEffect(Unit) { session.refreshAdminAccounts() }
    var noteTarget by remember { mutableStateOf<DirectoryUser?>(null) }
    val expandedRoles = remember {
        mutableStateMapOf<AccountRole, Boolean>().apply {
            AccountRole.entries.forEach { role -> put(role, true) }
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item("account_header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        uiText(R.string.ui_back)
                    )
                }
                Text(
                    stringResource(R.string.accounts),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() })
            }
        }
        item("account_actions") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onCreate,
                    enabled = !session.busy,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.create_account)) }
                OutlinedButton(
                    onClick = session::refreshAdminAccounts,
                    enabled = !session.busy,
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.refresh)) }
            }
        }
        if (session.adminAccounts.isEmpty() && !session.busy) item("no_accounts") {
            Text(uiText(R.string.ui_no_accounts_found), color = Muted)
        }
        AccountRole.entries.forEach { role ->
            val accounts = session.adminAccounts.filter { it.role == role }
            item("role_${role.name}") {
                AccountRoleHeader(
                    role = role,
                    accountCount = accounts.size,
                    expanded = expandedRoles[role] != false,
                    onExpandedChange = { expandedRoles[role] = it },
                )
            }
            if (expandedRoles[role] != false && accounts.isEmpty()) item("empty_${role.name}") {
                Text(
                    stringResource(R.string.admin_no_accounts_in_category),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = Muted,
                )
            }
            if (expandedRoles[role] != false) items(accounts, key = { it.uid }) { account ->
                AccountRow(
                    account = account,
                    enabled = !session.busy,
                    onNote = if (role == AccountRole.USER) ({
                        noteTarget = DirectoryUser(account.uid, account.username)
                    }) else null,
                    onDelete = { onDelete(account) },
                )
            }
        }
        item("account_status") { Status(session) }
    }
    noteTarget?.let { target ->
        PrivateContactNoteDialog(session, listOf(target), onDismiss = { noteTarget = null })
    }
}

// Admin account management groups accounts by role so a large organization list stays easy to scan.
@Composable
private fun AccountRoleHeader(
    role: AccountRole,
    accountCount: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    val uiText = rememberResourceText()
    OutlinedCard(Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "${role.localizedTitle(uiText)} ($accountCount)",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
            )
        }
    }
}
// Shows user, trusted-contact, staff, and admin totals as accessible cards.
@Composable
internal fun AccountCountGrid(counts: AccountCounts) {
    val uiText = rememberResourceText()
    val values = listOf(
        uiText(R.string.ui_users) to counts.users,
        uiText(R.string.ui_safe_contacts) to counts.safeContacts,
        uiText(R.string.staff) to counts.staff,
        uiText(R.string.ui_admins) to counts.admins
    )
    values.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { (label, count) ->
                Card(Modifier.weight(1f).clearAndSetSemantics {
                    contentDescription = "$count $label"
                }) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            count.toString(),
                            style = MaterialTheme.typography.titleLarge,
                            color = OliveGreen,
                        )
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}
// Shows one managed account and the actions allowed for it.
@Composable
private fun AccountRow(
    account: AdminAccountSummary,
    enabled: Boolean,
    onNote: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val uiText = rememberResourceText()
    val roleStatus = account.role.localizedTitle(uiText) +
        if (account.profileComplete) uiText(R.string.ui_setup_complete) else uiText(R.string.ui_setup_required)
    val accountSummary = listOfNotNull(
        account.username,
        roleStatus,
        account.fullName.takeIf { it.isNotBlank() },
        if (account.role == AccountRole.STAFF) uiText(R.string.assigned_users_count, account.assignedUsers) else null,
        account.email.takeIf { it.isNotBlank() },
        uiText(R.string.ui_protected).takeIf { account.protected },
    ).joinToString(", ")
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clearAndSetSemantics {
                    contentDescription = accountSummary
                }) {
                    Text(account.username, style = MaterialTheme.typography.titleMedium)
                    Text(
                        roleStatus, color = Muted
                    )
                    if (account.fullName.isNotBlank()) Text(account.fullName)
                    if (account.role == com.olive.app.data.AccountRole.STAFF) Text(
                        uiText(R.string.assigned_users_count, account.assignedUsers),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                    if (account.email.isNotBlank()) Text(
                        account.email,
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted
                    )
                }
                if (onNote != null) TextButton(
                    onClick = onNote,
                    enabled = enabled,
                ) { Text(stringResource(R.string.private_note)) }
                if (!account.protected) TextButton(
                    onClick = onDelete,
                    enabled = enabled
                ) { Text(uiText(R.string.ui_delete)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// Collects and validates the details an admin uses to create a managed staff or admin account.
internal fun CreateAccountDialog(session: SessionViewModel, onDismiss: () -> Unit) {
    val uiText = rememberResourceText()
    var role by remember { mutableStateOf(AccountRole.USER) }
    var roleOpen by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var affected by remember { mutableStateOf("") }
    var abuser by remember { mutableStateOf("") }
    var situation by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_account)) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ExposedDropdownMenuBox(
                    expanded = roleOpen,
                    onExpandedChange = { roleOpen = !roleOpen }) {
                    OutlinedTextField(
                        role.localizedTitle(uiText),
                        {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.account_type)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(roleOpen) },
                        modifier = Modifier.fillMaxWidth()
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        roleOpen,
                        {
                            roleOpen = false
                        }) {
                        AccountRole.entries.forEach { item ->
                            DropdownMenuItem(text = {
                                Text(
                                    item.localizedTitle(uiText)
                                )
                            }, onClick = {
                                role = item
                                roleOpen = false
                            })
                        }
                    }
                }
                Field(uiText(R.string.username), username, "admin_username") {
                    username = it.take(24)
                }
                Field(
                    uiText(R.string.ui_temporary_password),
                    password,
                    "admin_password",
                    secret = true,
                    showSecretToggle = true
                ) { password = it.take(24) }
                HorizontalDivider()
                Text(
                    uiText(R.string.ui_optional_onboarding_details),
                    style = MaterialTheme.typography.titleMedium
                )
                Field(uiText(R.string.full_name), fullName, "admin_full_name") {
                    fullName = it.take(120)
                }
                BirthdayPicker(dob, "admin_dob", true) { dob = it }
                // Only staff and admin accounts can be protected from deletion.
                if (role in setOf(AccountRole.STAFF, AccountRole.ADMIN)) {
                    Field(uiText(R.string.ui_login_email_optional), email, "admin_email") {
                        email = it.take(254)
                    }
                    Text(
                        uiText(R.string.ui_login_email_creation_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted
                    )
                    Field(uiText(R.string.ui_phone_optional), phone, "admin_phone") {
                        phone = it.take(40)
                    }
                    Text(
                        uiText(R.string.ui_leave_required_details_blank_to_have_this_person_finish_them_on_f),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted
                    )
                } else {
                    Field(uiText(R.string.city), city, "admin_city") { city = it.take(160) }
                    if (role == AccountRole.USER) {
                        Field(
                            uiText(R.string.names_of_people_experiencing_abuse_optional),
                            affected,
                            "admin_affected"
                        ) { affected = it.take(500) }
                        Field(
                            uiText(R.string.abuser_s_name_optional),
                            abuser,
                            "admin_abuser"
                        ) { abuser = it.take(120) }
                        Field(
                            uiText(R.string.ui_situation_optional),
                            situation,
                            "admin_situation",
                            multiline = true
                        ) { situation = it.take(2000) }
                        Text(
                            uiText(R.string.ui_the_user_sets_their_own_trigger_word_and_device_pin_on_first_sign),
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                session.createManagedAccount(
                    AdminAccountDraft(
                        username = username,
                        password = password,
                        role = role,
                        fullName = fullName,
                        dob = dob,
                        email = email,
                        phone = phone,
                        city = city,
                        affectedNames = affected,
                        abuserName = abuser,
                        situation = situation
                    )
                )
                onDismiss()
            }) { Text(stringResource(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

// Admins: shows admin tools and account totals.

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
