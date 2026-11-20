package com.olive.app.ui.users.shared.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.olive.app.R
import com.olive.app.security.InputPolicy
import com.olive.app.ui.users.shared.navigation.*

/**
 * The same PIN verifier and lockout rules, with Android's numeric password keyboard for everyone.
 *
 * @param session Shared session view model owning application state and actions.
 * @param challenge Challenge supplied to [SystemPinPrompt].
 */
@Composable
internal fun SystemPinPrompt(session: SessionViewModel, challenge: Int) {
    var pin by remember(challenge) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    /**
     * Computes submit.
     */
    fun submit() {
        if (session.account.busy || session.privacyBlackout || session.screen != SessionScreen.Pin ||
            session.pinChallengeVersion != challenge || !InputPolicy.validPin(pin)) return
        val attempt = pin
        pin = ""
        session.account.submitPin(attempt, challenge)
    }
    LaunchedEffect(challenge) { withFrameNanos { }; focus.requestFocus(); keyboard?.show() }
    FormColumn {
        Heading(stringResource(R.string.enter_pin), stringResource(R.string.enter_your_pin))
        OutlinedTextField(
            value = pin,
            onValueChange = { value -> if (value.length <= 6 && value.all(Char::isDigit)) pin = value },
            label = { Text(stringResource(R.string.a11y_pin)) },
            enabled = !session.account.busy,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done,
                autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("unlock_pin"),
        )
        Status(session)
        Button(onClick = ::submit, enabled = !session.account.busy && InputPolicy.validPin(pin),
            modifier = Modifier.fillMaxWidth().testTag("unlock_submit")) {
            Text(stringResource(R.string.a11y_unlock))
        }
    }
}
