// Safe-app users: shows the user’s private notes.
package com.olive.app.ui.users.user.notes


import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.PrivateEntry
import com.olive.app.data.PrivateEntryKind
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.keepVisibleWhileTyping
import com.olive.app.ui.shared.oliveScrollbar
import com.olive.app.ui.shared.rememberResourceText
import com.olive.app.ui.users.shared.navigation.Field
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.users.shared.navigation.Status
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.foundation.lazy.grid.items as gridItems
import com.olive.app.ui.shared.LocalizedIcon as Icon

// Notes stay behind the PIN, with a searchable Keep-style board and a full-screen editor.
// UI sizing/color: note editing uses 24dp sides, a 300dp minimum body field, yellow pinned icons, and an adaptive 160dp grid.
@Composable
internal fun PrivateNotes(session: SessionViewModel) {
    val uiText = rememberResourceText()
    var editorOpen by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf<PrivateEntryKind?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var logEntryId by remember { mutableStateOf<String?>(null) }
    var trashOpen by remember { mutableStateOf(false) }
    val formatter = remember {
        DateTimeFormatter.ofPattern(
            "MMM d, yyyy · HH:mm z",
            Locale.getDefault()
        ).withZone(ZoneId.systemDefault())
    }

    // Opens a new or existing private note in the editor.
    fun openEditor(entry: PrivateEntry?) {
        session.clearPrivateEntryStatus()
        editingId = entry?.id
        kind = entry?.kind
        title = entry?.title.orEmpty()
        body =
            entry?.body.orEmpty()
        editorOpen = true
    }
    BackHandler(editorOpen || logEntryId != null || trashOpen) {
        when {
            logEntryId != null -> logEntryId = null
            editorOpen -> editorOpen = false
            else -> trashOpen = false
        }
    }
    val logEntry = session.privateEntries.firstOrNull { it.id == logEntryId }
    if (logEntry != null) {
        PrivateEntryEditLog(logEntry, formatter) { logEntryId = null }
        return
    }
    // Show the note editor when the user is creating or changing a note.
    if (editorOpen) {
        val entry = session.privateEntries.firstOrNull { it.id == editingId }
        val editorListState = rememberLazyListState()
        LazyColumn(
            Modifier.fillMaxSize().oliveScrollbar(editorListState),
            state = editorListState,
            contentPadding = PaddingValues(
                start = 24.dp,
                end = 24.dp,
                top = 12.dp,
                bottom = 112.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { editorOpen = false },
                        modifier = Modifier.testTag("private_notes_back")
                    ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiText(R.string.all_notes)) }
                    Text(
                        if (editingId == null) uiText(R.string.new_private_entry) else uiText(R.string.edit_private_entry),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = (Modifier.weight(1f)).semantics { heading() })
                    if (entry != null) IconButton(
                        onClick = { session.togglePrivateEntryPin(entry.id) },
                        enabled = !session.busy,
                        modifier = Modifier.testTag("private_pin_editor")
                    ) {
                        Icon(
                            Icons.Outlined.PushPin,
                            if (entry.pinned) uiText(R.string.ui_unpin_entry) else uiText(R.string.ui_pin_entry),
                            tint = if (entry.pinned) Color(0xFFFFC107) else Muted
                        )
                    }
                    if (entry != null) IconButton(
                        onClick = {
                            session.trashPrivateEntry(entry.id)
                            editorOpen = false
                        },
                        enabled = !session.busy,
                        modifier = Modifier.testTag("delete_private_entry")
                    ) { Icon(Icons.Outlined.DeleteOutline, uiText(R.string.ui_move_to_trash)) }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(
                        PrivateEntryKind.Note to uiText(R.string.note),
                        PrivateEntryKind.Incident to uiText(R.string.incident)
                    ).forEach { (value, label) ->
                        FilterChip(
                            selected = kind == value,
                            onClick = { kind = if (kind == value) null else value },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                                .testTag("private_kind_${value.name.lowercase()}")
                        )
                    }
                }
            }
            item {
                Field(
                    uiText(R.string.title_optional),
                    title,
                    "private_entry_title",
                    enabled = !session.busy
                ) { title = it.take(120) }
            }
            item {
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it.take(10_000) },
                    label = { Text(stringResource(R.string.private_details)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp)
                        .testTag("private_entry_body").keepVisibleWhileTyping(),
                    enabled = !session.busy
                )
            }
            item {
                Button(
                    onClick = {
                        session.savePrivateEntry(editingId, kind, title, body)
                        if (body.isNotBlank()) editorOpen = false
                    },
                    enabled = !session.busy && body.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("save_private_entry")
                ) {
                    Text(if (editingId == null) uiText(R.string.save_private_entry) else uiText(R.string.update_private_entry))
                }
            }
            // Save the note only after at least one field has changed.
            if (entry?.edits?.isNotEmpty() == true) item {
                TextButton(
                    onClick = { logEntryId = entry.id },
                    modifier = Modifier.fillMaxWidth().testTag("private_edit_log")
                ) {
                    Text("${stringResource(R.string.edit_log)} (${entry.edits.size})")
                }
            }
            item { Status(session) }
        }
        return
    }
    val trashed =
        session.privateEntries.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt }
    // Leave the editor before opening the deleted-note folder.
    if (trashOpen) {
        Trash(trashed, formatter, { id ->
            session.restorePrivateEntry(id)
            trashOpen = false
        }) { trashOpen = false }
        return
    }
    val filtered = session.privateEntries.filter {
        it.deletedAt == null && (search.isBlank() || it.title.contains(
            search,
            true
        ) || it.body.contains(search, true))
    }
    val pinned = filtered.filter { it.pinned }.sortedByDescending { it.updatedAt }
    val others = filtered.filterNot { it.pinned }.sortedByDescending { it.updatedAt }
    val notesGridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        modifier = Modifier.fillMaxSize().oliveScrollbar(notesGridState),
        state = notesGridState,
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 112.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = session.navigation::back,
                    modifier = Modifier.testTag("safe_home")
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        uiText(R.string.ui_previous_safe_page)
                    )
                }
                Text(
                    stringResource(R.string.notes_incidents),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = (Modifier.weight(1f)).semantics { heading() })
                IconButton(
                    onClick = { trashOpen = true },
                    modifier = Modifier.testTag("open_scratchpad")
                ) {
                    BadgedBox(badge = { if (trashed.isNotEmpty()) Badge { Text(trashed.size.toString()) } }) {
                        Icon(Icons.Outlined.DeleteOutline, uiText(R.string.ui_trash))
                    }
                }
                FilledTonalIconButton(
                    onClick = { openEditor(null) },
                    enabled = !session.busy,
                    modifier = Modifier.testTag("new_private_entry")
                ) { Icon(Icons.Outlined.Add, uiText(R.string.ui_new_note)) }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedTextField(
                search,
                { search = it.take(200) },
                modifier = Modifier.fillMaxWidth().testTag("private_notes_search")
                    .keepVisibleWhileTyping(),
                placeholder = { Text(stringResource(R.string.search_notes)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                trailingIcon = if (search.isNotEmpty()) {
                    {
                        IconButton(onClick = { search = "" }) {
                            Icon(
                                Icons.Outlined.Close,
                                uiText(R.string.clear_search)
                            )
                        }
                    }
                } else null
            )
        }
        if (session.message != null || session.busy) item(span = { GridItemSpan(maxLineSpan) }) {
            Status(
                session
            )
        }
        // Let the empty-search message use the full grid width.
        if (filtered.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                if (search.isBlank()) uiText(
                    R.string.no_private_entries_yet
                ) else uiText(R.string.no_notes_found), color = Muted
            )
        }
        // Put the pinned heading across the full note grid.
        if (pinned.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                stringResource(R.string.pinned),
                style = MaterialTheme.typography.titleMedium,
                color = OliveGreen
            )
        }
        gridItems(pinned, key = { it.id }) { entry ->
            PrivateNoteCard(
                entry,
                formatter,
                { openEditor(entry) },
                { session.togglePrivateEntryPin(entry.id) },
                { logEntryId = entry.id },
                Modifier.animateItem()
            )
        }
        if (others.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                if (pinned.isEmpty()) uiText(
                    R.string.all_notes
                ) else uiText(R.string.others), style = MaterialTheme.typography.titleMedium
            )
        }
        gridItems(others, key = { it.id }) { entry ->
            PrivateNoteCard(
                entry,
                formatter,
                { openEditor(entry) },
                { session.togglePrivateEntryPin(entry.id) },
                { logEntryId = entry.id },
                Modifier.animateItem()
            )
        }
    }
}

// UI sizing/color: Trash cards use a 1dp black outline, warm paper fill, 16dp padding, and Material text sizes.
@Composable
private fun PrivateNoteCard(
    entry: PrivateEntry, formatter: DateTimeFormatter,
    open: () -> Unit, pin: () -> Unit, openLog: () -> Unit, modifier: Modifier = Modifier
) {
    val uiText = rememberResourceText()
    Card(
        onClick = open,
        modifier = modifier.fillMaxWidth().testTag("private_entry_row"),
        border = BorderStroke(1.dp, Color.Black),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFDF4))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                entry.kind?.let {
                    Text(
                        if (it == PrivateEntryKind.Incident) uiText(R.string.incident) else uiText(
                            R.string.note
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = OliveGreen,
                        modifier = Modifier.weight(1f)
                    )
                }
                    ?: Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = pin,
                    modifier = Modifier.size(34.dp).testTag("private_pin")
                ) {
                    Icon(
                        Icons.Outlined.PushPin,
                        if (entry.pinned) uiText(R.string.ui_unpin_entry) else uiText(R.string.ui_pin_entry),
                        tint = if (entry.pinned) Color(0xFFFFC107) else Muted
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
            Text(
                (if (entry.edits.isNotEmpty()) "${stringResource(R.string.edited)} · " else "") + formatter.format(
                    Instant.ofEpochMilli(entry.updatedAt)
                ), style = MaterialTheme.typography.bodySmall, color = Muted
            )
            if (entry.edits.isNotEmpty()) TextButton(
                onClick = openLog,
                modifier = Modifier.testTag("private_edit_log"),
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("${stringResource(R.string.edit_log)} (${entry.edits.size})")
            }
        }
    }
}
