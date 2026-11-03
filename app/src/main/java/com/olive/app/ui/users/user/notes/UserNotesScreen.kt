// Safe-app users: shows the user’s private notes.
package com.olive.app.ui.users.user.notes


import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.PrivateEntry
import com.olive.app.data.PrivateEntryKind
import com.olive.app.data.AddressSuggestion
import com.olive.app.data.GeoapifyClient
import com.olive.app.data.AccountIdentity
import com.olive.app.data.DeviceLocation
import com.olive.app.data.MapLocationPoint
import com.olive.app.ui.users.user.location.InteractiveLocationMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    var kind by remember { mutableStateOf(PrivateEntryKind.Note) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var incidentDate by remember { mutableStateOf("") }
    var incidentTime by remember { mutableStateOf("") }
    var incidentLocation by remember { mutableStateOf("") }
    var incidentLatitude by remember { mutableStateOf<Double?>(null) }
    var incidentLongitude by remember { mutableStateOf<Double?>(null) }
    var incidentMapOpen by remember { mutableStateOf(false) }
    var selectedAttachments by remember { mutableStateOf(emptySet<String>()) }
    var locationSuggestions by remember { mutableStateOf(emptyList<AddressSuggestion>()) }
    var attachAfterUpload by remember { mutableStateOf(false) }
    var filesBeforeUpload by remember { mutableStateOf(emptySet<String>()) }
    var currentLocationPoint by remember { mutableStateOf<MapLocationPoint?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var search by rememberSaveable { mutableStateOf("") }
    var logEntryId by remember { mutableStateOf<String?>(null) }
    var trashOpen by remember { mutableStateOf(false) }
    val editorTitleFocus = remember { FocusRequester() }
    val mapTitleFocus = remember { FocusRequester() }
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
        kind = entry?.kind ?: PrivateEntryKind.Note
        title = entry?.title.orEmpty()
        body =
            entry?.body.orEmpty()
        incidentDate = entry?.incidentDate.orEmpty()
        incidentTime = entry?.incidentTime.orEmpty()
        incidentLocation = entry?.incidentLocation.orEmpty()
        incidentLatitude = entry?.incidentLatitude
        incidentLongitude = entry?.incidentLongitude
        incidentMapOpen = false
        selectedAttachments = entry?.attachmentIds.orEmpty().toSet()
        editorOpen = true
    }
    val uid = session.coverAccountId
    val unableUpload = stringResource(R.string.documents_upload_failed)
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        session.endExternalFilePicker()
        if (uri != null && uid != null) {
            filesBeforeUpload = session.documents.state.files.mapTo(mutableSetOf()) { it.id }
            attachAfterUpload = true
            session.documents.upload(uri, AccountIdentity(uid, session.coverUsername), unableUpload)
        }
    }
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        if (permissions.values.any { it }) scope.launch {
            DeviceLocation.currentCoordinates(context)?.let { fix ->
                currentLocationPoint = MapLocationPoint(
                    uid ?: "current", uiText(R.string.current_location), fix.latitude, fix.longitude,
                    fix.timestamp, true, fix.accuracy,
                )
            }
        }
    }
    LaunchedEffect(uid, session.coverUsername, session.localMode) {
        if (uid != null) session.documents.load(uid, session.coverUsername, session.localMode)
    }
    LaunchedEffect(incidentLocation, kind) {
        locationSuggestions = emptyList()
        if (kind != PrivateEntryKind.Incident || incidentLocation.trim().length < 3 || !GeoapifyClient.configured) return@LaunchedEffect
        delay(300)
        locationSuggestions = runCatching {
            GeoapifyClient.autocomplete(incidentLocation, false)
        }.getOrDefault(emptyList())
    }
    LaunchedEffect(session.documents.state.files, attachAfterUpload) {
        if (!attachAfterUpload) return@LaunchedEffect
        val added = session.documents.state.files.firstOrNull { it.id !in filesBeforeUpload }
        if (added != null) {
            selectedAttachments = selectedAttachments + added.id
            attachAfterUpload = false
        }
    }
    // Move TalkBack to the editor title so opening a note or incident is announced immediately.
    LaunchedEffect(editorOpen, editingId) {
        if (editorOpen) {
            withFrameNanos { }
            editorTitleFocus.requestFocus()
        }
    }
    // Announce the map panel only after the user opens it from the location field.
    LaunchedEffect(incidentMapOpen) {
        if (incidentMapOpen) {
            withFrameNanos { }
            mapTitleFocus.requestFocus()
        }
    }
    BackHandler(editorOpen || logEntryId != null || trashOpen) {
        when {
            logEntryId != null -> logEntryId = null
            incidentMapOpen -> incidentMapOpen = false
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
                        modifier = Modifier.weight(1f).testTag("private_editor_title")
                            .focusRequester(editorTitleFocus).focusable()
                            .semantics {
                                heading()
                                paneTitle = if (editingId == null) uiText(R.string.new_private_entry)
                                else uiText(R.string.edit_private_entry)
                            })
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
                            onClick = { kind = value },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                                .testTag("private_kind_${value.name.lowercase()}")
                        )
                    }
                }
            }
            item {
                Field(
                    if (kind == PrivateEntryKind.Incident) uiText(R.string.title) else uiText(R.string.title_optional),
                    title,
                    "private_entry_title",
                    enabled = !session.busy
                ) { title = it.take(120) }
            }
            if (kind == PrivateEntryKind.Incident) {
                item {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Field(stringResource(R.string.incident_date), incidentDate, "incident_date", enabled = !session.busy) {
                            incidentDate = it.take(40)
                        }
                        Field(stringResource(R.string.incident_time), incidentTime, "incident_time", enabled = !session.busy) {
                            incidentTime = it.take(40)
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = incidentLocation,
                            onValueChange = { incidentLocation = it.take(240) },
                            label = { Text(stringResource(R.string.incident_location)) },
                            enabled = !session.busy,
                            singleLine = true,
                            trailingIcon = {
                                IconButton(
                                    onClick = { incidentMapOpen = !incidentMapOpen },
                                    enabled = !session.busy,
                                    modifier = Modifier.testTag("incident_location_pin"),
                                ) {
                                    Icon(
                                        Icons.Outlined.PushPin,
                                        stringResource(R.string.incident_pin_title),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                                .testTag("incident_location")
                                .keepVisibleWhileTyping(),
                        )
                        locationSuggestions.forEach { suggestion ->
                            TextButton(
                                onClick = {
                                    incidentLocation = listOf(suggestion.primary, suggestion.secondary)
                                        .filter(String::isNotBlank).joinToString(", ")
                                    incidentLatitude = suggestion.address.latitude
                                    incidentLongitude = suggestion.address.longitude
                                    locationSuggestions = emptyList()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(listOf(suggestion.primary, suggestion.secondary).filter(String::isNotBlank).joinToString(", "))
                            }
                        }
                    }
                }
                if (incidentMapOpen) item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.incident_pin_title),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("incident_map_title")
                                .focusRequester(mapTitleFocus).focusable().semantics {
                                heading()
                                paneTitle = uiText(R.string.incident_pin_title)
                            },
                        )
                        Text(stringResource(R.string.incident_pin_help), color = Muted)
                        TextButton(onClick = {
                            locationPermission.launch(arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ))
                        }) { Text(stringResource(R.string.incident_show_location)) }
                        InteractiveLocationMap(
                            points = currentLocationPoint?.let(::listOf)
                                ?: session.locationMap.state.displayedOwn(session.accountRole)?.let(::listOf).orEmpty(),
                            selectedLocation = incidentLatitude?.let { latitude -> incidentLongitude?.let { latitude to it } },
                            onSelectedLocationChange = { latitude, longitude ->
                                incidentLatitude = latitude
                                incidentLongitude = longitude
                                if (incidentLocation.isBlank()) incidentLocation = uiText(R.string.incident_dropped_pin, latitude, longitude)
                            },
                            modifier = Modifier.fillMaxWidth().height(300.dp).testTag("incident_location_map"),
                        )
                    }
                }
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
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.attachments), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.incident_attachments_help), color = Muted)
                    TextButton(
                        onClick = {
                            session.beginExternalFilePicker()
                            attachmentPicker.launch(arrayOf("image/*", "video/*", "application/pdf", "text/*"))
                        },
                        enabled = uid != null && !session.busy,
                    ) { Text(stringResource(R.string.upload_new_attachment)) }
                    session.documents.state.files.forEach { document ->
                        Row(
                            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = document.id in selectedAttachments,
                                onCheckedChange = { checked ->
                                    selectedAttachments = if (checked) selectedAttachments + document.id else selectedAttachments - document.id
                                },
                            )
                            Text(document.name, modifier = Modifier.weight(1f))
                            if (document.evidence) Text(stringResource(R.string.evidence), color = OliveGreen)
                        }
                    }
                    if (session.documents.state.files.isEmpty()) Text(stringResource(R.string.attachments_empty_help), color = Muted)
                }
            }
            item {
                Button(
                    onClick = {
                        if (kind == PrivateEntryKind.Incident && uid != null) {
                            session.documents.state.files.filter { it.id in selectedAttachments && !it.evidence }
                                .forEach { session.documents.setEvidence(it, uid, true) }
                        }
                        session.savePrivateEntry(
                            editingId, kind, title, body, incidentDate, incidentTime,
                            incidentLocation, incidentLatitude, incidentLongitude,
                            selectedAttachments.toList(),
                        )
                        if (body.isNotBlank()) editorOpen = false
                    },
                    enabled = !session.busy && body.isNotBlank() &&
                        (kind == PrivateEntryKind.Note || (title.isNotBlank() && incidentDate.isNotBlank() && incidentLocation.isNotBlank())),
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
                entry.kind.let {
                    Text(
                        if (it == PrivateEntryKind.Incident) uiText(R.string.incident) else uiText(
                            R.string.note
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = OliveGreen,
                        modifier = Modifier.weight(1f)
                    )
                }
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
