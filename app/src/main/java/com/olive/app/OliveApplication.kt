// All app roles: sets up app-wide services before any screen opens.
package com.olive.app

import android.app.Application
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.olive.app.data.messaging.VoipPushTokenRegistration
import com.olive.app.ui.covers.fitness.FitnessWatchSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OliveApplication : Application() {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Creates app-wide notification channels and background services at startup.
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.FIREBASE_CONFIGURED) {
            AppCheckProviderInstaller.install()
            backgroundScope.launch {
                try {
                    VoipPushTokenRegistration.registerForTokenUpdates(this@OliveApplication)
                } catch (error: Exception) {
                    Log.e("VoipPushToken", "Could not initialize Firebase push messaging", error)
                }
            }
            FirebaseAuth.getInstance().addAuthStateListener { auth ->
                // The watch follows the account currently authenticated on this phone.
                FitnessWatchSync.bindAccount(this@OliveApplication, auth.currentUser?.uid)
                if (auth.currentUser != null) {
                    backgroundScope.launch {
                        try {
                            VoipPushTokenRegistration.registerCurrentToken(this@OliveApplication)
                        } catch (error: Exception) {
                            Log.e(
                                "VoipPushToken",
                                "Could not register this device for call invitations",
                                error
                            )
                        }
                    }
                }
            }
        }
    }
}
