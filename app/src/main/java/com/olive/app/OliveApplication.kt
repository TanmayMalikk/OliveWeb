// Contains the olive application code for this part of Olive.
package com.olive.app

import android.app.Application

// Groups the data and actions for olive application.
class OliveApplication : Application() {
    // Runs when Android creates this part of the app.
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.FIREBASE_CONFIGURED) AppCheckProviderInstaller.install()
    }
}
