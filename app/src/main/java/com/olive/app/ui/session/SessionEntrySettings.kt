// Signed-in users: changes the private entry method.
package com.olive.app.ui.session

import com.olive.app.R
import com.olive.app.data.AccountRole
import com.olive.app.data.SettingsEvent
import com.olive.app.security.*
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// Changing entry credentials needs fresh proof of the currently usable method.
internal class SessionEntrySettings(private val runtime: SessionRuntime) {
    private val keys = DeviceLockKey(if (runtime.localMode) "demo" else "firebase")

    // Session entry settings: Checks whether these entry settings can be edited.
    private fun editable() = with(runtime) {
        !state.busy && state.screen == SessionScreen.Safe &&
                state.page == SafePage.Settings && !state.privacyBlackout && state.accountRole == AccountRole.USER
    }

    // returns the select for session entry settings.
    fun select(method: SafeEntryMethod, currentPin: String) {
        if (!editable()) return
        val device = runtime.enrollment ?: return
        if (method == SafeEntryMethod.APP_PIN && !device.hasAppPin) {
            runtime.state.message = runtime.application.getString(R.string.entry_set_pin_first)
            return
        }
        change(currentPin, null, method)
    }

    // Saves pin for session entry settings.
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

    // returns the change for session entry settings.
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
                // returns the current for session entry settings.
                fun current(expected: DeviceEnrollment = device) =
                    epoch == token && enrollment == expected && state.screen == SessionScreen.Safe &&
                            state.page == SafePage.Settings && !state.privacyBlackout
                // verify pin is handled here by current.
                if (verifyPin) {
                    val result =
                        withContext(Dispatchers.IO) { store.verify(device.uid, currentPin) }
                    if (!current()) return@runOperation
                    // Only continue with current when result is pin result rejected.
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
                // If current fails, catch the error so the app can keep running and explain the problem.
                try {
                    if (!current()) return@runOperation
                    var grant: org.json.JSONObject? = null
                    if (challenge != null) {
                        val request = DeviceLockRequest(challenge, settingsChange = true)
                        state.deviceLockRequest = request
                        // If current fails, catch the error so the app can keep running and explain the problem.
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
