// Staff: shows assigned users and support tickets.
package com.olive.app.ui.users.staff

import com.olive.app.ui.shared.*


import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.ui.users.shared.organization.OrganizationSettings
import com.olive.app.ui.users.shared.organization.OrganizationMap
import com.olive.app.ui.users.shared.organization.OrganizationDashboardCard
import com.olive.app.ui.users.shared.messaging.MessagesScreen
import com.olive.app.ui.users.safecontact.SharedEntryViewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.LocationOn
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

private enum class StaffPage { Dashboard, Settings, SafetyPlans, Messages, Map }

// This is the staff portal view for staff dashboard. It shows current details and the actions available to the user.
@Composable
internal fun StaffPortal(session: SessionViewModel) {
    LaunchedEffect(session.coverAccountId) { session.messages.ensureMessagingObservation() }
    var page by remember { mutableStateOf(StaffPage.Dashboard) }
    BackHandler(enabled = page != StaffPage.Dashboard) { page = StaffPage.Dashboard }
    // Choose what staff portal should do for the current value.
    when (page) {
        StaffPage.Dashboard -> StaffDashboard(
            session,
            onSettings = { page = StaffPage.Settings },
            onSafetyPlans = { page = StaffPage.SafetyPlans },
            onMessages = { page = StaffPage.Messages },
            onMap = { page = StaffPage.Map },
        )
        StaffPage.Settings -> OrganizationSettings(
            session,
            onBack = { page = StaffPage.Dashboard },
        )
        StaffPage.SafetyPlans -> SafetyConsultations(
            session,
            onBack = { page = StaffPage.Dashboard },
        )
        StaffPage.Messages -> {
            LaunchedEffect(Unit) { session.messages.ensureMessagingObservation() }
            MessagesScreen(session, exit = { page = StaffPage.Dashboard })
        }
        StaffPage.Map -> OrganizationMap(onBack = { page = StaffPage.Dashboard })
    }
    if (session.viewedSharedEntry != null) SharedEntryViewer(session)
}

// The staff dashboard view is part of staff dashboard. It gives the user the details and choices needed here.
@Composable
private fun StaffDashboard(
    session: SessionViewModel,
    onSettings: () -> Unit,
    onSafetyPlans: () -> Unit,
    onMessages: () -> Unit,
    onMap: () -> Unit,
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
        OrganizationDashboardCard(
            Icons.Outlined.HealthAndSafety,
            stringResource(R.string.safety_inbox),
            stringResource(R.string.staff_safety_plans_description),
            onSafetyPlans,
        )
        OrganizationDashboardCard(
            Icons.Outlined.Forum,
            stringResource(R.string.support_conversations),
            stringResource(R.string.staff_conversations_assigned_help),
            onMessages,
        )
        OrganizationDashboardCard(
            Icons.Outlined.LocationOn,
            stringResource(R.string.organization_live_map),
            stringResource(R.string.organization_location_feed_not_enabled),
            onMap,
        )
        Status(session)
        OutlinedButton(
            onClick = session::signOut,
            enabled = !session.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.sign_out)) }
    }
}
