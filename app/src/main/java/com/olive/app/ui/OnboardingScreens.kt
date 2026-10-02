package com.olive.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.olive.app.data.*
import com.olive.app.security.InputPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

// ALL ACCOUNT TYPES: role selection routes each person into the correct onboarding path.
// USER and SAFE CONTACT can create accounts here; STAFF and ADMIN use administrator-issued logins.
// UI sizing/text: the role menu inherits FormColumn spacing; descriptions intentionally use bodySmall and Muted so long translations can wrap.
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun Welcome(session: SessionViewModel) {
    var roleOpen by remember { mutableStateOf(false) }
    FormColumn {
        Heading("Welcome", "First, choose what kind of account you use.")
        ModeNotice(session)
        ExposedDropdownMenuBox(expanded=roleOpen,onExpandedChange={roleOpen=!roleOpen}) {
            OutlinedTextField(value=session.accountRole.title,onValueChange={},readOnly=true,label={LocalizedText("Account type")},
                trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(roleOpen)},modifier=Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).testTag("account_role"))
            ExposedDropdownMenu(expanded=roleOpen,onDismissRequest={roleOpen=false}) {
                AccountRole.entries.forEach { role -> DropdownMenuItem(
                    text={Column { LocalizedText(role.title);LocalizedText(role.description,style=MaterialTheme.typography.bodySmall,color=Muted) }},
                    onClick={session.selectRole(role);roleOpen=false},modifier=Modifier.testTag("role_${role.name.lowercase()}")) }
            }
        }
        if(session.accountRole==AccountRole.USER || session.accountRole==AccountRole.SAFE_CONTACT) {
            Button(onClick=session::chooseNew,enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("new_user")) { LocalizedText("Create a new account") }
            OutlinedButton(onClick=session::chooseReturning,enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("returning_user")) { LocalizedText("Sign in to an existing account") }
        } else {
            LocalizedText("${session.accountRole.title} accounts are created by an administrator. Sign in with the username and password you were given.",color=Muted)
            Button(onClick=session::chooseReturning,enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("returning_user")) { LocalizedText("Sign in") }
        }
        Status(session)
    }
}

// ALL ACCOUNT TYPES: username/password sign-in. New-account creation is available only to USER and SAFE CONTACT.
// UI sizing/text: credential fields use the shared full-width field size; password guidance uses bodySmall and Muted.
@Composable internal fun Credentials(session: SessionViewModel, create: Boolean) {
    // Deliberately not rememberSaveable: passwords never enter Android saved-state bundles.
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    FormColumn {
        Heading(if(create) "Create your ${session.accountRole.title} account" else "${session.accountRole.title} sign in",
            if(create) "Use these credentials when setting up another device."
            else when(session.accountRole) {
                AccountRole.USER -> "Sign in with your existing username and password. Next, create a PIN for this device."
                AccountRole.SAFE_CONTACT -> "Sign in with your existing username and password."
                else -> "Use the credentials issued by an administrator."
            })
        ModeNotice(session)
        Field("Username",username,"username",enabled=!session.busy) { username=it.take(if(create) 24 else 30) }
        Field("Password",password,"password",secret=true,enabled=!session.busy) { password=it.take(if(create) 24 else 128) }
        if(create) {
            LocalizedText("Passwords use 8–24 characters and are case-sensitive. Usernames are unique, ignore letter case, and use letters, numbers and underscores. No email or phone number needed.",style=MaterialTheme.typography.bodySmall,color=Muted)
            Field("Confirm password",confirm,"confirm_password",secret=true,enabled=!session.busy) { confirm=it.take(24) }
            LocalizedText("Keep your credentials somewhere safe. You can change your password later in safe Settings.",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
        Status(session)
        Button(onClick={
            if(create && password != confirm) session.error("The passwords do not match.")
            else session.authenticate(username,password,create)
        },enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("credentials_submit")) { LocalizedText(if(create) "Create account" else "Sign in") }
    }
}

// USER and SAFE CONTACT: birthday is required and saved as YYYY-MM-DD.
// UI sizing: the three birthday buttons share one row equally with 8dp gaps and 8dp horizontal button padding.
@Composable internal fun BirthdayPicker(value: String, tag: String, enabled: Boolean, onChange: (String)->Unit) {
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    val initial=remember { runCatching { LocalDate.parse(value) }.getOrNull() }
    var year by remember { mutableStateOf(initial?.year) }
    var month by remember { mutableStateOf(initial?.monthValue) }
    var day by remember { mutableStateOf(initial?.dayOfMonth) }
    val locale=if(LocalOliveLanguage.current=="French") java.util.Locale.CANADA_FRENCH else java.util.Locale.CANADA
    fun setDate(y: Int?,m: Int?,d: Int?) {
        if(y!=null && m!=null && d!=null) onChange("%04d-%02d-%02d".format(java.util.Locale.ROOT,y,m,d))
    }
    LocalizedText("Date of birth (required)",style=MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth().testTag(tag),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        @Composable fun Choice(label: String, selected: String, options: List<Pair<String,String>>, choiceTag: String, choose: (String)->Unit) {
            var open by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick={keyboard?.hide();focus.clearFocus(force=true);open=true},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag(choiceTag),contentPadding=PaddingValues(horizontal=8.dp)) {
                    LocalizedText(selected.ifBlank { label },maxLines=1)
                }
                DropdownMenu(expanded=open,onDismissRequest={open=false}) {
                    options.forEach { (id,text) ->
                        DropdownMenuItem(text={LocalizedText(text)},onClick={choose(id);open=false},modifier=Modifier.testTag("${choiceTag}_$id"))
                    }
                }
            }
        }
        Choice("Year",year?.toString().orEmpty(),(1950..LocalDate.now().year).reversed().map { it.toString() to it.toString() },"${tag}_year") {
            year=it.toInt();setDate(year,month,day)
        }
        Choice("Month",month?.let { Month.of(it).getDisplayName(java.time.format.TextStyle.FULL,locale) }.orEmpty(),
            (1..12).map { it.toString() to Month.of(it).getDisplayName(java.time.format.TextStyle.FULL,locale) },"${tag}_month") {
            month=it.toInt();setDate(year,month,day)
        }
        Choice("Day",day?.toString().orEmpty(),(1..31).map { it.toString() to it.toString() },"${tag}_day") {
            day=it.toInt();setDate(year,month,day)
        }
    }
}

// USER and SAFE CONTACT: shared profile questions live here.
// USER ONLY: abuse details, situation, trigger, optional safe contact, and cover-app choice are inside !safeContact blocks.
// UI sizing/text: section titles use Material title sizes, helper text uses bodySmall, and all fields use the shared responsive form width.
@Composable internal fun ProfileSetup(session: SessionViewModel) {
    val safeContact=session.accountRole==AccountRole.SAFE_CONTACT
    var fullName by remember { mutableStateOf("") }
    var affected by remember { mutableStateOf("") }
    var abuserName by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("English") }
    var situation by remember { mutableStateOf("") }
    var address by remember { mutableStateOf(DeliveryAddress()) }
    var trigger by remember { mutableStateOf("") }
    var contactName by remember { mutableStateOf("") }
    var contactDetails by remember { mutableStateOf("") }
    var coverType by remember { mutableStateOf(CoverType.FOOD) }
    FormColumn {
        // USER + SAFE CONTACT: these shared identity, birthday, language, and city questions are required for both roles.
        Heading("Your profile",if(safeContact) "Full name, date of birth, language and city are required." else "Full name, date of birth, language, city and a trigger word are required.")
        Field("Full name (required)",fullName,"full_name",enabled=!session.busy) { fullName=it.take(120) }
        // USER ONLY: Safe Contacts are helpers, so they are never asked for abuse details.
        if(!safeContact) {
            Field("Names of people experiencing abuse (optional)",affected,"affected_names",enabled=!session.busy) { affected=it.take(500) }
            Field("Abuser's name (optional)",abuserName,"abuser_name",enabled=!session.busy) { abuserName=it.take(120) }
        }
        BirthdayPicker(dob,"dob",!session.busy) { dob=it }
        LanguagePicker(language,!session.busy) { language=it }
        if(!safeContact) Field("Brief description of your situation (optional, recommended)",situation,"situation",multiline=true,enabled=!session.busy) { situation=it.take(2000) }
        HorizontalDivider()
        LocalizedText("Location",style=MaterialTheme.typography.titleLarge)
        LocalizedText("City is required. The rest of your delivery address is optional and can be added later.",style=MaterialTheme.typography.bodySmall,color=Muted)
        AddressFields(address,cityOnlyRequired=true) { address=it }
        // USER ONLY: the trigger opens the private app from the selected cover.
        if(!safeContact) {
            HorizontalDivider()
            LocalizedText("Safe entry",style=MaterialTheme.typography.titleLarge)
            Field("Trigger word or phrase",trigger,"trigger",secret=true,enabled=!session.busy) { trigger=it.take(64) }
            LocalizedText("Olive and Daily Digest ignore spaces and letter case in your trigger. Other searches keep their spaces. Better Calculator uses your PIN and two equals taps.",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
        // USER ONLY: Safe Contacts do not add another Safe Contact during their own onboarding.
        if(!safeContact) {
            LocalizedText("Safe contact · optional",style=MaterialTheme.typography.titleMedium)
            Field("Contact name",contactName,"setup_contact_name",enabled=!session.busy) { contactName=it.take(120) }
            Field("Contact phone or email",contactDetails,"setup_contact_details",enabled=!session.busy) { contactDetails=it.take(200) }
            LocalizedText("Contacts can be added and edited later. Olive never contacts anyone automatically.",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
        // USER ONLY: Safe Contacts open Olive Branch directly and therefore do not select a cover app.
        if(!safeContact) CoverPicker(coverType,!session.busy) { chosen ->
            if(chosen == CoverType.GAME) session.error("In development") else coverType=chosen
        }
        Status(session)
        Button(onClick={
            when {
                fullName.isBlank() -> session.error("Enter your full name.")
                !InputPolicy.validDob(dob) -> session.error("Use a valid date of birth in YYYY-MM-DD format.")
                language.isBlank() -> session.error("Enter your preferred language.")
                !address.locationValid -> session.error("Enter your city.")
                !safeContact && !InputPolicy.validTrigger(trigger) -> session.error("Choose a trigger word or phrase of 3–64 characters.")
                !safeContact && contactName.isBlank() != contactDetails.isBlank() -> session.error("Enter both contact fields or leave both empty.")
                else -> session.saveSetupProfile(UserProfile(
                    username=session.setupUsername,fullName=fullName.trim(),affectedNames=affected.trim(),abuserName=abuserName.trim(),dob=dob.trim(),language=language.trim(),
                    situation=situation.trim(),region=address.generalLocation,triggerHash=if(safeContact) "" else InputPolicy.triggerHash(trigger),
                    contacts=if(safeContact || contactName.isBlank()) emptyList() else listOf(SafeContact(UUID.randomUUID().toString(),contactName.trim(),contactDetails.trim())),address=address,coverType=coverType,role=session.accountRole))
            }
        },enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("profile_submit")) { LocalizedText("Save profile and continue") }
    }
}
