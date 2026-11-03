// All app roles: starts the cover app when the Android launcher opens.
package com.olive.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

// Android Studio launches through here so changing cover icons never breaks the Run button.
class OliveLauncher : Activity() {
    // Sends launcher taps into OliveActivity and closes this small forwarding activity.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, OliveActivity::class.java))
        finish()
    }
}
