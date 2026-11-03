// All app roles: sets up app-wide services before any screen opens.
package com.olive.app

import android.app.Application

class OliveApplication : Application() {
    // Creates app-wide notification channels and background services at startup.
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.FIREBASE_CONFIGURED) AppCheckProviderInstaller.install()
    }
}
