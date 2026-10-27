// Contains the admin accounts code for this part of Olive.
package com.olive.app.ui.users.admin.portal

import com.olive.app.ui.shared.*


import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.ui.users.shared.onboarding.BirthdayPicker

import com.olive.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.olive.app.ui.shared.LocalizedIcon as Icon
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.data.*
import com.olive.app.ui.users.shared.messaging.PrivateContactNoteDialog


@Composable
internal fun AdminAccounts(
    session: SessionViewModel,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onDelete: (AdminAccountSummary) -> Unit
) {
    val uiText = rememberResourceText()
    var noteTarget by remember { mutableStateOf<DirectoryUser?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
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
        if (session.adminAccounts.isEmpty() && !session.busy) Text(
            uiText(R.string.ui_no_accounts_found),
            color = Muted
        )
        session.adminAccounts.forEach { account ->
            AccountRow(
                account,
                !session.busy,
                onNote = if (account.role == AccountRole.USER) {
                    { noteTarget = DirectoryUser(account.uid, account.username) }
                } else null,
                onDelete = { onDelete(account) },
            )
        }
        Status(session)
    }
    noteTarget?.let { target ->
        PrivateContactNoteDialog(session, listOf(target), onDismiss = { noteTarget = null })
    }
}

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
                Card(Modifier.weight(1f)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            count.toString(),
                            style = MaterialTheme.typography.titleLarge,
                            color = OliveGreen,
                            modifier = Modifier.semantics { heading() })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    account: AdminAccountSummary,
    enabled: Boolean,
    onNote: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val uiText = rememberResourceText()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(account.username, style = MaterialTheme.typography.titleMedium)
                    Text(
                        account.role.localizedTitle(uiText) + if (account.profileComplete) uiText(R.string.ui_setup_complete) else uiText(
                            R.string.ui_setup_required
                        ), color = Muted
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
                TextButton(
                    onClick = onDelete,
                    enabled = enabled && !account.protected
                ) { Text(if (account.protected) uiText(R.string.ui_protected) else uiText(R.string.ui_delete)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
// Creates account dialog.
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
                if (role in setOf(AccountRole.STAFF, AccountRole.ADMIN)) {
                    Field(uiText(R.string.ui_email), email, "admin_email") { email = it.take(254) }
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
                        username,
                        password,
                        role,
                        fullName,
                        dob,
                        email,
                        phone,
                        city,
                        affected,
                        abuser,
                        situation
                    )
                )
                onDismiss()
            }) { Text(stringResource(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
