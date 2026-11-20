// Debug builds only: validates Android Credential Manager's WebAuthn PRF support before
// production recovery is considered. This activity never receives an account ARK or media key.
package com.olive.app.debug

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import com.olive.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * A disposable, local-only passkey PRF probe.
 *
 * Suggested setup:
 * - Run it only on a debug build whose signing fingerprint is in the published assetlinks.json.
 * - Use a disposable credential-provider account, then delete the generated passkey afterward.
 * - A passing result proves only this provider's Android PRF capability; it does not enroll recovery.
 *
 * No input here needs product or backend configuration. The only fixed public identifier is the
 * already-approved relying-party ID, yourolivebranch.ca.
 */
class PasskeyPrfProbeActivity : Activity() {
    private val random = SecureRandom()
    // This screen is a plain debug Activity rather than the production FragmentActivity host.
    // Cancel its scope explicitly so an in-flight compatibility check cannot outlive the screen.
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var status: TextView
    private lateinit var start: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.debug_prf_probe_title)
        val padding = (24 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply {
            text = getString(R.string.debug_prf_probe_idle)
            importantForAccessibility = TextView.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = ViewGroup.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        start = Button(this).apply {
            text = getString(R.string.debug_prf_probe_start)
            setOnClickListener { runProbe() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(this@PasskeyPrfProbeActivity).apply {
                text = getString(R.string.debug_prf_probe_description)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
            addView(start)
            addView(status)
        })
    }

    private fun runProbe() {
        start.isEnabled = false
        status.text = getString(R.string.debug_prf_probe_working)
        probeScope.launch {
            val createChallenge = randomBytes(32)
            val assertionChallenge = randomBytes(32)
            val userId = randomBytes(16)
            val prfInput = randomBytes(32)
            try {
                val credentialManager = CredentialManager.create(this@PasskeyPrfProbeActivity)
                val registration = credentialManager.createCredential(
                    this@PasskeyPrfProbeActivity,
                    CreatePublicKeyCredentialRequest(
                        PasskeyPrfProbeProtocol.creationRequest(createChallenge, userId, prfInput),
                    ),
                ) as? CreatePublicKeyCredentialResponse ?: throw ProbeFailure
                val credentialId = org.json.JSONObject(registration.registrationResponseJson)
                    .optString("rawId")
                    .takeIf { it.isNotBlank() } ?: throw ProbeFailure
                val response = credentialManager.getCredential(
                    this@PasskeyPrfProbeActivity,
                    GetCredentialRequest(
                        listOf(
                            GetPublicKeyCredentialOption(
                                PasskeyPrfProbeProtocol.assertionRequest(
                                    assertionChallenge, credentialId, prfInput,
                                ),
                            ),
                        ),
                    ),
                ).credential as? PublicKeyCredential ?: throw ProbeFailure
                val prfResult = PasskeyPrfProbeProtocol.prfOutputOrNull(response.authenticationResponseJson)
                    ?: throw PrfUnavailable
                try {
                    status.text = if (prfResult.size == 32) {
                        getString(R.string.debug_prf_probe_supported)
                    } else {
                        getString(R.string.debug_prf_probe_unavailable)
                    }
                } finally {
                    prfResult.fill(0)
                }
            } catch (_: PrfUnavailable) {
                status.text = getString(R.string.debug_prf_probe_unavailable)
            } catch (error: Exception) {
                status.text = if (isCancellation(error)) {
                    getString(R.string.debug_prf_probe_cancelled)
                } else {
                    getString(R.string.debug_prf_probe_failed)
                }
            } finally {
                createChallenge.fill(0)
                assertionChallenge.fill(0)
                userId.fill(0)
                prfInput.fill(0)
                start.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        probeScope.cancel()
        super.onDestroy()
    }

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    // Credential Manager exposes different cancellation exception classes across Android/provider versions.
    // Avoid surfacing class names or WebAuthn payloads; cancellation is the only useful user-facing detail.
    private fun isCancellation(error: Exception): Boolean =
        error.javaClass.simpleName.contains("Cancellation", ignoreCase = true)

    private data object PrfUnavailable : Exception()
    private data object ProbeFailure : Exception()

}
