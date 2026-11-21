package com.olive.app.ui.users.user.location

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveTextButton as TextButton
import android.os.Build
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.olive.app.R
import kotlinx.coroutines.launch

/**
 * Renders the background location button composable.
 *
 * @param requestPermission Function used to transform or provide request permission.
 * @param autoExplain Whether to apply auto explain.
 * @param onExplained Callback invoked when explained changes or occurs.
 */
@Composable
internal fun BackgroundLocationButton(
    requestPermission: suspend () -> Boolean,
    autoExplain: Boolean = false,
    onExplained: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var explain by remember { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    val pulse = rememberInfiniteTransition(label = "background_location_hint")
    val border by pulse.animateColor(
        initialValue = MaterialTheme.colorScheme.primary,
        targetValue = MaterialTheme.colorScheme.primaryContainer,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "location_button_border",
    )
    LaunchedEffect(autoExplain) {
        if (autoExplain) { explain = true; onExplained() }
    }
    val title = stringResource(R.string.location_background_access)
    OutlinedButton(onClick = { explain = true }, enabled = !requesting,
        border = BorderStroke(3.dp, border),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("background_location")) { Text(title) }
    if (explain) BackgroundLocationPermissionDialog(
        onDismiss = { explain = false },
        onContinue = {
            explain = false
            requesting = true
            scope.launch { try { requestPermission() } finally { requesting = false } }
        },
    )
}

/**
 * Displays the background location permission dialog overlay.
 *
 * @param onDismiss Callback invoked when the dialog or sheet is dismissed.
 * @param onContinue Callback invoked when continue changes or occurs.
 */
@Composable
internal fun BackgroundLocationPermissionDialog(onDismiss: () -> Unit, onContinue: () -> Unit) {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
    val help = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        stringResource(R.string.location_background_settings, context.packageManager.backgroundPermissionOptionLabel)
    } else stringResource(R.string.location_background_dialog)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.location_background_access)) },
        text = { Text(help) },
        confirmButton = {
            Button(onClick = onContinue,
                modifier = Modifier.focusRequester(focus).testTag("background_location_continue")) {
                Text(stringResource(R.string.next))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
