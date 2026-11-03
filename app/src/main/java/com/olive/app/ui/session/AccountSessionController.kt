// All account roles: restores sessions, completes account setup, and signs out.
package com.olive.app.ui.session
import android.util.Log
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.R
import com.olive.app.BuildConfig
import com.olive.app.data.*
import com.olive.app.data.account.AccountSyncRepository
import com.olive.app.security.*
import com.olive.app.location.LiveLocationController
import com.olive.app.notification.LocalNotificationEventHandler
import com.olive.app.data.messaging.VoipPushTokenRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

// Restores enrollment, signs accounts in/out and runs onboarding; stale account-choice callbacks cannot restart setup.
internal class AccountSessionController(private val runtime: SessionRuntime) {
    // Restores the saved account and sends the person to the correct first screen.
    fun load() {
        val state = runtime.state
        if (state.busy) return
        runtime.pinAuthorizedUid = null
        runtime.pinAuthorizedEpoch = -1L
        state.busy = true
        runtime.screenHistory.clear()
        runtime.safePageHistory.clear()
        state.screen = SessionScreen.Loading
        runtime.scope.launch {
            // Turn an unexpected load failure into a readable message.
            try {
                check(BuildConfig.DEBUG || !runtime.localMode)
                val restoredSession = restoreStoredSession()
                runtime.enrollment = restoredSession?.enrollment
                restoredSession?.let { restorePresentation(it) }
                routeRestoredAccount()
                runtime.enrollment?.let { enrollment ->
                    if (!runtime.localMode) withContext(Dispatchers.IO) {
                        MessageSendWorker.enqueuePending(runtime.application, enrollment.uid)
                    }
                    watchAccountChanges(
                        AccountIdentity(enrollment.uid, runtime.state.coverUsername)
                    )
                }
                if (runtime.enrollment?.role == AccountRole.ADMIN && !runtime.localMode) {
                    runtime.organization.queueAdminRefresh()
                }
            } catch (_: Exception) { // Incomplete account restoration is unsafe, so route to the storage error screen instead.
                state.screen = SessionScreen.StorageError
            } finally {
                state.busy = false
            }
        }
    }
    // Account choices are available only before a device has joined an account.
    private fun canChooseAccount(): Boolean {
        val state = runtime.state
        return state.screen == SessionScreen.Welcome && runtime.enrollment == null && !state.busy
    }

    // Saves the account type chosen on the welcome screen.
    fun selectRole(role: AccountRole) {
        if (!canChooseAccount()) return
        runtime.state.accountRole = role
        runtime.state.message = null
        runtime.state.credentialFieldError = null
    }
    // Starts account creation after blocking staff and admin self-registration.
    fun chooseNew() {
        if (!canChooseAccount()) return
        val state = runtime.state
        if (state.accountRole == AccountRole.STAFF || state.accountRole == AccountRole.ADMIN) {
            state.message = runtime.application.getString(
                R.string.ui_staff_and_admin_accounts_are_created_by_an_administrator
            )
            return
        }
        state.creatingAccount = true
        state.message = null
        runtime.navigation.openScreen(SessionScreen.NewCredentials)
    }

    // Opens the sign-in form for a person who already has an account.
    fun chooseReturning() {
        if (!canChooseAccount()) return
        runtime.state.creatingAccount = false
        runtime.state.message = null
        runtime.navigation.openScreen(SessionScreen.ReturningCredentials)
    }

    // Opens first-time setup when the cover app has not joined an account yet.
    fun openAccountSetup() {
        val state = runtime.state
        if (state.busy || runtime.enrollment != null || state.screen != SessionScreen.Cover) return
        state.accountRole = AccountRole.USER
        state.coverType = CoverType.FOOD
        state.creatingAccount = false
        state.message = null
        runtime.navigation.openScreen(SessionScreen.Welcome)
    }

    // Clears the old sign-in error before the person tries again.
    fun clearCredentialError() {
        runtime.state.credentialFieldError = null
        runtime.state.message = null
    }

    // Validates and saves the profile entered during account setup.
    fun saveSetupProfile(value: UserProfile) {
        if (runtime.state.screen != SessionScreen.ProfileSetup) return
        with(runtime) {
            with(state) {
                val identity = pendingIdentity ?: return
                if (busy) return
                if (value.role != accountRole) {
                    message =
                        runtime.application.getString(R.string.ui_account_type_mismatch)
                    return
                }
                if (value.fullName.isBlank() || !InputPolicy.validDob(value.dob) || value.language.isBlank() || !value.address.locationValid) {
                    message =
                        runtime.application.getString(R.string.full_name_a_valid_date_of_birth_language_and_city_are_required)
                    return
                }
                operations.runOperation(runtime.application.getString(R.string.your_account_exists_but_the_profile_was_not_saved_retry_here_or_sign_in_again_to_finish_setup)) { token ->
                    accounts.saveProfile(identity, value)
                    if (value.role == AccountRole.SAFE_CONTACT) {
                        val saved =
                            withContext(Dispatchers.IO) { store.enrollWithoutPin(identity, value) }
                        if (epoch == token) {
                            enrollment = saved
                            archivedConversationIds = withContext(Dispatchers.IO) { store.archivedConversationIds(saved.uid) }
                            privateContactNotes = withContext(Dispatchers.IO) { store.privateContactNotes(saved.uid) }
                            profile = value
                            coverUsername =
                                value.username
                            coverDob = value.dob
                            coverFullName = value.fullName
                            accountRole = value.role
                            language = value.language
                            deliveryAddress =
                                value.address
                            screenHistory.clear()
                            safePageHistory.clear()
                            page =
                                SafePage.Home
                            screen = SessionScreen.Safe
                            history.refreshHistory(saved.uid)
                            if (!localMode) history.loadCloudHistory(saved.uid, value.username)
                            watchAccountChanges(identity)
                        }
                    } else {
                        withContext(Dispatchers.IO) { store.cacheProfile(identity.uid, value) }
                        if (epoch == token) {
                            pendingProfile = value
                            navigation.openScreen(SessionScreen.DevicePin)
                        }
                    }
                }
            }
        }
    }

    // Validates and encrypts a new backup PIN before completing user enrollment.
    fun createDevicePin(pin: String, confirmation: String) {
        with(runtime) { if (state.busy || state.screen != SessionScreen.DevicePin || enrollment != null || state.accountRole != AccountRole.USER) return }
        with(runtime) {
            with(state) {
                if (busy) return
                if (!InputPolicy.validPin(pin) || pin != confirmation) {
                    message =
                        runtime.application.getString(R.string.enter_the_same_4_6_digit_pin_in_both_fields)
                    return
                }
                val identity = pendingIdentity ?: return
                val value = pendingProfile ?: return
                operations.runOperation(runtime.application.getString(R.string.unable_to_save_this_device_s_pin_please_retry)) { token ->
                    val saved = withContext(Dispatchers.IO) { store.enroll(identity, value, pin) }
                    finishEnrollment(saved, value, token)
                }
            }
        }
    }

    // Finishes enrollment only if this is still the same setup attempt.
    internal suspend fun finishEnrollment(
        saved: DeviceEnrollment,
        value: UserProfile,
        token: Long
    ): Unit = with(runtime) {
        with(state) {
            if (epoch != token || screen != SessionScreen.DevicePin || pendingIdentity?.uid != saved.uid) return@with
            enrollment = saved
            deliveryAddress = value.address
            language = value.language
            coverDob = value.dob
            coverFullName = value.fullName
            coverUsername = value.username
            coverType = value.coverType
            accountRole = value.role
            archivedConversationIds = withContext(Dispatchers.IO) { store.archivedConversationIds(saved.uid) }
            privateContactNotes = withContext(Dispatchers.IO) { store.privateContactNotes(saved.uid) }
            covers.restoreCoverData(saved.uid)
            navigation.quickExit()
            if (!localMode) history.loadCloudHistory(saved.uid, value.username)
            watchAccountChanges(AccountIdentity(saved.uid, value.username))
        }
    }

    // Saves the private trigger phrase before asking the user to create a PIN.
    fun saveProvisionedTrigger(trigger: String) {
        with(runtime) {
            with(state) {
                val identity = pendingIdentity ?: return
                val existing = pendingProfile ?: return
                if (!InputPolicy.validTrigger(trigger)) {
                    message =
                        runtime.application.getString(R.string.choose_a_trigger_word_or_phrase_of_3_64_characters)
                    return
                }
                val updated = existing.copy(triggerHash = InputPolicy.triggerHash(trigger))
                operations.runOperation(runtime.application.getString(R.string.ui_the_trigger_word_could_not_be_saved_retry_here)) { token ->
                    accounts.saveProfile(identity, updated)
                    withContext(Dispatchers.IO) { store.cacheProfile(identity.uid, updated) }
                    if (epoch == token) {
                        pendingProfile = updated
                        navigation.openScreen(SessionScreen.DevicePin)
                    }
                }
            }
        }
    }

    // Stops private services, clears the session, and returns to account entry.
    fun signOut() {
        with(runtime) {
            with(state) {
                if ((screen != SessionScreen.Safe && screen != SessionScreen.Portal) || busy || enrollment == null) return
                val uid = enrollment!!.uid
                accountSyncSubscription?.close()
                accountSyncSubscription = null
                LocalNotificationEventHandler.cancel(application)
                if (LiveLocationController.active(application)) LiveLocationController.stop(
                    application
                )
                navigation.quickExit()
                historySyncJob?.cancel()
                historySyncJob = null
                MessageSendWorker.cancelOwner(application, uid)
                busy = true
                scope.launch {
                    // Turn an unexpected sign out failure into a readable message.
                    try {
                        withContext(Dispatchers.IO) {
                            store.recordSettings(
                                uid,
                                SettingsEvent(
                                    area = "Olive Branch",
                                    action = "Signed out on this device"
                                )
                            )
                            store.forgetDevice()
                            messageOutbox.purge(uid)
                            chatCache.purge(uid)
                            documentIndex.purge(uid)
                            offlineMediaCache.purge(uid)
                        }
                        // Cloud accounts also remove their Firebase sign-in record.
                        if (!localMode) runCatching {
                            withTimeoutOrNull(2_000.milliseconds) {
                                store.pendingHistory(uid).forEach { record ->
                                    accounts.saveHistoryRecord(
                                        AccountIdentity(uid, coverUsername),
                                        record
                                    )
                                    withContext(Dispatchers.IO) {
                                        store.markHistorySynced(
                                            uid,
                                            record
                                        )
                                    }
                                }
                                locationSharingRepository?.setLiveRecipients(uid, emptyList())
                            }
                        }
                        if (!localMode) {
                            try {
                                withTimeoutOrNull(2_000.milliseconds) {
                                    VoipPushTokenRegistration.unregisterCurrentDevice(application)
                                } ?: Log.w(
                                    "VoipPushToken",
                                    "Push-token cleanup timed out during sign-out"
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                Log.w(
                                    "VoipPushToken",
                                    "Push-token cleanup failed; signed-out devices ignore call invitations",
                                    error
                                )
                            }
                        }
                        accounts.signOut()
                        enrollment = null
                        deliveryAddress =
                            DeliveryAddress()
                        language = "English"
                        coverDob = ""
                        coverFullName = ""
                        coverUsername = ""
                        coverType =
                            CoverType.FOOD
                        accountRole = AccountRole.USER
                        profile = null
                        foodCart = emptyMap()
                        foodFavorites = emptySet()
                        foodCustomizations =
                            emptyMap()
                        savedNews = emptyList()
                        archivedConversationIds = emptySet()
                        privateContactNotes = emptyMap()
                        coverDataVersion++
                        screen = SessionScreen.Cover
                    } catch (_: Exception) { // A failed quick exit must fail closed and leave the private interface locked.
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }

    // Reads the encrypted device session without blocking the main UI thread.
    private suspend fun restoreStoredSession(): DeviceSession? = with(runtime) {
        withContext(Dispatchers.IO) {
            store.currentSession() ?: if (!localMode) {
                accounts.authenticatedUid()?.let { uid ->
                    store.restoreActive(uid)
                    store.currentSession()
                }
            } else null
        }
    }

    // Rebuilds the correct cover, PIN, safe-app, or staff/admin screen from a saved session.
    private suspend fun restorePresentation(restored: DeviceSession) {
        with(runtime) {
            with(state) {
                val device = restored.enrollment
                val saved = restored.profile
                deliveryAddress = saved.address
                language = saved.language
                coverDob = saved.dob
                coverFullName = saved.fullName
                coverUsername = saved.username
                coverType = saved.coverType
                accountRole = saved.role
                covers.restoreCoverData(device.uid)
                checkoutHistory = withContext(Dispatchers.IO) { store.checkoutHistory(device.uid) }
                if (!localMode) history.loadCloudHistory(device.uid, saved.username)
                if (saved.role != AccountRole.USER) profile = saved
                archivedConversationIds = withContext(Dispatchers.IO) { store.archivedConversationIds(device.uid) }
                privateContactNotes = withContext(Dispatchers.IO) { store.privateContactNotes(device.uid) }
                if (saved.role == AccountRole.SAFE_CONTACT) withContext(Dispatchers.IO) {
                    store.removePin(
                        device.uid
                    )
                }
            }
        }
    }

    // Reloads Storage data when the same account changes on another phone or browser.
    private fun watchAccountChanges(identity: AccountIdentity) = with(runtime) {
        accountSyncSubscription?.close()
        accountSyncSubscription = null
        if (localMode) return@with
        accountSyncSubscription = AccountSyncRepository().observe(
            identity.uid,
            onChange = {
                scope.launch {
                    if (enrollment?.uid != identity.uid) return@launch
                    // A failed refresh leaves the last readable profile and notes on screen.
                    try {
                        val updatedProfile = accounts.profile(identity) ?: return@launch
                        val records = accounts.historyRecords(identity)
                        withContext(Dispatchers.IO) { store.mergeHistory(identity.uid, records) }
                        if (enrollment?.uid != identity.uid) return@launch
                        state.profile = updatedProfile
                        state.deliveryAddress = updatedProfile.address
                        state.language = updatedProfile.language
                        state.coverDob = updatedProfile.dob
                        state.coverFullName = updatedProfile.fullName
                        state.coverType = updatedProfile.coverType
                        enrollment = enrollment?.copy(
                            triggerHash = updatedProfile.triggerHash,
                            role = updatedProfile.role,
                        )
                        history.refreshHistory(identity.uid)
                    } catch (failure: Exception) {
                        state.message = failure.localizedMessage
                    }
                }
            },
            onError = { failure -> state.message = failure.localizedMessage },
        )
    }

    // Account session controller: Routes restored account.
    private fun routeRestoredAccount() {
        with(runtime) {
            with(state) {
                screen = when (enrollment?.role) {
                    null -> SessionScreen.Cover
                    AccountRole.USER -> SessionScreen.Cover
                    AccountRole.SAFE_CONTACT -> SessionScreen.Safe
                    AccountRole.STAFF, AccountRole.ADMIN -> SessionScreen.Portal
                }
            }
        }
    }
}
