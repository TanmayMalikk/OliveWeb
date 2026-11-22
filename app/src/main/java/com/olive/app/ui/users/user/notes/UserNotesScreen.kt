// Safe-app users: shows the user’s private notes.
package com.olive.app.ui.users.user.notes

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveFilledTonalIconButton as FilledTonalIconButton
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveTextButton as TextButton
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.BuildConfig
import com.olive.app.R
import com.olive.app.ui.users.user.dashboard.LocalLocationPermissionRequest
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.location.AddressSuggestion
import com.olive.app.data.location.DestinationAutocompleteClient
import com.olive.app.data.location.DeviceLocation
import com.olive.app.data.location.GeoapifyClient
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.user.documents.SafetyDocumentUploadFlow
import com.olive.app.ui.users.user.location.InteractiveLocationMap
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.LocalTime
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Validates incident date.
 *
 * @param value Input value to store, parse, or format.
 * @return The resulting valid incident date.
 */
private fun validIncidentDate(value: String) =
    value.isBlank() || com.olive.app.data.account.parseIncidentDate(value) != null

/**
 * Validates incident time.
 *
 * @param value Input value to store, parse, or format.
 * @param saved Optional saved supplied to [validIncidentTime].
 * @return The resulting valid incident time.
 */
private fun validIncidentTime(value: String, saved: String?) =
    value.isBlank() || value == saved || (value.trim().matches(Regex("[0-9]{2}:[0-9]{2}")) &&
        runCatching { LocalTime.parse(value.trim()) }.isSuccess)

/**
 * Notes stay behind the PIN, with a searchable Keep-style board and a full-screen editor. UI sizing/color: note editing uses 24dp sides, a 300dp minimum body field, yellow pinned icons, and an adaptive 160dp grid.
 *
 * @param session Shared session view model owning application state and actions.
 */
@Composable
internal fun PrivateNotes(session: SessionViewModel) {
    val uiText = rememberResourceText()
    val offerUndo = LocalOliveUndo.current
    val noteTrashed = stringResource(R.string.undo_note_trashed)
    var editorOpen by remember { mutableStateOf(false) }
    var editorVersion by remember { mutableIntStateOf(0) }
    val newEntryId = remember(editorVersion) { java.util.UUID.randomUUID().toString() }
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
    var attachmentMenuOpen by rememberSaveable { mutableStateOf(false) }
    var locationSuggestions by remember { mutableStateOf(emptyList<AddressSuggestion>()) }
    var locationSearchRetry by remember { mutableIntStateOf(0) }
    var locationSearchFailed by remember { mutableStateOf(false) }
    var attachAfterUpload by remember { mutableStateOf(false) }
    var filesBeforeUpload by remember { mutableStateOf(emptySet<String>()) }
    var currentLocationPoint by remember { mutableStateOf<MapLocationPoint?>(null) }
    var findingLocation by remember { mutableStateOf(false) }
    var locationLookupFailed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var search by rememberSaveable { mutableStateOf("") }
    var shownKind by rememberSaveable { mutableStateOf<PrivateEntryKind?>(null) }
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var logEntryId by remember { mutableStateOf<String?>(null) }
    var trashOpen by remember { mutableStateOf(false) }
    var sharingEntry by remember { mutableStateOf<PrivateEntry?>(null) }
    val notesGridState = rememberLazyGridState()
    var lastBoardFilter by remember { mutableStateOf(shownKind to newestFirst) }
    LaunchedEffect(shownKind, newestFirst) {
        val current = shownKind to newestFirst
        if (current != lastBoardFilter) {
            lastBoardFilter = current
            notesGridState.scrollToItem(0)
        }
    }
    val editorTitleFocus = remember { FocusRequester() }
    val mapTitleFocus = remember { FocusRequester() }
    val formatter = remember {
        DateTimeFormatter.ofPattern(
            "MMM d, yyyy · HH:mm z",
            Locale.getDefault()
        ).withZone(ZoneId.systemDefault())
    }

    /**
     * Opens a new or existing private note in the editor.
     *
     * @param entry Optional entry supplied to [openEditor].
     */
    fun openEditor(entry: PrivateEntry?) {
        session.clearPrivateEntryStatus()
        editorVersion++
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
        currentLocationPoint = null
        locationLookupFailed = false
        selectedAttachments = entry?.attachmentIds.orEmpty().toSet()
        editorOpen = true
    }
    val uid = session.coverAccountId
    val requestLocationPermission = LocalLocationPermissionRequest.current
    /**
     * Displays current location.
     */
    fun showCurrentLocation() {
        scope.launch {
            if (findingLocation) return@launch
            findingLocation = true
            locationLookupFailed = false
            val requestVersion = editorVersion
            try {
                val granted = requestLocationPermission()
                if (!editorOpen || editorVersion != requestVersion) return@launch
                if (!granted) {
                    locationLookupFailed = true
                    return@launch
                }
                val fix = DeviceLocation.currentCoordinates(context)
                if (!editorOpen || editorVersion != requestVersion) return@launch
                locationLookupFailed = fix == null
                if (fix != null) currentLocationPoint = MapLocationPoint(
                    uid ?: "current", uiText(R.string.current_location), fix.latitude, fix.longitude,
                    fix.timestamp, true, fix.accuracy,
                )
            } finally {
                findingLocation = false
            }
        }
    }
    LaunchedEffect(uid, session.coverUsername, session.localMode) {
        if (uid != null) {
            session.documents.load(uid, session.coverUsername, session.localMode)
            if (!session.localMode) {
                session.messages.ensureMessagingObservation()
                session.ensureSharedEntriesObservation()
            }
        }
    }
    LaunchedEffect(incidentLocation, kind, locationSearchRetry) {
        locationSearchFailed = false
        val trimmed = incidentLocation.trim()
        if (kind != PrivateEntryKind.Incident || incidentLatitude != null ||
            trimmed.length < 2 || (!GeoapifyClient.configured && BuildConfig.MAPBOX_ACCESS_TOKEN.isBlank())
        ) {
            locationSuggestions = emptyList()
            return@LaunchedEffect
        }
        delay(50.milliseconds)
        val geoResults = if (GeoapifyClient.configured) {
            try {
                GeoapifyClient.autocomplete(trimmed, false, minLength = 2)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        val results = if (geoResults.isNotEmpty() || BuildConfig.MAPBOX_ACCESS_TOKEN.isBlank()) {
            geoResults
        } else {
            DestinationAutocompleteClient.suggest(trimmed).map { item ->
                AddressSuggestion(
                    item.id,
                    item.title,
                    item.subtitle,
                    DeliveryAddress(line1 = item.title, latitude = item.latitude, longitude = item.longitude),
                )
            }
        }
        locationSuggestions = results
        locationSearchFailed = results.isEmpty()
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
    LaunchedEffect(incidentMapOpen, editorVersion) {
        if (incidentMapOpen) {
            withFrameNanos { }
            mapTitleFocus.requestFocus()
            do {
                showCurrentLocation()
                delay(15_000.milliseconds)
            } while (editorOpen && incidentMapOpen && !locationLookupFailed)
        }
    }
    val backProgress = rememberOliveBack(editorOpen || logEntryId != null || trashOpen, preview = editorOpen) {
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
            Modifier.fillMaxSize().oliveBackPreview(backProgress).testTag("private_editor_list").oliveScrollbar(editorListState),
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
                    if (entry != null && !session.localMode) IconButton(
                        onClick = { sharingEntry = entry },
                        enabled = !session.busy,
                        modifier = Modifier.size(48.dp).testTag("private_share_editor")
                    ) {
                        val sharedCount = session.sharedEntryRecipients[entry.id].orEmpty().size
                        Icon(
                            Icons.Outlined.Share,
                            uiText(R.string.notes_manage_sharing),
                            tint = if (sharedCount > 0) OliveGreen else Muted
                        )
                    }
                    if (entry != null) IconButton(
                        onClick = { session.togglePrivateEntryPin(entry.id) },
                        enabled = !session.busy,
                        modifier = Modifier.size(48.dp).testTag("private_pin_editor")
                    ) {
                        Icon(
                            Icons.Outlined.PushPin,
                            if (entry.pinned) uiText(R.string.ui_unpin_entry) else uiText(R.string.ui_pin_entry),
                            tint = if (entry.pinned) OliveGreen else Muted
                        )
                    }
                    if (entry != null) IconButton(
                        onClick = {
                            session.trashPrivateEntry(entry.id) {
                                offerUndo(noteTrashed) { session.restorePrivateEntry(entry.id) }
                            }
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
                    uiText(R.string.title_optional),
                    title,
                    "private_entry_title",
                    enabled = !session.busy
                ) { title = it.take(120) }
            }
            if (kind == PrivateEntryKind.Incident) {
                item {
                    Text(stringResource(R.string.incident_optional_fields), color = Muted)
                    IncidentDateTimeFields(
                        date = incidentDate,
                        time = incidentTime,
                        enabled = !session.busy,
                        onDateChange = { incidentDate = it },
                        onTimeChange = { incidentTime = it },
                    )
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = incidentLocation,
                            onValueChange = {
                                incidentLatitude = null
                                incidentLongitude = null
                                incidentLocation = it.take(240)
                                val coordinates = it.split(',').map(String::trim)
                                if (coordinates.size == 2) {
                                    val lat = coordinates[0].toDoubleOrNull()?.takeIf { value -> value.isFinite() && value in -90.0..90.0 }
                                    val lon = coordinates[1].toDoubleOrNull()?.takeIf { value -> value.isFinite() && value in -180.0..180.0 }
                                    if (lat != null && lon != null) {
                                        incidentLatitude = lat
                                        incidentLongitude = lon
                                    }
                                }
                            },
                            label = { Text(stringResource(R.string.incident_location)) },
                            supportingText = { Text(stringResource(R.string.incident_location_formats)) },
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
                        if (locationSearchFailed) {
                            AccessibleMessage(uiText(R.string.incident_no_address_suggestions))
                            TextButton(onClick = { locationSearchRetry++ }) {
                                Text(uiText(R.string.retry))
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
                        if (locationLookupFailed) AccessibleMessage(stringResource(R.string.incident_location_lookup_failed))
                        InteractiveLocationMap(
                            points = currentLocationPoint?.let(::listOf)
                                ?: session.locationMap.state.displayedOwn(session.accountRole)?.let(::listOf).orEmpty(),
                            selectedLocation = incidentLatitude?.let { latitude -> incidentLongitude?.let { latitude to it } },
                            onSelectedLocationChange = { latitude, longitude ->
                                incidentLatitude = latitude
                                incidentLongitude = longitude
                                incidentLocation = uiText(R.string.incident_dropped_pin, latitude, longitude)
                                locationSuggestions = emptyList()
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
                    label = {
                        Text(
                            stringResource(
                                if (kind == PrivateEntryKind.Incident) R.string.incident_body_required
                                else R.string.note_content_required
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp)
                        .testTag("private_entry_body").keepVisibleWhileTyping(),
                    enabled = !session.busy
                )
            }
            item {
                Button(
                    onClick = { attachmentMenuOpen = true },
                    enabled = uid != null && !session.busy,
                    modifier = Modifier.fillMaxWidth().testTag("private_add_attachment"),
                ) {
                    Icon(Icons.Outlined.Attachment, null)
                    Text(
                        stringResource(R.string.add_attachment),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (kind == PrivateEntryKind.Incident) Text(
                    stringResource(R.string.incident_attachments_help),
                    color = Muted,
                )
                session.documents.state.files.filter { it.id in selectedAttachments }.forEach { document ->
                    Row(
                        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = true,
                            onCheckedChange = { selectedAttachments = selectedAttachments - document.id },
                        )
                        Text(document.name, modifier = Modifier.weight(1f))
                        if (document.evidence) Text(stringResource(R.string.evidence), color = OliveGreen)
                    }
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
                            selectedAttachments.toList(), newEntryId = newEntryId,
                        )
                        if (body.isNotBlank()) editorOpen = false
                    },
                    enabled = !session.busy && body.isNotBlank() &&
                        (kind == PrivateEntryKind.Note || (validIncidentDate(incidentDate) &&
                            validIncidentTime(incidentTime, entry?.incidentTime))),
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
        SafetyDocumentUploadFlow(
            session = session,
            visible = attachmentMenuOpen,
            onDismiss = { attachmentMenuOpen = false },
            onSelectExisting = { document ->
                selectedAttachments = selectedAttachments + document.id
            },
            onUploadStarted = {
                filesBeforeUpload = session.documents.state.files.mapTo(mutableSetOf()) { it.id }
                attachAfterUpload = true
            },
        )
        sharingEntry?.let { target ->
            NoteSharingDialog(
                entry = target,
                currentRecipientUids = session.sharedEntryRecipients[target.id].orEmpty(),
                friends = session.messages.friends,
                contacts = session.profile?.contacts.orEmpty(),
                onDismiss = { sharingEntry = null },
                onSave = { friends ->
                    session.sharePrivateEntryWithFriends(target, friends) { sharingEntry = null }
                },
            )
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
    val hasFilters = search.isNotBlank() || shownKind != null
    val filtered = session.privateEntries.filter { entry ->
        entry.deletedAt == null && (shownKind == null || entry.kind == shownKind) &&
            (search.isBlank() || entry.title.contains(search, ignoreCase = true) ||
                entry.body.contains(search, ignoreCase = true))
    }
    val sorted = if (newestFirst) filtered.sortedByDescending { it.updatedAt }
        else filtered.sortedBy { it.updatedAt }
    val (pinned, others) = sorted.partition { it.pinned }
    val filteredShared = session.sharedPrivateEntries.filter { shared ->
        val entry = shared.entry
        entry.deletedAt == null && (shownKind == null || entry.kind == shownKind) &&
            (search.isBlank() || entry.title.contains(search, ignoreCase = true) ||
                entry.body.contains(search, ignoreCase = true))
    }.let { list ->
        if (newestFirst) list.sortedByDescending { it.entry.updatedAt }
        else list.sortedBy { it.entry.updatedAt }
    }
    LazyVerticalGrid(
        columns = if (LocalDensity.current.fontScale >= 1.3f) GridCells.Fixed(1) else GridCells.Adaptive(192.dp),
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
                    enabled = !session.loadingHistory && !session.historyLoadFailed,
                    modifier = Modifier.testTag("open_scratchpad")
                ) {
                    BadgedBox(badge = { if (!session.loadingHistory && !session.historyLoadFailed && trashed.isNotEmpty()) Badge { Text(trashed.size.toString()) } }) {
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
        if (session.loadingHistory) {
            items(3, key = { "loading_note_$it" }) { LoadingSkeleton(SkeletonLayout.Notes, rows = 1) }
            return@LazyVerticalGrid
        }
        if (session.historyLoadFailed) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                AccessibleMessage(stringResource(R.string.account_history_load_failed))
            }
            return@LazyVerticalGrid
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
            Status(session)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            NoteBoardControls(shownKind, newestFirst, { shownKind = it }, { newestFirst = it })
        }
        if (hasFilters) item(span = { GridItemSpan(maxLineSpan) }) {
            val totalMatches = filtered.size + filteredShared.size
            AccessibleMessage(
                androidx.compose.ui.res.pluralStringResource(R.plurals.note_search_results_count, totalMatches, totalMatches),
            )
        }
        // Let the empty-search message use the full grid width.
        if (filtered.isEmpty() && filteredShared.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                if (hasFilters) uiText(R.string.notes_board_no_matching_entries)
                else uiText(R.string.no_private_entries_yet),
                color = Muted
            )
        }
        // Put the pinned heading across the full note grid.
        if (pinned.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(stringResource(R.string.pinned), style = MaterialTheme.typography.titleMedium, color = OliveGreen)
        }
        gridItems(pinned, key = { it.id }) { entry ->
            PrivateNoteCard(
                entry, formatter, { openEditor(entry) },
                { session.togglePrivateEntryPin(entry.id) }, { logEntryId = entry.id }, Modifier.animateItem()
            )
        }
        if (others.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                if (pinned.isEmpty()) uiText(noteBoardKindLabel(shownKind)) else uiText(R.string.others),
                style = MaterialTheme.typography.titleMedium
            )
        }
        gridItems(others, key = { it.id }) { entry ->
            PrivateNoteCard(
                entry, formatter, { openEditor(entry) },
                { session.togglePrivateEntryPin(entry.id) }, { logEntryId = entry.id }, Modifier.animateItem()
            )
        }
        if (filteredShared.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                stringResource(R.string.notes_shared_with_you),
                style = MaterialTheme.typography.titleMedium,
                color = OliveGreen,
                modifier = Modifier.semantics { heading() }
            )
        }
        gridItems(filteredShared, key = { "shared_${it.entry.id}" }) { shared ->
            val ownerName = session.messages.friends.firstOrNull { it.uid == shared.ownerUid }?.username
                ?: stringResource(R.string.account)
            SharedPrivateNoteCard(
                item = shared,
                ownerDisplayName = ownerName,
                formatter = formatter,
                onOpen = { session.messages.openSharedPrivateEntry(shared.entry.id) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/**
 * Computes note board kind label.
 *
 * @param kind Optional kind supplied to [noteBoardKindLabel].
 * @return The computed note board kind label numeric value.
 */
private fun noteBoardKindLabel(kind: PrivateEntryKind?): Int = when (kind) {
    PrivateEntryKind.Note -> R.string.notes_board_notes
    PrivateEntryKind.Incident -> R.string.notes_board_incidents
    null -> R.string.notes_board_all
}

/**
 * Renders the note board controls composable.
 *
 * @param kind Optional kind supplied to [NoteBoardControls].
 * @param newestFirst Whether to apply newest first.
 * @param onKindChange Callback invoked when kind change changes or occurs.
 * @param onOrderChange Callback invoked when order change changes or occurs.
 */
@Composable
private fun NoteBoardControls(
    kind: PrivateEntryKind?,
    newestFirst: Boolean,
    onKindChange: (PrivateEntryKind?) -> Unit,
    onOrderChange: (Boolean) -> Unit,
) {
    var sortOpen by remember { mutableStateOf(false) }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(null, PrivateEntryKind.Note, PrivateEntryKind.Incident).forEach { option ->
            FilterChip(
                selected = kind == option,
                onClick = { onKindChange(option) },
                label = { Text(stringResource(noteBoardKindLabel(option))) },
                modifier = Modifier.testTag("private_filter_${option?.name?.lowercase() ?: "all"}"),
            )
        }
        Box {
            val orderLabel = stringResource(if (newestFirst) R.string.notes_board_newest else R.string.notes_board_oldest)
            OutlinedButton(
                onClick = { sortOpen = true },
                modifier = Modifier.testTag("private_sort"),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(orderLabel)
            }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                listOf(true, false).forEach { newest ->
                    val label = stringResource(if (newest) R.string.notes_board_newest else R.string.notes_board_oldest)
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onOrderChange(newest)
                            sortOpen = false
                        },
                        modifier = Modifier.testTag(if (newest) "private_sort_newest" else "private_sort_oldest")
                            .semantics { selected = newestFirst == newest },
                    )
                }
            }
        }
    }
}
