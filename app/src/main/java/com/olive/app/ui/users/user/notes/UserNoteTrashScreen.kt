// Safe-app users: shows deleted notes that a user can restore.
package com.olive.app.ui.users.user.notes

import com.olive.app.ui.shared.OliveCard as Card
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveTextButton as TextButton
import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.PushPin as FilledPushPin
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.olive.app.R
import com.olive.app.data.account.PRIVATE_ENTRY_TRASH_RETENTION_MS
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.account.parseIncidentDate
import com.olive.app.data.account.trashDaysRemaining
import com.olive.app.data.account.trashSecondsRemaining
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.LocalizedIcon as Icon
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import com.olive.app.ui.shared.OliveButton as Button
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.ui.text.style.TextOverflow
import com.olive.app.data.account.SafeContact
import com.olive.app.data.messaging.Friend
import com.olive.app.data.safety.SharedPrivateEntry
import com.olive.app.ui.users.user.documents.NoteAttachmentPreviewStrip
import com.olive.app.ui.users.user.documents.SafetyDocumentsController

private data class EntryCardPalette(val container: Color, val accent: Color)

private val notePalette = EntryCardPalette(PaleOlive, DarkOlive)
private val incidentPalette = EntryCardPalette(IncidentPaper, IncidentAccent)
private val PrivateEntryKind.cardPalette: EntryCardPalette
    get() = if (this == PrivateEntryKind.Incident) incidentPalette else notePalette



/**
 * Shows trashed notes with restore and permanent-delete actions.
 *
 * @param entries Collection of entries values to process.
 * @param formatter Formatter supplied to [Trash].
 * @param restore Action or callback executed for restore.
 * @param close Action or callback executed for close.
 */
@Composable
internal fun Trash(
    entries: List<PrivateEntry>, formatter: DateTimeFormatter,
    restore: (String) -> Unit, close: () -> Unit,
    viewerUid: String? = null,
    documentsController: SafetyDocumentsController? = null,
) {
    val uiText = rememberResourceText()
    var now by remember(entries) { mutableLongStateOf(System.currentTimeMillis()) }
    // Reload deleted notes after a restore or permanent delete.
    LaunchedEffect(entries) {
        while (true) {
            delay(1_000.milliseconds)
            now = System.currentTimeMillis()
        }
    }
    val listState = rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().oliveScrollbar(listState),
        state = listState,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close, modifier = Modifier.testTag("close_scratchpad")) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        uiText(R.string.all_notes)
                    )
                }
                Text(
                    uiText(R.string.ui_trash),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
            }
            Text(
                if (PRIVATE_ENTRY_TRASH_RETENTION_MS < 60_000L)
                    uiText(R.string.ui_for_testing_deleted_notes_stay_here_for_10_seconds_before_their_p)
                else uiText(R.string.deleted_notes_stay_here_for_30_days_then_their_private_contents_are_permanently_erased),
                color = Muted
            )
        }
        // Tell the user when there are no deleted notes to restore.
        if (entries.isEmpty()) item { Text(uiText(R.string.ui_trash_is_empty), color = Muted) }
        items(entries, key = { it.id }) { entry ->
            val palette = entry.kind.cardPalette
            Card(
                Modifier.animateItem().fillMaxWidth().testTag("scratchpad_entry"),
                border = BorderStroke(1.dp, palette.accent),
                colors = CardDefaults.cardColors(containerColor = palette.container, contentColor = Ink)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            if (entry.kind == PrivateEntryKind.Incident) uiText(R.string.incident) else uiText(
                                R.string.note
                            ),
                            color = palette.accent,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (entry.attachmentIds.isNotEmpty()) {
                            Icon(
                                Icons.Outlined.AttachFile,
                                contentDescription = uiText(R.string.note_attachment_count, entry.attachmentIds.size),
                                tint = palette.accent,
                                modifier = Modifier.size(16.dp).testTag("trash_entry_attachment_icon"),
                            )
                        }
                    }
                    if (entry.title.isNotBlank()) Text(
                        entry.title,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        entry.body,
                        maxLines = 5,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (entry.attachmentIds.isNotEmpty() && documentsController != null) {
                        NoteAttachmentPreviewStrip(
                            attachmentIds = entry.attachmentIds,
                            viewerUid = viewerUid,
                            controller = documentsController,
                        )
                    }
                    Text(
                        if (PRIVATE_ENTRY_TRASH_RETENTION_MS < 60_000L)
                            uiText(
                                R.string.ui_value_seconds_remaining,
                                entry.trashSecondsRemaining(now)
                            )
                        else uiText(
                            R.string.ui_value_days_remaining,
                            entry.trashDaysRemaining(now)
                        ),
                        style = MaterialTheme.typography.bodySmall, color = Muted
                    )
                    Text(
                        formatter.format(Instant.ofEpochMilli(entry.deletedAt ?: entry.updatedAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted
                    )
                    OutlinedButton(
                        onClick = { restore(entry.id) },
                        modifier = Modifier.fillMaxWidth().testTag("restore_private_entry")
                    ) {
                        Icon(
                            Icons.Outlined.RestoreFromTrash,
                            null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.restore))
                    }
                }
            }
        }
    }
}

/**
 * UI sizing/color: revision cards use 16dp padding, black outlines, soft olive badges, and small Muted timestamps.
 *
 * @param entry Entry supplied to [PrivateEntryEditLog].
 * @param formatter Formatter supplied to [PrivateEntryEditLog].
 * @param close Action or callback executed for close.
 */
@Composable
internal fun PrivateEntryEditLog(
    entry: PrivateEntry,
    formatter: DateTimeFormatter,
    close: () -> Unit
) {
    val uiText = rememberResourceText()
    val listState = rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().oliveScrollbar(listState),
        state = listState,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            TextButton(onClick = close, modifier = Modifier.testTag("close_private_edit_log")) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    null
                )
                Text(stringResource(R.string.close))
            }
            Text(
                stringResource(R.string.edit_log),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() })
            Text(
                entry.title.ifBlank { stringResource(R.string.untitled_entry) },
                style = MaterialTheme.typography.titleMedium,
                color = Muted
            )
            Text(
                "${entry.edits.size} ${stringResource(R.string.revisions)}",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
        }
        items(entry.edits.asReversed(), key = { it.id }) { edit ->
            val revision = entry.edits.indexOf(edit) + 1
            Card(
                Modifier.animateItem().fillMaxWidth(),
                border = BorderStroke(1.dp, Color.Black),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = NoteTagMint) {
                            Text(
                                revision.toString(),
                                Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                fontWeight = FontWeight.Bold,
                                color = OliveGreen
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "${stringResource(R.string.revision)} $revision",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                formatter.format(Instant.ofEpochMilli(edit.timestamp)),
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted
                            )
                        }
                    }
                    edit.changes.forEach { change ->
                        Surface(
                            Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.medium,
                            color = NoteMutedSurface
                        ) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    change.field,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = OliveGreen
                                )
                                Text(
                                    stringResource(R.string.before),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Muted
                                )
                                Text(change.before.ifBlank { uiText(R.string.empty) })
                                HorizontalDivider(Modifier.padding(vertical = 3.dp))
                                Text(
                                    stringResource(R.string.after),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Muted
                                )
                                Text(change.after.ifBlank { uiText(R.string.empty) })
                            }
                        }
                    }
                }
            }
        }
    }
}

// UI sizing/color: note cards use a 1dp outline, warm paper fill, 14dp padding, and up to three title lines.

// Safe-app users: shows the user’s private notes.


/**
 * UI sizing/color: Trash cards use a 1dp black outline, warm paper fill, 16dp padding, and Material text sizes.
 *
 * @param entry Entry supplied to [PrivateNoteCard].
 * @param formatter Formatter supplied to [PrivateNoteCard].
 * @param open Action or callback executed for open.
 * @param pin User-entered PIN string.
 * @param openLog Action or callback executed for open log.
 * @param modifier Compose modifier applied to the root layout.
 */
@Composable
internal fun PrivateNoteCard(
    entry: PrivateEntry, formatter: DateTimeFormatter,
    open: () -> Unit, pin: () -> Unit, openLog: () -> Unit, modifier: Modifier = Modifier,
    viewerUid: String? = null,
    documentsController: SafetyDocumentsController? = null,
) {
    val uiText = rememberResourceText()
    val palette = entry.kind.cardPalette
    val entryDetails = listOfNotNull(
        uiText(R.string.pinned).takeIf { entry.pinned },
        formatter.format(Instant.ofEpochMilli(entry.timestamp)),
        uiText(R.string.note_attachment_count, entry.attachmentIds.size),
    ).joinToString(", ")
    Card(
        onClick = open,
        modifier = modifier.fillMaxWidth().testTag("private_entry_row")
            .semantics { stateDescription = entryDetails },
        border = BorderStroke(1.dp, palette.accent),
        colors = CardDefaults.cardColors(containerColor = palette.container, contentColor = Ink)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        if (entry.kind == PrivateEntryKind.Incident) uiText(R.string.incident) else uiText(
                            R.string.note
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = palette.accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (entry.attachmentIds.isNotEmpty()) {
                        Icon(
                            Icons.Outlined.AttachFile,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(16.dp).testTag("private_entry_attachment_icon"),
                        )
                    }
                }
                IconButton(
                    onClick = pin,
                    modifier = Modifier.size(48.dp).testTag("private_pin")
                ) {
                    Icon(
                        if (entry.pinned) Icons.Filled.FilledPushPin else Icons.Outlined.PushPin,
                        if (entry.pinned) uiText(R.string.ui_unpin_entry) else uiText(R.string.ui_pin_entry),
                        tint = if (entry.pinned) palette.accent else Muted,
                    )
                }
            }
            if (entry.title.isNotBlank()) Text(
                entry.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 3,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                entry.body,
                maxLines = 8,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (entry.attachmentIds.isNotEmpty() && documentsController != null) {
                NoteAttachmentPreviewStrip(
                    attachmentIds = entry.attachmentIds,
                    viewerUid = viewerUid,
                    controller = documentsController,
                )
            }
            Text(
                (if (entry.edits.isNotEmpty()) "${stringResource(R.string.edited)} · " else "") + formatter.format(
                    Instant.ofEpochMilli(entry.updatedAt)
                ), style = MaterialTheme.typography.bodyMedium, color = Muted
            )
            if (entry.edits.isNotEmpty()) OutlinedButton(
                onClick = openLog,
                modifier = Modifier.testTag("private_edit_log"),
            ) {
                Text("${stringResource(R.string.edit_log)} (${entry.edits.size})")
            }
        }
    }
}

private enum class IncidentPicker { Date, Time }

/**
 * Renders the incident date time fields composable.
 *
 * @param date Date supplied to [IncidentDateTimeFields].
 * @param time Time supplied to [IncidentDateTimeFields].
 * @param enabled Whether this feature or control is currently enabled.
 * @param onDateChange Callback invoked when date change changes or occurs.
 * @param onTimeChange Callback invoked when time change changes or occurs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IncidentDateTimeFields(
    date: String,
    time: String,
    enabled: Boolean,
    onDateChange: (String) -> Unit,
    onTimeChange: (String) -> Unit
) {
    var openPicker by remember { mutableStateOf<IncidentPicker?>(null) }
    val today = LocalDate.now()
    val initialDate = remember(date, today) { parseIncidentDate(date, today) }
    val initialTime = remember(time) { runCatching { LocalTime.parse(time) }.getOrNull() }
    val use24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val locale = LocalConfiguration.current.locales[0]
    val timePattern = DateFormat.getBestDateTimePattern(locale, if (use24Hour) "Hm" else "hm")
    val dateLabel = stringResource(R.string.incident_date)
    val timeLabel = stringResource(R.string.incident_time)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IncidentPickerField(
            label = dateLabel,
            value = initialDate?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) ?: date,
            tag = "incident_date",
            enabled = enabled,
            icon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) },
            clearLabel = stringResource(R.string.incident_clear_date),
            onClear = { onDateChange("") },
        ) { openPicker = IncidentPicker.Date }
        if (date.isNotBlank() && initialDate == null) {
            Text(stringResource(R.string.incident_date_invalid), color = MaterialTheme.colorScheme.error)
        }
        IncidentPickerField(
            label = timeLabel,
            value = initialTime?.format(DateTimeFormatter.ofPattern(timePattern, locale)) ?: time,
            tag = "incident_time",
            enabled = enabled,
            icon = { Icon(Icons.Outlined.AccessTime, contentDescription = null) },
            clearLabel = stringResource(R.string.incident_clear_time),
            onClear = { onTimeChange("") },
        ) { openPicker = IncidentPicker.Time }
    }

    when (openPicker) {
        IncidentPicker.Date -> {
            val calendarDate = initialDate ?: today
            val picker = rememberDatePickerState(
                initialSelectedDateMillis = calendarDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                yearRange = minOf(1900, calendarDate.year)..today.year,
                selectableDates = object : SelectableDates {
                    /**
                     * Checks whether selectable date.
                     *
                     * @param utcTimeMillis Duration or timestamp for utc time, in milliseconds.
                     * @return `true` if is selectable date, or `false` otherwise.
                     */
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        !Instant.ofEpochMilli(utcTimeMillis).atOffset(ZoneOffset.UTC).toLocalDate().isAfter(today)
                    /**
                     * Checks whether selectable year.
                     *
                     * @param year Year supplied to [isSelectableYear].
                     * @return `true` if is selectable year, or `false` otherwise.
                     */
                    override fun isSelectableYear(year: Int): Boolean = year <= today.year
                },
            )
            DatePickerDialog(
                onDismissRequest = { openPicker = null },
                confirmButton = {
                    TextButton(
                        enabled = picker.selectedDateMillis != null,
                        modifier = Modifier.testTag("incident_date_apply"),
                        onClick = {
                            picker.selectedDateMillis?.let {
                                onDateChange(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate().toString())
                            }
                            openPicker = null
                        }
                    ) { Text(stringResource(R.string.apply)) }
                },
                dismissButton = {
                    TextButton(onClick = { openPicker = null }) { Text(stringResource(R.string.cancel)) }
                }
            ) {
                DatePicker(
                    state = picker,
                    modifier = Modifier.testTag("incident_calendar"),
                    showModeToggle = false
                )
            }
        }
        IncidentPicker.Time -> {
            val current = initialTime ?: LocalTime.now()
            val picker = rememberTimePickerState(current.hour, current.minute, is24Hour = use24Hour)
            Dialog(
                onDismissRequest = { openPicker = null },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(
                    modifier = Modifier.padding(16.dp).widthIn(max = 400.dp).fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            stringResource(R.string.select_time),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.align(Alignment.Start).padding(bottom = 16.dp)
                        )
                        TimePicker(
                            state = picker,
                            modifier = Modifier.testTag("incident_clock"),
                            layoutType = TimePickerLayoutType.Vertical
                        )
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { openPicker = null }) { Text(stringResource(R.string.cancel)) }
                            TextButton(
                                modifier = Modifier.testTag("incident_time_apply"),
                                onClick = {
                                    onTimeChange(LocalTime.of(picker.hour, picker.minute).toString())
                                    openPicker = null
                                }
                            ) { Text(stringResource(R.string.apply)) }
                        }
                    }
                }
            }
        }
        null -> Unit
    }
}

/**
 * Renders the incident picker field composable.
 *
 * @param label Accessible label or display caption.
 * @param value Input value to store, parse, or format.
 * @param tag Tag supplied to [IncidentPickerField].
 * @param enabled Whether this feature or control is currently enabled.
 * @param icon Action or callback executed for icon.
 * @param clearLabel Clear label supplied to [IncidentPickerField].
 * @param onClear Callback invoked when clear changes or occurs.
 * @param onClick Callback invoked when the control is activated.
 */
@Composable
private fun IncidentPickerField(
    label: String,
    value: String,
    tag: String,
    enabled: Boolean,
    icon: @Composable () -> Unit,
    clearLabel: String,
    onClear: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = ButtonDefaults.outlinedButtonColors()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (enabled) colors.containerColor else colors.disabledContainerColor,
        contentColor = if (enabled) colors.contentColor else colors.disabledContentColor,
        border = ButtonDefaults.outlinedButtonBorder(enabled),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f)
                    .testTag(tag)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                    .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                icon()
                Column {
                    Text(label, style = MaterialTheme.typography.labelMedium)
                    Text(
                        value.ifBlank { stringResource(R.string.olive_not_selected) },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            if (value.isNotBlank()) {
                IconButton(
                    onClick = onClear,
                    enabled = enabled,
                    modifier = Modifier.padding(end = 4.dp).testTag("${tag}_clear"),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = clearLabel)
                }
            }
        }
    }
}

/**
 * Displays a read-only private note or incident report that another account shared with this user or safe contact.
 *
 * @param item The resource or record being processed.
 * @param ownerDisplayName The display name associated with the item's owner.
 * @param formatter The formatter used to display dates or values.
 * @param onOpen Runs when the user opens the item.
 * @param modifier The Compose modifier applied to the component.
 */
@Composable
internal fun SharedPrivateNoteCard(
    item: SharedPrivateEntry,
    ownerDisplayName: String,
    formatter: DateTimeFormatter,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    viewerUid: String? = null,
    documentsController: SafetyDocumentsController? = null,
) {
    val entry = item.entry
    val isIncident = entry.kind == PrivateEntryKind.Incident
    val container = if (isIncident) IncidentPaper else PaleOlive
    val accent = if (isIncident) IncidentAccent else DarkOlive
    val sharedByLabel = stringResource(R.string.notes_shared_by, ownerDisplayName)
    val timestampLabel = formatter.format(Instant.ofEpochMilli(entry.updatedAt))
    Card(
        onClick = onOpen,
        modifier = modifier
            .fillMaxWidth()
            .testTag("shared_private_entry_${entry.id}")
            .semantics { stateDescription = "$sharedByLabel, $timestampLabel" },
        border = BorderStroke(1.dp, accent),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = Ink),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Outlined.FolderShared, null, tint = accent, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(if (isIncident) R.string.incident else R.string.note),
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                )
                if (entry.attachmentIds.isNotEmpty()) {
                    Icon(
                        Icons.Outlined.AttachFile,
                        contentDescription = stringResource(R.string.note_attachment_count, entry.attachmentIds.size),
                        tint = accent,
                        modifier = Modifier.size(16.dp).testTag("shared_private_entry_attachment_icon"),
                    )
                }
            }
            if (entry.title.isNotBlank()) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                entry.body,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.attachmentIds.isNotEmpty() && documentsController != null) {
                NoteAttachmentPreviewStrip(
                    attachmentIds = entry.attachmentIds,
                    viewerUid = viewerUid,
                    controller = documentsController,
                )
            }
            Text(
                sharedByLabel,
                style = MaterialTheme.typography.labelMedium,
                color = OliveGreen,
                fontWeight = FontWeight.Medium,
            )
            Text(
                timestampLabel,
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}

/**
 * Lets a note owner grant or revoke access to a private note or incident for accepted friends and safe contacts.
 *
 * @param entry The private entry to save, display, or share.
 * @param currentRecipientUids The accounts that currently hold access.
 * @param friends The accepted friends available for the operation.
 * @param contacts The contact details available for this operation.
 * @param onDismiss Runs when the user dismisses the dialog.
 * @param onSave Runs when the user saves the edited information.
 */
@Composable
internal fun NoteSharingDialog(
    entry: PrivateEntry,
    currentRecipientUids: List<String>,
    friends: List<Friend>,
    contacts: List<SafeContact>,
    onDismiss: () -> Unit,
    onSave: (List<Friend>) -> Unit,
) {
    var selected by remember(entry.id, currentRecipientUids) {
        mutableStateOf(currentRecipientUids.toSet())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notes_share_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.notes_share_help),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (friends.isEmpty()) {
                    Text(stringResource(R.string.location_no_friends), color = Muted)
                }
                friends.forEach { friend ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val trusted = contacts.firstOrNull { it.linkedUid == friend.uid }
                        Checkbox(
                            checked = friend.uid in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + friend.uid else selected - friend.uid
                            },
                            enabled = friend.uid in selected || selected.size < 10,
                            modifier = Modifier.testTag("note_share_friend_${friend.uid}"),
                        )
                        Column {
                            Text(trusted?.name ?: friend.username)
                            if (trusted != null) {
                                Text(
                                    "@${friend.username}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Muted,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(friends.filter { it.uid in selected }) },
                modifier = Modifier.testTag("note_share_save"),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
