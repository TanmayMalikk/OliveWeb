package com.olive.app.ui.covers.shared

import com.olive.app.ui.*

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.olive.app.R

@Composable internal fun CoverAccountPanel(session: SessionViewModel) {
    var fullName by remember(session.coverFullName) { mutableStateOf(session.coverFullName) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("@${session.coverUsername}")
        OutlinedTextField(
            fullName,
            { fullName = it.take(120) },
            label = { Text(stringResource(R.string.full_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) session.recordSettingsInteraction("Daily Digest", "Focused full name") }
        )
        Button(
            onClick = { session.saveCoverSettings(fullName, session.language) },
            enabled = !session.busy && fullName.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.save_account_settings))
        }
        session.message?.let { Text(it) }
    }
}
