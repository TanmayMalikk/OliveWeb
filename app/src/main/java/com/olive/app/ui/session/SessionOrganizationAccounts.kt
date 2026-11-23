// Signed-in users: loads staff and admin account details.
package com.olive.app.ui.session

import android.os.SystemClock
import androidx.compose.runtime.*
import com.olive.app.R
import com.olive.app.data.account.AccountCounts
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.AdminAccountDraft
import com.olive.app.data.account.AdminAccountSummary
import com.olive.app.data.account.FieldChange
import com.olive.app.data.account.PRIVATE_ENTRY_TRASH_RETENTION_MS
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryEdit
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.account.SettingsEvent
import com.olive.app.security.*
import com.olive.app.ui.shared.*
import com.olive.app.ui.users.shared.navigation.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import android.content.Context
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.functions.FirebaseFunctions
import com.olive.app.data.location.AdminLocationRepository
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.messaging.ChatSubscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

// Owns staff and administrator account workflows.
internal class OrganizationAccounts(private val runtime: SessionRuntime) {
    /**
     * Checks and saves the staff or admin organization profile.
     *
     * @param fullName Full name supplied to [saveOrganizationProfile].
     * @param dob Date of birth supplied to [saveOrganizationProfile].
     * @param email Email supplied to [saveOrganizationProfile].
     * @param phone Phone supplied to [saveOrganizationProfile].
     */
    fun saveOrganizationProfile(fullName: String, dob: String, email: String, phone: String) {
        with(runtime) {
            with(state) {
                val identity = pendingIdentity ?: return
                val repository = adminRepository ?: return
                if (fullName.isBlank() || !InputPolicy.validDob(dob) ||
                    !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
                ) {
                    failInput(runtime.application.getString(R.string.ui_full_name_a_valid_date_of_birth_and_a_real_email_are_required))
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

    /**
     * Updates organization profile.
     *
     * @param fullName Full name supplied to [updateOrganizationProfile].
     * @param dob Date of birth supplied to [updateOrganizationProfile].
     * @param email Email supplied to [updateOrganizationProfile].
     * @param phone Phone supplied to [updateOrganizationProfile].
     */
    fun updateOrganizationProfile(fullName: String, dob: String, email: String, phone: String) {
        with(runtime) {
            with(state) {
                val device = enrollment ?: return
                val repository = adminRepository ?: return
                if (screen != SessionScreen.Portal || accountRole !in setOf(AccountRole.STAFF, AccountRole.ADMIN) || busy) return
                if (fullName.isBlank() || !InputPolicy.validDob(dob) ||
                    !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
                ) {
                    failInput(runtime.application.getString(R.string.ui_full_name_a_valid_date_of_birth_and_a_real_email_are_required))
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

    /**
     * Refreshes admin summary.
     *
     * @return The resulting refresh admin summary.
     */
    fun refreshAdminSummary() = runtime.adminPreloader.summary(force = true)
    /**
     * Refreshes admin accounts.
     *
     * @return The resulting refresh admin accounts.
     */
    fun refreshAdminAccounts() = runtime.adminPreloader.accounts(force = true, showLoading = true)
    /**
     * Refreshes admin staff.
     */
    fun refreshAdminStaff() = runtime.adminPreloader.staff(force = true, showLoading = true)

    /**
     * Start small independent reads after authentication; none of them lock the portal UI.
     */
    fun queueAdminRefresh() {
        val owner = runtime.coverAccountId ?: return
        val token = runtime.epoch
        runtime.scope.launch {
            while (runtime.state.busy && runtime.coverAccountId == owner && runtime.epoch == token) delay(50.milliseconds)
            if (runtime.coverAccountId == owner && runtime.epoch == token &&
                runtime.state.screen == SessionScreen.Portal && runtime.state.accountRole == AccountRole.ADMIN) {
                runtime.adminPreloader.preload()
                runtime.safety.observeAccess()
                runtime.safety.refreshConsultations(force = false)
            }
        }
    }

    /**
     * Creates managed account.
     *
     * @param value Input value to store, parse, or format.
     */
    fun createManagedAccount(value: AdminAccountDraft) {
        with(runtime) {
            with(state) {
                val repository = adminRepository ?: return
                if (accountRole != AccountRole.ADMIN || busy) return
                if (!InputPolicy.validUsername(value.username)) {
                    failInput(
                        runtime.application.getString(R.string.ui_use_8_24_letters_numbers_or_underscores_for_the_username)
                    )
                    return
                }
                if (!InputPolicy.passwordExcludesUsername(value.password, value.username)) {
                    failInput(runtime.application.getString(R.string.password_excludes_username))
                    return
                }
                if (!InputPolicy.validAccountPassword(value.password, value.username)) {
                    failInput(
                        runtime.application.getString(R.string.ui_use_8_24_characters_with_a_letter_number_and_special_character)
                    )
                    return
                }
                operations.runOperation(runtime.application.getString(R.string.ui_unable_to_create_the_account_the_username_or_email_may_already_be)) { token ->
                    repository.create(value)
                    val rows = repository.accounts()
                    val counts = repository.summary()
                    // Apply the result only while the same organization portal is open.
                    if (epoch == token && screen == SessionScreen.Portal) {
                        adminPreloader.adopt(rows, counts)
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

    /**
     * Deletes managed account.
     *
     * @param value Input value to store, parse, or format.
     */
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
                        adminPreloader.adopt(rows, counts)
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
    /**
     * Removes the last private-note success or error message before a new action begins.
     */
    fun clearPrivateEntryStatus() {
        with(runtime) {
            with(state) {
                if (screen == SessionScreen.Safe && page == SafePage.Notes) message = null
            }
        }
    }

    /**
     * Creates or updates one encrypted private note.
     *
     * @param id Optional ID supplied to [savePrivateEntry].
     * @param kind Kind supplied to [savePrivateEntry].
     * @param title Primary display title text.
     * @param body Body text content.
     * @param incidentDate Incident date supplied to [savePrivateEntry].
     * @param incidentTime Incident time supplied to [savePrivateEntry].
     * @param incidentLocation Incident location supplied to [savePrivateEntry].
     * @param incidentLatitude Optional incident latitude supplied to [savePrivateEntry].
     * @param incidentLongitude Optional incident longitude supplied to [savePrivateEntry].
     * @param attachmentIds Collection of attachment IDs values to process.
     * @param newEntryId Stable identity for a new note across repeated save attempts.
     */
    fun savePrivateEntry(
        id: String?, kind: PrivateEntryKind, title: String, body: String,
        incidentDate: String, incidentTime: String, incidentLocation: String,
        incidentLatitude: Double?, incidentLongitude: Double?, attachmentIds: List<String>,
        newEntryId: String = UUID.randomUUID().toString(),
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
                val uid = enrollment?.uid ?: return
                val existing =
                    id?.let { wanted -> privateEntries.firstOrNull { it.id == wanted && it.deletedAt == null } }
                if (id != null && existing == null) return
                if (id == null && privateEntries.any { it.id == newEntryId }) return
                val changes = existing?.let { old ->
                    buildList {
                        /**
                         * Records a profile field only when its value really changed.
                         *
                         * @param field Field supplied to [changed].
                         * @param before Before supplied to [changed].
                         * @param after After supplied to [changed].
                         */
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
                    id = existing?.id ?: newEntryId,
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

    /**
     * Switches private entry PIN on or off.
     *
     * @param id ID supplied to [togglePrivateEntryPin].
     */
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

    /**
     * Removes private entry from the signed-in session.
     *
     * @param id ID supplied to [trashPrivateEntry].
     */
    fun trashPrivateEntry(id: String, onTrashed: (() -> Unit)? = null) {
        with(runtime) {
            with(state) {
                val existing =
                    privateEntries.firstOrNull { it.id == id && it.deletedAt == null } ?: return
                if (busy) return
                val now = System.currentTimeMillis()
                updatePrivateEntry(
                    existing.copy(updatedAt = now, pinned = false, deletedAt = now),
                    "Unable to move this entry to Trash. Please retry.",
                    when {
                        onTrashed != null -> null
                        PRIVATE_ENTRY_TRASH_RETENTION_MS < 60_000L -> "Moved to Trash for 10 seconds."
                        else -> "Moved to Trash for 30 days."
                    },
                    onTrashed,
                )
                val uid = enrollment?.uid ?: return
                if (!localMode) {
                    scope.launch(Dispatchers.IO) {
                        runCatching { secureDocumentRepository?.revokeAllRecipients(id, uid) }
                    }
                }
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

    /**
     * Restores private entry.
     *
     * @param id ID supplied to [restorePrivateEntry].
     */
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

    /**
     * Encrypts and saves an edited private note, then reports success or failure to its screen.
     *
     * @param entry Entry supplied to [updatePrivateEntry].
     * @param failure Exception or failure reason encountered during execution.
     * @param success Success supplied to [updatePrivateEntry].
     */
    private fun updatePrivateEntry(entry: PrivateEntry, failure: String, success: String?, onSaved: (() -> Unit)? = null) {
        with(runtime) {
            with(state) {
                if (screen != SessionScreen.Safe || page != SafePage.Notes || busy) return
                val uid = enrollment?.uid ?: return
                val previous = privateEntries.firstOrNull { it.id == entry.id } ?: return
                privateEntries = privateEntries.map { if (it.id == entry.id) entry else it }
                operations.runOperation(failure) { token ->
                    try {
                        withContext(Dispatchers.IO) { store.savePrivateEntry(uid, entry) }
                    } catch (failure: Exception) {
                        if (epoch == token && enrollment?.uid == uid) {
                            privateEntries = privateEntries.map { if (it == entry) previous else it }
                        }
                        throw failure
                    }
                    if (epoch == token && screen == SessionScreen.Safe && page == SafePage.Notes) {
                        privateEntries = withContext(Dispatchers.IO) { store.privateEntries(uid) }
                        message = success
                        onSaved?.invoke()
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

    /**
     * Allows entry settings only for a user who has an active device enrollment.
     */
    private fun editable() = with(runtime) {
        !state.busy && state.screen == SessionScreen.Safe &&
                state.page == SafePage.Settings && !state.privacyBlackout && state.accountRole == AccountRole.USER
    }

    /**
     * Validates and saves the selected private-entry method.
     *
     * @param method Method supplied to [select].
     * @param currentPin Current PIN supplied to [select].
     */
    fun select(method: SafeEntryMethod, currentPin: String) {
        if (!editable()) return
        val device = runtime.enrollment ?: return
        if (method == SafeEntryMethod.APP_PIN && !device.hasAppPin) {
            runtime.state.failInput(runtime.application.getString(R.string.entry_set_pin_first))
            return
        }
        change(currentPin, null, method)
    }

    /**
     * Checks both PIN fields and saves the new backup app PIN.
     *
     * @param currentPin Current PIN supplied to [savePin].
     * @param pin User-entered PIN string.
     * @param confirmation Confirmation supplied to [savePin].
     * @param usePin Whether to use PIN.
     */
    fun savePin(currentPin: String, pin: String, confirmation: String, usePin: Boolean) {
        if (!editable()) return
        if (!InputPolicy.validPin(pin)) {
            runtime.state.failInput(runtime.application.getString(R.string.enter_your_4_6_digit_pin))
            return
        }
        if (pin != confirmation) {
            runtime.state.failInput(runtime.application.getString(R.string.the_new_pins_do_not_match))
            return
        }
        change(currentPin, pin, if (usePin) SafeEntryMethod.APP_PIN else null)
    }

    /**
     * Checks the current PIN, then changes the private entry PIN or method.
     *
     * @param currentPin Current PIN supplied to [change].
     * @param pin User-entered PIN string, or null when not provided.
     * @param method Optional method supplied to [change].
     */
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
                state.failInput(application.getString(R.string.enter_your_4_6_digit_pin))
                return
            }
            if (!verifyPin && !secure) {
                state.failInput(application.getString(R.string.entry_restore_lock))
                return
            }
            operations.runOperation(
                application.getString(R.string.entry_settings_failed),
                timeoutMillis = 120_000
            ) { token ->
                /**
                 * Reads the current private-entry settings before changing them.
                 *
                 * @param expected Expected supplied to [current].
                 * @return The resulting current.
                 */
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
                        else state.failInput(
                            application.resources.getQuantityString(
                                R.plurals.ui_incorrect_pin_value_attempts_remaining,
                                5 - result.failures,
                                5 - result.failures
                            )
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

internal data class EmergencyAlert(
    val id: String, val ownerUid: String, val ownerName: String,
    val createdAt: Long, val mediaId: String, val resolvedAt: Long = 0L,
)

// Owns the remote emergency operations; Compose only observes results and sends actions.
internal class EmergencyResponseController(private val context: Context) {
    /**
     * Starts observing alerts.
     *
     * @param onUpdate Callback invoked when updated data is available.
     * @param onError Callback invoked when an error occurs.
     * @return Subscription or registration handle to close when observation is no longer needed.
     */
    fun observeAlerts(
        onUpdate: (List<EmergencyAlert>) -> Unit,
        onError: (String?) -> Unit,
    ): ChatSubscription {
        val listener = FirebaseFirestore.getInstance().collection("emergencyReports")
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, failure ->
                if (failure != null) onError(failure.localizedMessage)
                if (snapshot != null && !snapshot.metadata.isFromCache) {
                    onUpdate(snapshot.documents.mapNotNull { doc ->
                        val owner = doc.getString("ownerUid") ?: return@mapNotNull null
                        EmergencyAlert(
                            doc.id, owner, doc.getString("ownerName") ?: owner,
                            doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L,
                            doc.getString("mediaId").orEmpty(),
                            doc.getTimestamp("resolvedAt")?.toDate()?.time ?: 0L,
                        )
                    }.sortedByDescending { it.createdAt })
                }
            }
        return ChatSubscription { listener.remove() }
    }

    /**
     * Starts observing locations.
     *
     * @param onUpdate Callback invoked when updated data is available.
     * @param onError Callback invoked when an error occurs.
     * @return Subscription or registration handle to close when observation is no longer needed.
     */
    fun observeLocations(
        onUpdate: (List<MapLocationPoint>) -> Unit,
        onError: (String) -> Unit,
    ): ChatSubscription = AdminLocationRepository().observe(onUpdate, onError)

    /**
     * Sets resolved.
     *
     * @param accountId Optional account ID supplied to [setResolved].
     * @param alertId Alert ID supplied to [setResolved].
     * @param resolved Whether to apply resolved.
     */
    suspend fun setResolved(accountId: String?, alertId: String, resolved: Boolean) {
        val user = FirebaseAuth.getInstance().currentUser
        check(accountId != null && user?.uid == accountId) { "Sign in again to resolve this request." }
        user.getIdToken(false).await()
        try {
            FirebaseAppCheck.getInstance().getAppCheckToken(false).await()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            throw IllegalStateException(context.getString(R.string.emergency_report_device_verification_failed), failure)
        }
        check(FirebaseAuth.getInstance().currentUser?.uid == accountId) { "Sign in again to resolve this request." }
        FirebaseFunctions.getInstance("northamerica-northeast1")
            .getHttpsCallable(if (resolved) "emergencyResolve" else "emergencyReopen")
            .call(mapOf("id" to alertId)).await()
    }
}

internal data class AdminPreloaderUiState(
    val foregroundLoads: Set<String> = emptySet(),
    val accountsCache: List<AdminAccountSummary>? = null,
    val staffCache: List<AdminAccountSummary>? = null,
    val loadingAccounts: Boolean = false,
    val loadingStaff: Boolean = false,
    val accountsError: Int? = null,
    val staffError: Int? = null,
)

// In-memory admin data belongs to one authenticated session, including legitimately empty results.
internal class AdminDataPreloader(private val runtime: SessionRuntime) {
    private enum class Dataset { Summary, Accounts, Staff }
    private val jobs = ConcurrentHashMap<Dataset, Job>()
    private val refreshedAt = ConcurrentHashMap<Dataset, Long>()
    private var owner: Pair<String, Long>? = null
    private val uiStateFlow = MutableStateFlow(AdminPreloaderUiState())
    val uiState: StateFlow<AdminPreloaderUiState> = uiStateFlow.asStateFlow()
    private var uiStateSnapshot by mutableStateOf(uiStateFlow.value)

    /**
     * Atomically updates the UI state flow and updates the local state snapshot.
     *
     * @param transform State reducer transforming current state.
     */
    private inline fun updateUiState(transform: (AdminPreloaderUiState) -> AdminPreloaderUiState) {
        uiStateSnapshot = uiStateFlow.updateAndGet(transform)
    }

    private var foregroundLoads: Set<String>
        get() = uiStateSnapshot.foregroundLoads
        set(value) = updateUiState { it.copy(foregroundLoads = value) }
    private var accountsCache: List<AdminAccountSummary>?
        get() = uiStateSnapshot.accountsCache
        set(value) = updateUiState { it.copy(accountsCache = value) }
    private var staffCache: List<AdminAccountSummary>?
        get() = uiStateSnapshot.staffCache
        set(value) = updateUiState { it.copy(staffCache = value) }
    var loadingAccounts: Boolean
        get() = uiStateSnapshot.loadingAccounts
        private set(value) = updateUiState { it.copy(loadingAccounts = value) }
    var loadingStaff: Boolean
        get() = uiStateSnapshot.loadingStaff
        private set(value) = updateUiState { it.copy(loadingStaff = value) }
    var accountsError: Int?
        get() = uiStateSnapshot.accountsError
        private set(value) = updateUiState { it.copy(accountsError = value) }
    var staffError: Int?
        get() = uiStateSnapshot.staffError
        private set(value) = updateUiState { it.copy(staffError = value) }
    /**
     * Returns the session.
     *
     * @return The resulting session.
     */
    private fun session() = runtime.coverAccountId?.let { it to runtime.epoch }
    /**
     * Computes authorized.
     *
     * @param token Authentication, push notification, or session token.
     * @return The resulting authorized.
     */
    private fun authorized(token: Pair<String, Long>) = session() == token &&
        runtime.state.accountRole == AccountRole.ADMIN && runtime.state.screen == SessionScreen.Portal
    /**
     * Returns the owns cache.
     *
     * @return The resulting owns cache.
     */
    private fun ownsCache() = owner == session() && runtime.state.accountRole == AccountRole.ADMIN
    val accounts get() = if (ownsCache() || owner == null && runtime.state.accountRole == AccountRole.ADMIN) accountsCache ?: runtime.state.adminAccounts else emptyList()
    val staff get() = if (ownsCache() || owner == null && runtime.state.accountRole == AccountRole.ADMIN) staffCache ?: runtime.state.adminAccounts.filter { it.role == AccountRole.STAFF } else emptyList()
    val accountsReady get() = ownsCache() && accountsCache != null
    val staffReady get() = ownsCache() && staffCache != null
    val showAccountsSkeleton get() = loadingAccounts && (!accountsReady || Dataset.Accounts.name in foregroundLoads)
    val showStaffSkeleton get() = loadingStaff && (!staffReady || Dataset.Staff.name in foregroundLoads)

    /**
     * Clears the admin data preloader.
     */
    fun clear() {
        jobs.values.toList().forEach(Job::cancel)
        jobs.clear()
        refreshedAt.clear()
        owner = null
        foregroundLoads = emptySet()
        accountsCache = null
        staffCache = null
        loadingAccounts = false
        loadingStaff = false
        accountsError = null
        staffError = null
        runtime.state.adminAccounts = emptyList()
        runtime.state.setAdminSummary("", AccountCounts())
    }

    /**
     * Computes preload.
     */
    fun preload() {
        summary()
        accounts()
        staff()
    }
    /**
     * Computes summary.
     *
     * @param force Whether to apply force.
     * @return The resulting summary.
     */
    fun summary(force: Boolean = false) = load(Dataset.Summary, force)
    /**
     * Computes accounts.
     *
     * @param force Whether to apply force.
     * @param showLoading Whether to show loading.
     */
    fun accounts(force: Boolean = false, showLoading: Boolean = false) = load(Dataset.Accounts, force, showLoading)
    /**
     * Computes staff.
     *
     * @param force Whether to apply force.
     * @param showLoading Whether to show loading.
     */
    fun staff(force: Boolean = false, showLoading: Boolean = false) = load(Dataset.Staff, force, showLoading)

    /**
     * A mutation replaces the directory immediately; workload totals refresh independently.
     *
     * @param rows Collection of rows values to process.
     * @param counts Counts supplied to [adopt].
     */
    fun adopt(rows: List<AdminAccountSummary>, counts: AccountCounts) {
        val token = session() ?: return
        if (!authorized(token)) return
        if (owner != token) clear()
        owner = token
        jobs.remove(Dataset.Accounts)?.cancel()
        jobs.remove(Dataset.Summary)?.cancel()
        accountsCache = rows
        runtime.state.adminAccounts = rows
        runtime.state.setAdminSummary(token.first, counts)
        loadingAccounts = false
        foregroundLoads = foregroundLoads - Dataset.Accounts.name
        accountsError = null
        refreshedAt[Dataset.Accounts] = SystemClock.elapsedRealtime()
        refreshedAt[Dataset.Summary] = SystemClock.elapsedRealtime()
        jobs.remove(Dataset.Staff)?.cancel()
        staff(force = true)
    }

    /**
     * Loads the admin data preloader.
     *
     * @param dataset Dataset supplied to [load].
     * @param force Whether to apply force.
     * @param showLoading Whether to show loading.
     */
    private fun load(dataset: Dataset, force: Boolean, showLoading: Boolean = false) {
        val token = session() ?: return
        val repository = runtime.adminRepository ?: return
        if (!authorized(token)) return
        if (owner != token) { clear(); owner = token }
        if (jobs[dataset]?.isActive == true) {
            if (showLoading) foregroundLoads = foregroundLoads + dataset.name
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!force && refreshedAt[dataset]?.let { now - it < 90_000 } == true) return
        foregroundLoads = if (showLoading) foregroundLoads + dataset.name else foregroundLoads - dataset.name
        when (dataset) {
            Dataset.Accounts -> { loadingAccounts = true; accountsError = null }
            Dataset.Staff -> { loadingStaff = true; staffError = null }
            else -> Unit
        }
        val request = runtime.scope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(25_000) {
                    when (dataset) {
                        Dataset.Summary -> repository.summary().let {
                            if (authorized(token)) runtime.state.setAdminSummary(token.first, it)
                        }
                        Dataset.Accounts -> repository.accounts().let {
                            if (authorized(token)) { accountsCache = it; runtime.state.adminAccounts = it }
                        }
                        Dataset.Staff -> repository.staffAccounts().let {
                            if (authorized(token)) staffCache = it
                        }
                    }
                }
                if (authorized(token)) refreshedAt[dataset] = SystemClock.elapsedRealtime()
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                if (authorized(token)) when (dataset) {
                    Dataset.Accounts -> accountsError = R.string.ui_unable_to_load_organization_accounts
                    Dataset.Staff -> staffError = R.string.ui_unable_to_load_organization_accounts
                    Dataset.Summary -> runtime.state.message = runtime.application.getString(R.string.ui_unable_to_load_organization_accounts)
                }
            } finally {
                if (jobs[dataset] === coroutineContext.job) {
                    jobs.remove(dataset)
                    foregroundLoads = foregroundLoads - dataset.name
                    if (dataset == Dataset.Accounts) loadingAccounts = false
                    if (dataset == Dataset.Staff) loadingStaff = false
                }
            }
        }
        jobs[dataset] = request
        request.start()
    }
}
