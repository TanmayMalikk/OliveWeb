// All safe-app roles: shows sign-in and registration for every account role.
package com.olive.app.ui.users.shared.auth

import com.olive.app.ui.shared.*
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.shared.onboarding.*
import androidx.compose.ui.res.stringResource
import com.olive.app.R
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.olive.app.data.*
import com.olive.app.security.InputPolicy
import com.olive.app.ui.users.user.account.*
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp


// ALL ACCOUNT TYPES: role selection routes each person into the correct onboarding path.
// USER and SAFE CONTACT can create accounts here; STAFF and ADMIN use administrator-issued logins.
// UI sizing/text: the role menu inherits FormColumn spacing; descriptions intentionally use bodySmall and Muted so long translations can wrap.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Welcome(session: SessionViewModel) {
    val uiText = rememberResourceText()
    var roleOpen by remember { mutableStateOf(false) }
    FormColumn {
        Heading(
            uiText(R.string.welcome),
            uiText(R.string.first_choose_what_kind_of_account_you_use),
            if (session.account.role in setOf(
                    AccountRole.STAFF,
                    AccountRole.ADMIN
                )
            ) uiText(R.string.olive_tree) else uiText(R.string.olive_branch)
        )
        ModeNotice(session)
        ExposedDropdownMenuBox(expanded = roleOpen, onExpandedChange = { roleOpen = !roleOpen }) {
            OutlinedTextField(
                value = session.account.role.localizedTitle(uiText),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.account_type)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(roleOpen) },
                modifier = Modifier.fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .testTag("account_role")
            )
            ExposedDropdownMenu(expanded = roleOpen, onDismissRequest = { roleOpen = false }) {
                AccountRole.entries.forEach { role ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(role.localizedTitle(uiText))
                                Text(
                                    role.localizedDescription(
                                        uiText
                                    ), style = MaterialTheme.typography.bodySmall, color = Muted
                                )
                            }
                        },
                        onClick = {
                            session.account.selectRole(role)
                            roleOpen = false
                        },
                        modifier = Modifier.testTag("role_${role.name.lowercase()}")
                    )
                }
            }
        }
        // Users and safe contacts can create accounts here; organization accounts are created by an admin.
        if (session.account.role == AccountRole.USER || session.account.role == AccountRole.SAFE_CONTACT) {
            Button(
                onClick = session.account::chooseNew,
                enabled = !session.account.busy,
                modifier = Modifier.fillMaxWidth().testTag("new_user")
            ) { Text(stringResource(R.string.create_a_new_account)) }
            OutlinedButton(
                onClick = session.account::chooseReturning,
                enabled = !session.account.busy,
                modifier = Modifier.fillMaxWidth().testTag("returning_user")
            ) { Text(stringResource(R.string.sign_in_to_an_existing_account)) }
        } else {
            Text(
                uiText(
                    R.string.ui_value_accounts_are_created_by_an_administrator_sign_in_with_your_,
                    session.account.role.localizedTitle(uiText)
                ), color = Muted
            )
            Button(
                onClick = session.account::chooseReturning,
                enabled = !session.account.busy,
                modifier = Modifier.fillMaxWidth().testTag("returning_user")
            ) { Text(stringResource(R.string.sign_in)) }
        }
        Status(session)
    }
}

// ALL ACCOUNT TYPES: username/password sign-in. New-account creation is available only to USER and SAFE CONTACT.
// UI sizing/text: credential fields use the shared full-width field size; password guidance uses bodySmall and Muted.
@Composable
internal fun Credentials(session: SessionViewModel, create: Boolean) {
    val uiText = rememberResourceText()
    // Deliberately not rememberSaveable: passwords never enter Android saved-state bundles.
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    val organizationLogin =
        !create && session.account.role in setOf(AccountRole.STAFF, AccountRole.ADMIN)
    val usernameValid =
        if (organizationLogin && '@' in username) android.util.Patterns.EMAIL_ADDRESS.matcher(
            username.trim()
        ).matches()
        else if (create) InputPolicy.validUsername(username) else InputPolicy.validExistingUsername(
            username
        )
    val passwordValid =
        if (create) InputPolicy.validAccountPassword(password, username) else password.isNotEmpty()
    val confirmationValid = !create || (confirm.isNotEmpty() && confirm == password)
    val completed =
        listOf(usernameValid, passwordValid, confirmationValid).take(if (create) 3 else 2)
            .count { it }
    val stages = when {
        create && session.account.role == AccountRole.USER -> 4
        create && session.account.role == AccountRole.SAFE_CONTACT -> 3
        !create && session.account.role == AccountRole.USER -> 2
        else -> 1
    }
    val totalRequired = when {
        create && session.account.role == AccountRole.USER -> 9
        create && session.account.role == AccountRole.SAFE_CONTACT -> 6
        !create && session.account.role == AccountRole.USER -> 4
        else -> 2
    }
    val usernameError = when {
        session.account.credentialFieldError == CredentialFieldError.UsernameTaken -> uiText(R.string.ui_username_taken)
        submitted && create && !InputPolicy.usernameHasLetter(username) -> uiText(R.string.ui_at_least_1_letter)
        submitted && !usernameValid -> uiText(R.string.ui_invalid_username)
        else -> null
    }
    val passwordError = when {
        session.account.credentialFieldError == CredentialFieldError.IncorrectPassword -> uiText(R.string.ui_incorrect_password)
        submitted && create && !InputPolicy.passwordExcludesUsername(
            password,
            username
        ) -> uiText(R.string.password_excludes_username)

        submitted && !passwordValid -> if (create) uiText(R.string.ui_invalid_password) else uiText(
            R.string.ui_password_required
        )

        else -> null
    }
    val confirmError =
        if (create && submitted && !confirmationValid) uiText(R.string.ui_passwords_differ) else null
    FormColumn {
        Heading(
            if (create) uiText(
                R.string.ui_create_your_value_account,
                session.account.role.localizedTitle(uiText)
            ) else uiText(R.string.ui_value_sign_in, session.account.role.localizedTitle(uiText)),
            if (create) uiText(R.string.use_these_credentials_when_setting_up_another_device)
            else when (session.account.role) {
                AccountRole.USER -> uiText(R.string.sign_in_with_your_existing_username_and_password_next_create_a_pin_for_this_device)
                AccountRole.SAFE_CONTACT -> uiText(R.string.sign_in_with_your_existing_username_and_password)
                else -> uiText(R.string.ui_use_the_credentials_issued_by_an_administrator)
            },
            if (session.account.role in setOf(
                    AccountRole.STAFF,
                    AccountRole.ADMIN
                )
            ) uiText(R.string.olive_tree) else uiText(R.string.olive_branch)
        )
        ModeNotice(session)
        if (stages > 1) OnboardingProgress(
            completed, totalRequired, 0, stages,
            List(stages) { index -> index == 0 && usernameValid && passwordValid && confirmationValid })
        Field(
            if (organizationLogin) uiText(R.string.ui_username_or_email) else uiText(R.string.username),
            username,
            "username",
            enabled = !session.account.busy,
            error = usernameError
        ) {
            username = it.take(if (create) 24 else 254)
            submitted =
                false
            session.account.clearCredentialError()
        }
        // Only do this when create.
        if (create) {
            // UI spacing: keep related validation bullets close together below their input field.
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                RequirementBullet(
                    uiText(R.string.ui_8_24_characters),
                    username.length in 8..24,
                    username.isNotEmpty()
                )
                RequirementBullet(
                    uiText(R.string.ui_letters_numbers_or),
                    username.matches(Regex("[A-Za-z0-9_]+")),
                    username.isNotEmpty()
                )
                RequirementBullet(
                    uiText(R.string.ui_at_least_1_letter),
                    InputPolicy.usernameHasLetter(username),
                    username.isNotEmpty()
                )
            }
        }
        Field(
            uiText(R.string.password),
            password,
            "password",
            secret = true,
            showSecretToggle = true,
            enabled = !session.account.busy,
            error = passwordError
        ) {
            password = it.take(if (create) 24 else 128)
            submitted =
                false
            session.account.clearCredentialError()
        }
        // Registration shows confirmation and role fields that sign-in does not need.
        if (create) {
            // UI spacing: password requirements read as one compact list.
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                RequirementBullet(
                    uiText(R.string.ui_8_24_characters),
                    InputPolicy.passwordHasValidLength(password),
                    password.isNotEmpty()
                )
                RequirementBullet(
                    uiText(R.string.ui_at_least_1_letter),
                    InputPolicy.passwordHasLetter(password),
                    password.isNotEmpty()
                )
                RequirementBullet(
                    uiText(R.string.ui_at_least_1_number),
                    InputPolicy.passwordHasNumber(password),
                    password.isNotEmpty()
                )
                RequirementBullet(
                    uiText(R.string.ui_at_least_1_special_character),
                    InputPolicy.passwordHasSpecialCharacter(password),
                    password.isNotEmpty()
                )
                RequirementBullet(
                    uiText(R.string.password_excludes_username),
                    InputPolicy.passwordExcludesUsername(password, username),
                    password.isNotEmpty()
                )
            }
            Field(
                uiText(R.string.confirm_password),
                confirm,
                "confirm_password",
                secret = true,
                showSecretToggle = true,
                enabled = !session.account.busy,
                error = confirmError
            ) {
                confirm = it.take(24)
                submitted = false
                session.account.clearCredentialError()
            }
            RequirementBullet(
                uiText(R.string.ui_passwords_match),
                confirmationValid,
                password.isNotEmpty() || confirm.isNotEmpty()
            )
        }
        Status(session, showMessage = session.account.credentialFieldError == null)
        OnboardingNavigation(
            session.navigation::back, {
                submitted = true
                if (usernameValid && passwordValid && confirmationValid) session.account.authenticate(
                    username,
                    password,
                    create
                )
            }, nextTag = "credentials_submit", enabled = !session.account.busy,
            nextLabel = if (create) uiText(R.string.create_account) else uiText(R.string.sign_in)
        )
    }
}

// All safe-app roles: shows PIN entry before opening the safe app.

// USER ONLY: enrollment chooses a separate app PIN or Android device authentication.
@Composable
internal fun DevicePin(session: SessionViewModel) = SafeEntrySetup(session)

// UI sizing/text: PIN setup uses the shared form spacing and full-width fields; warning copy uses the Muted color.
@Composable
internal fun AppPinSetup(session: SessionViewModel, onBack: () -> Unit) {
    val uiText = rememberResourceText()
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val pinValid = InputPolicy.validPin(pin)
    val confirmationValid = confirm.isNotEmpty() && confirm == pin
    val stages = if (session.account.creatingAccount) 4 else 2
    val page = stages - 1
    val completedBeforePin = if (session.account.creatingAccount) 7 else 2
    val totalRequired = completedBeforePin + 2
    FormColumn {
        Heading(uiText(R.string.entry_setup_title), uiText(R.string.entry_new_pin_description))
        OnboardingProgress(
            completedBeforePin + listOf(pinValid, confirmationValid).count { it },
            totalRequired,
            page,
            stages,
            List(stages) { index -> index < page || (index == page && pinValid && confirmationValid) })
        Field(
            uiText(R.string.str_4_6_digit_pin),
            pin,
            "new_pin",
            secret = true,
            numeric = true,
            showSecretToggle = true,
            enabled = !session.account.busy,
            error = if (showErrors && !pinValid) uiText(R.string.ui_invalid_pin) else null
        ) {
            pin = it.filter(Char::isDigit).take(6)
            showErrors = false
        }
        RequirementBullet(uiText(R.string.ui_4_6_digits), pinValid, pin.isNotEmpty())
        Field(
            uiText(R.string.confirm_pin),
            confirm,
            "confirm_pin",
            secret = true,
            numeric = true,
            showSecretToggle = true,
            enabled = !session.account.busy,
            error = if (showErrors && !confirmationValid) uiText(R.string.ui_pins_differ) else null
        ) {
            confirm = it.filter(Char::isDigit).take(6)
            showErrors = false
        }
        RequirementBullet(
            uiText(R.string.ui_pins_match),
            confirmationValid,
            pin.isNotEmpty() || confirm.isNotEmpty()
        )
        Status(session)
        OnboardingNavigation(
            onBack,
            {
                showErrors = true
                if (pinValid && confirmationValid) session.account.createDevicePin(
                    pin,
                    confirm
                )
            },
            last = true,
            nextTag = "pin_setup_submit",
            enabled = !session.account.busy
        )
    }
}

// USER ONLY: a cover trigger opens this local PIN challenge before private content is available.
// UI sizing/color: this screen owns the compact breakpoint, keypad dimensions, PIN-dot size, OliveGreen accents, and responsive text sizes.
@Composable
internal fun PinPrompt(session: SessionViewModel) {
    val challenge = session.pinChallengeVersion
    var useBackupPin by remember(challenge) { mutableStateOf(false) }
    // Open Android authentication when device lock is preferred and the user did not choose the backup PIN.
    if (session.account.usesDeviceLock && !useBackupPin) {
        DeviceLockPrompt(
            session = session,
            onUseBackupPin = if (session.account.hasAppPin) {
                { useBackupPin = true }
            } else null,
        )
        return
    }
    if (!session.account.canUseAppPin) {
        FormColumn {
            Heading(
                stringResource(R.string.entry_device_lock),
                stringResource(R.string.entry_restore_lock)
            )
            Text(
                stringResource(R.string.entry_missing_backup_pin),
                color = Muted,
                modifier = Modifier.testTag("missing_backup_pin"),
            )
            Status(session)
        }
        return
    }
    val uiText = rememberResourceText()
    var pin by remember(challenge) { mutableStateOf("") }
    val headingScrollState = rememberScrollState()
    val pinLabel = uiText(R.string.a11y_pin)
    val enteredLabel = uiText(R.string.a11y_digits_entered)
    val deleteLabel = uiText(R.string.a11y_delete_digit)
    val unlockLabel = uiText(R.string.a11y_unlock)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Sizing note: raise 650dp to switch into the compact PIN layout sooner. The 48–72dp range below is the keypad's min/max size.
        // When adding a language with longer PIN text, test this breakpoint with Android's largest font setting too.
        val compact = maxHeight < 650.dp
        val sidePadding = if (maxWidth < 360.dp) 12.dp else 24.dp
        val horizontalLimit = (maxWidth - sidePadding * 2 - 32.dp) / 3
        val verticalLimit = (maxHeight - if (compact) 190.dp else 235.dp) / 4
        val keySize = minOf(72.dp, horizontalLimit, verticalLimit).coerceAtLeast(48.dp)
        val gap = if (compact) 7.dp else 12.dp
        Column(
            Modifier.fillMaxSize()
                .padding(horizontal = sidePadding, vertical = if (compact) 10.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 12.dp)
        ) {
            Column(
                Modifier.weight(1f).oliveScrollbar(headingScrollState)
                    .verticalScroll(headingScrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 12.dp)
            ) {
                // These are the compact/regular PIN headings; change both together so translated text scales consistently.
                Text(
                    stringResource(R.string.olive_branch),
                    fontFamily = FontFamily.Serif,
                    fontSize = if (compact) 22.sp else 26.sp,
                    color = OliveGreen
                )
                Text(
                    stringResource(R.string.enter_pin),
                    modifier = Modifier.padding(top = if (compact) 12.dp else 18.dp)
                        .semantics { heading() },
                    style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium
                )
                Text(stringResource(R.string.enter_your_pin), color = Muted)
                Row(
                    Modifier.height(if (compact) 11.dp else 14.dp).testTag("unlock_pin")
                        .clearAndSetSemantics {
                            contentDescription = pinLabel
                            stateDescription = "$enteredLabel: ${pin.length}"
                            liveRegion = LiveRegionMode.Polite
                        },
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)
                ) {
                    repeat(pin.length) {
                        Box(
                            Modifier.size(if (compact) 11.dp else 14.dp)
                                .background(OliveGreen, CircleShape)
                        )
                    }
                }
                Status(session)
            }
            // The keypad scales from the remaining width and height so compact phones never crop its bottom rows.
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("⌫", "0", "↪")
                ).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { key ->
                            val tag = when (key) {
                                "⌫" -> "pin_backspace"
                                "↪" -> "unlock_submit"
                                else -> "pin_digit_$key"
                            }
                            FilledTonalButton(
                                onClick = {
                                    // TalkBack can deliver an action from a node whose enabled state is stale.
                                    if (session.account.busy || session.privacyBlackout || session.screen != SessionScreen.Pin ||
                                        session.pinChallengeVersion != challenge
                                    ) return@FilledTonalButton
                                    // Shows the next PIN setup step based on the chosen entry method.
                                    when (key) {
                                        "⌫" -> pin = pin.dropLast(1)
                                        "↪" -> if (InputPolicy.validPin(pin)) {
                                            val attempt = pin
                                            pin = ""
                                            session.account.submitPin(
                                                attempt,
                                                challenge
                                            )
                                        }

                                        else -> if (pin.length < 6) pin += key
                                    }
                                }, enabled = !session.account.busy && (key != "↪" || pin.length in 4..6),
                                modifier = Modifier.size(keySize).testTag(tag), shape = CircleShape,
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(
                                    key,
                                    modifier = if (key == "⌫" || key == "↪") Modifier.clearAndSetSemantics {
                                        contentDescription =
                                            if (key == "⌫") deleteLabel else unlockLabel
                                    } else Modifier,
                                    fontSize = if (compact) 23.sp else if (key == "⌫" || key == "↪") 26.sp else 30.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
