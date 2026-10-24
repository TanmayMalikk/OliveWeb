// Contains the staff portal code for this part of Olive.
package com.olive.app.ui.users.staff.portal

import com.olive.app.ui.shared.*


import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.ui.users.shared.organization.OrganizationSettings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.R
import com.olive.app.ui.shared.LocalizedIcon as Icon

// Groups the data and actions for staff page.
private enum class StaffPage { Dashboard, Settings, SafetyPlans }

@Composable
internal fun StaffPortal(session: SessionViewModel) {
    var page by remember { mutableStateOf(StaffPage.Dashboard) }
    BackHandler(enabled = page != StaffPage.Dashboard) { page = StaffPage.Dashboard }
    when (page) {
        StaffPage.Dashboard -> StaffDashboard(
            session,
            onSettings = { page = StaffPage.Settings },
            onSafetyPlans = { page = StaffPage.SafetyPlans },
        )
        StaffPage.Settings -> OrganizationSettings(
            session,
            onBack = { page = StaffPage.Dashboard },
        )
        StaffPage.SafetyPlans -> SafetyConsultations(
            session,
            onBack = { page = StaffPage.Dashboard },
        )
    }
}

@Composable
private fun StaffDashboard(
    session: SessionViewModel,
    onSettings: () -> Unit,
    onSafetyPlans: () -> Unit,
) {
    val uiText = rememberResourceText()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(end = 56.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.olive_tree),
                    fontFamily = FontFamily.Serif,
                    fontSize = 30.sp,
                    color = OliveGreen,
                )
                Text(
                    uiText(R.string.staff_panel),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(uiText(R.string.ui_signed_in_as_value, session.coverUsername), color = Muted)
            }
            IconButton(onClick = onSettings, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Outlined.Settings, uiText(R.string.account_settings))
            }
        }
        Button(onClick = onSafetyPlans, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.safety_inbox))
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    uiText(R.string.ui_active_chats_with_staff_and_users),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(R.string.in_development), color = Muted)
            }
        }
        Status(session)
        OutlinedButton(
            onClick = session::signOut,
            enabled = !session.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.sign_out)) }
    }
}
