package com.olive.app.ui

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

private enum class OrganizationPage { Dashboard, Accounts, Settings }

// STAFF + ADMIN: Olive Tree is the organization workspace. Only Admin accounts receive account controls.
@Composable internal fun OrganizationPortal(session:SessionViewModel) {
    val admin=session.accountRole==AccountRole.ADMIN
    var page by remember { mutableStateOf(OrganizationPage.Dashboard) }
    var createOpen by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<AdminAccountSummary?>(null) }
    BackHandler(enabled=page!=OrganizationPage.Dashboard) { page=OrganizationPage.Dashboard }
    when(page) {
        OrganizationPage.Dashboard -> OrganizationDashboard(session,admin,onAccounts={page=OrganizationPage.Accounts},onSettings={page=OrganizationPage.Settings})
        OrganizationPage.Accounts -> OrganizationAccounts(session,onBack={page=OrganizationPage.Dashboard},onCreate={createOpen=true},onDelete={deleteTarget=it})
        OrganizationPage.Settings -> OrganizationSettings(session,onBack={page=OrganizationPage.Dashboard})
    }
    if(createOpen) CreateAccountDialog(session,onDismiss={createOpen=false})
    deleteTarget?.let { account -> AlertDialog(onDismissRequest={deleteTarget=null},title={LocalizedText("Delete ${account.username}?")},
        text={LocalizedText("This permanently removes the ${account.role.title} account from Firebase Authentication and deletes its account data.")},
        confirmButton={Button(onClick={session.deleteManagedAccount(account);deleteTarget=null},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){LocalizedText("Delete account")}},
        dismissButton={TextButton(onClick={deleteTarget=null}){LocalizedText("Cancel")}}) }
}

@Composable private fun OrganizationDashboard(session:SessionViewModel,admin:Boolean,onAccounts:()->Unit,onSettings:()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(end=56.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                LocalizedText("Olive Tree",fontFamily=FontFamily.Serif,fontSize=30.sp,color=OliveGreen)
                LocalizedText(if(admin) "Admin panel" else "Staff panel",style=MaterialTheme.typography.headlineMedium)
                LocalizedText("Signed in as ${session.coverUsername}",color=Muted)
            }
            IconButton(onClick=onSettings,modifier=Modifier.align(Alignment.TopEnd)) { Icon(Icons.Outlined.Settings,"Account settings") }
        }
        if(admin) AccountCountGrid(session.adminCounts)
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            LocalizedText("Active chats with staff and users",style=MaterialTheme.typography.titleMedium)
            LocalizedText("In development",color=Muted)
        } }
        if(admin) Button(onClick=onAccounts,modifier=Modifier.fillMaxWidth()) { LocalizedText("Accounts") }
        Status(session)
        OutlinedButton(onClick=session::signOut,enabled=!session.busy,modifier=Modifier.fillMaxWidth()) { LocalizedText("Sign out") }
    }
}

@Composable private fun OrganizationAccounts(session:SessionViewModel,onBack:()->Unit,onCreate:()->Unit,onDelete:(AdminAccountSummary)->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back") }
            LocalizedText("Accounts",style=MaterialTheme.typography.headlineMedium)
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Button(onClick=onCreate,enabled=!session.busy,modifier=Modifier.weight(1f)) { LocalizedText("Create account") }
            OutlinedButton(onClick=session::refreshAdminAccounts,enabled=!session.busy,modifier=Modifier.weight(1f)) { LocalizedText("Refresh") }
        }
        if(session.adminAccounts.isEmpty() && !session.busy) LocalizedText("No accounts found.",color=Muted)
        session.adminAccounts.forEach { account -> AccountRow(account,!session.busy) { onDelete(account) } }
        Status(session)
    }
}

@Composable private fun OrganizationSettings(session:SessionViewModel,onBack:()->Unit) {
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
            IconButton(onClick=onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back") }
            LocalizedText("Account settings",style=MaterialTheme.typography.headlineMedium)
        }
        LocalizedText("Profile information",style=MaterialTheme.typography.titleLarge)
        Field("Full name",fullName,"organization_settings_name",enabled=!session.busy) { fullName=it.take(120) }
        BirthdayPicker(dob,"organization_settings_dob",!session.busy) { dob=it }
        Field("Email",email,"organization_settings_email",enabled=!session.busy) { email=it.take(254) }
        Field("Phone (optional)",phone,"organization_settings_phone",enabled=!session.busy) { phone=it.take(40) }
        Button(onClick={session.updateOrganizationProfile(fullName,dob,email,phone)},enabled=!session.busy,modifier=Modifier.fillMaxWidth()) { LocalizedText("Save profile") }
        HorizontalDivider()
        LocalizedText("Change password",style=MaterialTheme.typography.titleLarge)
        Field("Current password",currentPassword,"organization_current_password",secret=true,showSecretToggle=true,enabled=!session.busy) { currentPassword=it.take(128) }
        Field("New password",newPassword,"organization_new_password",secret=true,showSecretToggle=true,enabled=!session.busy) { newPassword=it.take(24) }
        Field("Confirm new password",confirmation,"organization_confirm_password",secret=true,showSecretToggle=true,enabled=!session.busy) { confirmation=it.take(24) }
        Button(onClick={session.changePassword(currentPassword,newPassword,confirmation)},enabled=!session.busy,modifier=Modifier.fillMaxWidth()) { LocalizedText("Change password") }
        Status(session)
    }
}

// UI sizing/color: compact count cards use smaller padding and text while staying two-wide on narrow phones.
@Composable private fun AccountCountGrid(counts:AccountCounts) {
    val values=listOf("Users" to counts.users,"Safe Contacts" to counts.safeContacts,"Staff" to counts.staff,"Admins" to counts.admins)
    values.chunked(2).forEach { row -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        row.forEach { (label,count) -> Card(Modifier.weight(1f)) { Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            LocalizedText(count.toString(),style=MaterialTheme.typography.titleLarge,color=OliveGreen)
            LocalizedText(label,style=MaterialTheme.typography.bodyMedium)
        } } }
    } }
}

@Composable private fun AccountRow(account:AdminAccountSummary,enabled:Boolean,onDelete:()->Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                LocalizedText(account.username,style=MaterialTheme.typography.titleMedium)
                LocalizedText(account.role.title + if(account.profileComplete) " · Setup complete" else " · Setup required",color=Muted)
                if(account.fullName.isNotBlank()) LocalizedText(account.fullName)
                if(account.email.isNotBlank()) LocalizedText(account.email,style=MaterialTheme.typography.bodySmall,color=Muted)
            }
            TextButton(onClick=onDelete,enabled=enabled && !account.protected) { LocalizedText(if(account.protected) "Protected" else "Delete") }
        }
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CreateAccountDialog(session:SessionViewModel,onDismiss:()->Unit) {
    var role by remember { mutableStateOf(AccountRole.USER) }
    var roleOpen by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") };var password by remember { mutableStateOf("") }
    var fullName by remember { mutableStateOf("") };var dob by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") };var phone by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("English") };var city by remember { mutableStateOf("") }
    var affected by remember { mutableStateOf("") };var abuser by remember { mutableStateOf("") };var situation by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=onDismiss,title={LocalizedText("Create account")},text={
        Column(Modifier.fillMaxWidth().heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            ExposedDropdownMenuBox(expanded=roleOpen,onExpandedChange={roleOpen=!roleOpen}) {
                OutlinedTextField(role.title,{},readOnly=true,label={LocalizedText("Account type")},trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(roleOpen)},modifier=Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable))
                ExposedDropdownMenu(roleOpen,{roleOpen=false}) { AccountRole.entries.forEach { item -> DropdownMenuItem(text={LocalizedText(item.title)},onClick={role=item;roleOpen=false}) } }
            }
            Field("Username",username,"admin_username") { username=it.take(24) }
            Field("Temporary password",password,"admin_password",secret=true,showSecretToggle=true) { password=it.take(24) }
            HorizontalDivider();LocalizedText("Optional onboarding details",style=MaterialTheme.typography.titleMedium)
            Field("Full name",fullName,"admin_full_name") { fullName=it.take(120) }
            BirthdayPicker(dob,"admin_dob",true) { dob=it }
            if(role in setOf(AccountRole.STAFF,AccountRole.ADMIN)) {
                Field("Email",email,"admin_email") { email=it.take(254) }
                Field("Phone (optional)",phone,"admin_phone") { phone=it.take(40) }
                LocalizedText("Leave required details blank to have this person finish them on first sign-in.",style=MaterialTheme.typography.bodySmall,color=Muted)
            } else {
                LanguagePicker(language,true) { language=it }
                Field("City",city,"admin_city") { city=it.take(160) }
                if(role==AccountRole.USER) {
                    Field("Names of people experiencing abuse (optional)",affected,"admin_affected") { affected=it.take(500) }
                    Field("Abuser's name (optional)",abuser,"admin_abuser") { abuser=it.take(120) }
                    Field("Situation (optional)",situation,"admin_situation",multiline=true) { situation=it.take(2000) }
                    LocalizedText("The user sets their own trigger word and device PIN on first sign-in.",style=MaterialTheme.typography.bodySmall,color=Muted)
                }
            }
        }
    },confirmButton={Button(onClick={session.createManagedAccount(AdminAccountDraft(username,password,role,fullName,dob,email,phone,language,city,affected,abuser,situation));onDismiss()}){LocalizedText("Create")}},
        dismissButton={TextButton(onClick=onDismiss){LocalizedText("Cancel")}})
}
