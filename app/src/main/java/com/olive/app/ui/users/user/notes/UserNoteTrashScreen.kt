// Safe-app users: shows deleted notes that a user can restore.
package com.olive.app.ui.users.user.notes


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
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
import com.olive.app.data.PRIVATE_ENTRY_TRASH_RETENTION_MS
import com.olive.app.data.PrivateEntry
import com.olive.app.data.PrivateEntryKind
import com.olive.app.data.trashDaysRemaining
import com.olive.app.data.trashSecondsRemaining
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.oliveScrollbar
import com.olive.app.ui.shared.rememberResourceText
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.milliseconds
import com.olive.app.ui.shared.LocalizedIcon as Icon


// Shows trashed notes with restore and permanent-delete actions.
@Composable
internal fun Trash(
    entries: List<PrivateEntry>, formatter: DateTimeFormatter,
    restore: (String) -> Unit, close: () -> Unit
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
            Card(
                Modifier.animateItem().fillMaxWidth().testTag("scratchpad_entry"),
                border = BorderStroke(1.dp, Color.Black),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFDF4))
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    entry.kind?.let {
                        Text(
                            if (it == PrivateEntryKind.Incident) uiText(R.string.incident) else uiText(
                                R.string.note
                            ), color = OliveGreen
                        )
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

// UI sizing/color: revision cards use 16dp padding, black outlines, soft olive badges, and small Muted timestamps.
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
                        Surface(shape = CircleShape, color = Color(0xFFE7EDE2)) {
                            Text(
                                revision.toString(),
                                Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
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
                            color = Color(0xFFF5F3EC)
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
