// All app roles: sets up app-wide services before any screen opens.
package com.olive.app

import android.app.Application
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.olive.app.data.messaging.VoipPushTokenRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class OliveApplication : Application() {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
