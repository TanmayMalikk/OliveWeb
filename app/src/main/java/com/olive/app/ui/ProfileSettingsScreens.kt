package com.olive.app.ui

import androidx.compose.ui.semantics.*
import com.olive.app.ui.LocalizedIcon as Icon
import androidx.compose.ui.res.stringResource
import com.olive.app.R
import com.olive.app.ui.covers.olive.AddressFields

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.olive.app.data.*
import java.util.UUID

// UI sizing/text: this back action keeps Material button sizing and localized text rather than a custom fixed size.
@Composable internal fun SafeBack(session: SessionViewModel) {
    TextButton(onClick=session::back,modifier=Modifier.testTag("safe_home")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,null); Text(stringResource(R.string.back)) }
}

// Trusted people can be added or cleaned up here, but the app never auto-calls or auto-messages them.
// UI sizing/text: contact cards use 16dp padding and Material title text; edits stay in shared full-width fields.
@Composable internal fun ContactEditor(session: SessionViewModel, profile: UserProfile) {
    var editing by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    FormColumn {
        TextButton(onClick=session::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,null); Text(stringResource(R.string.settings)) }
        Heading("Safe contacts", "Save trusted people here. Olive does not send messages or make calls.")
        profile.contacts.forEach { contact ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(contact.name,style=MaterialTheme.typography.titleMedium)
                    Text(contact.details)
                    Row {
                        TextButton(onClick={session.recordSettingsInteraction("Olive Branch","Opened contact editor");editing=contact.id; name=contact.name; details=contact.details},enabled=!session.busy) { Text(stringResource(R.string.edit)) }
                        TextButton(onClick={session.recordSettingsInteraction("Olive Branch","Selected remove contact");session.saveContacts(profile.contacts.filterNot { it.id==contact.id }); if(editing==contact.id) {editing=null; name=""; details=""}},enabled=!session.busy) { Text(stringResource(R.string.remove)) }
                    }
                }
            }
        }
        if(profile.contacts.isEmpty()) Text(stringResource(R.string.no_contacts_yet_adding_one_is_optional),color=Muted)
        Text(if(editing==null) "Add a contact" else "Edit contact",style=MaterialTheme.typography.titleMedium)
        Field("Contact name",name,"contact_name",enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused contact name")}) { name=it.take(120) }
        Field("Phone or email",details,"contact_details",enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused contact details")}) { details=it.take(200) }
        Status(session)
        Button(onClick={
            if(name.isBlank() || details.isBlank()) session.error("Enter a name and a phone number or email.")
            else if(editing==null && profile.contacts.size>=25) session.error("You can save up to 25 contacts.")
            else {
                val contact=SafeContact(editing ?: UUID.randomUUID().toString(),name.trim(),details.trim())
                session.saveContacts(if(editing==null) profile.contacts+contact else profile.contacts.map { if(it.id==editing) contact else it })
                editing=null; name=""; details=""
            }
        },enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("save_contact")) { Text(stringResource(R.string.save_contact)) }
        if(editing!=null) TextButton(onClick={editing=null; name=""; details=""}) { Text(stringResource(R.string.cancel_edit)) }
    }
}

// Safe-side language choices come from AppLocaleManager's supported-language list.
// UI sizing/text: the language control fills its parent so translated language names have the full form width.
@Composable internal fun LanguagePicker(value: String, enabled: Boolean = true, onChange: (String)->Unit) {
    var open by remember { mutableStateOf(false) }
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    val supportedLanguages = AppLocaleManager.supportedLanguages
    Box {
        OutlinedButton(onClick={keyboard?.hide();focus.clearFocus(force=true);open=true},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("language")) { Text(stringResource(R.string.language_required)); Spacer(Modifier.weight(1f)); Text(AppLocaleManager.getDisplayLanguage(value)) }
        DropdownMenu(expanded=open,onDismissRequest={open=false}) {
            supportedLanguages.forEach { language -> DropdownMenuItem(text={Text(AppLocaleManager.getDisplayLanguage(language))},onClick={onChange(language);open=false}) }
        }
    }
}

// Edit private profile, language, and PIN-guarded trigger settings in this section.
// UI sizing/text: settings reuse shared form spacing and Material titleLarge section headings so translated labels can wrap.
@Composable internal fun SettingsEditor(session: SessionViewModel, profile: UserProfile) {
    val userRole=profile.role==AccountRole.USER
    var fullName by remember { mutableStateOf(profile.fullName) }
    var affected by remember { mutableStateOf(profile.affectedNames) }
    var abuserName by remember { mutableStateOf(profile.abuserName) }
    var dob by remember { mutableStateOf(profile.dob) }
    var language by remember { mutableStateOf(profile.language) }
    var situation by remember { mutableStateOf(profile.situation) }
    var address by remember { mutableStateOf(profile.address) }
    var trigger by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var currentDevicePin by remember { mutableStateOf("") }
    var newDevicePin by remember { mutableStateOf("") }
    var confirmDevicePin by remember { mutableStateOf("") }
    FormColumn {
        SafeBack(session)
        Heading("Settings","Update your profile and language at any time.")
        if(userRole) OutlinedButton(onClick={session.navigate(SafePage.Contacts)},modifier=Modifier.fillMaxWidth().testTag("safe_contacts")) { Text(stringResource(R.string.safe_contacts)) }
        if(userRole) {
            // Change the visible app and launcher icon here; add new covers to CoverType and CoverLauncher.
            CoverPicker(profile.coverType,!session.busy) { session.recordSettingsInteraction("Olive Branch","Selected cover app");session.changeCover(it) }
            Text(stringResource(R.string.olive_and_daily_digest_use_search_for_safe_entry_better_calculator_uses_your_device_pin_followed_by_two_equals_taps),style=MaterialTheme.typography.bodySmall,color=Muted)
        }
        Field("Full name (required)",fullName,"settings_full_name",onFocus={session.recordSettingsInteraction("Olive Branch","Focused full name")}) { fullName=it.take(120) }
        if(userRole) {
            Field("Names of people experiencing abuse (optional)",affected,"settings_affected",onFocus={session.recordSettingsInteraction("Olive Branch","Focused affected names")}) { affected=it.take(500) }
            Field("Abuser's name (optional)",abuserName,"settings_abuser_name",onFocus={session.recordSettingsInteraction("Olive Branch","Focused abuser name")}) { abuserName=it.take(120) }
        }
        BirthdayPicker(dob,"settings_dob",!session.busy) { dob=it }
        LanguagePicker(language,!session.busy) { session.recordSettingsInteraction("Olive Branch","Selected language");language=it }
        if(userRole) Field("Brief description of your situation (optional, recommended)",situation,"settings_situation",multiline=true,onFocus={session.recordSettingsInteraction("Olive Branch","Focused situation")}) { situation=it.take(2000) }
        Text("Location · city required",style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics { heading() })
        AddressFields(address,cityOnlyRequired=true,onFocus={session.recordSettingsInteraction("Olive Branch","Focused address: $it")}) { address=it }
        Button(onClick={session.saveSettings(fullName,affected,abuserName,dob,language,situation,address)},enabled=!session.busy,modifier=Modifier.testTag("save_settings")) { Text(stringResource(R.string.save_settings)) }
        HorizontalDivider()
        Text(stringResource(R.string.change_account_password),style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics { heading() })
        Text(stringResource(R.string.your_username_cannot_be_changed))
        Field("Current password",currentPassword,"current_password",secret=true,showSecretToggle=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused current password")}) { currentPassword=it.take(24) }
        Field("New password",newPassword,"new_password",secret=true,showSecretToggle=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused new password")}) { newPassword=it.take(24) }
        Field("Confirm new password",confirmPassword,"confirm_new_password",secret=true,showSecretToggle=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused password confirmation")}) { confirmPassword=it.take(24) }
        Button(onClick={session.changePassword(currentPassword,newPassword,confirmPassword);currentPassword="";newPassword="";confirmPassword=""},enabled=!session.busy,modifier=Modifier.testTag("change_password")) { Text(stringResource(R.string.change_password)) }
        if(userRole) {
            HorizontalDivider()
            Text(stringResource(R.string.change_device_pin),style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics { heading() })
            Field("Current device PIN",currentDevicePin,"current_device_pin",secret=true,numeric=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused current PIN")}) { currentDevicePin=it.filter(Char::isDigit).take(6) }
            Field("New device PIN",newDevicePin,"new_device_pin",secret=true,numeric=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused new PIN")}) { newDevicePin=it.filter(Char::isDigit).take(6) }
            Field("Confirm new device PIN",confirmDevicePin,"confirm_new_device_pin",secret=true,numeric=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused PIN confirmation")}) { confirmDevicePin=it.filter(Char::isDigit).take(6) }
            Button(onClick={session.changePin(currentDevicePin,newDevicePin,confirmDevicePin);currentDevicePin="";newDevicePin="";confirmDevicePin=""},enabled=!session.busy,modifier=Modifier.testTag("change_device_pin")) { Text(stringResource(R.string.change_device_pin)) }
            HorizontalDivider()
            Text(stringResource(R.string.change_trigger_word),style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics { heading() })
            Text(stringResource(R.string.enter_your_current_device_pin_to_change_the_trigger_word_incorrect_attempts_count_toward_the_five_attempt_limit))
            Field("New trigger word or phrase",trigger,"settings_trigger",secret=true,onFocus={session.recordSettingsInteraction("Olive Branch","Focused trigger word")}) { trigger=it.take(64) }
            Field("Current PIN",pin,"settings_pin",secret=true,numeric=true,onFocus={session.recordSettingsInteraction("Olive Branch","Focused trigger-change PIN")}) { pin=it.filter { c -> c in '0'..'9' }.take(6) }
            Button(onClick={session.changeTrigger(trigger,pin);pin="";trigger=""},enabled=!session.busy,modifier=Modifier.testTag("change_trigger")) { Text(stringResource(R.string.change_trigger_word)) }
        }
        Status(session)
    }
}

// UI sizing/text: cover choices fill the form width and use Material button text so longer translated names can wrap.
@Composable internal fun CoverPicker(selected: CoverType, enabled: Boolean, onSelect: (CoverType) -> Unit) {
    Text(stringResource(R.string.choose_your_cover_app),style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics { heading() })
    Text(stringResource(R.string.the_app_stays_open_its_icon_and_name_update_after_you_return_to_the_home_screen),style=MaterialTheme.typography.bodySmall,color=Muted)
    CoverType.entries.forEach { type ->
        val label=when(type) {
            CoverType.FOOD -> "Food Delivery App · Olive"
            CoverType.GAME -> "Game · In development"
            CoverType.NEWS -> "News App · Daily Digest"
            CoverType.CALCULATOR -> "Calculator · Better Calculator"
        }
        OutlinedButton(onClick={onSelect(type)},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("cover_choice_${type.name.lowercase()}")) {
            Text(if(selected==type) "✓  $label" else label)
        }
    }
}
