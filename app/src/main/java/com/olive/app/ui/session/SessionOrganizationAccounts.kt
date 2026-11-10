// Signed-in users: loads staff and admin account details.
package com.olive.app.ui.session

import com.olive.app.R
import com.olive.app.data.*
import com.olive.app.security.*
import com.olive.app.ui.shared.*
import com.olive.app.ui.users.shared.navigation.*
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*

// Owns staff and administrator account workflows.
internal class OrganizationAccounts(private val runtime: SessionRuntime) {
    // Checks and saves the staff or admin organization profile.
    fun saveOrganizationProfile(fullName: String, dob: String, email: String, phone: String) {
        with(runtime) {
            with(state) {
                val identity = pendingIdentity ?: return
                val repository = adminRepository ?: return
                if (fullName.isBlank() || !InputPolicy.validDob(dob) ||
                    !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
                ) {
                    message = runtime.application.getString(R.string.ui_full_name_a_valid_date_of_birth_and_a_real_email_are_required)
                    return
                }
                operations.runOperation(runtime.application.getString(R.string.ui_unable_to_finish_the_organization_profile_check_the_email_and_try)) { token ->
                    val updated = repository.completeOrganizationProfile(fullName.trim(), dob.trim(), email.trim(), phone.trim())
                    val saved = withContext(Dispatchers.IO) { store.enrollWithoutPin(identity, updated) }
                    if (epoch == token) {
                        enrollment = saved
                        archivedConversationIds = withContext(Dispatchers.IO) { store.archivedConversationIds(saved.uid) }
                        restoredClosedConversationIds = withContext(Dispatchers.IO) { store.restoredClosedConversationIds(saved.uid) }
                        privateContactNotes = withContext(Dispatchers.IO) { store.privateContactNotes(saved.uid) }
                        profile = updated
                        pendingProfile = updated
                        accountRole = updated.role
                        coverUsername = updated.username
                        coverDob = updated.dob
                        coverFullName = updated.fullName
                        language = updated.language
                        screenHistory.clear()
                        screen = SessionScreen.Portal
                        if (updated.role == AccountRole.ADMIN) queueAdminRefresh()
                    }
                }
            }
        }
    }

    // Updates organization profile.
    fun updateOrganizationProfile(fullName: String, dob: String, email: String, phone: String) {
        with(runtime) {
            with(state) {
                val device = enrollment ?: return
                val repository = adminRepository ?: return
                if (screen != SessionScreen.Portal || accountRole !in setOf(AccountRole.STAFF, AccountRole.ADMIN) || busy) return
                if (fullName.isBlank() || !InputPolicy.validDob(dob) ||
                    !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
                ) {
                    message = runtime.application.getString(R.string.ui_full_name_a_valid_date_of_birth_and_a_real_email_are_required)
                    return
                }
                operations.runOperation(runtime.application.getString(R.string.ui_unable_to_update_your_olive_tree_profile)) { token ->
                    val updated = repository.completeOrganizationProfile(fullName.trim(), dob.trim(), email.trim(), phone.trim())
                    withContext(Dispatchers.IO) { store.cacheProfile(device.uid, updated) }
                    if (epoch == token && screen == SessionScreen.Portal) {
                        profile = updated
                        pendingProfile = updated
                        coverDob = updated.dob
                        coverFullName = updated.fullName
                        message = runtime.application.getString(R.string.ui_profile_updated)
                    }
                }
            }
        }
    }

    // Refreshes only the small dashboard totals after an administrator signs in.
    fun refreshAdminSummary() {
        with(runtime) {
            with(state) {
                val repository = adminRepository ?: return
                if (accountRole != AccountRole.ADMIN || busy) return
                operations.runOperation(runtime.application.getString(R.string.ui_unable_to_load_organization_accounts)) { token ->
                    val counts = repository.summary()
                    if (epoch == token && screen == SessionScreen.Portal) adminCounts = counts
                }
            }
        }
    }

    // Loads the complete directory only when the administrator opens Accounts.
    fun refreshAdminAccounts() {
        with(runtime) {
            with(state) {
                val repository = adminRepository ?: return
                if (accountRole != AccountRole.ADMIN || busy) return
                adminAccounts = emptyList()
                operations.runOperation(runtime.application.getString(R.string.ui_unable_to_load_organization_accounts)) { token ->
                    val rows = repository.accounts()
                    if (epoch == token && screen == SessionScreen.Portal) {
                        adminAccounts = rows
                    }
                }
            }
        }
    }

    // Loads staff rows and their workload totals only when Staff management is open.
    fun refreshAdminStaff() {
        with(runtime) {
            with(state) {
                val repository = adminRepository ?: return
                if (accountRole != AccountRole.ADMIN || busy) return
                adminAccounts = emptyList()
                operations.runOperation(runtime.application.getString(R.string.ui_unable_to_load_organization_accounts)) { token ->
                    val rows = repository.staffAccounts()
                    if (epoch == token && screen == SessionScreen.Portal) {
                        adminAccounts = rows
                    }
                }
            }
        }
    }

    // Stores the newest staff and admin list after an admin refresh.
    fun queueAdminRefresh() {
        with(runtime) {
            with(state) {
                scope.launch {
                    while (busy) delay(50.milliseconds)
                    if (screen == SessionScreen.Portal && accountRole == AccountRole.ADMIN) refreshAdminSummary()
                }
            }
        }
    }

    // Creates managed account.
    fun createManagedAccount(value: AdminAccountDraft) {
        with(runtime) {
            with(state) {
                val repository = adminRepository ?: return
                if (accountRole != AccountRole.ADMIN || busy) return
                if (!InputPolicy.validUsername(value.username)) {
                    message =
                        runtime.application.getString(R.string.ui_use_8_24_letters_numbers_or_underscores_for_the_username)
                    return
                }
                if (!InputPolicy.passwordExcludesUsername(value.password, value.username)) {
                    message =
                        runtime.application.getString(R.string.password_excludes_username)
                    return
                }
                if (!InputPolicy.validAccountPassword(value.password, value.username)) {
                    message =
                        runtime.application.getString(R.string.ui_use_8_24_characters_with_a_letter_number_and_special_character)
                    return
                }
                operations.runOperation(runtime.application.getString(R.string.ui_unable_to_create_the_account_the_username_or_email_may_already_be)) { token ->
                    repository.create(value)
                    val rows = repository.accounts()
                    val counts = repository.summary()
                    // Apply the result only while the same organization portal is open.
                    if (epoch == token && screen == SessionScreen.Portal) {
                        adminCounts = counts
                        adminAccounts = rows
                        message =
                            runtime.application.getString(
                                R.string.ui_value_account_created,
                                value.role.localizedTitle(ResourceText(runtime.application.resources))
                            )
                    }
                }
            }
        }
    }

    // Deletes managed account.
    fun deleteManagedAccount(value: AdminAccountSummary) {
        with(runtime) {
            with(state) {
                val repository = adminRepository ?: return
                if (accountRole != AccountRole.ADMIN || busy || value.protected) return
                operations.runOperation(runtime.application.getString(R.string.ui_unable_to_delete_this_account)) { token ->
                    repository.delete(value.uid)
                    val rows = repository.accounts()
                    val counts = repository.summary()
                    // Apply the result only while the same organization portal is open.
                    if (epoch == token && screen == SessionScreen.Portal) {
                        adminCounts = counts
                        adminAccounts = rows
                        message =
                            runtime.application.getString(
                                R.string.ui_value_was_deleted,
                                value.username
                            )
                    }
                }
            }
        }
    }
}

// Signed-in users: loads and saves private contact notes.

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

    // Creates or updates one encrypted private note.
    fun savePrivateEntry(
        id: String?, kind: PrivateEntryKind, title: String, body: String,
        incidentDate: String, incidentTime: String, incidentLocation: String,
        incidentLatitude: Double?, incidentLongitude: Double?, attachmentIds: List<String>,
    ) {
        with(runtime) {
            with(state) {
                if (screen != SessionScreen.Safe || page != SafePage.Notes || busy) return
                val cleanTitle = title.trim()
                val cleanBody = body.trim()
                val cleanDate = incidentDate.trim()
                val cleanTime = incidentTime.trim()
                val cleanLocation = incidentLocation.trim()
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
                if (kind == PrivateEntryKind.Incident &&
                    (cleanTitle.isBlank() || cleanDate.isBlank() || cleanLocation.isBlank())) {
                    message = runtime.application.getString(R.string.incident_required_fields)
                    return
                }
                val uid = enrollment?.uid ?: return
                val existing =
                    id?.let { wanted -> privateEntries.firstOrNull { it.id == wanted && it.deletedAt == null } }
                val changes = existing?.let { old ->
                    buildList {
                        // Records a profile field only when its value really changed.
                        fun changed(field: String, before: String, after: String) {
                            if (before != after) add(FieldChange(field, before, after))
                        }
                        changed("Tag", old.kind.name, kind.name)
                        changed("Title", old.title, cleanTitle)
                        changed("Details", old.body, cleanBody)
                        changed("Incident date", old.incidentDate, cleanDate)
                        changed("Approximate time", old.incidentTime, cleanTime)
                        changed("Incident location", old.incidentLocation, cleanLocation)
                        changed("Attachments", old.attachmentIds.joinToString(), attachmentIds.distinct().joinToString())
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
                    incidentDate = if (kind == PrivateEntryKind.Incident) cleanDate else "",
                    incidentTime = if (kind == PrivateEntryKind.Incident) cleanTime else "",
                    incidentLocation = if (kind == PrivateEntryKind.Incident) cleanLocation else "",
                    incidentLatitude = if (kind == PrivateEntryKind.Incident) incidentLatitude else null,
                    incidentLongitude = if (kind == PrivateEntryKind.Incident) incidentLongitude else null,
                    attachmentIds = attachmentIds.distinct().take(20),
                    pinned = existing?.pinned ?: false,
                    edits = existing?.edits.orEmpty() + if (existing == null) emptyList() else listOf(
                        PrivateEntryEdit(timestamp = now, changes = changes)
                    )
                )
                operations.runOperation(runtime.application.getString(R.string.unable_to_save_this_private_entry_please_retry)) { token ->
                    withContext(Dispatchers.IO) { store.savePrivateEntry(uid, entry) }
                    // Update the note list only while the same Safe App notes page is open.
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

    // Removes private entry from the signed-in session.
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

// Signed-in users: changes the private entry method.

// Changing entry credentials needs fresh proof of the currently usable method.
internal class SessionEntrySettings(private val runtime: SessionRuntime) {
    private val keys = DeviceLockKey(if (runtime.localMode) "demo" else "firebase")

    // Allows entry settings only for a user who has an active device enrollment.
    private fun editable() = with(runtime) {
        !state.busy && state.screen == SessionScreen.Safe &&
                state.page == SafePage.Settings && !state.privacyBlackout && state.accountRole == AccountRole.USER
    }

    // Validates and saves the selected private-entry method.
    fun select(method: SafeEntryMethod, currentPin: String) {
        if (!editable()) return
        val device = runtime.enrollment ?: return
        if (method == SafeEntryMethod.APP_PIN && !device.hasAppPin) {
            runtime.state.message = runtime.application.getString(R.string.entry_set_pin_first)
            return
        }
        change(currentPin, null, method)
    }

    // Checks both PIN fields and saves the new backup app PIN.
    fun savePin(currentPin: String, pin: String, confirmation: String, usePin: Boolean) {
        if (!editable()) return
        if (!InputPolicy.validPin(pin)) {
            runtime.state.message =
                runtime.application.getString(R.string.enter_your_4_6_digit_pin)
            return
        }
        if (pin != confirmation) {
            runtime.state.message =
                runtime.application.getString(R.string.the_new_pins_do_not_match)
            return
        }
        change(currentPin, pin, if (usePin) SafeEntryMethod.APP_PIN else null)
    }

    // Checks the current PIN, then changes the private entry PIN or method.
    private fun change(currentPin: String, pin: String?, method: SafeEntryMethod?): Unit =
        with(runtime) {
            val device = enrollment ?: return
            if (state.busy || state.screen != SessionScreen.Safe || state.page != SafePage.Settings ||
                state.privacyBlackout || state.accountRole != AccountRole.USER || device.role != AccountRole.USER
            ) return
            val secure = phoneSecure()
            val enableDevice = method == SafeEntryMethod.DEVICE_LOCK
            if (enableDevice && !secure) {
                state.message = application.getString(R.string.entry_device_unavailable)
                return
            }
            // Keep Android authentication as proof while device lock is preferred.
            // The backup PIN becomes proof only when it is preferred or the phone lock is gone.
            val verifyPin = device.hasAppPin &&
                    (device.entryMethod == SafeEntryMethod.APP_PIN || !secure)
            if (verifyPin && !InputPolicy.validPin(currentPin)) {
                state.message = application.getString(R.string.enter_your_4_6_digit_pin)
                return
            }
            if (!verifyPin && !secure) {
                state.message = application.getString(R.string.entry_restore_lock)
                return
            }
            operations.runOperation(
                application.getString(R.string.entry_settings_failed),
                timeoutMillis = 120_000
            ) { token ->
                // Reads the current private-entry settings before changing them.
                fun current(expected: DeviceEnrollment = device) =
                    epoch == token && enrollment == expected && state.screen == SessionScreen.Safe &&
                            state.page == SafePage.Settings && !state.privacyBlackout
                // Check the current app PIN before allowing entry settings to change.
                if (verifyPin) {
                    val result =
                        withContext(Dispatchers.IO) { store.verify(device.uid, currentPin) }
                    if (!current()) return@runOperation
                    // Shows the rejection message when the entered PIN does not match.
                    if (result is PinResult.Rejected) {
                        if (result.failures >= 5) navigation.quickExit()
                        else state.message = application.resources.getQuantityString(
                            R.plurals.ui_incorrect_pin_value_attempts_remaining,
                            5 - result.failures,
                            5 - result.failures
                        )
                        return@runOperation
                    }
                }
                // A fresh key is staged when enabling device lock. Cancellation deletes only that staged key.
                val challenge = if (enableDevice || !verifyPin) withContext(Dispatchers.IO) {
                    keys.prepare(
                        device.uid,
                        if (enableDevice) null else store.deviceLockGrant(device.uid)
                    )
                } else null
                // Turn an unexpected current failure into a readable message.
                try {
                    if (!current()) return@runOperation
                    var grant: org.json.JSONObject? = null
                    if (challenge != null) {
                        val request = DeviceLockRequest(challenge, settingsChange = true)
                        state.deviceLockRequest = request
                        // Turn an unexpected current failure into a readable message.
                        try {
                            val cipher = request.result.await()
                            if (!current()) return@runOperation
                            if (cipher == null) {
                                state.message =
                                    application.getString(R.string.entry_settings_cancelled)
                                return@runOperation
                            }
                            grant = withContext(Dispatchers.IO) { challenge.finish(cipher) }
                        } finally {
                            if (state.deviceLockRequest === request) state.deviceLockRequest = null
                        }
                    }
                    if (!current()) return@runOperation
                    // Never commit a PIN fallback if the device became secure again during verification.
                    if (verifyPin && device.entryMethod == SafeEntryMethod.DEVICE_LOCK && phoneSecure()) return@runOperation
                    // Once the verified write begins, finish updating the cached enrollment even
                    // if Quick exit cancels the operation. This never reopens the safe screen.
                    val updated = withContext(NonCancellable) {
                        val saved = withContext(Dispatchers.IO) {
                            store.updateSafeEntry(
                                device,
                                method,
                                pin,
                                grant
                            )
                        }
                        if (grant != null) challenge?.markEnrolled()
                        if (enrollment == device) enrollment = saved
                        saved
                    }
                    if (!current(updated)) return@runOperation
                    state.message = application.getString(R.string.entry_settings_saved)
                    withContext(Dispatchers.IO) {
                        store.recordSettings(
                            device.uid,
                            SettingsEvent(
                                area = "Olive Branch",
                                action = "Updated safe entry settings"
                            )
                        )
                    }
                    history.queueHistorySync()
                } finally {
                    challenge?.close()
                }
            }
        }
}
