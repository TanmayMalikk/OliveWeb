// Safe-app users: shows account, privacy, and entry settings.
package com.olive.app.ui.users.user.account


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.security.InputPolicy
import com.olive.app.security.IdlePolicy
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.UserProfile
import com.olive.app.ui.shared.AddressFields
import com.olive.app.ui.shared.CardTintEnabled
import com.olive.app.ui.shared.Cream
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.OlivePrivateLayout
import com.olive.app.ui.shared.rememberResourceText
import com.olive.app.ui.users.shared.navigation.Field
import com.olive.app.ui.users.shared.navigation.FormColumn
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.users.shared.navigation.Status
import com.olive.app.ui.shared.BirthdayPicker
import com.olive.app.ui.shared.rememberBirthdayInput
import com.olive.app.ui.shared.LocalizedIcon as Icon
import kotlin.math.roundToInt


// Saves profile and situation edits from the user settings screen.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsEditor(session: SessionViewModel, profile: UserProfile) {
    val uiText = rememberResourceText()
    val userRole = profile.role == AccountRole.USER
    val tabs = if (userRole) SettingsCategory.entries else listOf(SettingsCategory.Profile, SettingsCategory.Password)
    var selectedTab by rememberSaveable(session.coverAccountId) { mutableStateOf(SettingsCategory.Profile) }
    val activeTab = selectedTab.takeIf { it in tabs } ?: SettingsCategory.Profile
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val entryPinDraft = remember { SafeEntryPinDraft() }
    var fullName by remember { mutableStateOf(profile.fullName) }
    var affected by remember { mutableStateOf(profile.affectedNames) }
    var abuserName by remember { mutableStateOf(profile.abuserName) }
    var birthday by rememberBirthdayInput(profile.dob)
    var showBirthdayErrors by remember(profile.dob) { mutableStateOf(false) }
    var situation by remember { mutableStateOf(profile.situation) }
    var address by remember { mutableStateOf(profile.address) }
    var trigger by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    val cacheUid = session.coverAccountId
    LaunchedEffect(cacheUid) {
        cacheUid?.let { session.media?.loadRecentCachingPreference(it) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SafeBack(session)
            Text(
                uiText(R.string.settings),
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.headlineSmall
            )
        }
        SecondaryScrollableTabRow(
            selectedTabIndex = tabs.indexOf(activeTab),
            edgePadding = 8.dp
        ) {
            tabs.forEach { tab ->
                Tab(
                    selected = tab == activeTab,
                    onClick = {
                        if (tab != activeTab) {
                            keyboard?.hide()
                            focus.clearFocus()
                            selectedTab = tab
                        }
                    },
                    modifier = Modifier.testTag(tab.tag),
                    text = { Text(uiText(tab.label)) }
                )
            }
        }
        key(activeTab) {
            FormColumn {
                when (activeTab) {
                    SettingsCategory.Profile ->
                        SettingsSection(uiText(R.string.settings_category_profile)) {
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
                            // User accounts can edit cover and private-entry settings here.
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
                            BirthdayPicker(birthday, "settings_dob", !session.busy, showBirthdayErrors) { birthday = it }
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
                            Button(onClick = save@{
                                showBirthdayErrors = !InputPolicy.validDob(birthday)
                                if (showBirthdayErrors) return@save
                                session.saveSettings(
                                    fullName,
                                    affected,
                                    abuserName,
                                    birthday,
                                    situation,
                                    address
                                )
                            }, enabled = !session.busy, modifier = Modifier.testTag("save_settings")) {
                                Text(
                                    stringResource(R.string.save_settings)
                                )
                            }
                        }
                    SettingsCategory.Password -> {
                        SettingsSection(uiText(R.string.change_account_password)) {
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
                        }
                        if (!userRole) {
                            OutlinedButton(
                                onClick = session::signOut, enabled = !session.busy,
                                modifier = Modifier.fillMaxWidth().testTag("sign_out")
                            ) {
                                Text(stringResource(R.string.sign_out_on_this_device))
                            }
                        }
                    }
                    SettingsCategory.Privacy ->
                        SettingsSection(uiText(R.string.settings_category_privacy)) {
                            IdleTimeoutSetting(session)
                            HorizontalDivider()
                            Text(
                                stringResource(R.string.documents_recent_cache_title),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.semantics { heading() },
                            )
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    stringResource(R.string.documents_recent_cache_help),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Muted,
                                    modifier = Modifier.weight(1f),
                                )
                                Switch(
                                    checked = session.media?.recentCachingEnabled == true,
                                    onCheckedChange = { enabled ->
                                        cacheUid?.let { session.media?.setRecentCachingPreference(it, enabled) }
                                    },
                                    enabled = cacheUid != null,
                                    modifier = Modifier.testTag("recent_file_caching"),
                                )
                            }
                        }
                    SettingsCategory.Cover ->
                        SettingsSection(uiText(R.string.settings_category_cover_entry)) {
                            // Change the visible app and launcher icon here; add new covers to CoverType and CoverLauncher.
                            CoverPicker(
                                profile.coverType,
                                !session.busy
                            ) { type ->
                                session.recordSettingsInteraction(
                                    "Olive Branch",
                                    "Selected cover app"
                                )
                                session.changeCover(type)
                            }
                            HorizontalDivider()
                            SafeEntrySettings(session, entryPinDraft)
                            HorizontalDivider()
                            Text(
                                stringResource(R.string.change_trigger_word),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.semantics { heading() })
                            Text(stringResource(if (session.account.usesDeviceLock) R.string.entry_verify_trigger else R.string.enter_your_current_device_pin_to_change_the_trigger_word_incorrect_attempts_count_toward_the_five_attempt_limit))
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
                            if (!session.account.usesDeviceLock) Field(
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
                                if (session.account.usesDeviceLock) session.account.changeDeviceLockTrigger(trigger) else session.changeTrigger(
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
                        }
                }
                Status(session)
            }
        }
    }

}

private enum class SettingsCategory(val label: Int, val tag: String) {
    Profile(R.string.ui_profile, "settings_tab_profile"),
    Password(R.string.password, "settings_tab_password"),
    Privacy(R.string.settings_category_privacy, "settings_tab_privacy"),
    Cover(R.string.settings_cover_tab, "settings_tab_cover")
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = OlivePrivateLayout.GroupShape,
        color = CardTintEnabled,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }
            )
            content()
        }
    }
}

@Composable
private fun IdleTimeoutSetting(session: SessionViewModel) {
    val uiText = rememberResourceText()
    val options = IdlePolicy.timeoutOptions
    var timeout by remember(session.coverAccountId) { mutableIntStateOf(session.idleTimeoutSeconds()) }
    val title = uiText(R.string.idle_lock_setting)
    fun duration(seconds: Int): String =
        if (seconds % 60 == 0) uiText(R.string.idle_lock_minutes, seconds / 60)
        else uiText(R.string.idle_lock_minutes_seconds, seconds / 60, seconds % 60)

    val currentDuration = duration(timeout)
    val selectedLabel = if (timeout <= 120) uiText(R.string.idle_lock_recommended, currentDuration) else currentDuration
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(selectedLabel, style = MaterialTheme.typography.bodyLarge, color = OliveGreen)
        Slider(
            value = options.indexOf(timeout).coerceAtLeast(0).toFloat(),
            onValueChange = { timeout = options[it.roundToInt().coerceIn(options.indices)] },
            onValueChangeFinished = {
                if (timeout != session.idleTimeoutSeconds()) session.setIdleTimeoutSeconds(timeout)
            },
            valueRange = 0f..options.lastIndex.toFloat(),
            steps = options.size - 2,
            modifier = Modifier.fillMaxWidth().testTag("idle_timeout").semantics {
                contentDescription = title
                stateDescription = selectedLabel
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(duration(options.first()), style = MaterialTheme.typography.labelMedium, color = Muted)
            Text(duration(options.last()), style = MaterialTheme.typography.labelMedium, color = Muted)
        }
        Text(uiText(R.string.idle_lock_recommended_hint), style = MaterialTheme.typography.bodySmall, color = Muted)
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
        val isSelected = selected == type
        val label = when (type) {
            CoverType.GAME -> uiText(R.string.game_cover_app_olive_tap)
            CoverType.NEWS -> uiText(R.string.news_app_daily_digest)
            CoverType.FITNESS -> uiText(R.string.fitness_cover_choice)
        }
        OutlinedButton(
            onClick = { onSelect(type) },
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (isSelected) OliveGreen else Cream,
                contentColor = if (isSelected) Cream else OliveGreen,
                disabledContainerColor = if (isSelected) OliveGreen.copy(alpha = 0.55f)
                else Cream,
                disabledContentColor = if (isSelected) Cream.copy(alpha = 0.75f)
                else OliveGreen.copy(alpha = 0.55f),
            ),
            border = BorderStroke(
                1.dp,
                if (isSelected) OliveGreen else MaterialTheme.colorScheme.outline,
            ),
            modifier = Modifier.fillMaxWidth()
                .testTag("cover_choice_${type.name.lowercase()}")
                .semantics { this.selected = isSelected },
        ) {
            Icon(
                // Saves the chosen cover, or asks for a backup PIN when the cover needs one.
                when (type) {
                    CoverType.NEWS -> Icons.Outlined.Newspaper
                    CoverType.FITNESS -> Icons.Outlined.FitnessCenter
                    CoverType.GAME -> Icons.Outlined.SportsEsports
                }, null, Modifier.size(20.dp).testTag("cover_choice_icon_${type.name.lowercase()}")
            )
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
    }
}

// The profile screen displays saved information without mixing it with form editing.
