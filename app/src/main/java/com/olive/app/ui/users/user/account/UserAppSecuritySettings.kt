// Safe-app users: lets users change PIN and device-lock options.
package com.olive.app.ui.users.user.account


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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.security.InputPolicy
import com.olive.app.security.SafeEntryMethod
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.rememberResourceText
import com.olive.app.ui.users.shared.auth.AppPinSetup
import com.olive.app.ui.users.shared.navigation.Field
import com.olive.app.ui.users.shared.navigation.FormColumn
import com.olive.app.ui.users.shared.navigation.Heading
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.users.shared.navigation.Status
import com.olive.app.ui.users.shared.onboarding.OnboardingNavigation
import com.olive.app.ui.users.shared.onboarding.OnboardingProgress
import com.olive.app.ui.users.shared.onboarding.RequirementBullet

// The safety answer changes the recommendation, never silently chooses an unlock method.
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

// Draws the device lock prompt for the user safe app.
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

// The account owner sets this private trigger personally on first login.
@Composable
internal fun TriggerSetup(session: SessionViewModel) {
    val uiText = rememberResourceText()
    var trigger by remember { mutableStateOf("") }
    var showErrors by rememberSaveable { mutableStateOf(false) }
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
            error = if (showErrors && !valid) uiText(R.string.ui_invalid_trigger) else null
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
            },
            last = true,
            nextTag = "trigger_setup_submit",
            enabled = !session.busy
        )
    }
}

// Both methods may be configured; only the preferred method appears on a normal trigger.
@Composable
internal fun SafeEntrySettings(session: SessionViewModel) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val text = rememberResourceText()
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
    if (session.account.canUseAppPin) Field(
        text(R.string.current_device_pin),
        currentPin,
        "current_device_pin",
        secret = true,
        numeric = true,
        showSecretToggle = true,
        enabled = !session.busy
    ) { currentPin = it.filter(Char::isDigit).take(6) }
    Button(
        onClick = {
            session.account.setSafeEntryMethod(
                SafeEntryMethod.DEVICE_LOCK,
                currentPin
            )
            currentPin = ""
        },
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
            onClick = {
                session.account.setSafeEntryMethod(
                    SafeEntryMethod.APP_PIN,
                    currentPin
                )
                currentPin = ""
            }, enabled = !session.busy,
            modifier = Modifier.fillMaxWidth().testTag("entry_use_pin")
        ) { Text(stringResource(R.string.entry_use_pin)) }
    }
    Text(
        stringResource(R.string.entry_pin_setup),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() })
    Field(
        text(R.string.new_device_pin),
        newPin,
        "new_device_pin",
        secret = true,
        numeric = true,
        showSecretToggle = true,
        enabled = !session.busy
    ) { newPin = it.filter(Char::isDigit).take(6) }
    Field(
        text(R.string.confirm_new_device_pin),
        confirmation,
        "confirm_new_device_pin",
        secret = true,
        numeric = true,
        showSecretToggle = true,
        enabled = !session.busy
    ) { confirmation = it.filter(Char::isDigit).take(6) }
    // Validates the form before saving a new backup PIN or entry method.
    fun save(usePin: Boolean) {
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
