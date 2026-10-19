package com.olive.app.ui
import com.olive.app.R
import androidx.compose.ui.res.stringResource

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.olive.app.ui.LocalizedIcon as Icon
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

private enum class OrganizationPage { Dashboard, Accounts, Settings, SafetyPlans }

// STAFF + ADMIN: Olive Tree is the organization workspace. Only Admin accounts receive account controls.
@Composable internal fun OrganizationPortal(session:SessionViewModel) {
    val uiText=rememberResourceText()
    val admin=session.accountRole==AccountRole.ADMIN
    var page by remember { mutableStateOf(OrganizationPage.Dashboard) }
    var createOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<AdminAccountSummary?>(null) }
    BackHandler(enabled=page!=OrganizationPage.Dashboard) { page=OrganizationPage.Dashboard }
    when(page) {
        OrganizationPage.Dashboard -> OrganizationDashboard(session,admin,onAccounts={page=OrganizationPage.Accounts},onSettings={page=OrganizationPage.Settings},onSafetyPlans={page=OrganizationPage.SafetyPlans})
        OrganizationPage.Accounts -> OrganizationAccounts(session,onBack={page=OrganizationPage.Dashboard},onCreate={createOpen=true},onDelete={deleteTarget=it})
        OrganizationPage.Settings -> OrganizationSettings(session,onBack={page=OrganizationPage.Dashboard})
        OrganizationPage.SafetyPlans -> SafetyConsultationsScreen(session,onBack={page=OrganizationPage.Dashboard})
    }
    if(createOpen) CreateAccountDialog(session,onDismiss={createOpen=false})
    deleteTarget?.let { account -> AlertDialog(onDismissRequest={deleteTarget=null},title={Text(uiText(R.string.ui_delete_value , account.username))},
        text={Text(uiText(R.string.delete_managed_account_warning , account.role.localizedTitle(uiText)))},
        confirmButton={Button(onClick={session.deleteManagedAccount(account);deleteTarget=null},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text(stringResource(R.string.delete_account))}},
        dismissButton={TextButton(onClick={deleteTarget=null}){Text(stringResource(R.string.cancel))}}) }
}

@Composable private fun OrganizationDashboard(session:SessionViewModel,admin:Boolean,onAccounts:()->Unit,onSettings:()->Unit,onSafetyPlans:()->Unit) {
    val uiText=rememberResourceText()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(end=56.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.olive_tree),fontFamily=FontFamily.Serif,fontSize=30.sp,color=OliveGreen)
                Text(if(admin) uiText(R.string.admin_panel) else uiText(R.string.staff_panel),style=MaterialTheme.typography.headlineMedium,modifier=Modifier.semantics { heading() })
                Text(uiText(R.string.ui_signed_in_as_value , session.coverUsername),color=Muted)
            }
            IconButton(onClick=onSettings,modifier=Modifier.align(Alignment.TopEnd)) { Icon(Icons.Outlined.Settings,uiText(R.string.account_settings)) }
        }
        if(admin) AccountCountGrid(session.adminCounts)
        if(!admin) Button(onClick=onSafetyPlans,modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.safety_inbox)) }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            Text(uiText(R.string.ui_active_chats_with_staff_and_users),style=MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.in_development),color=Muted)
        } }
        if(admin) Button(onClick=onAccounts,modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.accounts)) }
        Status(session)
        OutlinedButton(onClick=session::signOut,enabled=!session.busy,modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.sign_out)) }
    }
}

@Composable private fun OrganizationAccounts(session:SessionViewModel,onBack:()->Unit,onCreate:()->Unit,onDelete:(AdminAccountSummary)->Unit) {
    val uiText=rememberResourceText()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,uiText(R.string.ui_back)) }
            Text(stringResource(R.string.accounts),style=MaterialTheme.typography.headlineMedium,modifier=Modifier.semantics { heading() })
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Button(onClick=onCreate,enabled=!session.busy,modifier=Modifier.weight(1f)) { Text(stringResource(R.string.create_account)) }
            OutlinedButton(onClick=session::refreshAdminAccounts,enabled=!session.busy,modifier=Modifier.weight(1f)) { Text(stringResource(R.string.refresh)) }
        }
        if(session.adminAccounts.isEmpty() && !session.busy) Text(uiText(R.string.ui_no_accounts_found),color=Muted)
        session.adminAccounts.forEach { account -> AccountRow(account,!session.busy) { onDelete(account) } }
        Status(session)
    }
}

@Composable private fun OrganizationSettings(session:SessionViewModel,onBack:()->Unit) {
    val uiText=rememberResourceText()
    val profile=session.profile ?: return
    var fullName by remember(profile) { mutableStateOf(profile.fullName) }
    var dob by remember(profile) { mutableStateOf(profile.dob) }
    var email by remember(profile) { mutableStateOf(profile.email) }
    var phone by remember(profile) { mutableStateOf(profile.phone) }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=18.dp).padding(bottom=40.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,uiText(R.string.ui_back)) }
            Text(stringResource(R.string.account_settings),style=MaterialTheme.typography.headlineMedium,modifier=Modifier.semantics { heading() })
        }
        Text(uiText(R.string.ui_profile_information),style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics { heading() })
        Field(uiText(R.string.full_name),fullName,"organization_settings_name",enabled=!session.busy) { fullName=it.take(120) }
        BirthdayPicker(dob,"organization_settings_dob",!session.busy) { dob=it }
        Field(uiText(R.string.ui_email),email,"organization_settings_email",enabled=!session.busy) { email=it.take(254) }
        Field(uiText(R.string.ui_phone_optional),phone,"organization_settings_phone",enabled=!session.busy) { phone=it.take(40) }
        Button(onClick={session.updateOrganizationProfile(fullName,dob,email,phone)},enabled=!session.busy,modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_profile)) }
        HorizontalDivider()
        Text(stringResource(R.string.change_password),style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics { heading() })
        Field(uiText(R.string.current_password),currentPassword,"organization_current_password",secret=true,showSecretToggle=true,enabled=!session.busy) { currentPassword=it.take(128) }
        Field(uiText(R.string.new_password),newPassword,"organization_new_password",secret=true,showSecretToggle=true,enabled=!session.busy) { newPassword=it.take(24) }
        Field(uiText(R.string.confirm_new_password),confirmation,"organization_confirm_password",secret=true,showSecretToggle=true,enabled=!session.busy) { confirmation=it.take(24) }
        Button(onClick={session.changePassword(currentPassword,newPassword,confirmation)},enabled=!session.busy,modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.change_password)) }
        Status(session)
    }
}

// UI sizing/color: compact count cards use smaller padding and text while staying two-wide on narrow phones.
@Composable private fun AccountCountGrid(counts:AccountCounts) {
    val uiText=rememberResourceText()
    val values=listOf(uiText(R.string.ui_users) to counts.users,uiText(R.string.ui_safe_contacts) to counts.safeContacts,uiText(R.string.staff) to counts.staff,uiText(R.string.ui_admins) to counts.admins)
    values.chunked(2).forEach { row -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        row.forEach { (label,count) -> Card(Modifier.weight(1f)) { Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(count.toString(),style=MaterialTheme.typography.titleLarge,color=OliveGreen,modifier=Modifier.semantics { heading() })
            Text(label,style=MaterialTheme.typography.bodyMedium)
        } } }
    } }
}

@Composable private fun AccountRow(account:AdminAccountSummary,enabled:Boolean,onDelete:()->Unit) {
    val uiText=rememberResourceText()
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(account.username,style=MaterialTheme.typography.titleMedium)
                Text(account.role.localizedTitle(uiText) + if(account.profileComplete) uiText(R.string.ui_setup_complete) else uiText(R.string.ui_setup_required),color=Muted)
                if(account.fullName.isNotBlank()) Text(account.fullName)
                if(account.email.isNotBlank()) Text(account.email,style=MaterialTheme.typography.bodySmall,color=Muted)
            }
            TextButton(onClick=onDelete,enabled=enabled && !account.protected) { Text(if(account.protected) uiText(R.string.ui_protected) else uiText(R.string.ui_delete)) }
        }
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CreateAccountDialog(session:SessionViewModel,onDismiss:()->Unit) {
    val uiText=rememberResourceText()
    var role by remember { mutableStateOf(AccountRole.USER) }
    var roleOpen by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") };var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") };var dob by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") };var phone by remember { mutableStateOf("") }
    val language=AppLocaleManager.resolveSystemDefaultLanguageName()
    var city by remember { mutableStateOf("") }
    var affected by remember { mutableStateOf("") };var abuser by remember { mutableStateOf("") };var situation by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.create_account))},text={
        Column(Modifier.fillMaxWidth().heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            ExposedDropdownMenuBox(expanded=roleOpen,onExpandedChange={roleOpen=!roleOpen}) {
                OutlinedTextField(role.localizedTitle(uiText),{},readOnly=true,label={Text(stringResource(R.string.account_type))},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(roleOpen)},modifier=Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
                ExposedDropdownMenu(roleOpen,{roleOpen=false}) { AccountRole.entries.forEach { item -> DropdownMenuItem(text={Text(item.localizedTitle(uiText))},onClick={role=item;roleOpen=false}) } }
            }
            Field(uiText(R.string.username),username,"admin_username") { username=it.take(24) }
            Field(uiText(R.string.ui_temporary_password),password,"admin_password",secret=true,showSecretToggle=true) { password=it.take(24) }
            HorizontalDivider();Text(uiText(R.string.ui_optional_onboarding_details),style=MaterialTheme.typography.titleMedium)
            Field(uiText(R.string.full_name),fullName,"admin_full_name") { fullName=it.take(120) }
            BirthdayPicker(dob,"admin_dob",true) { dob=it }
            if(role in setOf(AccountRole.STAFF,AccountRole.ADMIN)) {
                Field(uiText(R.string.ui_email),email,"admin_email") { email=it.take(254) }
                Field(uiText(R.string.ui_phone_optional),phone,"admin_phone") { phone=it.take(40) }
                Text(uiText(R.string.ui_leave_required_details_blank_to_have_this_person_finish_them_on_f),style=MaterialTheme.typography.bodySmall,color=Muted)
            } else {
                Field(uiText(R.string.city),city,"admin_city") { city=it.take(160) }
                if(role==AccountRole.USER) {
                    Field(uiText(R.string.names_of_people_experiencing_abuse_optional),affected,"admin_affected") { affected=it.take(500) }
                    Field(uiText(R.string.abuser_s_name_optional),abuser,"admin_abuser") { abuser=it.take(120) }
                    Field(uiText(R.string.ui_situation_optional),situation,"admin_situation",multiline=true) { situation=it.take(2000) }
                    Text(uiText(R.string.ui_the_user_sets_their_own_trigger_word_and_device_pin_on_first_sign),style=MaterialTheme.typography.bodySmall,color=Muted)
                }
            }
        }
    },confirmButton={Button(onClick={session.createManagedAccount(AdminAccountDraft(username,password,role,fullName,dob,email,phone,language,city,affected,abuser,situation));onDismiss()}){Text(stringResource(R.string.create))}},
        dismissButton={TextButton(onClick=onDismiss){Text(stringResource(R.string.cancel))}})
}
