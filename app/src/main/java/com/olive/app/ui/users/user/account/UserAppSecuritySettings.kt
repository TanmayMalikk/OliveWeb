// Safe-app users: lets users change PIN and device-lock options.
package com.olive.app.ui.users.user.account


import com.olive.app.ui.shared.systemLocale
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveCard as Card
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveOutlinedCard as OutlinedCard
import com.olive.app.ui.shared.OliveTextButton as TextButton
import android.app.KeyguardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.SafeContact
import com.olive.app.data.account.UserProfile
import com.olive.app.security.InputPolicy
import com.olive.app.security.SafeEntryMethod
import com.olive.app.ui.shared.LightSage
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.localizedTitle
import com.olive.app.ui.shared.rememberResourceText
import com.olive.app.ui.shared.walkthroughTarget
import com.olive.app.ui.users.shared.auth.AppPinSetup
import com.olive.app.ui.users.shared.navigation.Field
import com.olive.app.ui.users.shared.navigation.FormColumn
import com.olive.app.ui.users.shared.navigation.Heading
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.users.shared.navigation.Status
import com.olive.app.ui.users.shared.onboarding.OnboardingNavigation
import com.olive.app.ui.users.shared.onboarding.OnboardingProgress
import com.olive.app.ui.users.shared.onboarding.RequirementBullet
import com.olive.app.ui.users.shared.safetyplan.TrustedContactLinkPicker
import java.util.UUID

/**
 * The safety answer changes the recommendation, never silently chooses an unlock method.
 *
 * @param session Shared session view model owning application state and actions.
 */
@Composable
internal fun SafeEntrySetup(session: SessionViewModel) {
    var knowsPin by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var method by rememberSaveable { mutableStateOf<SafeEntryMethod?>(null) }
    val title =
        stringResource(if (method == SafeEntryMethod.APP_PIN) R.string.entry_new_pin else R.string.entry_setup_title)
    Box(Modifier.fillMaxSize().semantics { paneTitle = title }) {
        if (method == SafeEntryMethod.APP_PIN) {
            BackHandler { if (!session.busy) method = null }
            AppPinSetup(session) { method = null }
            return@Box
        }
        val context = LocalContext.current
        val secure = context.getSystemService(KeyguardManager::class.java).isDeviceSecure
        FormColumn {
            Heading(
                stringResource(R.string.entry_setup_title),
                stringResource(R.string.entry_setup_description)
            )
            Text(
                stringResource(R.string.entry_safety_question),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleLarge
            )
            // Use the same full-width outlined controls as the other onboarding choices.
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(true, false).forEach { answer ->
                    val chosen = knowsPin == answer
                    // The entire answer is one radio item; the painted radio has no separate spoken node.
                    Surface(
                        shape = ButtonDefaults.outlinedShape,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .selectable(
                                    selected = chosen,
                                    enabled = !session.busy,
                                    role = Role.RadioButton,
                                    onClick = { knowsPin = answer })
                                .testTag(if (answer) "entry_knows_pin_yes" else "entry_knows_pin_no")
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            RadioButton(
                                selected = chosen,
                                onClick = null,
                                enabled = !session.busy,
                                modifier = Modifier.clearAndSetSemantics {})
                            Text(
                                stringResource(if (answer) R.string.entry_yes else R.string.entry_no),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
            knowsPin?.let { answer ->
                val recommended = SafeEntryMethod.recommended(answer)
                Text(
                    stringResource(R.string.entry_choose_method),
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    stringResource(if (answer) R.string.entry_recommend_pin else R.string.entry_recommend_device),
                    modifier = Modifier.testTag("entry_recommendation")
                        .semantics { liveRegion = LiveRegionMode.Polite }, color = Muted
                )
                SafeEntryMethod.entries.forEach { choice ->
                    val label =
                        stringResource(if (choice == SafeEntryMethod.APP_PIN) R.string.entry_new_pin else R.string.entry_device_lock)
                    val buttonLabel = if (choice == recommended) stringResource(
                        R.string.entry_recommended,
                        label
                    ) else label
                    OutlinedButton(
                        onClick = {
                            if (choice == SafeEntryMethod.APP_PIN) method =
                                choice else session.account.authenticateDeviceLock(enroll = true)
                        }, enabled = !session.busy && (choice == SafeEntryMethod.APP_PIN || secure),
                        modifier = Modifier.fillMaxWidth()
                            .testTag(if (choice == SafeEntryMethod.APP_PIN) "entry_choose_pin" else "entry_choose_device")
                    ) {
                        Text(buttonLabel)
                    }
                }
                Text(
                    stringResource(R.string.entry_device_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
                if (!secure) Text(
                    stringResource(R.string.entry_device_unavailable),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error
                )
            }
            Status(session)
            OutlinedButton(
                onClick = session.navigation::back,
                enabled = !session.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.back))
            }
        }
    }
}

/**
 * Draws the device lock prompt for the user safe app.
 *
 * @param session Shared session view model owning application state and actions.
 * @param onUseBackupPin Callback invoked when use backup PIN changes or occurs.
 */
@Composable
internal fun DeviceLockPrompt(
    session: SessionViewModel,
    onUseBackupPin: (() -> Unit)? = null,
) {
    // Rechecks Android device-lock support when the security screen returns.
    LaunchedEffect(Unit) { session.account.authenticateDeviceLock() }
    FormColumn {
        Heading(
            stringResource(R.string.entry_device_lock),
            stringResource(R.string.entry_unlock_description)
        )
        Text(stringResource(R.string.entry_device_description), color = Muted)
        Status(session)
        Button(
            onClick = { session.account.authenticateDeviceLock() }, enabled = !session.busy,
            modifier = Modifier.fillMaxWidth().testTag("device_lock_unlock")
        ) {
            Text(stringResource(R.string.entry_unlock))
        }
        if (onUseBackupPin != null) {
            OutlinedButton(
                onClick = onUseBackupPin,
                enabled = !session.busy,
                modifier = Modifier.fillMaxWidth().testTag("use_backup_pin"),
            ) {
                Text(stringResource(R.string.entry_use_backup_pin))
            }
        }
    }
}

/**
 * The account owner sets this private trigger personally on first login.
 *
 * @param session Shared session view model owning application state and actions.
 */
@Composable
internal fun TriggerSetup(session: SessionViewModel) {
    val uiText = rememberResourceText()
    var trigger by remember { mutableStateOf("") }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var shakeAttempt by rememberSaveable { mutableIntStateOf(0) }
    val valid = InputPolicy.validTrigger(trigger)
    FormColumn {
        Heading(
            uiText(R.string.ui_set_your_private_entry),
            uiText(R.string.ui_choose_your_trigger_word)
        )
        OnboardingProgress(if (valid) 1 else 0, 1, 0, 1, listOf(valid))
        Field(
            uiText(R.string.trigger_word_or_phrase),
            trigger,
            "trigger",
            secret = true,
            showSecretToggle = true,
            enabled = !session.busy,
            error = if (showErrors && !valid) uiText(R.string.ui_invalid_trigger) else null,
            shakeTrigger = shakeAttempt
        ) {
            trigger = it.take(64)
            showErrors = false
        }
        RequirementBullet(uiText(R.string.ui_3_64_characters), valid, trigger.isNotEmpty())
        Status(session)
        OnboardingNavigation(
            session.navigation::back,
            {
                showErrors = true
                if (valid) session.account.saveProvisionedTrigger(trigger)
                else shakeAttempt += 1
            },
            last = true,
            nextTag = "trigger_setup_submit",
            enabled = !session.busy
        )
    }
}

// Kept in memory for the lifetime of Settings, including when the Password tab is hidden.
internal class SafeEntryPinDraft {
    var currentPin by mutableStateOf("")
    var newPin by mutableStateOf("")
    var confirmation by mutableStateOf("")
}

/**
 * Both methods may be configured; only the preferred method appears on a normal trigger.
 *
 * @param session Shared session view model owning application state and actions.
 */
@Composable
internal fun SafeEntryMethodSettings(session: SessionViewModel) {
    var pendingMethod by remember { mutableStateOf<SafeEntryMethod?>(null) }
    /**
     * Selects the target.
     *
     * @param method Method supplied to [select].
     */
    fun select(method: SafeEntryMethod) {
        if (session.account.hasAppPin &&
            (session.account.preferredEntryMethod == SafeEntryMethod.APP_PIN || !session.account.phoneHasLock)
        ) pendingMethod = method
        else session.account.setSafeEntryMethod(method, "")
    }
    Text(
        stringResource(R.string.entry_choose_method),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() })
    val currentLabel =
        stringResource(if (session.account.preferredEntryMethod == SafeEntryMethod.DEVICE_LOCK) R.string.entry_device_lock else R.string.entry_new_pin)
    Text(
        stringResource(R.string.entry_current_method, currentLabel),
        modifier = Modifier.testTag("entry_current_method")
            .semantics { liveRegion = LiveRegionMode.Polite })
    Text(stringResource(R.string.entry_backup_description))
    Button(
        onClick = { select(SafeEntryMethod.DEVICE_LOCK) },
        enabled = !session.busy && session.account.phoneHasLock,
        modifier = Modifier.fillMaxWidth().testTag("entry_use_device")
    ) {
        Text(stringResource(R.string.entry_use_device))
    }
    if (!session.account.phoneHasLock) Text(
        stringResource(R.string.entry_device_unavailable),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    if (session.account.hasAppPin && session.account.preferredEntryMethod != SafeEntryMethod.APP_PIN) {
        OutlinedButton(
            onClick = { select(SafeEntryMethod.APP_PIN) }, enabled = !session.busy,
            modifier = Modifier.fillMaxWidth().testTag("entry_use_pin")
        ) { Text(stringResource(R.string.entry_use_pin)) }
    }
    pendingMethod?.let { method ->
        var pin by remember { mutableStateOf("") }
        var showPinError by remember { mutableStateOf(false) }
        var localShake by remember { mutableIntStateOf(0) }
        val errorText = if (showPinError && !InputPolicy.validPin(pin)) stringResource(R.string.ui_invalid_pin) else null
        AlertDialog(
            onDismissRequest = { pendingMethod = null },
            title = { Text(stringResource(R.string.entry_choose_method)) },
            text = {
                Field(
                    stringResource(R.string.current_device_pin),
                    pin,
                    "current_device_pin",
                    secret = true,
                    numeric = true,
                    showSecretToggle = true,
                    error = errorText,
                    shakeTrigger = localShake + session.inputErrorVersion,
                ) {
                    pin = it.filter(Char::isDigit).take(6)
                    showPinError = false
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!InputPolicy.validPin(pin)) {
                            showPinError = true
                            localShake += 1
                            return@TextButton
                        }
                        session.account.setSafeEntryMethod(method, pin)
                        pendingMethod = null
                    },
                    enabled = !session.busy,
                    modifier = Modifier.testTag("confirm_entry_method"),
                ) { Text(stringResource(R.string.next)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingMethod = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/**
 * Composes the safe entry PIN settings UI element.
 *
 * @param session Shared session view model owning application state and actions.
 * @param draft Draft supplied to [SafeEntryPinSettings].
 */
@Composable
internal fun SafeEntryPinSettings(session: SessionViewModel, draft: SafeEntryPinDraft) = with(draft) {
    val text = rememberResourceText()
    var showErrors by remember { mutableStateOf(false) }
    var shakeAttempt by remember { mutableIntStateOf(0) }
    var pendingPinCheck by remember { mutableStateOf(false) }
    var currentPinRejected by remember { mutableStateOf(false) }
    val sessionErrorVersion = session.inputErrorVersion
    var lastSeenErrorVersion by remember { mutableIntStateOf(sessionErrorVersion) }
    LaunchedEffect(sessionErrorVersion) {
        if (sessionErrorVersion > lastSeenErrorVersion) {
            lastSeenErrorVersion = sessionErrorVersion
            if (pendingPinCheck) {
                currentPinRejected = true
                pendingPinCheck = false
                showErrors = true
            }
        }
    }
    val currentRequired = session.account.canUseAppPin
    val currentPinError = when {
        currentPinRejected -> text(R.string.enter_your_4_6_digit_pin)
        showErrors && currentRequired && !InputPolicy.validPin(currentPin) -> text(R.string.ui_invalid_pin)
        else -> null
    }
    val newPinError = if (showErrors && !InputPolicy.validPin(newPin)) text(R.string.ui_invalid_pin) else null
    val confirmPinError = if (showErrors && confirmation != newPin) text(R.string.ui_pins_differ) else null
    val pinShakeTrigger = shakeAttempt + sessionErrorVersion
    if (currentRequired) Field(
        text(R.string.current_device_pin), currentPin, "current_device_pin",
        secret = true, numeric = true, showSecretToggle = true, enabled = !session.busy,
        error = currentPinError, shakeTrigger = pinShakeTrigger,
    ) {
        currentPin = it.filter(Char::isDigit).take(6)
        currentPinRejected = false
        showErrors = false
    }
    Field(
        text(R.string.new_device_pin),
        newPin,
        "new_device_pin",
        secret = true,
        numeric = true,
        showSecretToggle = true,
        enabled = !session.busy,
        error = newPinError,
        shakeTrigger = pinShakeTrigger,
    ) {
        newPin = it.filter(Char::isDigit).take(6)
        showErrors = false
    }
    Field(
        text(R.string.confirm_new_device_pin),
        confirmation,
        "confirm_new_device_pin",
        secret = true,
        numeric = true,
        showSecretToggle = true,
        enabled = !session.busy,
        error = confirmPinError,
        shakeTrigger = pinShakeTrigger,
    ) {
        confirmation = it.filter(Char::isDigit).take(6)
        showErrors = false
    }
    /**
     * Validates the form before saving a new backup PIN or entry method.
     *
     * @param usePin Whether to use PIN.
     */
    fun save(usePin: Boolean) {
        currentPinRejected = false
        val validLocal = (!currentRequired || InputPolicy.validPin(currentPin)) &&
            InputPolicy.validPin(newPin) &&
            confirmation == newPin
        showErrors = !validLocal
        if (!validLocal) {
            shakeAttempt += 1
        } else {
            pendingPinCheck = true
        }
        session.account.saveSafeEntryPin(currentPin, newPin, confirmation, usePin)
        currentPin = ""
        newPin = ""
        confirmation = ""
    }
    // A current PIN is optional only when Android device lock is the main entry method.
    if (session.account.preferredEntryMethod == SafeEntryMethod.DEVICE_LOCK) {
        OutlinedButton(
            onClick = { save(false) },
            enabled = !session.busy,
            modifier = Modifier.fillMaxWidth().testTag("entry_save_backup")
        ) {
            Text(stringResource(R.string.entry_save_backup))
        }
    }
    Button(
        onClick = { save(true) },
        enabled = !session.busy,
        modifier = Modifier.fillMaxWidth()
            .testTag(if (session.account.preferredEntryMethod == SafeEntryMethod.APP_PIN) "change_device_pin" else "entry_save_use_pin")
    ) {
        Text(stringResource(R.string.entry_save_use_pin))
    }
}

/**
 * UI sizing/text: this back action keeps Material button sizing and localized text rather than a custom fixed size.
 *
 * @param session Shared session view model owning application state and actions.
 */
@Composable
internal fun SafeBack(session: SessionViewModel) {
    TextButton(
        onClick = session.navigation::back,
        modifier = Modifier.testTag("safe_home")
    ) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, null)
        Text(stringResource(R.string.back))
    }
}

/**
 * Trusted people can be added or cleaned up here, but the app never auto-calls or auto-messages them. UI sizing/text: contact cards use 16dp padding and Material title text; edits stay in shared full-width fields.
 *
 * @param session Shared session view model owning application state and actions.
 * @param profile Account profile details.
 */
@Composable
internal fun ContactEditor(session: SessionViewModel, profile: UserProfile) {
    val uiText = rememberResourceText()
    var editing by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf("") }
    var linkedUid by remember { mutableStateOf("") }
    var showErrors by remember { mutableStateOf(false) }
    var shakeAttempt by remember { mutableIntStateOf(0) }
    FormColumn {
        IconButton(onClick = session.navigation::back, modifier = Modifier.testTag("trusted_contacts_back")) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                stringResource(R.string.ui_previous_safe_page)
            )
        }
        Heading(
            uiText(R.string.safe_contacts),
            uiText(R.string.save_trusted_people_here_olive_does_not_send_messages_or_make_calls)
        )
        val safeToStay = profile.contacts.filter { it.safeToStay }
        if (safeToStay.isNotEmpty()) Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = LightSage)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.safe_to_stay),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
                safeToStay.forEach { Text("• ${it.name}") }
            }
        }
        profile.contacts.forEach { contact ->
            val safeStayDescription = stringResource(R.string.safe_to_stay_for, contact.name)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(contact.name, style = MaterialTheme.typography.titleMedium)
                            Text(contact.details)
                            if (contact.relationship.isNotBlank()) Text(contact.relationship, color = Muted)
                            if (contact.safeToStay) Text(
                                stringResource(R.string.safe_to_stay), color = OliveGreen,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        Switch(
                            checked = contact.safeToStay,
                            onCheckedChange = { enabled ->
                                session.saveContacts(profile.contacts.map {
                                    if (it.id == contact.id) it.copy(
                                        safeToStay = enabled
                                    ) else it
                                })
                            },
                            enabled = !session.busy,
                            modifier = Modifier.testTag("safe_to_stay_${contact.id}")
                                .semantics { contentDescription = safeStayDescription })
                    }
                    Text(
                        stringResource(R.string.safe_to_stay_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted
                    )
                    TrustedContactLinkPicker(
                        session.messages.friends, contact.linkedUid,
                        profile.contacts.filterNot { it.id == contact.id }
                            .mapNotNull { it.linkedUid.takeIf(String::isNotBlank) }.toSet(),
                        !session.busy, "trusted_link_${contact.id}"
                    ) { selectedUid ->
                        session.saveContacts(profile.contacts.map {
                            if (it.id == contact.id) it.copy(
                                linkedUid = selectedUid
                            ) else it
                        })
                        if (editing == contact.id) linkedUid = selectedUid
                    }
                    Row {
                        TextButton(onClick = {
                            session.recordSettingsInteraction(
                                "Olive Branch",
                                "Opened contact editor"
                            )
                            editing = contact.id
                            name = contact.name
                            details =
                                contact.details
                            linkedUid = contact.linkedUid
                            relationship = contact.relationship
                            showErrors = false
                        }, enabled = !session.busy) { Text(stringResource(R.string.edit)) }
                        TextButton(onClick = {
                            session.recordSettingsInteraction(
                                "Olive Branch",
                                "Selected remove contact"
                            )
                            session.saveContacts(profile.contacts.filterNot { it.id == contact.id })
                            // Clear the form when the removed contact was being edited.
                            if (editing == contact.id) {
                                editing = null
                                name = ""
                                details = ""
                                relationship = ""
                                linkedUid = ""
                                showErrors = false
                            }
                        }, enabled = !session.busy) { Text(stringResource(R.string.remove)) }
                    }
                }
            }
        }
        if (profile.contacts.isEmpty()) Text(
            stringResource(R.string.no_contacts_yet_adding_one_is_optional),
            color = Muted
        )
        Text(
            if (editing == null) uiText(R.string.add_a_contact) else uiText(R.string.edit_contact),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.walkthroughTarget("contacts_add_form"),
        )
        Field(
            uiText(R.string.contact_name),
            name,
            "contact_name",
            enabled = !session.busy,
            error = if (showErrors && name.isBlank()) uiText(R.string.ui_name_required) else null,
            shakeTrigger = shakeAttempt,
            onFocus = {
                session.recordSettingsInteraction(
                    "Olive Branch",
                    "Focused contact name"
                )
            }) {
                name = it.take(120)
                showErrors = false
            }
        Field(
            uiText(R.string.phone_or_email),
            details,
            "contact_details",
            enabled = !session.busy,
            error = if (showErrors && details.isBlank()) uiText(R.string.enter_a_name_and_a_phone_number_or_email) else null,
            shakeTrigger = shakeAttempt,
            onFocus = {
                session.recordSettingsInteraction(
                    "Olive Branch",
                    "Focused contact details"
                )
            }) {
                details = it.take(200)
                showErrors = false
            }
        Field(uiText(R.string.contact_relationship), relationship, "contact_relationship",
            enabled = !session.busy) { relationship = it.take(120) }
        TrustedContactLinkPicker(
            session.messages.friends, linkedUid,
            profile.contacts.filterNot { it.id == editing }
                .mapNotNull { it.linkedUid.takeIf(String::isNotBlank) }.toSet(),
            !session.busy, "contact_link_account"
        ) { linkedUid = it }
        Status(session)
        Button(
            onClick = {
                // A trusted contact needs both a name and a phone number or email.
                if (name.isBlank() || details.isBlank()) {
                    showErrors = true
                    shakeAttempt += 1
                    session.account.showError(uiText(R.string.enter_a_name_and_a_phone_number_or_email))
                } else if (editing == null && profile.contacts.size >= 25) {
                    session.account.showError(uiText(R.string.you_can_save_up_to_25_contacts))
                } else {
                    val contact = SafeContact(
                        editing ?: UUID.randomUUID().toString(),
                        name.trim(),
                        details.trim(),
                        profile.contacts.firstOrNull { it.id == editing }?.safeToStay ?: false,
                        linkedUid,
                        relationship.trim()
                    )
                    session.saveContacts(if (editing == null) profile.contacts + contact else profile.contacts.map { if (it.id == editing) contact else it })
                    editing = null
                    name = ""
                    details = ""
                    relationship = ""
                    linkedUid = ""
                    showErrors = false
                }
            },
            enabled = !session.busy,
            modifier = Modifier.fillMaxWidth().testTag("save_contact")
        ) { Text(stringResource(R.string.save_contact)) }
        if (editing != null) TextButton(onClick = {
            editing = null
            name = ""
            details = ""
            relationship = ""
            linkedUid = ""
            showErrors = false
        }) { Text(stringResource(R.string.cancel_edit)) }
    }
}

/**
 * Edit the private profile, safe-entry methods and authenticated trigger settings. UI sizing/text: settings reuse shared form spacing and Material titleLarge section headings so translated labels can wrap.
 *
 * @param session Shared session view model owning application state and actions.
 * @param profile Account profile details.
 */
@Composable
internal fun SafeProfileScreen(session: SessionViewModel, profile: UserProfile) {
    val locale = systemLocale()
    val uiText = rememberResourceText()
    FormColumn {
        SafeBack(session)
        Heading(uiText(R.string.my_profile), uiText(R.string.your_saved_account_information))
        val rows = buildList {
            add(uiText(R.string.account_type) to profile.role.localizedTitle(uiText))
            add(uiText(R.string.username) to profile.username)
            add(
                uiText(R.string.full_name) to profile.fullName
            )
            if (profile.role == AccountRole.USER) {
                add(uiText(R.string.people_experiencing_abuse) to profile.affectedNames)
                add(
                    uiText(
                        R.string.abuser_s_name
                    ) to profile.abuserName
                )
                add(uiText(R.string.situation) to profile.situation)
            }
            add(uiText(R.string.date_of_birth) to profile.dob)
            if (profile.role in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT)) {
                add(uiText(R.string.ui_email) to profile.email)
                if (profile.email.isNotBlank()) {
                    add(
                        uiText(R.string.email_notifications_label) to uiText(
                            if (profile.emailCommunicationConsent) R.string.email_notifications_enabled
                            else R.string.email_notifications_disabled
                        )
                    )
                }
            }
            add(uiText(R.string.preferred_language) to locale.getDisplayLanguage(locale))
            add(
                uiText(R.string.region_city_state) to profile.region
            )
            // Only users choose a discreet cover; other roles open their workspace directly.
            if (profile.role == AccountRole.USER) {
                add(uiText(R.string.cover_app) to profile.coverType.localizedTitle(uiText))
                add(
                    uiText(R.string.delivery_address) to listOf(
                        profile.address.line1,
                        profile.address.line2,
                        profile.address.city,
                        profile.address.province,
                        profile.address.postalCode,
                        profile.address.country
                    ).filter { it.isNotBlank() }.joinToString(", ")
                )
            }
        }
        rows.forEachIndexed { index, (label, value) ->
            OutlinedCard(Modifier.fillMaxWidth().then(if (index == 0) Modifier.walkthroughTarget("profile_summary_cards") else Modifier)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = OliveGreen)
                    Text(value.ifBlank { stringResource(R.string.not_provided) }, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
