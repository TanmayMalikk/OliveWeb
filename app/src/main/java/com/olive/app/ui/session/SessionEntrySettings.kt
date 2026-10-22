package com.olive.app.ui.session

import com.olive.app.R
import com.olive.app.data.AccountRole
import com.olive.app.data.SettingsEvent
import com.olive.app.security.*
import com.olive.app.ui.SafePage
import com.olive.app.ui.SessionScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// Changing entry credentials needs fresh proof of the currently usable method.
internal class SessionEntrySettings(private val runtime:SessionRuntime) {
    private val keys=DeviceLockKey(if(runtime.localMode) "demo" else "firebase")

    private fun editable()=with(runtime) { !state.busy && state.screen==SessionScreen.Safe &&
        state.page==SafePage.Settings && !state.privacyBlackout && state.accountRole==AccountRole.USER }

    fun select(method:SafeEntryMethod,currentPin:String) {
        if(!editable()) return
        val device=runtime.enrollment ?: return
        if(method==SafeEntryMethod.APP_PIN && !device.hasAppPin) {
            runtime.state.message=runtime.application.getString(R.string.entry_set_pin_first)
            return
        }
        change(currentPin,null,method)
    }

    fun savePin(currentPin:String,pin:String,confirmation:String,usePin:Boolean) {
        if(!editable()) return
        if(!InputPolicy.validPin(pin)) {
            runtime.state.message=runtime.application.getString(R.string.enter_your_4_6_digit_pin);return
        }
        if(pin!=confirmation) {
            runtime.state.message=runtime.application.getString(R.string.the_new_pins_do_not_match);return
        }
        change(currentPin,pin,if(usePin) SafeEntryMethod.APP_PIN else null)
    }

    private fun change(currentPin:String,pin:String?,method:SafeEntryMethod?):Unit = with(runtime) {
        val device=enrollment ?: return
        if(state.busy || state.screen!=SessionScreen.Safe || state.page!=SafePage.Settings ||
            state.privacyBlackout || state.accountRole!=AccountRole.USER || device.role!=AccountRole.USER) return
        val secure=phoneSecure()
        val enableDevice=method==SafeEntryMethod.DEVICE_LOCK
        if(enableDevice && !secure) { state.message=application.getString(R.string.entry_device_unavailable);return }
        val verifyPin=device.allowsAppPin(secure)
        if(verifyPin && !InputPolicy.validPin(currentPin)) {
            state.message=application.getString(R.string.enter_your_4_6_digit_pin);return
        }
        if(!verifyPin && !secure) { state.message=application.getString(R.string.entry_restore_lock);return }
        operations.runOperation(application.getString(R.string.entry_settings_failed),timeoutMillis=120_000) { token ->
            fun current(expected:DeviceEnrollment=device)=epoch==token && enrollment==expected && state.screen==SessionScreen.Safe &&
                state.page==SafePage.Settings && !state.privacyBlackout
            if(verifyPin) {
                val result=withContext(Dispatchers.IO) { store.verify(device.uid,currentPin) }
                if(!current()) return@runOperation
                if(result is PinResult.Rejected) {
                    if(result.failures>=5) navigation.quickExit()
                    else state.message=application.resources.getQuantityString(
                        R.plurals.ui_incorrect_pin_value_attempts_remaining,5-result.failures,5-result.failures)
                    return@runOperation
                }
            }
            // A fresh key is staged when enabling device lock. Cancellation deletes only that staged key.
            val challenge=if(enableDevice || !verifyPin) withContext(Dispatchers.IO) {
                keys.prepare(device.uid,if(enableDevice) null else store.deviceLockGrant(device.uid))
            } else null
            try {
                if(!current()) return@runOperation
                var grant:org.json.JSONObject?=null
                if(challenge!=null) {
                    val request=DeviceLockRequest(challenge,settingsChange=true)
                    state.deviceLockRequest=request
                    try {
                        val cipher=request.result.await()
                        if(!current()) return@runOperation
                        if(cipher==null) { state.message=application.getString(R.string.entry_settings_cancelled);return@runOperation }
                        grant=withContext(Dispatchers.IO) { challenge.finish(cipher) }
                    } finally { if(state.deviceLockRequest===request) state.deviceLockRequest=null }
                }
                if(!current()) return@runOperation
                // Never commit a PIN fallback if the device became secure again during verification.
                if(verifyPin && device.entryMethod==SafeEntryMethod.DEVICE_LOCK && phoneSecure()) return@runOperation
                // Once the verified write begins, finish updating the cached enrollment even
                // if Quick exit cancels the operation. This never reopens the safe screen.
                val updated=withContext(NonCancellable) {
                    val saved=withContext(Dispatchers.IO) { store.updateSafeEntry(device,method,pin,grant) }
                    if(grant!=null) challenge?.markEnrolled()
                    if(enrollment==device) enrollment=saved
                    saved
                }
                if(!current(updated)) return@runOperation
                state.message=application.getString(R.string.entry_settings_saved)
                withContext(Dispatchers.IO) { store.recordSettings(device.uid,SettingsEvent(area="Olive Branch",action="Updated safe entry settings")) }
                history.queueHistorySync()
            } finally { challenge?.close() }
        }
    }
}
