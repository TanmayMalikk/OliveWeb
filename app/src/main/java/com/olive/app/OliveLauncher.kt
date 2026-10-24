// Contains the olive launcher code for this part of Olive.
package com.olive.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle

// Android Studio launches through here so changing cover icons never breaks the Run button.
class OliveLauncher : Activity() {
    // Runs when Android creates this part of the app.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, OliveActivity::class.java))
        finish()
    }
}
