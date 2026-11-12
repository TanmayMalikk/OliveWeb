// All app roles: builds the local stores, account-scoped data sources, and PIN/device-lock access controllers used by a session.
package com.olive.app.ui.session

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.olive.app.R
import com.olive.app.data.*
import com.olive.app.data.media.MediaRepository
import com.olive.app.data.media.MediaTicketDataSource
import com.olive.app.data.media.OfflineMediaCache
import com.olive.app.security.*
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import javax.crypto.Cipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

// Owns the device-only stores. Keeping them together makes their scopes visible at construction.
internal class SessionLocalStorage(application: Application, localMode: Boolean) {
    val vault = EncryptedVault(application, if (localMode) "demo" else "firebase")
    val activityVault = EncryptedVault(
        application,
        if (localMode) "demo-activity" else "firebase-activity",
    )
    val deviceStore = DeviceStore(vault, activityVault)
    val coverStore = CoverStateStore(EncryptedVault(application, "cover"))
    val coverSaveMutex = Mutex()
    val newsRevisions = mutableMapOf<String, Long>()
    val messageReadState: SharedPreferences = application.getSharedPreferences(
        "olive_message_read_state",
        Context.MODE_PRIVATE,
    )
    val cloudState: SharedPreferences = application.getSharedPreferences(
        "olive_cloud_state",
        Context.MODE_PRIVATE,
    )
    val messageOutbox = MessageOutboxStore(application)
    val chatCache = ChatCacheStore(application)
    val documentIndex = DocumentIndexStore(application)
    val offlineMediaCache = OfflineMediaCache(application)
}

// Builds cloud repositories only for a Firebase session. Tests can supply a replacement bundle.
internal class SessionDataSources(
    val accounts: AccountRepository,
    val admin: AdminRepository?,
    val messaging: MessagingRepository?,
    val secureChat: SecureChatRepository?,
    val secureDocument: SecureDocumentRepository?,
    val media: MediaTicketDataSource?,
    val safetyDocuments: SafetyDocumentDataSource?,
    val locations: LocationSharingRepository?,
    val adminLocations: AdminLocationRepository?,
) {
    companion object {
        // Creates the cloud repository bundle used by a signed-in Firebase account.
        fun create(
            application: Application,
            localMode: Boolean,
            vault: EncryptedVault,
            documentIndex: DocumentIndexDataSource,
        ): SessionDataSources {
            val media: MediaTicketDataSource? = if (localMode) null else MediaRepository()
            return SessionDataSources(
                accounts = if (localMode) LocalAccountRepository(vault) else FirebaseAccountRepository(),
                admin = if (localMode) null else AdminRepository(),
                messaging = if (localMode) null else MessagingRepository(),
                secureChat = if (localMode) null else SecureChatRepository(),
                secureDocument = if (localMode) null else SecureDocumentRepository(),
                media = media,
                safetyDocuments = media?.let {
                    IndexedSafetyDocumentRepository(SafetyDocumentRepository(media = it), documentIndex)
                },
                locations = if (localMode) null else LocationSharingRepository(application),
                adminLocations = if (localMode) null else AdminLocationRepository(),
            )
        }
    }
}

// Checks cover triggers and PINs; only a verified result for the current enrollment may authorize the safe area.
internal class SessionAccess(private val runtime: SessionRuntime) {
    // Detects a valid private-entry search for session access.
    fun interceptSearch(value: String): Boolean {
        with(runtime) {
            with(state) {
                if (accountRole != AccountRole.USER) return false
                val device = enrollment ?: return false
                if (value.isBlank() || (InputPolicy.triggerHash(value) != device.triggerHash &&
                        InputPolicy.legacyTriggerHash(value) != device.triggerHash)
                ) return false
                if (screen == SessionScreen.Cover && !busy) {
                    val event = TriggerEvent(source = if (coverType == CoverType.NEWS) "Daily Digest search" else "Olive search")
                    activeTriggerId = event.id
                    triggerJob = scope.launch(Dispatchers.IO) {
                        store.recordTrigger(device.uid, event)
                        history.queueHistorySync()
                    }
                    pinChallengeVersion++
                    message = null
                    screen = SessionScreen.Pin
                }
                return true
            }
        }
    }

    // Starts the same locked PIN flow when the user double-taps the discreet olive on a cover.
    fun beginDiscreetEntry() {
        with(runtime) {
            with(state) {
                if (accountRole != AccountRole.USER || screen != SessionScreen.Cover || busy || privacyBlackout) return
                val device = enrollment
                if (device == null) {
                    accountSession.openAccountSetup()
                    return
                }
                if (device.role != AccountRole.USER) return
                val event = TriggerEvent(source = "Cover olive")
                activeTriggerId = event.id
                triggerJob = scope.launch(Dispatchers.IO) {
                    store.recordTrigger(device.uid, event)
                    history.queueHistorySync()
                }
                pinChallengeVersion++
                message = null
                screen = SessionScreen.Pin
            }
        }
    }

    // Rejects queued clicks from an old PIN screen or while verification/blackout is active.
    fun submitPin(pin: String, challenge: Int) {
        with(runtime) {
            with(state) {
                if (busy || privacyBlackout || screen != SessionScreen.Pin || accountRole != AccountRole.USER || challenge != pinChallengeVersion) return
                val device = enrollment ?: return
                if (device.role != AccountRole.USER || activeTriggerId == null || !device.allowsAppPin()) return
                if (!InputPolicy.validPin(pin)) {
                    message = runtime.application.getString(R.string.enter_your_4_6_digit_pin)
                    return
                }
                operations.runOperation(runtime.application.getString(R.string.unable_to_check_the_pin_or_record_this_attempt_access_remains_locked)) { token ->
                    val result = withContext(Dispatchers.IO) {
                        triggerJob?.join()
                        store.verify(device.uid, pin).also { outcome ->
                            activeTriggerId?.let { store.recordTriggerAttempt(device.uid, it, outcome is PinResult.Accepted) }
                            history.queueHistorySync()
                        }
                    }
                    if (epoch != token || screen != SessionScreen.Pin || challenge != pinChallengeVersion) return@runOperation
                    when (result) {
                        PinResult.Accepted -> unlockVerifiedDevice(device, token, SessionScreen.Pin, challenge, usingAppPin = true)
                        is PinResult.Rejected -> if (result.failures >= 5) navigation.quickExit() else {
                            message = runtime.application.resources.getQuantityString(
                                R.plurals.ui_incorrect_pin_value_attempts_remaining,
                                5 - result.failures,
                                5 - result.failures
                            )
                        }
                    }
                }
            }
        }
    }

    // Rechecks identity, screen, and cancellation epoch after loading private data.
    internal suspend fun unlockVerifiedDevice(
        device: DeviceEnrollment,
        token: Long,
        expectedScreen: SessionScreen,
        challenge: Int? = null,
        usingAppPin: Boolean = false
    ) {
        with(runtime) {
            with(state) {
                if (usingAppPin && !device.allowsAppPin()) return
                val saved = withContext(Dispatchers.IO) { store.unlockSnapshot(device.uid) }
                if (epoch != token || screen != expectedScreen || enrollment != device || privacyBlackout ||
                    (challenge != null && challenge != pinChallengeVersion) ||
                    (usingAppPin && !device.allowsAppPin())
                ) return
                pinAuthorizedUid = device.uid
                pinAuthorizedEpoch = token
                busy = false
                profile = saved.first
                attempts = saved.second
                pendingSync = saved.third
                history.refreshHistory(device.uid)
                screenHistory.clear()
                safePageHistory.clear()
                page = SafePage.Home
                screen = SessionScreen.Safe
                unlockAnimationVersion++
                privacy.startIdleTimer()
                scope.launch(Dispatchers.IO) {
                    runCatching { dataSources.safetyDocuments?.upgradeOwnedRecoveryKeys(device.uid) }
                }
            }
        }
    }
}

// Holds an active biometric/device-credential unlock request.
internal class DeviceLockRequest internal constructor(
    val challenge: DeviceLockChallenge,
    val settingsChange: Boolean = false
) {
    internal val result = CompletableDeferred<Cipher?>()
    fun complete(cipher: Cipher?) {
        result.complete(cipher)
    }
}

// Authenticates the signed-in user via Android Keystore device lock.
internal class SessionDeviceAccess(private val runtime: SessionRuntime) {
    private val keys = DeviceLockKey(if (runtime.localMode) "demo" else "firebase")

    // Authenticates the account for session device access.
    fun authenticate(enroll: Boolean, trigger: String? = null): Unit = with(runtime) {
        with(state) {
            if (busy || privacyBlackout || accountRole != AccountRole.USER) return
            val identity = pendingIdentity
            val value = pendingProfile
            val device = enrollment
            val expectedScreen = if (enroll) SessionScreen.DevicePin else if (trigger != null) SessionScreen.Safe else SessionScreen.Pin
            if (screen != expectedScreen) return
            if (enroll && (identity == null || value == null || device != null || value.role != AccountRole.USER)) return
            if (!enroll && (device?.entryMethod != SafeEntryMethod.DEVICE_LOCK || device.role != AccountRole.USER)) return
            if (!enroll && trigger == null && activeTriggerId == null) return
            if (trigger != null && !InputPolicy.validTrigger(trigger)) {
                message = application.getString(R.string.entry_invalid_trigger)
                return
            }
            val uid = if (enroll) identity!!.uid else device!!.uid
            val version = pinChallengeVersion
            operations.runOperation(application.getString(R.string.entry_auth_failed), timeoutMillis = 120_000) { token ->
                val challenge = withContext(Dispatchers.IO) {
                    keys.prepare(uid, if (enroll) null else store.deviceLockGrant(uid))
                }
                if (!current(token, expectedScreen, uid, enroll)) {
                    challenge.close()
                    return@runOperation
                }
                val request = DeviceLockRequest(challenge, settingsChange = trigger != null)
                deviceLockRequest = request
                try {
                    val cipher = request.result.await()
                    if (!current(token, expectedScreen, uid, enroll)) return@runOperation
                    if (cipher == null) {
                        message = application.getString(R.string.entry_auth_cancelled)
                        return@runOperation
                    }
                    val grant = withContext(Dispatchers.IO) { challenge.finish(cipher) }
                    if (!current(token, expectedScreen, uid, enroll)) return@runOperation
                    if (enroll) {
                        val saved = withContext(Dispatchers.IO) {
                            store.enrollDeviceLock(identity!!, value!!, checkNotNull(grant)).also { challenge.markEnrolled() }
                        }
                        if (current(token, expectedScreen, uid, true)) accountSession.finishEnrollment(saved, value!!, token)
                    } else if (trigger != null) {
                        settings.saveVerifiedTrigger(device!!, trigger, token)
                    } else {
                        withContext(Dispatchers.IO) {
                            triggerJob?.join()
                            activeTriggerId?.let { store.recordTriggerAttempt(uid, it, true) }
                        }
                        if (current(token, expectedScreen, uid, false) && version == pinChallengeVersion) {
                            access.unlockVerifiedDevice(device!!, token, expectedScreen, version)
                        }
                    }
                } finally {
                    if (deviceLockRequest === request) deviceLockRequest = null
                    challenge.close()
                }
            }
        }
    }

    private fun current(token: Long, expected: SessionScreen, uid: String, enroll: Boolean) = with(runtime) {
        epoch == token && state.screen == expected && !state.privacyBlackout && state.accountRole == AccountRole.USER &&
            (if (enroll) pendingIdentity?.uid == uid else enrollment?.uid == uid)
    }
}
