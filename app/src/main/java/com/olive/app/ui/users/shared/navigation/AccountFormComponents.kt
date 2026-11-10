// All safe-app roles: draws shared fields and buttons for account forms.
package com.olive.app.ui.users.shared.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.R
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.LocalizedIcon as Icon

// Gives every long form the same spacing, scrolling, and room above Quick exit.
@Composable
internal fun FormColumn(
    scrollResetKey: Any? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(scrollResetKey) {
        if (scrollResetKey != null) scrollState.scrollTo(0)
    }
    Column(
        Modifier.fillMaxSize().oliveScrollbar(scrollState).verticalScroll(scrollState)
            .padding(horizontal = 24.dp).padding(top = 24.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), content = content
    )
}

// Shows the app name, page title, and help text, then moves TalkBack focus to the new page title.
@Composable
internal fun Heading(
    title: String,
    subtitle: String,
    appName: String = stringResource(R.string.olive_branch),
    focusKey: Any? = title,
) {
    val focusRequester = remember { FocusRequester() }
    // Moves TalkBack focus to the new form heading after the account step changes.
    LaunchedEffect(focusKey) {
        if (focusKey != null) {
            withFrameNanos { }
            focusRequester.requestFocus()
        }
    }
    Text(appName, fontFamily = FontFamily.Serif, fontSize = 30.sp, color = OliveGreen)
    Text(
        title,
        modifier = Modifier.focusRequester(focusRequester).focusable().semantics { heading() },
        style = MaterialTheme.typography.headlineMedium
    )
    if (subtitle.isNotBlank()) Text(subtitle, color = Muted)
}

// Warns testers when data is only being saved on the current device.
@Composable
internal fun ModeNotice(session: SessionViewModel) {
    if (session.localMode) Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            stringResource(R.string.local_test_mode_accounts_stay_on_this_installation_firebase_is_not_connected_use_fictional_information_while_testing),
            Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// Announces status changes to TalkBack and shows when the app is busy.
@Composable
internal fun Status(session: SessionViewModel, showMessage: Boolean = true) {
    // Shows the account form message only when there is text to explain.
    if (showMessage) session.message?.let {
        AccessibleMessage(
            it,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)
                .testTag("session_message")
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
        )
    }
    AnimatedVisibility(
        session.busy,
        enter = fadeIn(tween(120)) + expandVertically(),
        exit = fadeOut(tween(100)) + shrinkVertically()
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
        }
    }
}

// Builds a text field with consistent validation, password controls, and keyboard behavior.
@Composable
internal fun Field(
    label: String,
    value: String,
    tag: String,
    secret: Boolean = false,
    numeric: Boolean = false,
    showSecretToggle: Boolean = false,
    multiline: Boolean = false,
    enabled: Boolean = true,
    error: String? = null,
    onFocus: (() -> Unit)? = null,
    onChange: (String) -> Unit
) {
    val uiText = rememberResourceText()
    var secretVisible by rememberSaveable(tag) { mutableStateOf(false) }
    val secretDescription = when {
        numeric -> uiText(R.string.a11y_pin)
        tag.contains("trigger", ignoreCase = true) -> uiText(R.string.a11y_trigger_word)
        else -> uiText(R.string.a11y_password)
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics { error?.let { error(it) } }
            .onFocusChanged { if (it.isFocused) onFocus?.invoke() }.keepVisibleWhileTyping(),
        enabled = enabled,
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        isError = error != null,
        supportingText = error?.let { message ->
            {
                AccessibleMessage(
                    message,
                    modifier = Modifier.testTag("${tag}_error"),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        visualTransformation = if (secret && !secretVisible) PasswordVisualTransformation()
        else VisualTransformation.None,
        trailingIcon = if (secret && showSecretToggle) {
            {
                IconButton(
                    onClick = { secretVisible = !secretVisible },
                    modifier = Modifier.testTag("${tag}_visibility")
                ) {
                    Icon(
                        if (secretVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (secretVisible) uiText(
                            R.string.ui_hide_value,
                            secretDescription
                        ) else uiText(R.string.ui_show_value, secretDescription)
                    )
                }
            }
        } else null,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false, keyboardType = when {
                numeric -> KeyboardType.NumberPassword
                secret -> KeyboardType.Password
                else -> KeyboardType.Text
            }
        )
    )
}

