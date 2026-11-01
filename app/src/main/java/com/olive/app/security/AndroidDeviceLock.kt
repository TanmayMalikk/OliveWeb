// All signed-in roles: checks device credentials before safe-app entry.
package com.olive.app.security

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import com.olive.app.R
import com.olive.app.ui.session.DeviceLockRequest

// Hosts Android authentication without retaining an Activity in the session ViewModel.
internal class AndroidDeviceLock(
    private val activity: ComponentActivity,
    private val onAbandoned: () -> Unit
) {
    private var request: DeviceLockRequest? = null
    private var cancellation: CancellationSignal? = null
    private var pendingCipher: javax.crypto.Cipher? = null
    var awaitingCredential: Boolean = false
        private set
    private val credentialLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val current = request
            awaitingCredential = false
            if (current != null) {
                val cipher =
                    if (result.resultCode == Activity.RESULT_OK) runCatching { current.challenge.legacyCipher() }.getOrNull() else null
                finish(current, cipher)
            }
        }

    // returns the show for android device lock.
    fun show(value: DeviceLockRequest) {
        cancel()
        request = value
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            finish(value, null)
            return
        }
        // A show error is handled here instead of closing the app.
        try {
            // the Android version is at least 30 is handled here by show.
            if (Build.VERSION.SDK_INT >= 30) {
                awaitingCredential = true
                val signal = CancellationSignal().also { cancellation = it }
                val prompt = BiometricPrompt.Builder(activity)
                    .setTitle(activity.getString(R.string.entry_device_lock))
                    .setSubtitle(subtitle(value))
                    .setAllowedAuthenticators(
                        android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
                    ).build()
                prompt.authenticate(
                    BiometricPrompt.CryptoObject(checkNotNull(value.challenge.promptCipher)),
                    signal,
                    activity.mainExecutor,
                    // Unlocks only when Android returns the cipher from a successful device authentication.
                    object : BiometricPrompt.AuthenticationCallback() {
                        // Responds when authentication succeeded for android device lock.
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            finish(value, result.cryptoObject?.cipher)
                        }

                        // Android device lock: Responds when authentication error.
                        override fun onAuthenticationError(code: Int, error: CharSequence) {
                            finish(value, null)
                        }
                        // A failed biometric sample keeps Android's prompt open; it never grants access.
                    })
            } else showLegacyPrompt(value)
        } catch (_: Exception) { // If Android cannot open its lock prompt, deny access by returning no authentication result.
            finish(value, null)
        }
    }

    // Android device lock: returns the subtitle.
    private fun subtitle(value: DeviceLockRequest) = activity.getString(
        when {
            value.settingsChange -> R.string.entry_confirm_settings
            value.challenge.enrolling -> R.string.entry_confirm_setup
            else -> R.string.entry_unlock_description
        }
    )

    // Opens the Android 9/10 biometric or device-credential prompt used by older phones.
    private fun showLegacyPrompt(value: DeviceLockRequest) {
        val builder = BiometricPrompt.Builder(activity)
            .setTitle(activity.getString(R.string.entry_device_lock)).setSubtitle(subtitle(value))
        if (Build.VERSION.SDK_INT >= 29) {
            allowLegacyDeviceCredential(builder)
        } else builder.setNegativeButton(
            activity.getString(R.string.entry_use_phone_lock),
            activity.mainExecutor
        ) { _, _ ->
            launchCredential(value)
        }
        val signal = CancellationSignal().also { cancellation = it }
        awaitingCredential = true
        builder.build().authenticate(
            signal,
            activity.mainExecutor,
            // Converts the older Android authentication result into the same locked/unlocked result.
            object : BiometricPrompt.AuthenticationCallback() {
                // Android device lock: Responds when authentication succeeded.
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    finish(value, runCatching { value.challenge.legacyCipher() }.getOrNull())
                }

                // Android device lock: Responds when authentication error.
                override fun onAuthenticationError(code: Int, error: CharSequence) {
                    if (code == BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS || code == BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT ||
                        code == BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE || code == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT ||
                        code == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT
                    ) launchCredential(value)
                    else finish(value, null)
                }
            })
    }

    // Android device lock: Starts credential.
    private fun launchCredential(value: DeviceLockRequest) {
        if (request !== value) return
        val intent = createLegacyCredentialIntent(value)
        if (intent == null) {
            finish(value, null)
            return
        }
        awaitingCredential = true
        credentialLauncher.launch(intent)
    }

    // Android device lock: returns the allow legacy device credential.
    @Suppress("DEPRECATION")
    @RequiresApi(29)
    private fun allowLegacyDeviceCredential(builder: BiometricPrompt.Builder) {
        // Android 10 has no allowed-authenticators replacement; this path never runs on Android 11+.
        builder.setDeviceCredentialAllowed(true)
    }

    @Suppress("DEPRECATION")
    // Creates legacy credential intent.
    private fun createLegacyCredentialIntent(value: DeviceLockRequest) =
        // Android 9/10 cannot use a credential-capable auth-per-use CryptoObject.
        activity.getSystemService(KeyguardManager::class.java).createConfirmDeviceCredentialIntent(
            activity.getString(R.string.entry_device_lock), subtitle(value)
        )

    // Android device lock: returns the finish.
    private fun finish(value: DeviceLockRequest, cipher: javax.crypto.Cipher?) {
        if (request !== value) return
        // Only continue with finish when activity lifecycle current state is at least lifecycle state started is false.
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            request = null
            cancellation = null
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
        cancellation = null
        awaitingCredential = false
        pendingCipher = null
        value.complete(cipher)
    }

    // Android device lock: returns the resume.
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
        cancellation?.cancel()
        cancellation = null
        previous?.complete(null)
    }
}
