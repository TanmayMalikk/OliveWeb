// Signed-in users: saves account settings and profile changes.
package com.olive.app.ui.session

import com.olive.app.ui.users.shared.navigation.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olive.app.R
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.FieldChange
import com.olive.app.data.account.SafeContact
import com.olive.app.data.account.SettingsEvent
import com.olive.app.data.account.UserProfile
import com.olive.app.data.account.profileChanges
import com.olive.app.data.account.PasskeyRecoveryRepository
import com.olive.app.security.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock


// Saves private profile, cover choice, password, trigger, PIN and trusted-contact settings.
internal class SessionSettings(private val runtime: SessionRuntime) {
    private val publishedKeysets = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    /**
     * Saves the user's cover choice and other general account settings.
     *
     * @param fullName Full name supplied to [saveSettings].
     * @param affected Affected supplied to [saveSettings].
     * @param abuserName Abuser name supplied to [saveSettings].
     * @param dob Date of birth supplied to [saveSettings].
     * @param situation Situation supplied to [saveSettings].
     * @param address Address supplied to [saveSettings].
     */
    fun saveSettings(
        fullName: String, affected: String, abuserName: String,
        dob: String, situation: String, address: DeliveryAddress,
        email: String? = null, emailCommunicationConsent: Boolean? = null,
    ) {
        with(runtime) {
            with(state) {
                if (screen != SessionScreen.Safe || busy) return
                history.recordSettingsInteraction("Olive Branch", "Pressed save profile settings")
                val nextEmail = email?.let(InputPolicy::normalizedEmail) ?: profile?.email.orEmpty()
                val nextConsent = if (nextEmail.isBlank()) false else (emailCommunicationConsent ?: (profile?.emailCommunicationConsent == true))
                if (fullName.isBlank() || !InputPolicy.validDob(dob) || !address.locationValid) {
                    failInput(runtime.application.getString(R.string.ui_profile_required_fields)); return
                }
                if (nextEmail.isNotBlank() && !InputPolicy.validEmail(nextEmail)) {
                    failInput(runtime.application.getString(R.string.email_invalid_message)); return
                }
                val updated = profile?.copy(
                    fullName = fullName.trim(), affectedNames = affected.trim(), abuserName = abuserName.trim(),
                    dob = dob, situation = situation.trim(), region = address.generalLocation,
                    address = address, email = nextEmail, emailCommunicationConsent = nextConsent,
                ) ?: return
                saveSafeProfile(updated)
            }
        }
    }

    /**
     * Saves settings that belong to the current discreet cover app.
     *
     * @param fullName Full name supplied to [saveCoverSettings].
     * @param dob Date of birth supplied to [saveCoverSettings].
     * @param address Address supplied to [saveCoverSettings].
     */
    fun saveCoverSettings(fullName: String, dob: String, address: DeliveryAddress) {
        with(runtime) {
            with(state) {
                if (screen != SessionScreen.Cover || busy) return
                history.recordSettingsInteraction("Cover", "Pressed save account settings")
                if (fullName.isBlank() || !InputPolicy.validDob(dob) || !address.locationValid) {
                    failInput(runtime.application.getString(R.string.ui_profile_required_fields))
                    return
                }
                val device = enrollment ?: return
                operations.runOperation(runtime.application.getString(R.string.unable_to_save_account_settings)) { token ->
                    val updated = withContext(Dispatchers.IO) {
                        val before = store.profile(device.uid)
                        val after = before.copy(
                            fullName = fullName.trim(),
                            dob = dob,
                            address = address,
                            region = address.generalLocation
                        )
                        store.cacheProfile(
                            device.uid, after, pending = !localMode,
                            audit = SettingsEvent(
                                area = "Cover",
                                action = "Saved account settings",
                                changes = profileChanges(before, after)
                            )
                        )
                        after
                    }
                    // Apply the result only if the user is still on the same cover session.
                    if (epoch == token && screen == SessionScreen.Cover) {
                        coverDob = updated.dob
                        coverFullName = updated.fullName
                        language =
                            updated.language
                        deliveryAddress = updated.address
                        pendingSync = !localMode
                        message =
                            runtime.application.getString(R.string.account_settings_saved)
                    }
                    history.queueHistorySync()
                    if (!localMode) syncProfile(device.uid, updated, token)
                }
            }
        }
    }

    /**
     * Saves private Safe App profile changes on the phone and in the cloud.
     *
     * @param updated Updated supplied to [saveSafeProfile].
     */
    fun saveSafeProfile(updated: UserProfile) {
        with(runtime) {
            with(state) {
                val device = enrollment ?: return
                val before = profile ?: return
                operations.runOperation(runtime.application.getString(R.string.unable_to_save_profile_changes)) { token ->
                    withContext(Dispatchers.IO) {
                        store.cacheProfile(
                            device.uid, updated, pending = !localMode,
                            audit = SettingsEvent(
                                area = "Olive Branch",
                                action = "Saved profile settings",
                                changes = profileChanges(before, updated)
                            )
                        )
                    }
                    // Apply the save only to the session that started it.
                    if (epoch == token) {
                        profile = updated
                        coverDob = updated.dob
                        coverFullName =
                            updated.fullName
                        language = updated.language
                        deliveryAddress =
                            updated.address
                        pendingSync = !localMode
                        message =
                            runtime.application.getString(R.string.changes_saved)
                    }
                    history.queueHistorySync()
                    if (!localMode) syncProfile(device.uid, updated, token)
                }
            }
        }
    }

    /**
     * Checks cover requirements before saving the newly selected cover.
     *
     * @param type Type supplied to [changeCover].
     */
    fun changeCover(type: CoverType) {
        with(runtime) {
            with(state) {
                if (screen != SessionScreen.Safe || busy || accountRole != AccountRole.USER) return
                val before = profile ?: return
                if (type == before.coverType) return
                val device = enrollment ?: return
                val updated = before.copy(coverType = type)
                // Show the chosen cover right away; saving keeps going even if Quick exit is tapped immediately.
                val token = epoch
                profile = updated
                coverType = type
                busy = true
                message = null
                scope.launch {
                    // Turn an unexpected change cover failure into a readable message.
                    try {
                        withContext(Dispatchers.IO) {
                            store.cacheProfile(
                                device.uid, updated, pending = !localMode,
                                audit = SettingsEvent(
                                    area = "Olive Branch",
                                    action = "Changed cover app",
                                    changes = profileChanges(before, updated)
                                )
                            )
                        }
                        // Apply the save only to the session that started it.
                        if (epoch == token) {
                            pendingSync = !localMode
                            message = runtime.application.getString(
                                R.string.ui_cover_app_changed_to_value,
                                type.title
                            )
                        }
                        history.queueHistorySync()
                        if (!localMode) syncProfile(device.uid, updated, token)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) { // Restore the previous cover choice and explain that the requested change failed.
                        if (epoch == token) message =
                            runtime.application.getString(R.string.ui_unable_to_change_the_cover_app)
                    } finally {
                        if (epoch == token) busy = false
                    }
                }
            }
        }
    }

    /**
     * Checks and saves the discreet trigger phrase used by cover apps.
     *
     * @param trigger Trigger supplied to [changeTrigger].
     * @param pin User-entered PIN string.
     */
    fun changeTrigger(trigger: String, pin: String) {
        with(runtime) {
            with(state) {
                if (screen != SessionScreen.Safe || busy || accountRole != AccountRole.USER) return
                history.recordSettingsInteraction("Olive Branch", "Pressed change trigger word")
                val device = enrollment ?: return
                if (!InputPolicy.validTrigger(trigger) || !InputPolicy.validPin(pin)) {
                    failInput(runtime.application.getString(R.string.enter_a_trigger_of_3_64_characters_and_your_4_6_digit_pin))
                    return
                }
                operations.runOperation(runtime.application.getString(R.string.unable_to_change_the_trigger_please_retry)) { token ->
                    val result = withContext(Dispatchers.IO) { store.verify(device.uid, pin) }
                    if (epoch != token || screen != SessionScreen.Safe) return@runOperation
                    // Shows whether the hidden trigger was saved or rejected.
                    when (result) {
                        PinResult.Accepted -> {
                            saveVerifiedTrigger(device, trigger, token)
                        }

                        is PinResult.Rejected -> {
                            attempts = withContext(Dispatchers.IO) { store.attempts(device.uid) }
                            if (result.failures >= 5) navigation.quickExit() else failInput(
                                runtime.application.resources.getQuantityString(R.plurals.ui_incorrect_pin_value_attempts_remaining, 5 - result.failures, 5 - result.failures)
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Stores a trigger phrase after the current entry method proves the user is authorized.
     *
     * @param device Device supplied to [saveVerifiedTrigger].
     * @param trigger Trigger supplied to [saveVerifiedTrigger].
     * @param token Authentication, push notification, or session token.
     */
    internal suspend fun saveVerifiedTrigger(
        device: DeviceEnrollment,
        trigger: String,
        token: Long
    ): Unit = with(runtime) {
        with(state) {
            if (epoch != token || screen != SessionScreen.Safe || enrollment?.uid != device.uid) return@with
            val before = profile ?: return@with
            val updated = before.copy(triggerHash = withContext(Dispatchers.Default) { InputPolicy.triggerHash(trigger) })
            if (epoch != token || screen != SessionScreen.Safe || enrollment?.uid != device.uid) return@with
            withContext(Dispatchers.IO) {
                store.updateTrigger(
                    device.uid, updated, !localMode,
                    SettingsEvent(
                        area = "Olive Branch",
                        action = "Changed trigger word",
                        changes = profileChanges(before, updated)
                    )
                )
            }
            if (epoch != token || screen != SessionScreen.Safe || enrollment?.uid != device.uid) return@with
            enrollment = device.copy(triggerHash = updated.triggerHash)
            profile = updated
            message =
                application.getString(R.string.trigger_word_changed)
            pendingSync = !localMode
            history.queueHistorySync()
            if (!localMode) syncProfile(device.uid, updated, token)
        }
    }

    /**
     * Saves the user's trusted contacts to the encrypted profile and cloud copy.
     *
     * @param value Input value to store, parse, or format.
     */
    fun saveContacts(value: List<SafeContact>) {
        with(runtime) {
            with(state) {
                if (screen != SessionScreen.Safe || busy) return
                history.recordSettingsInteraction("Olive Branch", "Pressed save safe contacts")
                val device = enrollment ?: return
                val before = profile ?: return
                val updated = before.copy(contacts = value)
                operations.runOperation(runtime.application.getString(R.string.unable_to_save_contacts_on_this_device_please_retry)) { token ->
                    withContext(Dispatchers.IO) {
                        store.cacheProfile(
                            device.uid, updated, pending = !localMode,
                            audit = SettingsEvent(
                                area = "Olive Branch",
                                action = "Saved safe contacts",
                                changes = profileChanges(before, updated)
                            )
                        )
                    }
                    if (epoch == token) {
                        profile = updated
                        pendingSync = !localMode
                        message =
                            runtime.application.getString(R.string.contacts_saved_on_this_device)
                    }
                    history.queueHistorySync()
                    if (!localMode) syncProfile(device.uid, updated, token)
                }
            }
        }
    }

    /**
     * Retries a profile upload that was saved on the phone while the cloud was unavailable.
     */
    fun sync() {
        with(runtime) {
            with(state) {
                val device = enrollment ?: return
                val value = profile ?: return
                if (screen != SessionScreen.Safe || busy || localMode) return
                history.recordSettingsInteraction("Olive Branch", "Pressed sync changes")
                operations.runOperation(runtime.application.getString(R.string.changes_are_saved_on_this_device_cloud_sync_is_unavailable_retry_when_connected)) { token ->
                    syncProfile(device.uid, value, token)
                    history.queueHistorySync()
                }
            }
        }
    }

    /**
     * Uploads a locally edited user profile and clears its pending-sync marker after confirmation.
     *
     * @param uid Firebase UID of the target user account.
     * @param value Input value to store, parse, or format.
     * @param token Authentication, push notification, or session token.
     */
    suspend fun syncProfile(uid: String, value: UserProfile, token: Long) {
        with(runtime) {
            with(state) {
                // Turn an unexpected sync profile failure into a readable message.
                try {
                    withTimeout(15_000.milliseconds) {
                        accounts.saveProfile(
                            AccountIdentity(
                                uid,
                                value.username
                            ), value
                        )
                    }
                    withContext(Dispatchers.IO) { store.markSynced(uid) }
                    if (epoch == token) {
                        pendingSync = false
                        message =
                            runtime.application.getString(R.string.changes_synced)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { // Keep the profile edit locally and mark it for a later cloud sync.
                    if (epoch == token) {
                        pendingSync = true
                        message =
                            runtime.application.getString(R.string.saved_on_this_device_cloud_sync_failed_retry_when_connected)
                    }
                }
            }
        }
    }

    /**
     * Changes the account password after checking the current one.
     *
     * @param currentPassword Current password supplied to [changePassword].
     * @param newPassword New password supplied to [changePassword].
     * @param confirmation Confirmation supplied to [changePassword].
     */
    fun changePassword(currentPassword: String, newPassword: String, confirmation: String) {
        with(runtime) {
            with(state) {
                if (screen !in setOf(SessionScreen.Safe, SessionScreen.Portal) || busy) return
                val area = if (screen == SessionScreen.Portal) "Olive Tree" else "Olive Branch"
                if (screen == SessionScreen.Safe) history.recordSettingsInteraction(
                    area,
                    "Pressed change account password"
                )
                val device = enrollment ?: return
                val username = profile?.username ?: return
                if (currentPassword.isEmpty()) {
                    failInput(runtime.application.getString(R.string.enter_your_current_password))
                    return
                }
                if (!InputPolicy.passwordExcludesUsername(newPassword, profile?.username ?: coverUsername)) {
                    failInput(runtime.application.getString(R.string.password_excludes_username))
                    return
                }
                if (!InputPolicy.validAccountPassword(newPassword, profile?.username ?: coverUsername)) {
                    failInput(runtime.application.getString(R.string.ui_use_8_24_characters_with_a_letter_number_and_special_character))
                    return
                }
                if (newPassword != confirmation) {
                    failInput(runtime.application.getString(R.string.the_new_passwords_do_not_match))
                    return
                }
                operations.runOperation(runtime.application.getString(R.string.unable_to_change_the_password_check_your_current_password_and_connection)) { token ->
                    accounts.changePassword(
                        AccountIdentity(device.uid, username),
                        currentPassword,
                        newPassword
                    )
                    withContext(Dispatchers.IO) {
                        store.recordSettings(
                            device.uid, SettingsEvent(
                                area = area,
                                action = "Changed account password",
                                changes = listOf(FieldChange("Password", "Hidden", "Changed"))
                            )
                        )
                    }
                    history.queueHistorySync()
                    if (epoch == token && screen in setOf(
                            SessionScreen.Safe,
                            SessionScreen.Portal
                        )
                    ) message = runtime.application.getString(R.string.password_changed)
                }
            }
        }
    }

    /** Loads the user's enrolled passkey recovery credentials into session state. */
    fun refreshPasskeyRecovery() = with(runtime) {
        val uid = enrollment?.uid ?: return@with
        val repository = passkeyRecoveryRepository ?: return@with
        scope.launch {
            val credentials = runCatching { repository.listCredentials() }.getOrNull() ?: return@launch
            if (enrollment?.uid == uid) state.passkeyRecoveryCredentials = credentials
        }
    }

    /** Enrolls a new passkey recovery factor protecting the user's Account Recovery Key. */
    fun enablePasskeyRecovery() = with(runtime) {
        with(state) {
            val uid = enrollment?.uid ?: return@with
            val recovery = passkeyRecoveryRepository ?: return@with
            if (screen != SessionScreen.Safe || busy || accountRole != AccountRole.USER) return@with
            operations.runOperation(
                application.getString(R.string.passkey_recovery_setup_failed),
                timeoutMillis = 180_000,
            ) { token ->
                val ark = accountRecoveryKey(uid, token, ensureRemotePublished = true)
                val registration = recovery.beginEnrollment()
                val registrationResult = requestPasskey(
                    PasskeyPrfProtocol.registrationRequest(registration.options), false, uid, token,
                ) as? PasskeyCredentialResult.Registration ?: error("Passkey registration was cancelled")
                val proof = recovery.continueEnrollment(registration.challengeId, registrationResult.serverResponseJson)
                val assertion = requestPasskey(
                    PasskeyPrfProtocol.assertionRequest(proof.options, proof.prfInputs), true, uid, token,
                ) as? PasskeyCredentialResult.Assertion ?: error("Passkey verification was cancelled")
                val result = assertion.value
                try {
                    check(proof.prfInputs.containsKey(result.credentialId)) {
                        "Passkey proof selected an unexpected credential"
                    }
                    val envelope = PasskeyRecoveryEnvelope.create(uid, result.credentialId, result.prfOutput, ark)
                    recovery.completeEnrollment(proof.challengeId, result.serverResponseJson, envelope)
                } finally {
                    result.prfOutput.fill(0)
                }
                val credentials = recovery.listCredentials()
                if (currentRecoverySession(uid, token)) {
                    passkeyRecoveryCredentials = credentials
                    message = application.getString(R.string.passkey_recovery_enabled)
                }
            }
        }
    }

    /** Removes an enrolled passkey recovery credential and refreshes the stored list. */
    fun removePasskeyRecovery(credentialId: String) = with(runtime) {
        with(state) {
            val uid = enrollment?.uid ?: return@with
            val recovery = passkeyRecoveryRepository ?: return@with
            if (screen != SessionScreen.Safe || busy || accountRole != AccountRole.USER) return@with
            operations.runOperation(application.getString(R.string.passkey_recovery_remove_failed)) { token ->
                recovery.removeCredential(credentialId)
                val credentials = recovery.listCredentials()
                if (currentRecoverySession(uid, token)) {
                    passkeyRecoveryCredentials = credentials
                    message = application.getString(R.string.passkey_recovery_removed)
                }
            }
        }
    }

    /** Publishes the user's encrypted account keyset envelope to Firestore once per session epoch. */
    private suspend fun publishKeyset(e: AccountKeysetEnvelope) {
        val m = "${e.accountId}:${e.keyId}:${e.epoch}"
        if (m !in publishedKeysets) { runtime.accountKeysetRepository?.publish(e); publishedKeysets.add(m) }
    }

    /** Obtains the unlocked [AccountRecoveryKey], recovering from passkey or provisioning if missing. */
    private suspend fun accountRecoveryKey(
        uid: String, token: Long, ensureRemotePublished: Boolean = false,
    ): AccountRecoveryKey {
        val local = withContext(Dispatchers.IO) { runtime.accountKeysets.read(uid) }
        if (local != null) {
            val key = unlockDeviceRecoveryKey(uid, local.deviceRecord, token)
            if (ensureRemotePublished) publishKeyset(local.envelope)
            else runtime.scope.launch(Dispatchers.IO) { runCatching { publishKeyset(local.envelope) } }
            return key
        }
        val remote = runtime.accountKeysetRepository?.fetch(uid) ?: return createAccountRecoveryKey(uid, token)
        return recoverAccountRecoveryKey(uid, remote, token)
    }

    /** Generates a new Account Recovery Key and envelope, protects it locally, and publishes it. */
    private suspend fun createAccountRecoveryKey(uid: String, token: Long): AccountRecoveryKey {
        val repository = checkNotNull(runtime.accountKeysetRepository)
        val recoveryKey = withContext(Dispatchers.IO) { AccountRecoveryKey.generate() }
        val envelope = withContext(Dispatchers.IO) { AccountKeysetEnvelope.create(uid, recoveryKey) }
        protectDeviceRecoveryKey(uid, recoveryKey, envelope, token)
        try {
            publishKeyset(envelope)
        } catch (failure: Exception) {
            val authoritative = runCatching { repository.fetch(uid) }.getOrNull()
            if (authoritative != null && authoritative.keyId != envelope.keyId) {
                withContext(Dispatchers.IO) {
                    runtime.accountKeysets.purge(uid)?.let(AccountRecoveryKeyDeviceProtector()::forget)
                }
            }
            throw failure
        }
        return recoveryKey
    }

    /** Unlocks [keyset] via a passkey PRF assertion and re-wraps the ARK under device authentication. */
    private suspend fun recoverAccountRecoveryKey(
        uid: String,
        keyset: AccountKeysetEnvelope,
        token: Long,
    ): AccountRecoveryKey {
        val repository = checkNotNull(runtime.passkeyRecoveryRepository)
        val start = repository.beginUnlock()
        val assertion = requestPasskey(
            PasskeyPrfProtocol.assertionRequest(start.options, start.prfInputs),
            assertion = true,
            uid = uid,
            token = token,
        ) as? PasskeyCredentialResult.Assertion ?: error("Passkey recovery was cancelled")
        val result = assertion.value
        try {
            val completion = repository.completeUnlock(start.challengeId, result.serverResponseJson)
            check(start.prfInputs[result.credentialId] == completion.prfInput) {
                "Passkey recovery input mismatch"
            }
            val recoveryKey = completion.envelope.open(uid, result.credentialId, result.prfOutput)
            withContext(Dispatchers.IO) { keyset.unlock(uid, recoveryKey) }
            protectDeviceRecoveryKey(uid, recoveryKey, keyset, token)
            return recoveryKey
        } finally {
            result.prfOutput.fill(0)
        }
    }

    /** Unlocks a local [AccountRecoveryKey] using biometric or device credential authentication. */
    private suspend fun unlockDeviceRecoveryKey(
        uid: String,
        record: AccountRecoveryKeyDeviceRecord,
        token: Long,
    ): AccountRecoveryKey {
        val challenge = withContext(Dispatchers.IO) {
            AccountRecoveryKeyDeviceProtector().prepare(uid, record)
        }
        return try {
            val cipher = requestDeviceAuthentication(challenge, uid, token)
            withContext(Dispatchers.IO) { challenge.unlock(cipher) }
        } finally {
            challenge.close()
        }
    }

    /** Wraps and saves a local copy of [recoveryKey] protected by the Android device lock. */
    private suspend fun protectDeviceRecoveryKey(
        uid: String,
        recoveryKey: AccountRecoveryKey,
        envelope: AccountKeysetEnvelope,
        token: Long,
    ) {
        val challenge = withContext(Dispatchers.IO) {
            AccountRecoveryKeyDeviceProtector().prepare(uid, null)
        }
        return try {
            val cipher = requestDeviceAuthentication(challenge, uid, token)
            val record = withContext(Dispatchers.IO) { challenge.enroll(cipher, recoveryKey) }
            withContext(Dispatchers.IO) {
                runtime.accountKeysets.save(AccountKeysetLocalStore.Saved(envelope, record))
            }
            challenge.markStored()
        } finally {
            challenge.close()
        }
    }

    /** Requests UI-layer biometric/device lock authentication for [challenge]. */
    private suspend fun requestDeviceAuthentication(
        challenge: DeviceAuthenticationChallenge,
        uid: String,
        token: Long,
    ): javax.crypto.Cipher {
        check(currentRecoverySession(uid, token)) { "Recovery session changed" }
        val request = DeviceLockRequest(challenge, settingsChange = true)
        runtime.state.deviceLockRequest = request
        return try {
            request.result.await() ?: error("Device authentication was cancelled")
        } finally {
            if (runtime.state.deviceLockRequest === request) runtime.state.deviceLockRequest = null
        }
    }

    /** Requests an Activity-hosted Credential Manager ceremony for passkey recovery. */
    private suspend fun requestPasskey(
        requestJson: String,
        assertion: Boolean,
        uid: String,
        token: Long,
    ): PasskeyCredentialResult {
        check(currentRecoverySession(uid, token)) { "Recovery session changed" }
        val request = PasskeyCredentialRequest(requestJson, assertion)
        runtime.state.passkeyCredentialRequest = request
        return try {
            request.result.await() ?: error("Passkey request was cancelled")
        } finally {
            if (runtime.state.passkeyCredentialRequest === request) {
                runtime.state.passkeyCredentialRequest = null
            }
        }
    }

    /** Verifies that the active Safe session, epoch, and user UID still match the started flow. */
    private fun currentRecoverySession(uid: String, token: Long) = with(runtime) {
        epoch == token && state.screen == SessionScreen.Safe && enrollment?.uid == uid &&
            state.accountRole == AccountRole.USER
    }

    /** Ensures media encryption has a stable account public key without unlocking private material. */
    internal suspend fun ensureAccountKeysetForMedia(uid: String): AccountKeysetEnvelope = with(runtime) {
        withContext(Dispatchers.IO) { accountKeysets.read(uid)?.envelope }?.let { local ->
            scope.launch(Dispatchers.IO) { runCatching { publishKeyset(local) } }
            return@with local
        }
        accountKeysetRepository?.fetch(uid)?.let { remote ->
            scope.launch(Dispatchers.IO) { runCatching { publishKeyset(remote) } }
            return@with remote
        }
        val token = epoch
        check(currentRecoverySession(uid, token)) { "Account session changed" }
        accountRecoveryKey(uid, token)
        withContext(Dispatchers.IO) { accountKeysets.read(uid)?.envelope } ?: error("Account recovery keyset was not stored")
    }

    /** Unlocks account private material only in client memory for one media-key operation. */
    internal suspend fun unlockAccountKeysetForMedia(uid: String): UnlockedAccountKeyset = with(runtime) {
        val token = epoch
        check(currentRecoverySession(uid, token)) { "Account session changed" }
        val local = withContext(Dispatchers.IO) { accountKeysets.read(uid) }
        val (envelope, recoveryKey) = if (local != null) {
            val key = unlockDeviceRecoveryKey(uid, local.deviceRecord, token)
            scope.launch(Dispatchers.IO) { runCatching { publishKeyset(local.envelope) } }
            local.envelope to key
        } else {
            val key = accountRecoveryKey(uid, token)
            val stored = withContext(Dispatchers.IO) { accountKeysets.read(uid)?.envelope }
                ?: error("Account recovery keyset was not stored")
            stored to key
        }
        withContext(Dispatchers.Default) { envelope.unlock(uid, recoveryKey) }
    }

}

// Signed-in users: records safe-app activity for the account.

// Owns activity records and history synchronization.
internal class SessionHistory(private val runtime: SessionRuntime) {
    private var loadedSession: Pair<String, Long>? = null
    private var loadVersion = 0
    var loadingUid by mutableStateOf<String?>(null)
        private set
    var failedUid by mutableStateOf<String?>(null)
        private set
    /**
     * Adds a non-sensitive settings action to private account history.
     *
     * @param area Area supplied to [recordSettingsInteraction].
     * @param action Action supplied to [recordSettingsInteraction].
     */
    fun recordSettingsInteraction(area: String, action: String) {
        with(runtime) {
            with(state) {
                if (area == "Olive Branch") return
                val uid = enrollment?.uid ?: return
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        store.recordSettings(
                            uid,
                            SettingsEvent(area = area, action = action)
                        )
                        queueHistorySync()
                    }
                }
            }
        }
    }

    /**
     * Reloads local activity history and private notes for the current account.
     *
     * @param uid Firebase UID of the target user account.
     */
    fun refreshHistory(uid: String) {
        with(runtime) {
            with(state) {
                val version = ++loadVersion
                val loadEpoch = epoch
                loadingUid = if (loadedSession == (uid to loadEpoch)) null else uid
                failedUid = null
                scope.launch {
                    try {
                        val (histories, cleanupNeeded) = withContext(Dispatchers.IO) {
                            val purged = store.purgeExpiredPrivateEntries(uid)
                            Triple(store.settingsHistory(uid), store.triggerHistory(uid), store.privateEntries(uid)) to purged
                        }
                        if (enrollment?.uid == uid && epoch == loadEpoch && version == loadVersion) {
                            loadedSession = uid to loadEpoch
                            settingsHistory = histories.first.filter {
                                it.area != "Privacy" && (it.area != "Olive Branch" || it.changes.isNotEmpty())
                            }
                            privacyHistory = histories.first.filter { it.area == "Privacy" }
                            triggerHistory = histories.second
                            privateEntries = histories.third
                        }
                        if (cleanupNeeded) queueHistorySync()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (enrollment?.uid == uid && version == loadVersion) failedUid = uid
                    } finally {
                        if (version == loadVersion) loadingUid = null
                    }
                }
            }
        }
    }

    /**
     * Opens the private activity page with the requested history category selected.
     *
     * @param value Input value to store, parse, or format.
     */
    fun showActivityCategory(value: ActivityCategory) {
        with(runtime) {
            with(state) {
                if (screen == SessionScreen.Safe) {
                    activityCategory = value
                    page = SafePage.Activity
                    message = null
                }
            }
        }
    }

    /**
     * Leaves a history category, or leaves the activity page when no category is open.
     */
    fun activityBack() {
        with(runtime) {
            with(state) {
                if (activityCategory != null) activityCategory = null else navigation.back()
            }
        }
    }

    /**
     * Downloads account activity, merges it with local records, and refreshes the selected history page.
     *
     * @param uid Firebase UID of the target user account.
     * @param username Account username.
     */
    fun loadCloudHistory(uid: String, username: String) {
        with(runtime) {
            scope.launch {
                // Paint the local cover first; cloud reconciliation does not belong on the launch critical path.
                delay(1_000.milliseconds)
                val identity = AccountIdentity(uid, username)
                // Messaging registration failing must never block the existing profile/history sync path.
                runCatching { messaging.registerMessagingIdentity(identity) }
                runCatching {
                    accounts.profile(identity) // Reading once upgrades the existing profile object to readable JSON.
                    val records = accounts.historyRecords(identity)
                    withContext(Dispatchers.IO) { store.mergeHistory(uid, records) }
                    refreshHistory(uid)
                }
                queueHistorySync()
            }
        }
    }

    /**
     * Starts a background upload when local history has not reached the cloud yet.
     */
    fun queueHistorySync() {
        with(runtime) {
            with(state) {
                if (localMode || enrollment == null) return
                historySyncJob?.cancel()
                val uid = enrollment!!.uid
                val username = coverUsername
                historySyncJob = scope.launch {
                    delay(750.milliseconds)
                    historySyncMutex.withLock {
                        // Keep syncing only while the same account remains signed in.
                        while (enrollment?.uid == uid) {
                            val pending = withContext(Dispatchers.IO) { store.pendingHistory(uid) }
                            if (pending.isEmpty()) break
                            for (record in pending) {
                                // Turn an unexpected queue history sync failure into a readable message.
                                try {
                                    accounts.saveHistoryRecord(
                                        AccountIdentity(uid, username),
                                        record
                                    )
                                    withContext(Dispatchers.IO) {
                                        store.markHistorySynced(
                                            uid,
                                            record
                                        )
                                    }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) { // Local history is already saved, so a failed cloud copy waits for the next refresh.
                                    return@withLock
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
