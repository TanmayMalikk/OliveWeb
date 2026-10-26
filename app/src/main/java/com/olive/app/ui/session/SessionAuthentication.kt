// Contains the session authentication code for this part of Olive.
package com.olive.app.ui.session
import com.olive.app.ui.shared.*
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.R
import com.olive.app.BuildConfig
import com.olive.app.data.*
import com.olive.app.security.*
import com.olive.app.location.LiveLocationController
import com.olive.app.notification.LocalNotificationEventHandler
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

// Restores enrollment, signs accounts in/out and runs onboarding; stale account-choice callbacks cannot restart setup.
internal class SessionAuthentication(private val runtime: SessionRuntime) {
    fun load() {
        with(runtime) {
            with(state) {
                if (busy) return
                pinAuthorizedUid = null
                pinAuthorizedEpoch = -1L
                busy = true
                screenHistory.clear()
                safePageHistory.clear()
                screen = SessionScreen.Loading
                scope.launch {
                    try {
                        check(BuildConfig.DEBUG || !localMode)
                        val restoredSession = restoreStoredSession()
                        enrollment = restoredSession?.enrollment
                        restoredSession?.let { restorePresentation(it) }
                        routeRestoredAccount()
                        if (enrollment?.role == AccountRole.ADMIN && !localMode) organization.queueAdminRefresh()
                    } catch (_: Exception) {
                        screen = SessionScreen.StorageError
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }
    private fun canChooseAccount() =
        with(runtime) { state.screen == SessionScreen.Welcome && enrollment == null && !state.busy }
    // Selects role.
    fun selectRole(role: AccountRole) {
        with(runtime) {
            with(state) {
                if (canChooseAccount()) {
                    accountRole = role
                    message = null
                    credentialFieldError = null
                }
            }
        }
    }
    fun chooseNew() {
        with(runtime) {
            with(state) {
                if (!canChooseAccount()) return
                if (accountRole == AccountRole.STAFF || accountRole == AccountRole.ADMIN) {
                    message =
                        runtime.application.getString(R.string.ui_staff_and_admin_accounts_are_created_by_an_administrator)
                    return
                }
                creatingAccount = true
                message =
                    null
                navigation.openScreen(SessionScreen.NewCredentials)
            }
        }
    }

    fun chooseReturning() {
        with(runtime) {
            with(state) {
                if (canChooseAccount()) {
                    creatingAccount = false
                    message =
                        null
                    navigation.openScreen(SessionScreen.ReturningCredentials)
                }
            }
        }
    }

    // Sets up.
    fun setup() {
        with(runtime) {
            with(state) {
                if (!busy && enrollment == null && screen == SessionScreen.Cover) {
                    accountRole = AccountRole.USER
                    coverType = CoverType.FOOD
                    creatingAccount = false
                    message =
                        null
                    navigation.openScreen(SessionScreen.Welcome)
                }
            }
        }
    }

    fun error(text: String) {
        with(runtime) {
            with(state) {
                message = text
            }
        }
    }

    // Clears credential error.
    fun clearCredentialError() {
        with(runtime) {
            with(state) {
                credentialFieldError = null
                message = null
            }
        }
    }

    fun authenticate(username: String, password: String, create: Boolean) {
        with(runtime) {
            with(state) {
                if (busy || enrollment != null || screen !in setOf(
                        SessionScreen.NewCredentials,
                        SessionScreen.ReturningCredentials
                    )
                ) return
                credentialFieldError = null
                val organizationEmail =
                    accountRole in setOf(AccountRole.STAFF, AccountRole.ADMIN) && '@' in username
                val validUsername =
                    if (create) InputPolicy.validUsername(username) else InputPolicy.validExistingUsername(
                        username
                    )
                if (!organizationEmail && !validUsername) {
                    credentialFieldError = CredentialFieldError.InvalidUsername
                    message =
                        runtime.application.getString(R.string.ui_invalid_username_10e538)
                    return
                }
                if (create && !InputPolicy.passwordExcludesUsername(password, username)) {
                    message =
                        runtime.application.getString(R.string.password_excludes_username)
                    return
                }
                if (create && !InputPolicy.validAccountPassword(password, username)) {
                    credentialFieldError = CredentialFieldError.InvalidPassword
                    message =
                        runtime.application.getString(R.string.ui_invalid_password_a2df22)
                    return
                }
                if (password.isEmpty()) {
                    credentialFieldError = CredentialFieldError.IncorrectPassword
                    message =
                        runtime.application.getString(R.string.ui_password_required_ec4e2c)
                    return
                }
                operations.runOperation(runtime.application.getString(R.string.unable_to_sign_in_check_your_credentials_and_connection_then_try_again)) { token ->
                    val identity = try {
                        if (create) accounts.create(
                            username,
                            password,
                            accountRole
                        ) else accounts.authenticate(username, password, accountRole)
                    } catch (error: Exception) {
                        val details = error.message.orEmpty()
                        val usernameUnavailable =
                            create && (error is FirebaseAuthUserCollisionException ||
                                    (error is FirebaseFunctionsException &&
                                            error.code == FirebaseFunctionsException.Code.ALREADY_EXISTS) ||
                                    details.contains("unavailable", ignoreCase = true))
                        val incorrectLogin =
                            !create && (error is FirebaseAuthInvalidCredentialsException ||
                                    error is FirebaseAuthInvalidUserException || details.contains(
                                "sign-in failed",
                                ignoreCase = true
                            ))
                        if (!usernameUnavailable && !incorrectLogin) throw error
                        if (epoch == token) {
                            credentialFieldError =
                                if (usernameUnavailable) CredentialFieldError.UsernameTaken else CredentialFieldError.IncorrectPassword
                            message =
                                if (usernameUnavailable) runtime.application.getString(R.string.ui_username_taken_c04650) else runtime.application.getString(
                                    R.string.ui_incorrect_password_b68db3
                                )
                        }
                        return@runOperation
                    }
                    val remote = accounts.profile(identity)
                    val existing = withContext(Dispatchers.IO) {
                        if (store.pendingSync(identity.uid)) store.profile(identity.uid) else remote
                    }
                    if (epoch == token) {
                        if (existing == null && !create && (accountRole == AccountRole.STAFF || accountRole == AccountRole.ADMIN)) {
                            accounts.signOut()
                            message = runtime.application.getString(
                                R.string.ui_this_value_account_has_not_been_provisioned_by_an_administrator,
                                accountRole.localizedTitle(ResourceText(runtime.application.resources))
                            )
                            return@runOperation
                        }
                        if (existing != null && existing.role != accountRole) {
                            accounts.signOut()
                            message = runtime.application.getString(
                                R.string.ui_this_account_is_registered_as_value_select_that_account_type_to_s,
                                existing.role.localizedTitle(ResourceText(runtime.application.resources))
                            )
                            return@runOperation
                        }
                        pendingIdentity = identity
                        pendingProfile = existing
                        if (existing != null && existing.role == AccountRole.SAFE_CONTACT && AccountPolicy.personalProfileNeedsSetup(
                                existing
                            )
                        ) {
                            navigation.openScreen(SessionScreen.ProfileSetup)
                        } else if (existing != null && existing.role in setOf(
                                AccountRole.STAFF,
                                AccountRole.ADMIN
                            ) && AccountPolicy.organizationProfileNeedsSetup(existing)
                        ) {
                            navigation.openScreen(SessionScreen.OrganizationProfileSetup)
                        } else if (existing != null && existing.role != AccountRole.USER) {
                            val saved = withContext(Dispatchers.IO) {
                                store.enrollWithoutPin(
                                    identity,
                                    existing
                                )
                            }
                            enrollment = saved
                            archivedConversationIds = withContext(Dispatchers.IO) { store.archivedConversationIds(saved.uid) }
                            privateContactNotes = withContext(Dispatchers.IO) { store.privateContactNotes(saved.uid) }
                            profile = existing
                            coverUsername =
                                existing.username
                            coverDob = existing.dob
                            coverFullName =
                                existing.fullName
                            accountRole = existing.role
                            language =
                                existing.language
                            deliveryAddress = existing.address
                            screenHistory.clear()
                            safePageHistory.clear()
                            if (existing.role == AccountRole.SAFE_CONTACT) {
                                page = SafePage.Home
                                screen =
                                    SessionScreen.Safe
                                history.refreshHistory(saved.uid)
                            } else {
                                screen = SessionScreen.Portal
                                if (existing.role == AccountRole.ADMIN) organization.queueAdminRefresh()
                            }
                        } else navigation.openScreen(
                            when {
                                existing == null || AccountPolicy.personalProfileNeedsSetup(existing) -> SessionScreen.ProfileSetup
                                existing.triggerHash.isBlank() -> SessionScreen.TriggerSetup
                                else -> SessionScreen.DevicePin
                            }
                        )
                    }
                }
            }
        }
    }

    // Saves setup profile.
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

    // Creates device PIN.
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
        }
    }

    // Saves provisioned trigger.
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

    fun signOut() {
        with(runtime) {
            with(state) {
                if ((screen != SessionScreen.Safe && screen != SessionScreen.Portal) || busy || enrollment == null) return
                val uid = enrollment!!.uid
                LocalNotificationEventHandler.cancel(application)
                if (LiveLocationController.active(application)) LiveLocationController.stop(
                    application
                )
                navigation.quickExit()
                historySyncJob?.cancel()
                historySyncJob = null
                busy = true
                scope.launch {
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
                        }
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
                    } catch (_: Exception) { /* Fail closed: the interface remains locked. */
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }

    // Restores stored session.
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

    // Restores presentation.
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
