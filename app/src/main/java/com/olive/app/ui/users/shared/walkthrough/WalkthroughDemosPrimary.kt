// Safe-app users and safe contacts: interactive practice sandboxes for privacy and core safety tools.
package com.olive.app.ui.users.shared.walkthrough

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.ui.shared.Cream
import com.olive.app.ui.shared.DarkOlive
import com.olive.app.ui.shared.Ink
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveButton
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.OliveIconButton
import com.olive.app.ui.shared.OliveOutlinedButton
import com.olive.app.ui.shared.OliveOutlinedCard
import com.olive.app.ui.shared.OliveTextButton
import com.olive.app.ui.shared.PaleOlive
import com.olive.app.ui.shared.SageGreen
import com.olive.app.ui.shared.oliveErrorShake
import com.olive.app.ui.shared.rememberFieldErrorShakeTrigger

private const val DEMO_TRIGGER_PHRASE = "moon garden"
private const val DEMO_PIN_CODE = "1234"
private const val DEMO_INVITE_CODE = "4F8K-92M1"

/**
 * Interactive cover disguise, secret trigger, hidden olive double-tap, and PIN shake sandbox.
 *
 * @param onInteracted Callback fired whenever the user practices inside this sandbox.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CoverEntryDemo(onInteracted: () -> Unit) {
    var disguiseNews by remember { mutableStateOf(true) }
    var stage by remember { mutableStateOf("cover") }
    var phrase by remember { mutableStateOf("") }
    var phraseError by remember { mutableStateOf<String?>(null) }
    var phraseAttempt by remember { mutableIntStateOf(0) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pinAttempt by remember { mutableIntStateOf(0) }
    val phraseErrorText = stringResource(R.string.wt_cover_trigger_error)
    val pinErrorText = stringResource(R.string.wt_cover_pin_error)
    val phraseShake = rememberFieldErrorShakeTrigger(phraseError, phraseAttempt)
    val pinShake = rememberFieldErrorShakeTrigger(pinError, pinAttempt)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.wt_cover_label), style = MaterialTheme.typography.labelLarge, color = Ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = disguiseNews,
                onClick = { disguiseNews = true; onInteracted() },
                label = { Text(stringResource(R.string.wt_cover_news)) },
            )
            FilterChip(
                selected = !disguiseNews,
                onClick = { disguiseNews = false; onInteracted() },
                label = { Text(stringResource(R.string.wt_cover_fitness)) },
            )
        }
        when (stage) {
            "cover" -> {
                OutlinedTextField(
                    value = phrase,
                    onValueChange = { phrase = it; phraseError = null },
                    label = { Text(stringResource(R.string.wt_cover_trigger_label)) },
                    placeholder = { Text(stringResource(R.string.wt_cover_trigger_hint)) },
                    isError = phraseError != null,
                    supportingText = phraseError?.let { msg -> { Text(msg) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().oliveErrorShake(phraseShake),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OliveOutlinedButton(
                        onClick = { phrase = DEMO_TRIGGER_PHRASE; phraseError = null; onInteracted() },
                    ) { Text(stringResource(R.string.wt_cover_trigger_chip)) }
                    OliveButton(
                        onClick = {
                            onInteracted()
                            if (phrase.trim().equals(DEMO_TRIGGER_PHRASE, ignoreCase = true)) {
                                phraseError = null
                                stage = "pin"
                            } else {
                                phraseError = phraseErrorText
                                phraseAttempt += 1
                            }
                        },
                    ) { Text(stringResource(R.string.wt_cover_trigger_unlock_btn)) }
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = PaleOlive,
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { onInteracted(); stage = "pin" })
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Outlined.Spa, null, tint = OliveGreen, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.wt_cover_olive_hint), style = MaterialTheme.typography.labelMedium, color = DarkOlive)
                    }
                }
            }
            "pin" -> {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it; pinError = null },
                    label = { Text(stringResource(R.string.wt_cover_pin_label)) },
                    isError = pinError != null,
                    supportingText = pinError?.let { msg -> { Text(msg) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().oliveErrorShake(pinShake),
                )
                OliveButton(
                    onClick = {
                        onInteracted()
                        if (pinInput.trim() == DEMO_PIN_CODE) {
                            pinError = null
                            stage = "unlocked"
                        } else {
                            pinError = pinErrorText
                            pinAttempt += 1
                        }
                    },
                ) {
                    Icon(Icons.Outlined.LockOpen, null, modifier = Modifier.size(16.dp))
                    Text(stringResource(R.string.wt_cover_pin_unlock), modifier = Modifier.padding(start = 6.dp))
                }
            }
            else -> {
                Surface(shape = RoundedCornerShape(10.dp), color = PaleOlive, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Outlined.CheckCircle, null, tint = OliveGreen, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.wt_cover_unlocked), style = MaterialTheme.typography.bodyMedium, color = DarkOlive)
                    }
                }
            }
        }
        DemoResetRow {
            disguiseNews = true; stage = "cover"; phrase = ""; phraseError = null
            pinInput = ""; pinError = null
        }
    }
}

/**
 * Interactive Quick Exit & idle auto-lock preview switching between simulated safe and cover screens.
 *
 * @param onInteracted Callback fired whenever the user practices inside this sandbox.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun QuickExitDemo(onInteracted: () -> Unit) {
    var idleMinutes by remember { mutableIntStateOf(2) }
    var exitedToCover by remember { mutableStateOf(false) }
    val idleLabel = when (idleMinutes) {
        1 -> stringResource(R.string.wt_exit_1m)
        2 -> stringResource(R.string.wt_exit_2m)
        else -> stringResource(R.string.wt_exit_5m)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.wt_exit_idle_label), style = MaterialTheme.typography.labelLarge, color = Ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1 to R.string.wt_exit_1m, 2 to R.string.wt_exit_2m, 5 to R.string.wt_exit_5m).forEach { (mins, res) ->
                FilterChip(
                    selected = idleMinutes == mins,
                    onClick = { idleMinutes = mins; onInteracted() },
                    label = { Text(stringResource(res)) },
                )
            }
        }
        OliveOutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(if (exitedToCover) R.string.wt_exit_cover_title else R.string.wt_exit_inside_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                )
                Text(
                    if (exitedToCover) stringResource(R.string.wt_exit_cover_body, idleLabel)
                    else stringResource(R.string.wt_exit_inside_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                if (exitedToCover) {
                    OliveOutlinedButton(onClick = { exitedToCover = false; onInteracted() }) {
                        Text(stringResource(R.string.wt_exit_reenter))
                    }
                } else {
                    OliveButton(onClick = { exitedToCover = true; onInteracted() }) {
                        Icon(Icons.AutoMirrored.Outlined.ExitToApp, null, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.wt_exit_button), modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
        DemoResetRow { idleMinutes = 2; exitedToCover = false }
    }
}

/**
 * Interactive Safety Plan checklist and trusted-contact sharing sandbox.
 *
 * @param onInteracted Callback fired whenever the user checks an item or toggles sharing.
 */
@Composable
internal fun SafetyPlanDemo(onInteracted: () -> Unit) {
    val itemLabels = listOf(R.string.wt_plan_item_1, R.string.wt_plan_item_2, R.string.wt_plan_item_3)
    val checked = remember { mutableStateListOf(false, false, false) }
    var shared by remember { mutableStateOf(false) }
    val doneCount = checked.count { it }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.wt_plan_progress, doneCount, itemLabels.size),
                style = MaterialTheme.typography.labelLarge,
                color = DarkOlive,
            )
            DemoStatusChip(
                text = stringResource(if (shared) R.string.wt_plan_shared_badge else R.string.wt_plan_private_badge),
                highlighted = shared,
            )
        }
        LinearProgressIndicator(
            progress = { doneCount / itemLabels.size.toFloat() },
            modifier = Modifier.fillMaxWidth(),
            color = OliveGreen,
            trackColor = SageGreen,
        )
        itemLabels.forEachIndexed { idx, labelRes ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .testTag("walkthrough_demo_plan_item_$idx")
                    .clickable {
                        checked[idx] = !checked[idx]
                        onInteracted()
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = checked[idx],
                    onCheckedChange = {
                        checked[idx] = it
                        onInteracted()
                    },
                )
                Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium, color = Ink)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.wt_plan_share_toggle), style = MaterialTheme.typography.bodyMedium, color = Ink, modifier = Modifier.weight(1f))
            Switch(checked = shared, onCheckedChange = { shared = it; onInteracted() })
        }
        DemoResetRow {
            checked[0] = false; checked[1] = false; checked[2] = false
            shared = false
        }
    }
}

/**
 * Interactive Notes & Incident filter, pin, edit history, and trash/undo sandbox.
 *
 * @param onInteracted Callback fired whenever the user practices inside this sandbox.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NotesIncidentsDemo(onInteracted: () -> Unit) {
    var showIncident by remember { mutableStateOf(true) }
    var pinned by remember { mutableStateOf(false) }
    var editCount by remember { mutableIntStateOf(1) }
    var showHistory by remember { mutableStateOf(false) }
    var trashed by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !showIncident,
                onClick = { showIncident = false; onInteracted() },
                label = { Text(stringResource(R.string.wt_notes_kind_note)) },
            )
            FilterChip(
                selected = showIncident,
                onClick = { showIncident = true; onInteracted() },
                label = { Text(stringResource(R.string.wt_notes_kind_incident)) },
            )
        }
        if (trashed) {
            Surface(shape = RoundedCornerShape(10.dp), color = PaleOlive, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.wt_notes_trashed_notice), style = MaterialTheme.typography.bodySmall, color = DarkOlive, modifier = Modifier.weight(1f))
                    OliveTextButton(onClick = { trashed = false; onInteracted() }) {
                        Text(stringResource(R.string.wt_notes_undo))
                    }
                }
            }
        } else {
            OliveOutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(if (showIncident) R.string.wt_notes_sample_incident_title else R.string.wt_notes_sample_note_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Ink,
                            modifier = Modifier.weight(1f),
                        )
                        if (pinned) DemoStatusChip(stringResource(R.string.wt_notes_pinned_badge), highlighted = true)
                    }
                    Text(
                        stringResource(if (showIncident) R.string.wt_notes_sample_incident_body else R.string.wt_notes_sample_note_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OliveOutlinedButton(onClick = { pinned = !pinned; onInteracted() }) {
                            Icon(if (pinned) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, null, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.wt_notes_pin_action), modifier = Modifier.padding(start = 6.dp))
                        }
                        OliveOutlinedButton(onClick = { editCount += 1; showHistory = true; onInteracted() }) {
                            Text(stringResource(R.string.wt_notes_add_edit))
                        }
                        OliveTextButton(onClick = { showHistory = !showHistory; onInteracted() }) {
                            Text(stringResource(R.string.wt_notes_history_toggle, editCount))
                        }
                        OliveTextButton(onClick = { trashed = true; onInteracted() }) {
                            Icon(Icons.Outlined.DeleteOutline, null, modifier = Modifier.size(16.dp))
                            Text(stringResource(R.string.wt_notes_trash), modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                    if (showHistory) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("1. ${stringResource(R.string.wt_notes_edit_initial)}", style = MaterialTheme.typography.bodySmall, color = Muted)
                            for (i in 2..editCount) {
                                Text("$i. ${stringResource(R.string.wt_notes_edit_sample)}", style = MaterialTheme.typography.bodySmall, color = DarkOlive)
                            }
                        }
                    }
                }
            }
        }
        DemoResetRow { showIncident = true; pinned = false; editCount = 1; showHistory = false; trashed = false }
    }
}

/**
 * Interactive encrypted documents capture, OCR text extraction, and contact sharing sandbox.
 *
 * @param onInteracted Callback fired whenever the user practices inside this sandbox.
 */
@Composable
internal fun DocumentsOcrDemo(onInteracted: () -> Unit) {
    var captured by remember { mutableStateOf(false) }
    var shared by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OliveButton(onClick = { captured = true; onInteracted() }) {
            Icon(Icons.Outlined.DocumentScanner, null, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.wt_docs_capture_ocr), modifier = Modifier.padding(start = 6.dp))
        }
        OliveOutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.wt_docs_file_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = Ink)
                    DemoStatusChip(
                        stringResource(if (captured) R.string.wt_docs_file_encrypted else R.string.wt_docs_file_pending),
                        highlighted = captured,
                    )
                }
                if (captured) {
                    Text(stringResource(R.string.wt_docs_ocr_label), style = MaterialTheme.typography.labelMedium, color = DarkOlive)
                    Text(stringResource(R.string.wt_docs_ocr_sample), style = MaterialTheme.typography.bodySmall, color = Ink)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.wt_docs_share_toggle), style = MaterialTheme.typography.bodyMedium, color = Ink, modifier = Modifier.weight(1f))
                        Switch(checked = shared, onCheckedChange = { shared = it; onInteracted() })
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.Lock, null, tint = Muted, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.wt_docs_voice_note), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        DemoResetRow { captured = false; shared = false }
    }
}

/**
 * Interactive encrypted chat, 48-hour invite code, voice message, and unsend sandbox.
 *
 * @param onInteracted Callback fired whenever the user practices inside this sandbox.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MessagingCallsDemo(onInteracted: () -> Unit) {
    var inviteVisible by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var sentMessage by remember { mutableStateOf<String?>(null) }
    var voiceAdded by remember { mutableStateOf(false) }
    var unsent by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OliveOutlinedButton(onClick = { inviteVisible = true; onInteracted() }) {
                Icon(Icons.Outlined.AttachFile, null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.wt_chat_invite_btn), modifier = Modifier.padding(start = 6.dp))
            }
            OliveOutlinedButton(onClick = { voiceAdded = true; onInteracted() }) {
                Icon(Icons.Outlined.GraphicEq, null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.wt_chat_voice_btn), modifier = Modifier.padding(start = 6.dp))
            }
        }
        if (inviteVisible) {
            DemoStatusChip(stringResource(R.string.wt_chat_invite_value, DEMO_INVITE_CODE), highlighted = true)
        }
        OliveOutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.wt_chat_sample_incoming), style = MaterialTheme.typography.bodySmall, color = Ink)
                if (voiceAdded) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.wt_chat_sample_voice), style = MaterialTheme.typography.bodySmall, color = DarkOlive)
                        DemoStatusChip(stringResource(R.string.wt_chat_voice_badge), highlighted = true)
                    }
                }
                sentMessage?.let { text ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (unsent) stringResource(R.string.wt_chat_unsent) else text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (unsent) Muted else OliveGreen,
                            modifier = Modifier.weight(1f),
                        )
                        if (!unsent) {
                            OliveTextButton(onClick = { unsent = true; onInteracted() }) {
                                Text(stringResource(R.string.wt_chat_unsend))
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.wt_chat_msg_input)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            val fallbackReply = stringResource(R.string.wt_staff_user_reply)
            OliveButton(onClick = {
                val msg = draft.trim().ifEmpty { fallbackReply }
                sentMessage = msg
                draft = ""
                unsent = false
                onInteracted()
            }) {
                Icon(Icons.Outlined.ChatBubbleOutline, null, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.wt_chat_send), modifier = Modifier.padding(start = 6.dp))
            }
        }
        DemoResetRow { inviteVisible = false; draft = ""; sentMessage = null; voiceAdded = false; unsent = false }
    }
}

/**
 * Shared reset action footer rendered at the bottom of every interactive sandbox card.
 *
 * @param onReset Callback invoked to restore initial sandbox state.
 */
@Composable
internal fun DemoResetRow(onReset: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        OliveTextButton(onClick = onReset) {
            Icon(Icons.Outlined.Refresh, null, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.walkthrough_demo_reset), modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/**
 * Compact rounded status badge used inside walkthrough sandboxes and cards.
 *
 * @param text Localized label text inside the badge.
 * @param highlighted Whether to emphasize the badge in active olive tones.
 */
@Composable
internal fun DemoStatusChip(text: String, highlighted: Boolean) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (highlighted) PaleOlive else SageGreen.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, if (highlighted) OliveGreen else SageGreen),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) OliveGreen else Ink,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
