package com.olive.app.ui.session

import com.olive.app.data.*
import com.olive.app.security.*
import com.olive.app.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Checks cover triggers and PINs; only a verified result for the current enrollment may authorize the safe area.
internal class SessionAccess(private val runtime:SessionRuntime) {
    fun interceptSearch(value: String) : Boolean {
        with(runtime) {
            with(state) {
                if(accountRole!=AccountRole.USER) return false
                val device = enrollment ?: return false
                if(value.isBlank() || (InputPolicy.triggerHash(value) != device.triggerHash &&
                        InputPolicy.legacyTriggerHash(value) != device.triggerHash)) return false
                if(screen == SessionScreen.Cover && !busy) {
                    val event=TriggerEvent(source=if(coverType==CoverType.NEWS) "Daily Digest search" else "Olive search")
                    activeTriggerId=event.id
                    triggerJob=scope.launch(Dispatchers.IO) { store.recordTrigger(device.uid,event);history.queueHistorySync() }
                    pinChallengeVersion++
                    message=null; screen=SessionScreen.Pin
                }
                return true
            }
        }
    }

    // Reject queued clicks from an old PIN screen or while verification/blackout is active.
    fun submitPin(pin: String,challenge:Int) {
        with(runtime) {
            with(state) {
                if(busy || privacyBlackout || screen != SessionScreen.Pin || accountRole!=AccountRole.USER || challenge!=pinChallengeVersion) return
                val device = enrollment ?: return
                if(device.role!=AccountRole.USER || activeTriggerId==null) return
                if(!InputPolicy.validPin(pin)) { message="Enter your 4–6 digit PIN."; return }
                operations.runOperation("Unable to check the PIN or record this attempt. Access remains locked.") { token ->
                    val result = withContext(Dispatchers.IO) {
                        triggerJob?.join()
                        store.verify(device.uid,pin).also { outcome ->
                            activeTriggerId?.let { store.recordTriggerAttempt(device.uid,it,outcome is PinResult.Accepted) }
                            history.queueHistorySync()
                        }
                    }
                    if(epoch != token || screen != SessionScreen.Pin || challenge!=pinChallengeVersion) return@runOperation
                    when(result) {
                        PinResult.Accepted -> unlockVerifiedDevice(device,token,SessionScreen.Pin,challenge)
                        is PinResult.Rejected -> if(result.failures >= 5) navigation.quickExit() else {
                            message="Incorrect PIN. ${5-result.failures} attempts remaining."
                        }
                    }
                }
            }
        }
    }

    fun submitCalculatorPin(pin: String) {
        with(runtime) {
            with(state) {
                if(busy || privacyBlackout || accountRole!=AccountRole.USER || screen!=SessionScreen.Cover || coverType!=CoverType.CALCULATOR || !InputPolicy.validPin(pin)) return
                val device=enrollment ?: return
                if(device.role!=AccountRole.USER) return
                val newTrigger=if(activeTriggerId==null) TriggerEvent(source="Better Calculator").also { activeTriggerId=it.id } else null
                val triggerId=activeTriggerId ?: return
                operations.runOperation("Unable to check the PIN or record this attempt. Access remains locked.") { token ->
                    val result=withContext(Dispatchers.IO) {
                        // Reuse one calculator trigger session so a later success can follow its earlier wrong attempts.
                        newTrigger?.let { store.recordTrigger(device.uid,it) }
                        store.verify(device.uid,pin).also { outcome ->
                            store.recordTriggerAttempt(device.uid,triggerId,outcome is PinResult.Accepted)
                            history.queueHistorySync()
                        }
                    }
                    if(epoch!=token || screen!=SessionScreen.Cover || coverType!=CoverType.CALCULATOR) return@runOperation
                    when(result) {
                        PinResult.Accepted -> unlockVerifiedDevice(device,token,SessionScreen.Cover)
                        is PinResult.Rejected -> if(result.failures>=5) navigation.quickExit()
                    }
                }
            }
        }
    }

    // Recheck identity, screen and cancellation epoch after loading private data; exit invalidates pending unlocks.
    private suspend fun unlockVerifiedDevice(device:DeviceEnrollment,token:Long,expectedScreen:SessionScreen,challenge:Int?=null) {
        with(runtime) {
            with(state) {
                val saved=withContext(Dispatchers.IO) { store.unlockSnapshot(device.uid) }
                if(epoch!=token || screen!=expectedScreen || enrollment!=device || privacyBlackout ||
                    (challenge!=null && challenge!=pinChallengeVersion)) return
                pinAuthorizedUid=device.uid;pinAuthorizedEpoch=token
                busy=false
                profile=saved.first
                attempts=saved.second
                pendingSync=saved.third
                history.refreshHistory(device.uid)
                screenHistory.clear()
                safePageHistory.clear()
                page=SafePage.Home
                screen=SessionScreen.Safe
                privacy.startIdleTimer()
            }
        }
    }
}
