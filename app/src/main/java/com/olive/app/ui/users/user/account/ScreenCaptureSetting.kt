package com.olive.app.ui.users.user.account

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.olive.app.R
import com.olive.app.security.ScreenCapturePreferences

@Composable
internal fun ScreenCaptureSetting(uid: String?) {
    val context = LocalContext.current
    val label = stringResource(R.string.privacy_block_captures)
    var enabled by remember(uid) { mutableStateOf(ScreenCapturePreferences.enabled(context, uid)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.privacy_block_captures_help), style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = enabled, enabled = !uid.isNullOrBlank(), onCheckedChange = { value ->
            uid?.let { ScreenCapturePreferences.setEnabled(context, it, value); enabled = value }
        }, modifier = Modifier.testTag("block_screen_captures").semantics { contentDescription = label })
    }
}
