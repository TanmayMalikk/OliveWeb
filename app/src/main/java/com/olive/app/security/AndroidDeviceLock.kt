package com.olive.app.security

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import com.olive.app.R
import com.olive.app.ui.session.DeviceLockRequest

// Hosts Android authentication without retaining an Activity in the session ViewModel.
class AndroidDeviceLock(private val activity: ComponentActivity, private val onAbandoned: () -> Unit) {
    private var request: DeviceLockRequest? = null
    private var cancellation: CancellationSignal? = null
    private var pendingCipher: javax.crypto.Cipher? = null
    var awaitingCredential: Boolean = false
        private set
    private val credentialLauncher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val current=request
        awaitingCredential=false
        if(current!=null) {
            val cipher=if(result.resultCode==Activity.RESULT_OK) runCatching { current.challenge.legacyCipher() }.getOrNull() else null
            finish(current,cipher)
        }
    }

    fun show(value: DeviceLockRequest) {
        cancel()
        request=value
        val keyguard=activity.getSystemService(KeyguardManager::class.java)
        if(!keyguard.isDeviceSecure) { finish(value,null);return }
        try {
            if(Build.VERSION.SDK_INT>=30) {
                awaitingCredential=true
                val signal=CancellationSignal().also { cancellation=it }
                val prompt=BiometricPrompt.Builder(activity)
                    .setTitle(activity.getString(R.string.entry_device_lock))
                    .setSubtitle(subtitle(value))
                    .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL).build()
                prompt.authenticate(BiometricPrompt.CryptoObject(checkNotNull(value.challenge.promptCipher)),signal,
                    activity.mainExecutor,object:BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult) {
                            finish(value,result.cryptoObject?.cipher)
                        }
                        override fun onAuthenticationError(code:Int,error:CharSequence) { finish(value,null) }
                        // A failed biometric sample keeps Android's prompt open; it never grants access.
                    })
            } else showLegacyPrompt(value)
        } catch(_:Exception) { finish(value,null) }
    }

    private fun subtitle(value:DeviceLockRequest)=activity.getString(when {
        value.settingsChange -> R.string.entry_confirm_settings
        value.challenge.enrolling -> R.string.entry_confirm_setup
        else -> R.string.entry_unlock_description
    })

    private fun showLegacyPrompt(value:DeviceLockRequest) {
        val builder=BiometricPrompt.Builder(activity).setTitle(activity.getString(R.string.entry_device_lock)).setSubtitle(subtitle(value))
        if(Build.VERSION.SDK_INT>=29) {
            @Suppress("DEPRECATION") // Android 10 uses this API for the device-credential fallback.
            builder.setDeviceCredentialAllowed(true)
        } else builder.setNegativeButton(activity.getString(R.string.entry_use_phone_lock),activity.mainExecutor) { _,_ ->
            launchCredential(value)
        }
        val signal=CancellationSignal().also { cancellation=it }
        awaitingCredential=true
        builder.build().authenticate(signal,activity.mainExecutor,object:BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult) {
                finish(value,runCatching { value.challenge.legacyCipher() }.getOrNull())
            }
            override fun onAuthenticationError(code:Int,error:CharSequence) {
                if(code==BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS || code==BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT ||
                    code==BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE || code==BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT ||
                    code==BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT) launchCredential(value)
                else finish(value,null)
            }
        })
    }

    private fun launchCredential(value:DeviceLockRequest) {
        if(request!==value) return
        @Suppress("DEPRECATION") // Android 9/10 cannot use a credential-capable auth-per-use CryptoObject.
        val intent=activity.getSystemService(KeyguardManager::class.java).createConfirmDeviceCredentialIntent(
            activity.getString(R.string.entry_device_lock),subtitle(value))
        if(intent==null) { finish(value,null);return }
        awaitingCredential=true
        credentialLauncher.launch(intent)
    }

    private fun finish(value:DeviceLockRequest,cipher:javax.crypto.Cipher?) {
        if(request!==value) return
        if(!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            request=null;cancellation=null;awaitingCredential=false;pendingCipher=null
            value.complete(null);onAbandoned()
            return
        }
        if(cipher!=null && !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            pendingCipher=cipher
            return
        }
        request=null;cancellation=null;awaitingCredential=false
        pendingCipher=null
        value.complete(cipher)
    }

    fun resume() {
        activity.window.decorView.post {
            val value=request
            val cipher=pendingCipher
            if(value!=null && cipher!=null) finish(value,cipher)
        }
    }

    fun cancel() {
        val previous=request
        request=null;awaitingCredential=false;pendingCipher=null
        cancellation?.cancel();cancellation=null
        previous?.complete(null)
    }
}
