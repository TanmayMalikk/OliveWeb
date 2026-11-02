// All signed-in roles: checks device credentials before safe-app entry.
package com.olive.app.security

import android.app.KeyguardManager
import android.os.Build
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.olive.app.R
import com.olive.app.ui.session.DeviceLockRequest

// Hosts Android authentication without retaining an Activity in the session ViewModel.
internal class AndroidDeviceLock(
    private val activity: FragmentActivity,
    private val onAbandoned: () -> Unit
) {
    private var request: DeviceLockRequest? = null
    private var pendingCipher: javax.crypto.Cipher? = null
    var awaitingCredential: Boolean = false
        private set
    private val prompt = BiometricPrompt(
        activity,
        activity.mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
            // Continues private entry after Android confirms the device owner.
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val current = request ?: return
                val cipher = result.cryptoObject?.cipher
                    ?: runCatching { current.challenge.legacyCipher() }.getOrNull()
                finish(current, cipher)
            }

            // Any cancellation or rejected credential keeps the Safe App locked.
            override fun onAuthenticationError(code: Int, error: CharSequence) {
                request?.let { finish(it, null) }
            }
        }
    )

    // Opens Android device authentication and returns its result.
    fun show(value: DeviceLockRequest) {
        cancel()
        request = value
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            finish(value, null)
            return
        }
        // AndroidX chooses the correct system prompt for this Android version.
        try {
            awaitingCredential = true
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.entry_device_lock))
                .setSubtitle(subtitle(value))
            // Android 11+ can bind the exact cipher operation to biometrics or the device lock.
            if (Build.VERSION.SDK_INT >= 30) {
                promptInfo.setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                prompt.authenticate(
                    promptInfo.build(),
                    BiometricPrompt.CryptoObject(checkNotNull(value.challenge.promptCipher))
                )
            } else {
                // AndroidX handles the device-lock fallback on Android 9/10. The cipher is opened
                // immediately after authentication because these versions cannot bind it to a PIN.
                promptInfo.setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                prompt.authenticate(promptInfo.build())
            }
        } catch (_: Exception) { // If Android cannot open its lock prompt, deny access by returning no authentication result.
            finish(value, null)
        }
    }

    // Formats the subtitle shown by app security.
    private fun subtitle(value: DeviceLockRequest) = activity.getString(
        when {
            value.settingsChange -> R.string.entry_confirm_settings
            value.challenge.enrolling -> R.string.entry_confirm_setup
            else -> R.string.entry_unlock_description
        }
    )

    // Ends the authentication request once and sends its final result.
    private fun finish(value: DeviceLockRequest, cipher: javax.crypto.Cipher?) {
        if (request !== value) return
        // Wait to show device authentication until the activity is visible.
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            request = null
            awaitingCredential = false
            pendingCipher = null
            value.complete(null)
            onAbandoned()
            return
        }
        if (cipher != null && !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            pendingCipher = cipher
            return
        }
        request = null
        awaitingCredential = false
        pendingCipher = null
        value.complete(cipher)
    }

    // Delivers the pending device-lock result when the app resumes.
    fun resume() {
        activity.window.decorView.post {
            val value = request
            val cipher = pendingCipher
            if (value != null && cipher != null) finish(value, cipher)
        }
    }

    // Android device lock: Cancels the active task.
    fun cancel() {
        val previous = request
        request = null
        awaitingCredential = false
        pendingCipher = null
        prompt.cancelAuthentication()
        previous?.complete(null)
    }
}
