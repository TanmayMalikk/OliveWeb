// Safe-app users and safe contacts: interactive practice sandboxes for location, support, and account features.
package com.olive.app.ui.users.shared.walkthrough

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.ui.shared.DarkOlive
import com.olive.app.ui.shared.Ink
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveButton
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.OliveIconButton
import com.olive.app.ui.shared.OliveOutlinedButton
import com.olive.app.ui.shared.OliveOutlinedCard
import com.olive.app.ui.shared.PaleOlive

/**
 * Dispatches to the interactive sandbox matching [topicId].
 *
 * @param topicId Selected topic identifier.
 * @param onPracticeCompleted Callback notified as soon as the user interacts with the sandbox.
 */
@Composable
internal fun WalkthroughInteractiveDemo(
    topicId: String,
    onPracticeCompleted: () -> Unit,
) {
    when (topicId) {
        "cover_entry" -> CoverEntryDemo(onPracticeCompleted)
        "quick_exit" -> QuickExitDemo(onPracticeCompleted)
        "safety_plan" -> SafetyPlanDemo(onPracticeCompleted)
        "notes_incidents" -> NotesIncidentsDemo(onPracticeCompleted)
        "documents_ocr" -> DocumentsOcrDemo(onPracticeCompleted)
        "messaging_calls" -> MessagingCallsDemo(onPracticeCompleted)
        "location_map" -> LocationMapDemo(onPracticeCompleted)
        "staff_help" -> StaffHelpDemo(onPracticeCompleted)
        "emergency_report" -> EmergencyReportDemo(onPracticeCompleted)
        "hotlines_contacts" -> HotlinesContactsDemo(onPracticeCompleted)
        "activity_settings" -> ActivitySettingsDemo(onPracticeCompleted)
    }
}

/**
 * Interactive live-location recipient, duration, and route preview sandbox.
 *
 * @param onInteracted Callback fired whenever the user practices inside this sandbox.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocationMapDemo(onInteracted: () -> Unit) {
    var shareMaya by remember { mutableStateOf(true) }
    var shareJordan by remember { mutableStateOf(false) }
    var durationIndex by remember { mutableIntStateOf(0) }
    var sharingActive by remember { mutableStateOf(false) }
    var routeVisible by remember { mutableStateOf(false) }
    val durationLabels = listOf(R.string.wt_loc_15m, R.string.wt_loc_1h, R.string.wt_loc_until_stop)
    val selectedCount = listOf(shareMaya, shareJordan).count { it }.coerceAtLeast(1)
    val durationText = stringResource(durationLabels[durationIndex])

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.wt_loc_recipients), style = MaterialTheme.typography.labelLarge, color = Ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = shareMaya,
                onClick = { shareMaya = !shareMaya || !shareJordan; onInteracted() },
                label = { Text(stringResource(R.string.wt_loc_maya)) },
            )
            FilterChip(
                selected = shareJordan,
                onClick = { shareJordan = !shareJordan || !shareMaya; onInteracted() },
                label = { Text(stringResource(R.string.wt_loc_jordan)) },
            )
        }
        Text(stringResource(R.string.wt_loc_duration), style = MaterialTheme.typography.labelLarge, color = Ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            durationLabels.forEachIndexed { idx, res ->
                FilterChip(
                    selected = durationIndex == idx,
                    onClick = { durationIndex = idx; onInteracted() },
                    label = { Text(stringResource(res)) },
                )
            }
        }
        Surface(shape = RoundedCornerShape(10.dp), color = PaleOlive, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.LocationOn, null, tint = OliveGreen, modifier = Modifier.size(18.dp))
                Text(
                    if (sharingActive) stringResource(R.string.wt_loc_status_on, selectedCount, durationText)
                    else stringResource(R.string.wt_loc_status_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = DarkOlive,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OliveButton(onClick = { sharingActive = !sharingActive; onInteracted() }) {
                Text(stringResource(if (sharingActive) R.string.wt_loc_stop else R.string.wt_loc_start))
            }
            OliveOutlinedButton(onClick = { routeVisible = !routeVisible; onInteracted() }) {
                Text(stringResource(R.string.wt_loc_preview_route))
            }
        }
        if (routeVisible) {
            Text(stringResource(R.string.wt_loc_route_info), style = MaterialTheme.typography.bodySmall, color = DarkOlive)
        }
        DemoResetRow { shareMaya = true; shareJordan = false; durationIndex = 0; sharingActive = false; routeVisible = false }
    }
}

/**
 * Interactive staff support request, assignment queue, reply, and friend escalation sandbox.
 *
 * @param onInteracted Callback fired whenever the user practices inside this sandbox.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StaffHelpDemo(onInteracted: () -> Unit) {
    var status by remember { mutableStateOf("idle") }
    var sentReply by remember { mutableStateOf(false) }
    var addedFriend by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (status) {
            "idle" -> {
                OliveButton(onClick = { status = "pending"; onInteracted() }) {
                    Icon(Icons.Outlined.SupportAgent, null, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.wt_staff_request), modifier = Modifier.padding(start = 6.dp))
                }
            }
            "pending" -> {
                Text(stringResource(R.string.wt_staff_searching), style = MaterialTheme.typography.bodyMedium, color = DarkOlive)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OliveButton(onClick = { status = "assigned"; onInteracted() }) {
                        Text(stringResource(R.string.wt_staff_simulate_assign))
                    }
                    OliveOutlinedButton(onClick = { status = "idle"; onInteracted() }) {
                        Text(stringResource(R.string.wt_staff_cancel))
                    }
                }
            }
            else -> {
                OliveOutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.wt_staff_ticket_header), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Ink)
                        Text(stringResource(R.string.wt_staff_sample_reply), style = MaterialTheme.typography.bodySmall, color = Muted)
                        if (sentReply) {
                            Text(stringResource(R.string.wt_staff_user_reply), style = MaterialTheme.typography.bodySmall, color = OliveGreen)
                        }
                        if (!sentReply) {
                            OliveButton(onClick = { sentReply = true; onInteracted() }) {
                                Text(stringResource(R.string.wt_staff_send_reply))
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.wt_staff_add_friend), style = MaterialTheme.typography.bodySmall, color = Ink, modifier = Modifier.weight(1f))
                            Switch(checked = addedFriend, onCheckedChange = { addedFriend = it; onInteracted() })
                        }
                    }
                }
            }
        }
        DemoResetRow { status = "idle"; sentReply = false; addedFriend = false }
    }
}

/**
 * Interactive emergency PDF outline preview sandbox without writing files or alerting admins.
 *
 * @param onInteracted Callback fired whenever the user practices inside this sandbox.
 */
@Composable
internal fun EmergencyReportDemo(onInteracted: () -> Unit) {
    var generated by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OliveButton(onClick = { generated = true; onInteracted() }) {
            Icon(Icons.Outlined.PictureAsPdf, null, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.wt_emerg_generate), modifier = Modifier.padding(start = 6.dp))
        }
        Text(stringResource(R.string.wt_emerg_note), style = MaterialTheme.typography.bodySmall, color = Muted)
        if (generated) {
            OliveOutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.wt_emerg_doc_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = OliveGreen)
                    Text(stringResource(R.string.wt_emerg_sec_1), style = MaterialTheme.typography.bodySmall, color = Ink)
                    Text(stringResource(R.string.wt_emerg_sec_2), style = MaterialTheme.typography.bodySmall, color = Ink)
                    Text(stringResource(R.string.wt_emerg_sec_3), style = MaterialTheme.typography.bodySmall, color = Ink)
                    Text(stringResource(R.string.wt_emerg_sec_4), style = MaterialTheme.typography.bodySmall, color = Ink)
                }
            }
        }
        DemoResetRow { generated = false }
    }
}

/**
 * Interactive hotline filter, bookmark heart toggle, and safe-to-stay-with trusted contact sandbox.
 *
 * @param onInteracted Callback fired whenever the user practices inside this sandbox.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HotlinesContactsDemo(onInteracted: () -> Unit) {
    var only247 by remember { mutableStateOf(false) }
    val saved = remember { mutableStateListOf(false, false, false) }
    var safeToStay by remember { mutableStateOf(true) }
    val lines = listOf(
        Triple(0, R.string.wt_hotline_1, true),
        Triple(1, R.string.wt_hotline_2, true),
        Triple(2, R.string.wt_hotline_3, false),
    ).filter { !only247 || it.third }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = !only247,
                onClick = { only247 = false; onInteracted() },
                label = { Text(stringResource(R.string.wt_hotline_all)) },
            )
            FilterChip(
                selected = only247,
                onClick = { only247 = true; onInteracted() },
                label = { Text(stringResource(R.string.wt_hotline_247)) },
            )
            DemoStatusChip(stringResource(R.string.wt_hotline_saved, saved.count { it }), highlighted = saved.any { it })
        }
        lines.forEach { (idx, nameRes, _) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(nameRes), style = MaterialTheme.typography.bodySmall, color = Ink, modifier = Modifier.weight(1f))
                OliveIconButton(onClick = { saved[idx] = !saved[idx]; onInteracted() }) {
                    Icon(
                        if (saved[idx]) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = stringResource(R.string.wt_hotline_save_btn),
                        tint = if (saved[idx]) OliveGreen else Muted,
                    )
                }
            }
        }
        OliveOutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.wt_hotline_contact_label), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = Ink)
                    Text(stringResource(R.string.wt_hotline_safe_stay), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                Switch(checked = safeToStay, onCheckedChange = { safeToStay = it; onInteracted() })
            }
        }
        DemoResetRow { only247 = false; saved[0] = false; saved[1] = false; saved[2] = false; safeToStay = true }
    }
}

/**
 * Interactive activity-log category filter and Passkey Account Recovery Key enrollment preview sandbox.
 *
 * @param onInteracted Callback fired whenever the user practices inside this sandbox.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActivitySettingsDemo(onInteracted: () -> Unit) {
    var filter by remember { mutableStateOf("all") }
    var passkeyEnrolled by remember { mutableStateOf(true) }
    val entries = listOf(
        "settings" to R.string.wt_act_log_1,
        "triggers" to R.string.wt_act_log_2,
        "pin" to R.string.wt_act_log_3,
        "settings" to R.string.wt_act_log_4,
    ).filter { filter == "all" || it.first == filter }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "all" to R.string.wt_act_all,
                "settings" to R.string.wt_act_settings,
                "triggers" to R.string.wt_act_triggers,
                "pin" to R.string.wt_act_pin,
            ).forEach { (key, res) ->
                FilterChip(
                    selected = filter == key,
                    onClick = { filter = key; onInteracted() },
                    label = { Text(stringResource(res)) },
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            entries.forEach { (_, textRes) ->
                Text(stringResource(textRes), style = MaterialTheme.typography.bodySmall, color = Ink)
            }
        }
        OliveOutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.wt_act_passkey_toggle), style = MaterialTheme.typography.bodyMedium, color = Ink, modifier = Modifier.weight(1f))
                    Switch(checked = passkeyEnrolled, onCheckedChange = { passkeyEnrolled = it; onInteracted() })
                }
                Text(stringResource(R.string.wt_act_passkey_note), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
        DemoResetRow { filter = "all"; passkeyEnrolled = true }
    }
}
