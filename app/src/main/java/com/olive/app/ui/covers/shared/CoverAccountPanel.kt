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
import com.olive.app.security.InputPolicy
import com.olive.app.ui.covers.olive.AddressFields
import androidx.compose.ui.platform.testTag

@Composable internal fun CoverAccountPanel(session: SessionViewModel) {
    var fullName by remember(session.coverFullName) { mutableStateOf(session.coverFullName) }
    var dob by remember(session.coverDob) { mutableStateOf(session.coverDob) }
    var address by remember(session.deliveryAddress) { mutableStateOf(session.deliveryAddress) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("@${session.coverUsername}")
        OutlinedTextField(
            fullName,
            { fullName = it.take(120) },
            label = { Text(stringResource(R.string.full_name)) },
            singleLine = true,
            enabled = !session.busy,
            modifier = Modifier.fillMaxWidth().testTag("cover_full_name").onFocusChanged { if (it.isFocused) session.recordSettingsInteraction(session.coverType.title, "Focused full name") }.keepVisibleWhileTyping()
        )
        BirthdayPicker(dob,"cover_dob",!session.busy) { dob=it }
        Text(stringResource(R.string.location),style=MaterialTheme.typography.titleMedium)
        AddressFields(address,cityOnlyRequired=true) { address=it }
        Button(
            onClick = { session.saveCoverSettings(fullName,dob,address) },
            enabled = !session.busy && fullName.isNotBlank() && InputPolicy.validDob(dob) && address.locationValid,
            modifier = Modifier.fillMaxWidth().testTag("cover_save_settings")
        ) {
            Text(stringResource(R.string.save_account_settings))
        }
        session.message?.let { AccessibleMessage(it) }
    }
}
