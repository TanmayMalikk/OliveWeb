package com.olive.app.ui.users.user.documents

import android.widget.Toast
import com.olive.app.R
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountRole
import com.olive.app.location.AdminLocationService
import com.olive.app.data.location.DeviceLocation
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Send an explicitly requested report without unlocking or exposing the Safe App on the cover.
internal fun SessionViewModel.sendCoverEmergency() {
    val uid = coverAccountId ?: return
    if (accountRole != AccountRole.USER || screen != SessionScreen.Cover || busy || coverEmergencyInFlight) return
    coverEmergencyInFlight = true
    val token = runtime.epoch
    fun finish(result: Result<Unit>) {
        coverEmergencyInFlight = false
        if (coverAccountId == uid && runtime.epoch == token && result.isFailure) {
            Toast.makeText(runtime.application, R.string.emergency_report_failed, Toast.LENGTH_LONG).show()
        }
    }
    runtime.scope.launch {
        try {
            val (savedProfile, entries) = withContext(Dispatchers.IO) {
                runtime.store.profile(uid) to runtime.store.privateEntries(uid)
            }
            if (coverAccountId != uid || runtime.epoch != token) {
                coverEmergencyInFlight = false
                return@launch
            }
            if (!localMode) runCatching { AdminLocationService.start(runtime.application, uid, savedProfile.fullName) }
            documents.createEmergencyReport(AccountIdentity(uid, savedProfile.username), savedProfile, entries,
                location = {
                    DeviceLocation.currentCoordinates(runtime.application)?.let {
                        EmergencyReportLocation(it.latitude, it.longitude, it.accuracy, it.timestamp)
                    }
                },
                onAlertSent = {
                    if (coverAccountId == uid && runtime.epoch == token) {
                        val confirmation = uid to android.os.SystemClock.elapsedRealtime()
                        coverEmergencyConfirmation = confirmation
                        runtime.scope.launch {
                            kotlinx.coroutines.delay(3_500)
                            if (coverEmergencyConfirmation == confirmation) coverEmergencyConfirmation = null
                        }
                    }
                },
                onFinished = ::finish,
            )
        } catch (cancelled: CancellationException) {
            coverEmergencyInFlight = false
            throw cancelled
        } catch (failure: Exception) {
            finish(Result.failure(failure))
        }
    }
}
