package com.olive.app.ui


import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.olive.app.data.*
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Notes stay behind the PIN, with a searchable Keep-style board and a full-screen editor.
// UI sizing/color: note editing uses 24dp sides, a 300dp minimum body field, yellow pinned icons, and an adaptive 160dp grid.
@Composable internal fun PrivateNotes(session: SessionViewModel) {
    var editorOpen by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf<PrivateEntryKind?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var logEntryId by remember { mutableStateOf<String?>(null) }
    var trashOpen by remember { mutableStateOf(false) }
    val language=LocalOliveLanguage.current
    val formatter=remember(language) { DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm z",
        Locale.forLanguageTag(getLanguageTag(language))).withZone(ZoneId.systemDefault()) }
    fun openEditor(entry: PrivateEntry?) {
        session.clearPrivateEntryStatus()
        editingId=entry?.id;kind=entry?.kind;title=entry?.title.orEmpty();body=entry?.body.orEmpty();editorOpen=true
    }
    BackHandler(editorOpen || logEntryId!=null || trashOpen) {
        when {
            logEntryId!=null -> logEntryId=null
            editorOpen -> editorOpen=false
            else -> trashOpen=false
        }
    }
    val logEntry=session.privateEntries.firstOrNull { it.id==logEntryId }
    if(logEntry!=null) {
        PrivateEntryEditLog(logEntry,formatter,language) { logEntryId=null }
        return
    }
    if(editorOpen) {
        val entry=session.privateEntries.firstOrNull { it.id==editingId }
        val editorListState=rememberLazyListState()
        LazyColumn(Modifier.fillMaxSize().oliveScrollbar(editorListState),state=editorListState,contentPadding=PaddingValues(start=24.dp,end=24.dp,top=12.dp,bottom=112.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={editorOpen=false},modifier=Modifier.testTag("private_notes_back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"All notes") }
                LocalizedText(if(editingId==null) "New private entry" else "Edit private entry",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                if(entry!=null) IconButton(onClick={session.togglePrivateEntryPin(entry.id)},enabled=!session.busy,modifier=Modifier.testTag("private_pin_editor")) {
                    Icon(Icons.Outlined.PushPin,if(entry.pinned) "Unpin entry" else "Pin entry",tint=if(entry.pinned) Color(0xFFFFC107) else Muted)
                }
                if(entry!=null) IconButton(onClick={session.trashPrivateEntry(entry.id);editorOpen=false},enabled=!session.busy,
                    modifier=Modifier.testTag("delete_private_entry")) { Icon(Icons.Outlined.DeleteOutline,"Move to Trash") }
            } }
            item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                listOf(PrivateEntryKind.Note to "Note",PrivateEntryKind.Incident to "Incident").forEach { (value,label) ->
                    FilterChip(selected=kind==value,onClick={kind=if(kind==value) null else value},label={LocalizedText(label)},
                        modifier=Modifier.weight(1f).testTag("private_kind_${value.name.lowercase()}"))
                }
            } }
            item { Field("Title (optional)",title,"private_entry_title",enabled=!session.busy) { title=it.take(120) } }
            item { OutlinedTextField(value=body,onValueChange={body=it.take(10_000)},label={LocalizedText("Private details")},
                modifier=Modifier.fillMaxWidth().heightIn(min=300.dp).testTag("private_entry_body"),enabled=!session.busy) }
            item { Button(onClick={
                session.savePrivateEntry(editingId,kind,title,body)
                if(body.isNotBlank()) editorOpen=false
            },enabled=!session.busy && body.isNotBlank(),modifier=Modifier.fillMaxWidth().testTag("save_private_entry")) {
                LocalizedText(if(editingId==null) "Save private entry" else "Update private entry")
            } }
            if(entry?.edits?.isNotEmpty()==true) item { TextButton(onClick={logEntryId=entry.id},modifier=Modifier.fillMaxWidth().testTag("private_edit_log")) {
                Text("${translate("Edit log",language)} (${entry.edits.size})")
            } }
            item { Status(session) }
        }
        return
    }
    val trashed=session.privateEntries.filter { it.deletedAt!=null }.sortedByDescending { it.deletedAt }
    if(trashOpen) {
        Trash(trashed,formatter,language,{ id ->
            session.restorePrivateEntry(id)
            trashOpen=false
        }) { trashOpen=false }
        return
    }
    val filtered=session.privateEntries.filter { it.deletedAt==null && (search.isBlank() || it.title.contains(search,true) || it.body.contains(search,true)) }
    val pinned=filtered.filter { it.pinned }.sortedByDescending { it.updatedAt }
    val others=filtered.filterNot { it.pinned }.sortedByDescending { it.updatedAt }
    val notesGridState=rememberLazyGridState()
    LazyVerticalGrid(columns=GridCells.Adaptive(160.dp),modifier=Modifier.fillMaxSize().oliveScrollbar(notesGridState),state=notesGridState,
        contentPadding=PaddingValues(start=18.dp,end=18.dp,top=12.dp,bottom=112.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item(span={GridItemSpan(maxLineSpan)}) { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=session::back,modifier=Modifier.testTag("safe_home")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Previous safe page") }
            LocalizedText("Notes & incidents",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f))
            IconButton(onClick={trashOpen=true},modifier=Modifier.testTag("open_scratchpad")) {
                BadgedBox(badge={if(trashed.isNotEmpty()) Badge { Text(trashed.size.toString()) }}) {
                    Icon(Icons.Outlined.DeleteOutline,"Trash")
                }
            }
            FilledTonalIconButton(onClick={openEditor(null)},enabled=!session.busy,modifier=Modifier.testTag("new_private_entry")) { Icon(Icons.Outlined.Add,"New note") }
        } }
        item(span={GridItemSpan(maxLineSpan)}) { OutlinedTextField(search,{search=it.take(200)},modifier=Modifier.fillMaxWidth().testTag("private_notes_search"),
            placeholder={LocalizedText("Search notes")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,
            trailingIcon=if(search.isNotEmpty()) {{IconButton(onClick={search=""}) { Icon(Icons.Outlined.Close,"Clear search") }}} else null) }
        if(session.message!=null || session.busy) item(span={GridItemSpan(maxLineSpan)}) { Status(session) }
        if(filtered.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) { LocalizedText(if(search.isBlank()) "No private entries yet." else "No notes found.",color=Muted) }
        if(pinned.isNotEmpty()) item(span={GridItemSpan(maxLineSpan)}) { LocalizedText("Pinned",style=MaterialTheme.typography.titleMedium,color=OliveGreen) }
        gridItems(pinned,key={it.id}) { entry -> PrivateNoteCard(entry,formatter,language,{openEditor(entry)},{session.togglePrivateEntryPin(entry.id)},{logEntryId=entry.id},Modifier.animateItem()) }
        if(others.isNotEmpty()) item(span={GridItemSpan(maxLineSpan)}) { LocalizedText(if(pinned.isEmpty()) "All notes" else "Others",style=MaterialTheme.typography.titleMedium) }
        gridItems(others,key={it.id}) { entry -> PrivateNoteCard(entry,formatter,language,{openEditor(entry)},{session.togglePrivateEntryPin(entry.id)},{logEntryId=entry.id},Modifier.animateItem()) }
    }
}

// UI sizing/color: Trash cards use a 1dp black outline, warm paper fill, 16dp padding, and Material text sizes.
@Composable private fun Trash(entries: List<PrivateEntry>, formatter: DateTimeFormatter, language: String,
    restore: (String)->Unit, close: ()->Unit) {
    var now by remember(entries) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(entries) {
        while(true) { delay(1_000.milliseconds);now=System.currentTimeMillis() }
    }
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState),state=listState,contentPadding=PaddingValues(start=20.dp,end=20.dp,top=12.dp,bottom=112.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=close,modifier=Modifier.testTag("close_scratchpad")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"All notes") }
                LocalizedText("Trash",style=MaterialTheme.typography.headlineSmall)
            }
            LocalizedText(if(PRIVATE_ENTRY_TRASH_RETENTION_MS<60_000L)
                "For testing, deleted notes stay here for 10 seconds before their private contents are permanently erased."
                else "Deleted notes stay here for 30 days, then their private contents are permanently erased.",color=Muted)
        }
        if(entries.isEmpty()) item { LocalizedText("Trash is empty.",color=Muted) }
        items(entries,key={it.id}) { entry ->
            Card(Modifier.animateItem().fillMaxWidth().testTag("scratchpad_entry"),border=BorderStroke(1.dp,Color.Black),
                colors=CardDefaults.cardColors(containerColor=Color(0xFFFFFDF4))) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    entry.kind?.let { LocalizedText(if(it==PrivateEntryKind.Incident) "Incident" else "Note",color=OliveGreen) }
                    if(entry.title.isNotBlank()) Text(entry.title,style=MaterialTheme.typography.titleMedium)
                    Text(entry.body,maxLines=5,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(if(PRIVATE_ENTRY_TRASH_RETENTION_MS<60_000L)
                        "${entry.trashSecondsRemaining(now)} ${translate("seconds remaining",language)}"
                        else "${entry.trashDaysRemaining(now)} ${translate("days remaining",language)}",
                        style=MaterialTheme.typography.bodySmall,color=Muted)
                    Text(formatter.format(Instant.ofEpochMilli(entry.deletedAt ?: entry.updatedAt)),style=MaterialTheme.typography.bodySmall,color=Muted)
                    OutlinedButton(onClick={restore(entry.id)},modifier=Modifier.fillMaxWidth().testTag("restore_private_entry")) {
                        Icon(Icons.Outlined.RestoreFromTrash,null);Spacer(Modifier.width(8.dp));LocalizedText("Restore")
                    }
                }
            }
        }
    }
}

// UI sizing/color: revision cards use 16dp padding, black outlines, soft olive badges, and small Muted timestamps.
@Composable private fun PrivateEntryEditLog(entry: PrivateEntry, formatter: DateTimeFormatter, language: String, close: ()->Unit) {
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState),state=listState,contentPadding=PaddingValues(start=20.dp,end=20.dp,top=12.dp,bottom=112.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {
            TextButton(onClick=close,modifier=Modifier.testTag("close_private_edit_log")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack,null);LocalizedText("Close")
            }
            LocalizedText("Edit log",style=MaterialTheme.typography.headlineMedium)
            Text(entry.title.ifBlank { translate("Untitled entry",language) },style=MaterialTheme.typography.titleMedium,color=Muted)
            Text("${entry.edits.size} ${translate("revisions",language)}",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
        items(entry.edits.asReversed(),key={it.id}) { edit ->
            val revision=entry.edits.indexOf(edit)+1
            Card(Modifier.animateItem().fillMaxWidth(),border=BorderStroke(1.dp,Color.Black),colors=CardDefaults.cardColors(containerColor=Color.White)) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Surface(shape=CircleShape,color=Color(0xFFE7EDE2)) {
                            Text(revision.toString(),Modifier.padding(horizontal=11.dp,vertical=6.dp),fontWeight=androidx.compose.ui.text.font.FontWeight.Bold,color=OliveGreen)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("${translate("Revision",language)} $revision",style=MaterialTheme.typography.titleMedium)
                            Text(formatter.format(Instant.ofEpochMilli(edit.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted)
                        }
                    }
                    edit.changes.forEach { change ->
                        Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,color=Color(0xFFF5F3EC)) {
                            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                LocalizedText(change.field,style=MaterialTheme.typography.labelLarge,color=OliveGreen)
                                LocalizedText("Before",style=MaterialTheme.typography.labelSmall,color=Muted)
                                Text(translate(change.before.ifBlank { "Empty" },language))
                                HorizontalDivider(Modifier.padding(vertical=3.dp))
                                LocalizedText("After",style=MaterialTheme.typography.labelSmall,color=Muted)
                                Text(translate(change.after.ifBlank { "Empty" },language))
                            }
                        }
                    }
                }
            }
        }
    }
}

// UI sizing/color: note cards use a 1dp outline, warm paper fill, 14dp padding, and up to three title lines.
@Composable private fun PrivateNoteCard(entry: PrivateEntry, formatter: DateTimeFormatter, language: String,
    open: ()->Unit, pin: ()->Unit, openLog: ()->Unit, modifier: Modifier=Modifier) {
    Card(onClick=open,modifier=modifier.fillMaxWidth().testTag("private_entry_row"),
        border=BorderStroke(1.dp,Color.Black),colors=CardDefaults.cardColors(containerColor=Color(0xFFFFFDF4))) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                entry.kind?.let { LocalizedText(if(it==PrivateEntryKind.Incident) "Incident" else "Note",style=MaterialTheme.typography.labelMedium,color=OliveGreen,modifier=Modifier.weight(1f)) }
                    ?: Spacer(Modifier.weight(1f))
                IconButton(onClick=pin,modifier=Modifier.size(34.dp).testTag("private_pin")) { Icon(Icons.Outlined.PushPin,if(entry.pinned) "Unpin entry" else "Pin entry",tint=if(entry.pinned) Color(0xFFFFC107) else Muted) }
            }
            if(entry.title.isNotBlank()) Text(entry.title,style=MaterialTheme.typography.titleMedium,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(entry.body,maxLines=8,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text((if(entry.edits.isNotEmpty()) "${translate("Edited",language)} · " else "")+formatter.format(Instant.ofEpochMilli(entry.updatedAt)),style=MaterialTheme.typography.bodySmall,color=Muted)
            if(entry.edits.isNotEmpty()) TextButton(onClick=openLog,modifier=Modifier.testTag("private_edit_log"),contentPadding=PaddingValues(0.dp)) {
                Text("${translate("Edit log",language)} (${entry.edits.size})")
            }
        }
    }
}
