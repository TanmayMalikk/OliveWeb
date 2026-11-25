// All safe-app roles: shows sign-in and registration for every account role.
package com.olive.app.ui.users.shared.auth

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.*
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.shared.onboarding.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.olive.app.R
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.olive.app.security.InputPolicy
import com.olive.app.ui.users.user.account.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.saveable.rememberSaveable
import com.olive.app.data.account.AccountRole
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds


/**
 * ALL ACCOUNT TYPES: role selection routes each person into the correct onboarding path. USER and SAFE CONTACT can create accounts here; STAFF and ADMIN use administrator-issued logins. UI sizing/text: the role menu inherits FormColumn spacing; descriptions intentionally use bodySmall and Muted so long translations can wrap.
 *
 * @param session Shared session view model owning application state and actions.
 */
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
                    .semantics { stateDescription = uiText(if (roleOpen) R.string.role_picker_expanded else R.string.role_picker_collapsed) }
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

/**
 * ALL ACCOUNT TYPES: username/password sign-in. New-account creation is available only to USER and SAFE CONTACT. UI sizing/text: credential fields use the shared full-width field size; password guidance uses bodySmall and Muted.
 *
 * @param session Shared session view model owning application state and actions.
 * @param create Whether to apply create.
 */
@Composable
internal fun Credentials(session: SessionViewModel, create: Boolean) {
    val uiText = rememberResourceText()
    // Deliberately not rememberSaveable: passwords never enter Android saved-state bundles.
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    var shakeAttempt by rememberSaveable(create) { mutableIntStateOf(0) }
    val combinedShakeTrigger = shakeAttempt + session.account.inputErrorVersion
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
        create && session.account.role == AccountRole.USER -> 6
        create && session.account.role == AccountRole.SAFE_CONTACT -> 4
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
            error = usernameError,
            shakeTrigger = combinedShakeTrigger
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
            error = passwordError,
            newPassword = create,
            shakeTrigger = combinedShakeTrigger
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
                error = confirmError,
                shakeTrigger = combinedShakeTrigger
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
                if (usernameValid && passwordValid && confirmationValid) {
                    session.account.authenticate(username, password, create)
                } else {
                    shakeAttempt += 1
                }
            }, nextTag = "credentials_submit", enabled = !session.account.busy,
            nextLabel = if (create) uiText(R.string.create_account) else uiText(R.string.sign_in)
        )
    }
}

// All safe-app roles: shows PIN entry before opening the safe app.

/**
 * USER ONLY: enrollment chooses a separate app PIN or Android device authentication.
 *
 * @param session Shared session view model owning application state and actions.
 */
@Composable
internal fun DevicePin(session: SessionViewModel) = SafeEntrySetup(session)

/**
 * UI sizing/text: PIN setup uses the shared form spacing and full-width fields; warning copy uses the Muted color.
 *
 * @param session Shared session view model owning application state and actions.
 * @param onBack Callback invoked when the user navigates back.
 */
@Composable
internal fun AppPinSetup(session: SessionViewModel, onBack: () -> Unit) {
    val uiText = rememberResourceText()
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var shakeAttempt by rememberSaveable { mutableIntStateOf(0) }
    val pinValid = InputPolicy.validPin(pin)
    val confirmationValid = confirm.isNotEmpty() && confirm == pin
    val stages = if (session.account.creatingAccount) 7 else 2
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
            error = if (showErrors && !pinValid) uiText(R.string.ui_invalid_pin) else null,
            shakeTrigger = shakeAttempt
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
            error = if (showErrors && !confirmationValid) uiText(R.string.ui_pins_differ) else null,
            shakeTrigger = shakeAttempt
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
                if (pinValid && confirmationValid) {
                    session.account.createDevicePin(pin, confirm)
                } else {
                    shakeAttempt += 1
                }
            },
            last = true,
            nextTag = "pin_setup_submit",
            enabled = !session.account.busy
        )
    }
}

/**
 * USER ONLY: a cover trigger opens this local PIN challenge before private content is available. App PIN entry uses the system numeric password keyboard for every user.
 *
 * @param session Shared session view model owning application state and actions.
 */
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
    SystemPinPrompt(session, challenge)
}

/**
 * The same PIN verifier and lockout rules, with Android's numeric password keyboard for everyone.
 *
 * @param session Shared session view model owning application state and actions.
 * @param challenge Challenge supplied to [SystemPinPrompt].
 */
@Composable
internal fun SystemPinPrompt(session: SessionViewModel, challenge: Int) {
    val uiText = rememberResourceText()
    val expectedLength = session.account.appPinLength
    val pinLabel = stringResource(R.string.a11y_pin)
    val digitsEntered = stringResource(R.string.a11y_digits_entered)
    val pinField = remember(challenge) { TextFieldState() }
    val enteredLength = pinField.text.length
    var localShake by remember(challenge) { mutableIntStateOf(0) }
    var pinError by remember(challenge) { mutableStateOf(false) }
    val sessionErrorVersion = session.account.inputErrorVersion
    var lastSeenErrorVersion by remember(challenge) { mutableIntStateOf(sessionErrorVersion) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val imeInsets = WindowInsets.ime
    val density = LocalDensity.current
    var pinFieldPlaced by remember(challenge) { mutableStateOf(false) }
    var openedKeyboard by remember(challenge) { mutableStateOf(false) }
    LaunchedEffect(pinField.text) {
        if (pinField.text.isNotEmpty()) pinError = false
    }
    LaunchedEffect(sessionErrorVersion) {
        if (sessionErrorVersion > lastSeenErrorVersion) {
            lastSeenErrorVersion = sessionErrorVersion
            pinError = true
            localShake += 1
        }
    }
    /**
     * Submits the entered PIN or shows feedback if digits are missing.
     */
    fun submit() {
        if (session.account.busy || session.privacyBlackout || session.screen != SessionScreen.Pin ||
            session.pinChallengeVersion != challenge) return
        val attempt = pinField.text.toString()
        if (!InputPolicy.validPin(attempt) || (expectedLength != null && attempt.length != expectedLength)) {
            pinError = true
            localShake += 1
            session.account.showError(uiText(if (expectedLength == null) R.string.enter_your_4_6_digit_pin else R.string.enter_your_pin))
            return
        }
        pinError = false
        pinField.edit { replace(0, length, "") }
        session.account.submitPin(attempt, challenge)
    }
    val readyForKeyboard = pinFieldPlaced && windowFocused && !session.account.busy &&
        session.screen == SessionScreen.Pin && !session.privacyBlackout
    LaunchedEffect(challenge, readyForKeyboard, keyboard) {
        if (!readyForKeyboard || openedKeyboard || keyboard == null) return@LaunchedEffect
        // The cover editor can finish hiding its IME after the PIN editor has taken focus.
        for (attempt in 0 until 4) {
            if (attempt > 0 && imeInsets.getBottom(density) > 0) break
            if (focus.requestFocus()) {
                withFrameNanos { }
                keyboard.show()
            }
            delay(180.milliseconds)
        }
        openedKeyboard = true
    }
    FormColumn {
        Column(
            Modifier.align(Alignment.CenterHorizontally).widthIn(max = 320.dp)
                .fillMaxWidth().padding(top = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Text(
                stringResource(R.string.enter_pin),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            BasicSecureTextField(
                state = pinField,
                inputTransformation = InputTransformation {
                    if (length > (expectedLength ?: 6) || asCharSequence().any { it !in '0'..'9' }) revertAllChanges()
                },
                enabled = !session.account.busy,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.Transparent),
                cursorBrush = SolidColor(Color.Unspecified),
                textObfuscationMode = TextObfuscationMode.Hidden,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done,
                    autoCorrectEnabled = false, showKeyboardOnFocus = true),
                onKeyboardAction = { submit() },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("unlock_pin")
                    .onGloballyPositioned { pinFieldPlaced = true }
                    .oliveErrorShake(localShake)
                    .semantics {
                        contentDescription = pinLabel
                        stateDescription = "$digitsEntered: " +
                            if (expectedLength != null) "$enteredLength/$expectedLength" else enteredLength.toString()
                    },
                decorator = { innerField ->
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) { innerField() }
                        PinDots(expectedLength, enteredLength, pinError)
                    }
                },
            )
            Status(session)
            Button(onClick = ::submit, enabled = !session.account.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("unlock_submit")) {
                Text(stringResource(R.string.a11y_unlock))
            }
        }
    }
}

/**
 * Draws the PIN indicators and shows the accepted length when the saved PIN length is unknown.
 *
 * @param expectedLength The configured PIN length, or null when it is unknown.
 * @param enteredLength The number of digits currently entered.
 * @param isError Whether to draw the indicators in their error state.
 */
@Composable
private fun PinDots(expectedLength: Int?, enteredLength: Int, isError: Boolean) {
    if (expectedLength == null && enteredLength == 0) {
        Text(stringResource(R.string.ui_4_6_digits), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val outline = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline
    val fill = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(expectedLength ?: enteredLength) { index ->
            Box(
                Modifier.size(18.dp)
                    .testTag("unlock_pin_dot_$index")
                    .background(if (index < enteredLength) fill else Color.Transparent, CircleShape)
                    .border(1.5.dp, if (index < enteredLength) fill else outline, CircleShape),
            )
        }
    }
}
