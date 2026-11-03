// All safe-app roles: shows staff and admin account settings.
package com.olive.app.ui.users.shared.organization

import com.olive.app.ui.shared.*


import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.ui.users.shared.onboarding.BirthdayPicker

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.ui.shared.LocalizedIcon as Icon

// This is the organization settings view for shared safe app organization settings. It shows current details and the actions available to the user.
@Composable
internal fun OrganizationSettings(session: SessionViewModel, onBack: () -> Unit) {
    val uiText = rememberResourceText()
    val profile = session.profile ?: return
    var fullName by remember(profile) { mutableStateOf(profile.fullName) }
    var dob by remember(profile) { mutableStateOf(profile.dob) }
    var email by remember(profile) { mutableStateOf(profile.email) }
    var phone by remember(profile) { mutableStateOf(profile.phone) }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp).padding(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiText(R.string.ui_back))
            }
            Text(
                stringResource(R.string.account_settings),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(
            uiText(R.string.ui_profile_information),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Field(
            uiText(R.string.full_name),
            fullName,
            "organization_settings_name",
            enabled = !session.account.busy,
        ) { fullName = it.take(120) }
        BirthdayPicker(dob, "organization_settings_dob", !session.account.busy) { dob = it }
        Field(
            uiText(R.string.ui_email),
            email,
            "organization_settings_email",
            enabled = !session.account.busy,
        ) { email = it.take(254) }
        Field(
            uiText(R.string.ui_phone_optional),
            phone,
            "organization_settings_phone",
            enabled = !session.account.busy,
        ) { phone = it.take(40) }
        Button(
            onClick = { session.updateOrganizationProfile(fullName, dob, email, phone) },
            enabled = !session.account.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.save_profile)) }
        HorizontalDivider()
        Text(
            stringResource(R.string.change_password),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Field(
            uiText(R.string.current_password),
            currentPassword,
            "organization_current_password",
            secret = true,
            showSecretToggle = true,
            enabled = !session.account.busy,
        ) { currentPassword = it.take(128) }
        Field(
            uiText(R.string.new_password),
            newPassword,
            "organization_new_password",
            secret = true,
            showSecretToggle = true,
            enabled = !session.account.busy,
        ) { newPassword = it.take(24) }
        Field(
            uiText(R.string.confirm_new_password),
            confirmation,
            "organization_confirm_password",
            secret = true,
            showSecretToggle = true,
            enabled = !session.account.busy,
        ) { confirmation = it.take(24) }
        Button(
            onClick = { session.changePassword(currentPassword, newPassword, confirmation) },
            enabled = !session.account.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.change_password)) }
        Status(session)
    }
}
