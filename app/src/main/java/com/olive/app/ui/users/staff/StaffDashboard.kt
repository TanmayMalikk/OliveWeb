// Staff: shows assigned users and support tickets.
package com.olive.app.ui.users.staff

import com.olive.app.ui.shared.*
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.shared.organization.OrganizationSettings
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
import com.olive.app.ui.users.shared.safetyplan.SafetyPlanScreen
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import com.olive.app.data.SafetyConsultation
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay


private enum class StaffPage { Dashboard, Settings, SafetyPlans, Messages }

// Shows the staff workspace with assigned users, tickets, map, and settings.
@Composable
internal fun StaffPortal(session: SessionViewModel) {
    LaunchedEffect(session.coverAccountId) { session.messages.ensureMessagingObservation() }
    var page by remember { mutableStateOf(StaffPage.Dashboard) }
    BackHandler(enabled = page != StaffPage.Dashboard) { page = StaffPage.Dashboard }
    // Opens the selected staff dashboard section.
    when (page) {
        StaffPage.Dashboard -> StaffDashboard(
            session,
            onSettings = { page = StaffPage.Settings },
            onSafetyPlans = { page = StaffPage.SafetyPlans },
            onMessages = { page = StaffPage.Messages },
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
    }
    if (session.viewedSharedEntry != null) SharedEntryViewer(session)
}

// Draws the staff workspace navigation and selected section.
@Composable
private fun StaffDashboard(
    session: SessionViewModel,
    onSettings: () -> Unit,
    onSafetyPlans: () -> Unit,
    onMessages: () -> Unit,
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
            session.messages.pendingActionCount,
        )
        Status(session)
        OutlinedButton(
            onClick = session::signOut,
            enabled = !session.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.sign_out)) }
    }
}

// Staff: shows safety-plan consultation requests to staff.
// Lists the plan-consult tickets assigned to this staff member.
@Composable
internal fun SafetyConsultations(session: SessionViewModel, onBack: () -> Unit) {
    var selected by remember { mutableStateOf<SafetyConsultation?>(null) }
    val controller = session.safetyPlans
    DisposableEffect(Unit) { onDispose { controller.close() } }
    BackHandler { if (selected != null) selected = null else onBack() }
    val plan = selected
    if (plan != null) {
        SafetyPlanScreen(session, plan.ownerUid, plan.ownerName) { selected = null }
        return
    }
    // Reload assigned plan consultations when staff return to this screen.
    LaunchedEffect(Unit) {
        while (true) {
            controller.refreshConsultations()
            delay(15.seconds)
        }
    }
    FormColumn {
        TextButton(onClick = onBack) { Text(stringResource(R.string.safety_back)) }
        Heading(
            stringResource(R.string.safety_inbox),
            stringResource(if (session.accountRole == com.olive.app.data.AccountRole.ADMIN) R.string.safety_admin_intro else R.string.safety_staff_intro),
            stringResource(R.string.olive_tree),
        )
        if (session.accountRole == com.olive.app.data.AccountRole.STAFF) {
            val availabilityLabel = stringResource(R.string.safety_available)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(availabilityLabel, Modifier.weight(1f))
                Switch(
                    checked = controller.available,
                    onCheckedChange = controller::changeAvailability,
                    modifier = Modifier.testTag("safety_staff_available")
                        .semantics { contentDescription = availabilityLabel },
                )
            }
        }
        controller.error?.let { AccessibleMessage(stringResource(it)) }
        if (controller.consultations.isEmpty()) Text(stringResource(R.string.safety_inbox_empty))
        controller.consultations.forEach { consultation ->
            OutlinedButton(
                onClick = { selected = consultation },
                modifier = Modifier.fillMaxWidth()
                    .testTag("safety_consultation_${consultation.ownerUid}"),
            ) {
                Text(stringResource(R.string.safety_open_plan, consultation.ownerName))
            }
        }
    }
}
