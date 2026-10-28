// Signed-in users: checks device-lock and PIN entry.
package com.olive.app.ui.session

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.R
import com.olive.app.data.AccountRole
import com.olive.app.security.DeviceLockChallenge
import com.olive.app.security.DeviceLockKey
import com.olive.app.security.SafeEntryMethod
import com.olive.app.security.InputPolicy
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.crypto.Cipher

internal class DeviceLockRequest internal constructor(
    val challenge: DeviceLockChallenge,
    val settingsChange: Boolean = false
) {
    internal val result = CompletableDeferred<Cipher?>()
    fun complete(cipher: Cipher?) {
        result.complete(cipher)
    }
}

// A prompt result is useful only to its original account, screen and cancellation epoch.
internal class SessionDeviceAccess(private val runtime: SessionRuntime) {
    private val keys = DeviceLockKey(if (runtime.localMode) "demo" else "firebase")

    fun authenticate(enroll: Boolean, trigger: String? = null): Unit = with(runtime) {
        with(state) {
            if (busy || privacyBlackout) return
            if (accountRole != AccountRole.USER) return
            val identity = pendingIdentity
            val value = pendingProfile
            val device = enrollment
            val expectedScreen =
                if (enroll) SessionScreen.DevicePin else if (trigger != null) SessionScreen.Safe else SessionScreen.Pin
            if (screen != expectedScreen) return
            if (enroll && (identity == null || value == null || device != null || value.role != AccountRole.USER)) return
            if (!enroll && device?.entryMethod != SafeEntryMethod.DEVICE_LOCK) return
            if (!enroll && device?.role != AccountRole.USER) return
            if (!enroll && trigger == null && activeTriggerId == null) return
            if (trigger != null && !InputPolicy.validTrigger(trigger)) {
                message = application.getString(R.string.entry_invalid_trigger)
                return
            }
            val uid = if (enroll) identity!!.uid else device!!.uid
            // Counts changes to value.
            val version = pinChallengeVersion
            operations.runOperation(
                application.getString(R.string.entry_auth_failed),
                timeoutMillis = 120_000
            ) { token ->
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
                        message =
                            application.getString(R.string.entry_auth_cancelled)
                        return@runOperation
                    }
                    // The callback alone grants no access: Android Keystore must complete the protected operation.
                    val grant = withContext(Dispatchers.IO) { challenge.finish(cipher) }
                    if (!current(token, expectedScreen, uid, enroll)) return@runOperation
                    if (enroll) {
                        val saved = withContext(Dispatchers.IO) {
                            store.enrollDeviceLock(identity!!, value!!, checkNotNull(grant))
                                .also { challenge.markEnrolled() }
                        }
                        if (current(token, expectedScreen, uid, true)) accountSession.finishEnrollment(
                            saved,
                            value!!,
                            token
                        )
                    } else if (trigger != null) {
                        settings.saveVerifiedTrigger(device!!, trigger, token)
                    } else {
                        withContext(Dispatchers.IO) {
                            triggerJob?.join()
                            activeTriggerId?.let { store.recordTriggerAttempt(uid, it, true) }
                        }
                        if (current(
                                token,
                                expectedScreen,
                                uid,
                                false
                            ) && version == pinChallengeVersion
                        )
                            access.unlockVerifiedDevice(device!!, token, expectedScreen, version)
                    }
                } finally {
                    if (deviceLockRequest === request) deviceLockRequest = null
                    challenge.close()
                }
            }
        }
    }

    private fun current(token: Long, expected: SessionScreen, uid: String, enroll: Boolean) =
        with(runtime) {
            epoch == token && state.screen == expected && !state.privacyBlackout && state.accountRole == AccountRole.USER &&
                    (if (enroll) pendingIdentity?.uid == uid else enrollment?.uid == uid)
        }
}
