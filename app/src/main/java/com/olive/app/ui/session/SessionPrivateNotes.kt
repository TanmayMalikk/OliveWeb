// Signed-in users: loads and saves private contact notes.
package com.olive.app.ui.session

import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.R

import com.olive.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

// Owns private note edits, trash and restoration.
internal class SessionNotes(private val runtime: SessionRuntime) {
    // Removes the last private-note success or error message before a new action begins.
    fun clearPrivateEntryStatus() {
        with(runtime) {
            with(state) {
                if (screen == SessionScreen.Safe && page == SafePage.Notes) message = null
            }
        }
    }

    fun savePrivateEntry(id: String?, kind: PrivateEntryKind?, title: String, body: String) {
        with(runtime) {
            with(state) {
                if (screen != SessionScreen.Safe || page != SafePage.Notes || busy) return
                val cleanTitle = title.trim()
                val cleanBody = body.trim()
                if (cleanBody.isBlank()) {
                    message =
                        runtime.application.getString(R.string.write_something_before_saving)
                    return
                }
                if (cleanTitle.length > 120 || cleanBody.length > 10_000) {
                    message =
                        runtime.application.getString(R.string.use_up_to_120_title_characters_and_10_000_detail_characters)
                    return
                }
                val uid = enrollment?.uid ?: return
                val existing =
                    id?.let { wanted -> privateEntries.firstOrNull { it.id == wanted && it.deletedAt == null } }
                val changes = existing?.let { old ->
                    buildList {
                        fun changed(field: String, before: String, after: String) {
                            if (before != after) add(FieldChange(field, before, after))
                        }
                        changed("Tag", old.kind?.name ?: "No tag", kind?.name ?: "No tag")
                        changed("Title", old.title, cleanTitle)
                        changed("Details", old.body, cleanBody)
                    }
                }.orEmpty()
                if (existing != null && changes.isEmpty()) {
                    message = runtime.application.getString(R.string.no_changes_to_save)
                    return
                }
                val now = System.currentTimeMillis()
                val entry = PrivateEntry(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    timestamp = existing?.timestamp ?: now,
                    updatedAt = now,
                    kind = kind,
                    title = cleanTitle,
                    body = cleanBody,
                    pinned = existing?.pinned ?: false,
                    edits = existing?.edits.orEmpty() + if (existing == null) emptyList() else listOf(
                        PrivateEntryEdit(timestamp = now, changes = changes)
                    )
                )
                operations.runOperation(runtime.application.getString(R.string.unable_to_save_this_private_entry_please_retry)) { token ->
                    withContext(Dispatchers.IO) { store.savePrivateEntry(uid, entry) }
                    if (epoch == token && screen == SessionScreen.Safe && page == SafePage.Notes) {
                        privateEntries = withContext(Dispatchers.IO) { store.privateEntries(uid) }
                        message =
                            if (localMode) runtime.application.getString(R.string.saved_privately_on_this_device) else runtime.application.getString(
                                R.string.saved_privately_syncing_to_your_account
                            )
                    }
                    history.queueHistorySync()
                }
            }
        }
    }

    // Switches private entry PIN on or off.
    fun togglePrivateEntryPin(id: String) {
        with(runtime) {
            with(state) {
                if (screen != SessionScreen.Safe || page != SafePage.Notes || busy) return
                val uid = enrollment?.uid ?: return
                val existing =
                    privateEntries.firstOrNull { it.id == id && it.deletedAt == null } ?: return
                val now = System.currentTimeMillis()
                val pinned = !existing.pinned
                // Pinning changes placement only, so it never creates a content revision in the edit log.
                val entry = existing.copy(updatedAt = now, pinned = pinned)
                operations.runOperation(runtime.application.getString(R.string.ui_unable_to_update_this_private_entry_please_retry)) { token ->
                    withContext(Dispatchers.IO) { store.savePrivateEntry(uid, entry) }
                    if (epoch == token && screen == SessionScreen.Safe && page == SafePage.Notes)
                        privateEntries = withContext(Dispatchers.IO) { store.privateEntries(uid) }
                    history.queueHistorySync()
                }
            }
        }
    }

    fun trashPrivateEntry(id: String) {
        with(runtime) {
            with(state) {
                val existing =
                    privateEntries.firstOrNull { it.id == id && it.deletedAt == null } ?: return
                val now = System.currentTimeMillis()
                updatePrivateEntry(
                    existing.copy(updatedAt = now, pinned = false, deletedAt = now),
                    "Unable to move this entry to Trash. Please retry.",
                    if (PRIVATE_ENTRY_TRASH_RETENTION_MS < 60_000L) "Moved to Trash for 10 seconds." else "Moved to Trash for 30 days."
                )
                val uid = enrollment?.uid ?: return
                scope.launch {
                    delay((PRIVATE_ENTRY_TRASH_RETENTION_MS + 100L).milliseconds)
                    val purged =
                        withContext(Dispatchers.IO) { store.purgeExpiredPrivateEntries(uid) }
                    if (purged && enrollment?.uid == uid) {
                        privateEntries = withContext(Dispatchers.IO) { store.privateEntries(uid) }
                        history.queueHistorySync()
                    }
                }
            }
        }
    }

    // Restores private entry.
    fun restorePrivateEntry(id: String) {
        with(runtime) {
            with(state) {
                val existing =
                    privateEntries.firstOrNull { it.id == id && it.deletedAt != null } ?: return
                updatePrivateEntry(
                    existing.copy(updatedAt = System.currentTimeMillis(), deletedAt = null),
                    "Unable to restore this entry. Please retry.", "Restored from Trash."
                )
            }
        }
    }

    // Encrypts and saves an edited private note, then reports success or failure to its screen.
    fun updatePrivateEntry(entry: PrivateEntry, failure: String, success: String) {
        with(runtime) {
            with(state) {
                if (screen != SessionScreen.Safe || page != SafePage.Notes || busy) return
                val uid = enrollment?.uid ?: return
                operations.runOperation(failure) { token ->
                    withContext(Dispatchers.IO) { store.savePrivateEntry(uid, entry) }
                    if (epoch == token && screen == SessionScreen.Safe && page == SafePage.Notes) {
                        privateEntries = withContext(Dispatchers.IO) { store.privateEntries(uid) }
                        message = success
                    }
                    history.queueHistorySync()
                }
            }
        }
    }
}
