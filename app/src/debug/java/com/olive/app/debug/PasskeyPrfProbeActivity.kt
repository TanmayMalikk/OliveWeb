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
import kotlinx.coroutines.withTimeoutOrNull
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
    private var pendingAssertion: PendingAssertion? = null

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
            setOnClickListener { runNextAction() }
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

    private fun runNextAction() = when (PasskeyPrfProbeFlow.nextAction(pendingAssertion != null)) {
        PasskeyPrfProbeAction.CREATE_PASSKEY -> createPasskey()
        PasskeyPrfProbeAction.REQUEST_PRF_ASSERTION -> requestPrfAssertion()
    }

    private fun createPasskey() {
        start.isEnabled = false
        probeScope.launch {
            status.text = getString(R.string.debug_prf_probe_creating)
            val createChallenge = randomBytes(32)
            val userId = randomBytes(16)
            val prfInput = randomBytes(32)
            var transferredPrfInput = false
            try {
                val credentialManager = CredentialManager.create(this@PasskeyPrfProbeActivity)
                val registration = credentialManager.createCredential(
                    this@PasskeyPrfProbeActivity,
                    CreatePublicKeyCredentialRequest(
                        PasskeyPrfProbeProtocol.creationRequest(createChallenge, userId),
                    ),
                ) as? CreatePublicKeyCredentialResponse ?: throw ProbeFailure
                val credentialId = org.json.JSONObject(registration.registrationResponseJson)
                    .optString("rawId")
                    .takeIf { it.isNotBlank() } ?: throw ProbeFailure
                // The PRF input stays only in this Activity's memory until the user's second tap.
                // Separating ceremonies avoids immediately reopening a pre-Android-14 provider.
                pendingAssertion = PendingAssertion(credentialId, prfInput)
                transferredPrfInput = true
                status.text = getString(R.string.debug_prf_probe_ready_for_assertion)
                start.text = getString(R.string.debug_prf_probe_assert)
            } catch (error: Exception) {
                showFailure(PasskeyPrfProbeStage.CREATING_PASSKEY, error)
            } finally {
                createChallenge.fill(0)
                userId.fill(0)
                if (!transferredPrfInput) prfInput.fill(0)
                start.isEnabled = true
            }
        }
    }

    private fun requestPrfAssertion() {
        val pending = pendingAssertion ?: return
        start.isEnabled = false
        probeScope.launch {
            status.text = getString(R.string.debug_prf_probe_asserting)
            val assertionChallenge = randomBytes(32)
            try {
                val credentialManager = CredentialManager.create(this@PasskeyPrfProbeActivity)
                val providerResponse = withTimeoutOrNull(PRF_ASSERTION_TIMEOUT_MS) {
                    credentialManager.getCredential(
                        this@PasskeyPrfProbeActivity,
                        GetCredentialRequest(
                            listOf(
                                GetPublicKeyCredentialOption(
                                    PasskeyPrfProbeProtocol.assertionRequest(
                                        assertionChallenge, pending.credentialId, pending.prfInput,
                                    ),
                                ),
                            ),
                        ),
                    )
                } ?: throw PasskeyPrfProbeTimeout
                val response = providerResponse.credential as? PublicKeyCredential ?: throw ProbeFailure
                val prfResult = PasskeyPrfProbeProtocol.prfOutputOrNull(response.authenticationResponseJson)
                    ?: throw PrfUnavailable
                try {
                    status.text = getString(R.string.debug_prf_probe_supported)
                } finally {
                    prfResult.fill(0)
                }
            } catch (_: PrfUnavailable) {
                status.text = getString(R.string.debug_prf_probe_unavailable)
            } catch (error: Exception) {
                showFailure(PasskeyPrfProbeStage.REQUESTING_PRF_ASSERTION, error)
            } finally {
                assertionChallenge.fill(0)
                clearPendingAssertion()
                start.text = getString(R.string.debug_prf_probe_start)
                start.isEnabled = true
            }
        }
    }

    private fun showFailure(stage: PasskeyPrfProbeStage, error: Exception) {
        status.text = when (PasskeyPrfProbeFailureClassifier.classify(stage, error)) {
            PasskeyPrfProbeFailure.CANCELLED -> getString(R.string.debug_prf_probe_cancelled)
            PasskeyPrfProbeFailure.ASSERTION_TIMED_OUT -> getString(R.string.debug_prf_probe_assertion_timed_out)
            PasskeyPrfProbeFailure.PROVIDER_CONFIGURATION -> getString(R.string.debug_prf_probe_provider_configuration)
            PasskeyPrfProbeFailure.UNSUPPORTED -> getString(R.string.debug_prf_probe_unsupported)
            PasskeyPrfProbeFailure.SECURITY_REJECTED -> getString(R.string.debug_prf_probe_security_rejected)
            PasskeyPrfProbeFailure.NOT_ALLOWED -> getString(R.string.debug_prf_probe_not_allowed)
            PasskeyPrfProbeFailure.CONSTRAINT_REJECTED -> getString(R.string.debug_prf_probe_constraint_rejected)
            PasskeyPrfProbeFailure.INVALID_STATE -> getString(R.string.debug_prf_probe_invalid_state)
            PasskeyPrfProbeFailure.REQUEST_REJECTED -> getString(R.string.debug_prf_probe_request_rejected)
            PasskeyPrfProbeFailure.CREATION_FAILED -> getString(R.string.debug_prf_probe_creation_failed)
            PasskeyPrfProbeFailure.ASSERTION_FAILED -> getString(R.string.debug_prf_probe_assertion_failed)
        }
    }

    override fun onDestroy() {
        clearPendingAssertion()
        probeScope.cancel()
        super.onDestroy()
    }

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    private fun clearPendingAssertion() {
        pendingAssertion?.prfInput?.fill(0)
        pendingAssertion = null
    }

    private companion object {
        const val PRF_ASSERTION_TIMEOUT_MS = 30_000L
    }

    private data object PrfUnavailable : Exception()
    private data object ProbeFailure : Exception()

    private data class PendingAssertion(
        val credentialId: String,
        val prfInput: ByteArray,
    )

}
