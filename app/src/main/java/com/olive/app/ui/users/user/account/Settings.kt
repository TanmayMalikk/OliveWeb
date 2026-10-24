// Contains the settings code for this part of Olive.
package com.olive.app.ui.users.user.account

import com.olive.app.ui.shared.*


import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.ui.users.shared.onboarding.BirthdayPicker

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.LocalDining
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.data.*
import com.olive.app.R
import com.olive.app.ui.covers.olive.AddressFields
import com.olive.app.ui.shared.LocalizedIcon as Icon
import java.util.UUID


@Composable
// Sets tings editor.
internal fun SettingsEditor(session: SessionViewModel, profile: UserProfile) {
    val uiText = rememberResourceText()
    val userRole = profile.role == AccountRole.USER
    var fullName by remember { mutableStateOf(profile.fullName) }
    var affected by remember { mutableStateOf(profile.affectedNames) }
    var abuserName by remember { mutableStateOf(profile.abuserName) }
    var dob by remember { mutableStateOf(profile.dob) }
    var situation by remember { mutableStateOf(profile.situation) }
    var address by remember { mutableStateOf(profile.address) }
    var trigger by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    FormColumn {
        SafeBack(session)
        Heading(uiText(R.string.settings), uiText(R.string.ui_update_your_profile_details))
        if (userRole) {
            // Change the visible app and launcher icon here; add new covers to CoverType and CoverLauncher.
            CoverPicker(
                profile.coverType,
                !session.busy
            ) {
                session.recordSettingsInteraction(
                    "Olive Branch",
                    "Selected cover app"
                )
                session.changeCover(it)
            }
            Text(
                stringResource(R.string.entry_calculator_help),
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
        }
        Field(
            uiText(R.string.full_name_required),
            fullName,
            "settings_full_name",
            onFocus = {
                session.recordSettingsInteraction(
                    "Olive Branch",
                    "Focused full name"
                )
            }) { fullName = it.take(120) }
        if (userRole) {
            Field(
                uiText(R.string.names_of_people_experiencing_abuse_optional),
                affected,
                "settings_affected",
                onFocus = {
                    session.recordSettingsInteraction(
                        "Olive Branch",
                        "Focused affected names"
                    )
                }) { affected = it.take(500) }
            Field(
                uiText(R.string.abuser_s_name_optional),
                abuserName,
                "settings_abuser_name",
                onFocus = {
                    session.recordSettingsInteraction(
                        "Olive Branch",
                        "Focused abuser name"
                    )
                }) { abuserName = it.take(120) }
        }
        BirthdayPicker(dob, "settings_dob", !session.busy) { dob = it }
        if (userRole) Field(
            uiText(R.string.brief_description_of_your_situation_optional_recommended),
            situation,
            "settings_situation",
            multiline = true,
            onFocus = {
                session.recordSettingsInteraction(
                    "Olive Branch",
                    "Focused situation"
                )
            }) { situation = it.take(2000) }
        Text(
            uiText(R.string.ui_location_city_required),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() })
        AddressFields(
            address,
            cityOnlyRequired = true,
            onFocus = {
                session.recordSettingsInteraction(
                    "Olive Branch",
                    "Focused address: $it"
                )
            }) { address = it }
        Button(onClick = {
            session.saveSettings(
                fullName,
                affected,
                abuserName,
                dob,
                situation,
                address
            )
        }, enabled = !session.busy, modifier = Modifier.testTag("save_settings")) {
            Text(
                stringResource(R.string.save_settings)
            )
        }
        HorizontalDivider()
        Text(
            stringResource(R.string.change_account_password),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.your_username_cannot_be_changed))
        Field(
            uiText(R.string.current_password),
            currentPassword,
            "current_password",
            secret = true,
            showSecretToggle = true,
            enabled = !session.busy,
            onFocus = {
                session.recordSettingsInteraction(
                    "Olive Branch",
                    "Focused current password"
                )
            }) { currentPassword = it.take(24) }
        Field(
            uiText(R.string.new_password),
            newPassword,
            "new_password",
            secret = true,
            showSecretToggle = true,
            enabled = !session.busy,
            onFocus = {
                session.recordSettingsInteraction(
                    "Olive Branch",
                    "Focused new password"
                )
            }) { newPassword = it.take(24) }
        Field(
            uiText(R.string.confirm_new_password),
            confirmPassword,
            "confirm_new_password",
            secret = true,
            showSecretToggle = true,
            enabled = !session.busy,
            onFocus = {
                session.recordSettingsInteraction(
                    "Olive Branch",
                    "Focused password confirmation"
                )
            }) { confirmPassword = it.take(24) }
        Button(onClick = {
            session.changePassword(
                currentPassword,
                newPassword,
                confirmPassword
            )
            currentPassword = ""
            newPassword = ""
            confirmPassword = ""
        }, enabled = !session.busy, modifier = Modifier.testTag("change_password")) {
            Text(
                stringResource(R.string.change_password)
            )
        }
        if (userRole) {
            HorizontalDivider()
            SafeEntrySettings(session)
            HorizontalDivider()
            Text(
                stringResource(R.string.change_trigger_word),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            Text(stringResource(if (session.usesDeviceLock) R.string.entry_verify_trigger else R.string.enter_your_current_device_pin_to_change_the_trigger_word_incorrect_attempts_count_toward_the_five_attempt_limit))
            Field(
                uiText(R.string.new_trigger_word_or_phrase),
                trigger,
                "settings_trigger",
                secret = true,
                onFocus = {
                    session.recordSettingsInteraction(
                        "Olive Branch",
                        "Focused trigger word"
                    )
                }) { trigger = it.take(64) }
            if (!session.usesDeviceLock) Field(
                uiText(R.string.current_pin),
                pin,
                "settings_pin",
                secret = true,
                numeric = true,
                onFocus = {
                    session.recordSettingsInteraction(
                        "Olive Branch",
                        "Focused trigger-change PIN"
                    )
                }) { pin = it.filter { c -> c in '0'..'9' }.take(6) }
            Button(onClick = {
                if (session.usesDeviceLock) session.changeDeviceLockTrigger(trigger) else session.changeTrigger(
                    trigger,
                    pin
                )
                pin = ""
                trigger = ""
            }, enabled = !session.busy, modifier = Modifier.testTag("change_trigger")) {
                Text(
                    stringResource(R.string.change_trigger_word)
                )
            }
        } else {
            HorizontalDivider()
            OutlinedButton(
                onClick = session::signOut, enabled = !session.busy,
                modifier = Modifier.fillMaxWidth().testTag("sign_out")
            ) {
                Text(stringResource(R.string.sign_out_on_this_device))
            }
        }
        Status(session)
    }
}

// UI sizing/text: cover choices fill the form width and use Material button text so longer translated names can wrap.
@Composable
internal fun CoverPicker(selected: CoverType, enabled: Boolean, onSelect: (CoverType) -> Unit) {
    val uiText = rememberResourceText()
    Text(
        stringResource(R.string.choose_your_cover_app),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() })
    Text(
        stringResource(R.string.the_app_stays_open_its_icon_and_name_update_after_you_return_to_the_home_screen),
        style = MaterialTheme.typography.bodySmall,
        color = Muted
    )
    CoverType.entries.forEach { type ->
        val label = when (type) {
            CoverType.FOOD -> uiText(R.string.food_delivery_app_olive)
            CoverType.GAME -> uiText(R.string.game_cover_app_olive_tap)
            CoverType.NEWS -> uiText(R.string.news_app_daily_digest)
            CoverType.CALCULATOR -> uiText(R.string.calculator_better_calculator)
        }
        OutlinedButton(
            onClick = { onSelect(type) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("cover_choice_${type.name.lowercase()}")
        ) {
            Icon(
                when (type) {
                    CoverType.FOOD -> Icons.Outlined.LocalDining
                    CoverType.NEWS -> Icons.Outlined.Newspaper
                    CoverType.CALCULATOR -> Icons.Outlined.Calculate
                    CoverType.GAME -> Icons.Outlined.SportsEsports
                }, null, Modifier.size(20.dp).testTag("cover_choice_icon_${type.name.lowercase()}")
            )
            Spacer(Modifier.width(8.dp))
            Text(if (selected == type) "✓  $label" else label)
        }
    }
}

// The profile screen displays saved information without mixing it with form editing.
