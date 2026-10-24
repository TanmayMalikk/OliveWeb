// Contains the session access code for this part of Olive.
package com.olive.app.ui.session

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.R

import com.olive.app.data.*
import com.olive.app.security.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Checks cover triggers and PINs; only a verified result for the current enrollment may authorize the safe area.
internal class SessionAccess(private val runtime: SessionRuntime) {
    fun interceptSearch(value: String): Boolean {
        with(runtime) {
            with(state) {
                if (accountRole != AccountRole.USER) return false
                val device = enrollment ?: return false
                if (value.isBlank() || (InputPolicy.triggerHash(value) != device.triggerHash &&
                            InputPolicy.legacyTriggerHash(value) != device.triggerHash)
                ) return false
                if (screen == SessionScreen.Cover && !busy) {
                    val event =
                        TriggerEvent(source = if (coverType == CoverType.NEWS) "Daily Digest search" else "Olive search")
                    activeTriggerId = event.id
                    triggerJob = scope.launch(Dispatchers.IO) {
                        store.recordTrigger(
                            device.uid,
                            event
                        )
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

    // Reject queued clicks from an old PIN screen or while verification/blackout is active.
    fun submitPin(pin: String, challenge: Int) {
        with(runtime) {
            with(state) {
                if (busy || privacyBlackout || screen != SessionScreen.Pin || accountRole != AccountRole.USER || challenge != pinChallengeVersion) return
                val device = enrollment ?: return
                if (device.role != AccountRole.USER || activeTriggerId == null) return
                if (!device.allowsAppPin(phoneSecure())) return
                if (!InputPolicy.validPin(pin)) {
                    message =
                        runtime.application.getString(R.string.enter_your_4_6_digit_pin)
                    return
                }
                operations.runOperation(runtime.application.getString(R.string.unable_to_check_the_pin_or_record_this_attempt_access_remains_locked)) { token ->
                    val result = withContext(Dispatchers.IO) {
                        triggerJob?.join()
                        store.verify(device.uid, pin).also { outcome ->
                            activeTriggerId?.let {
                                store.recordTriggerAttempt(
                                    device.uid,
                                    it,
                                    outcome is PinResult.Accepted
                                )
                            }
                            history.queueHistorySync()
                        }
                    }
                    if (epoch != token || screen != SessionScreen.Pin || challenge != pinChallengeVersion) return@runOperation
                    when (result) {
                        PinResult.Accepted -> unlockVerifiedDevice(
                            device,
                            token,
                            SessionScreen.Pin,
                            challenge,
                            usingAppPin = true
                        )

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

    // Submits calculator PIN.
    fun submitCalculatorPin(pin: String) {
        with(runtime) {
            with(state) {
                if (busy || privacyBlackout || accountRole != AccountRole.USER || screen != SessionScreen.Cover || coverType != CoverType.CALCULATOR || !InputPolicy.validPin(
                        pin
                    )
                ) return
                val device = enrollment ?: return
                if (device.role != AccountRole.USER) return
                if (!device.allowsAppPin(phoneSecure())) {
                    // Calculator digits open a fresh challenge; Android must still authenticate the account.
                    val event = TriggerEvent(source = "Better Calculator")
                    activeTriggerId = event.id
                    triggerJob = scope.launch(Dispatchers.IO) {
                        store.recordTrigger(
                            device.uid,
                            event
                        )
                        history.queueHistorySync()
                    }
                    pinChallengeVersion++
                    message = null
                    screen = SessionScreen.Pin
                    return
                }
                val newTrigger =
                    if (activeTriggerId == null) TriggerEvent(source = "Better Calculator").also {
                        activeTriggerId = it.id
                    } else null
                val triggerId = activeTriggerId ?: return
                operations.runOperation(runtime.application.getString(R.string.unable_to_check_the_pin_or_record_this_attempt_access_remains_locked)) { token ->
                    val result = withContext(Dispatchers.IO) {
                        // Reuse one calculator trigger session so a later success can follow its earlier wrong attempts.
                        newTrigger?.let { store.recordTrigger(device.uid, it) }
                        store.verify(device.uid, pin).also { outcome ->
                            store.recordTriggerAttempt(
                                device.uid,
                                triggerId,
                                outcome is PinResult.Accepted
                            )
                            history.queueHistorySync()
                        }
                    }
                    if (epoch != token || screen != SessionScreen.Cover || coverType != CoverType.CALCULATOR) return@runOperation
                    when (result) {
                        PinResult.Accepted -> unlockVerifiedDevice(
                            device,
                            token,
                            SessionScreen.Cover,
                            usingAppPin = true
                        )

                        is PinResult.Rejected -> if (result.failures >= 5) navigation.quickExit()
                    }
                }
            }
        }
    }

    // Recheck identity, screen and cancellation epoch after loading private data; exit invalidates pending unlocks.
    internal suspend fun unlockVerifiedDevice(
        device: DeviceEnrollment,
        token: Long,
        expectedScreen: SessionScreen,
        challenge: Int? = null,
        usingAppPin: Boolean = false
    ) {
        with(runtime) {
            with(state) {
                if (usingAppPin && !device.allowsAppPin(phoneSecure())) return
                val saved = withContext(Dispatchers.IO) { store.unlockSnapshot(device.uid) }
                if (epoch != token || screen != expectedScreen || enrollment != device || privacyBlackout ||
                    (challenge != null && challenge != pinChallengeVersion) ||
                    (usingAppPin && !device.allowsAppPin(phoneSecure()))
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
                privacy.startIdleTimer()
            }
        }
    }
}
