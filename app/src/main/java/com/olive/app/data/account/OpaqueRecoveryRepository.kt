package com.olive.app.data.account

import com.olive.app.security.AccountRecoveryKey
import com.olive.app.security.OpaquePasswordRecoveryEnvelope
import java.util.UUID
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import com.olive.app.security.AccountKeysetEnvelope
import com.olive.app.security.AccountKeysetException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import kotlin.coroutines.resume

/**
 * Protocol interface abstracting client-side OPAQUE operations.
 * Allows pure cryptographic testing without Android WebView / FFI dependencies.
 */
internal interface OpaqueClientProtocol {
    data class RegistrationStart(
        val clientRegistrationState: String,
        val registrationRequest: String,
    )

    data class RegistrationFinish(
        val registrationRecord: String,
        val exportKey: ByteArray,
    )

    data class LoginStart(
        val clientLoginState: String,
        val startLoginRequest: String,
    )

    data class LoginFinish(
        val finishLoginRequest: String,
        val exportKey: ByteArray,
    )

    /**
     * Begins OPAQUE password-recovery registration on the client.
     *
     * @param password User-chosen recovery password.
     * @return Client registration state and opaque request payload for the server.
     */
    suspend fun startRegistration(password: String): RegistrationStart

    /**
     * Completes OPAQUE registration from the server response and derives the export key.
     *
     * @param state Client registration state returned by [startRegistration].
     * @param response Server registration response payload.
     * @param password User-chosen recovery password.
     * @return Completed registration record and export key for envelope encryption.
     */
    suspend fun finishRegistration(state: String, response: String, password: String): RegistrationFinish

    /**
     * Begins an OPAQUE login ceremony to recover an account key on a new device.
     *
     * @param password User-entered recovery password.
     * @return Client login state and start-login request payload.
     */
    suspend fun startLogin(password: String): LoginStart

    /**
     * Completes an OPAQUE login ceremony and derives the export key when the password matches.
     *
     * @param state Client login state returned by [startLogin].
     * @param response Server login response payload.
     * @param password User-entered recovery password.
     * @return Completed login proof and export key, or `null` if verification failed locally.
     */
    suspend fun finishLogin(state: String, response: String, password: String): LoginFinish?
}

/**
 * Repository for zero-knowledge password recovery.
 *
 * Facilitates the client-side OPAQUE exchange with Cloud Functions to enroll, rotate, and unlock
 * the Account Recovery Key (ARK). The plaintext password and export key never leave the client.
 */
internal class OpaqueRecoveryRepository(
    private val callable: AccountRecoveryCallable = FirebaseAccountRecoveryCallable(),
    private val authenticator: AccountRecoveryAuthenticator = FirebaseAccountRecoveryAuthenticator(),
    private val opaqueClient: OpaqueClientProtocol,
) {
    /**
     * Enrolls an OPAQUE password recovery failsafe for an existing unlocked ARK.
     *
     * @param accountId Firebase UID of the signed-in account.
     * @param password User-chosen recovery password.
     * @param recoveryKey Unlocked [AccountRecoveryKey] to wrap in the recovery envelope.
     * @return The enrolled [OpaquePasswordRecoveryEnvelope] stored on the server.
     */
    suspend fun enroll(
        accountId: String,
        password: String,
        recoveryKey: AccountRecoveryKey,
    ): OpaquePasswordRecoveryEnvelope {
        require(password.isNotBlank()) { "Password cannot be blank" }
        authenticator.reauthenticate(accountId, password)

        val regStart = opaqueClient.startRegistration(password)
        val beginResult = response(callable.call(BEGIN_REGISTRATION, mapOf(
            "registrationRequest" to regStart.registrationRequest,
        )))
        val registrationResponse = beginResult["registrationResponse"] as? String
            ?: error("Invalid server registration response")

        val regFinish = opaqueClient.finishRegistration(
            regStart.clientRegistrationState,
            registrationResponse,
            password,
        )

        val recordId = "rec_" + UUID.randomUUID().toString().replace("-", "")
        val envelope = try {
            OpaquePasswordRecoveryEnvelope.create(
                accountId = accountId,
                opaqueRecordId = recordId,
                exportKey = regFinish.exportKey,
                recoveryKey = recoveryKey,
            )
        } finally {
            regFinish.exportKey.fill(0)
        }

        val finishResult = response(callable.call(FINISH_REGISTRATION, mapOf(
            "opaqueRecordId" to recordId,
            "registrationRecord" to regFinish.registrationRecord,
            "envelope" to envelope.toMap(),
        )))
        check(finishResult["success"] == true) { "Failed to complete password recovery registration" }

        return envelope
    }

    /**
     * Unlocks and recovers the Account Recovery Key using the account password on a new device.
     *
     * @param accountId Firebase UID of the signed-in account.
     * @param password Recovery password entered by the user.
     * @return The decrypted [AccountRecoveryKey].
     */
    suspend fun unlock(
        accountId: String,
        password: String,
    ): AccountRecoveryKey {
        require(password.isNotBlank()) { "Password cannot be blank" }

        val loginStart = opaqueClient.startLogin(password)
        val beginResult = response(callable.call(BEGIN_UNLOCK, mapOf(
            "startLoginRequest" to loginStart.startLoginRequest,
        )))
        val sessionId = beginResult["sessionId"] as? String ?: error("Missing unlock session ID")
        val loginResponse = beginResult["loginResponse"] as? String ?: error("Missing server login response")

        val loginFinish = opaqueClient.finishLogin(
            loginStart.clientLoginState,
            loginResponse,
            password,
        ) ?: throw IllegalArgumentException("Incorrect recovery password")

        val finishResult = response(callable.call(FINISH_UNLOCK, mapOf(
            "sessionId" to sessionId,
            "finishLoginRequest" to loginFinish.finishLoginRequest,
        )))

        val envelopeMap = finishResult["envelope"] as? Map<*, *>
            ?: error("Missing password recovery envelope from server")
        val envelope = OpaquePasswordRecoveryEnvelope.fromMap(envelopeMap)

        return try {
            envelope.open(accountId, envelope.opaqueRecordId, loginFinish.exportKey)
        } finally {
            loginFinish.exportKey.fill(0)
        }
    }

    /**
     * Rotates the recovery password and updates the recovery envelope atomically.
     *
     * @param accountId Firebase UID of the signed-in account.
     * @param oldPassword Current recovery password to reauthenticate and unlock with if needed.
     * @param newPassword New recovery password to enroll.
     * @param currentRecoveryKey Already unlocked recovery key, or `null` to unlock via [oldPassword].
     * @param firebasePassword Current Firebase credential, which may already have changed.
     * @return The newly enrolled [OpaquePasswordRecoveryEnvelope].
     */
    suspend fun rotate(
        accountId: String,
        oldPassword: String,
        newPassword: String,
        currentRecoveryKey: AccountRecoveryKey? = null,
        firebasePassword: String = oldPassword,
    ): OpaquePasswordRecoveryEnvelope {
        require(newPassword.isNotBlank()) { "New password cannot be blank" }
        authenticator.reauthenticate(accountId, firebasePassword)

        val activeKey = currentRecoveryKey ?: unlock(accountId, oldPassword)

        val regStart = opaqueClient.startRegistration(newPassword)
        val beginResult = response(callable.call(BEGIN_REGISTRATION, mapOf(
            "registrationRequest" to regStart.registrationRequest,
        )))
        val registrationResponse = beginResult["registrationResponse"] as? String
            ?: error("Invalid server registration response")

        val regFinish = opaqueClient.finishRegistration(
            regStart.clientRegistrationState,
            registrationResponse,
            newPassword,
        )

        val recordId = "rec_" + UUID.randomUUID().toString().replace("-", "")
        val newEnvelope = try {
            OpaquePasswordRecoveryEnvelope.create(
                accountId = accountId,
                opaqueRecordId = recordId,
                exportKey = regFinish.exportKey,
                recoveryKey = activeKey,
            )
        } finally {
            regFinish.exportKey.fill(0)
        }

        val rotateResult = response(callable.call(ROTATE, mapOf(
            "opaqueRecordId" to recordId,
            "registrationRecord" to regFinish.registrationRecord,
            "envelope" to newEnvelope.toMap(),
        )))
        check(rotateResult["success"] == true) { "Failed to rotate recovery password" }

        return newEnvelope
    }

    /**
     * Validates and normalizes a callable map response from the recovery backend.
     *
     * @param value Raw data payload returned by [AccountRecoveryCallable.call].
     * @return String-keyed map extracted from [value].
     */
    private fun response(value: Any?): Map<String, Any?> =
        (value as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }
            ?: error("Invalid response from recovery service")

    companion object {
        private const val BEGIN_REGISTRATION = "opaqueRecoveryBeginRegistration"
        private const val FINISH_REGISTRATION = "opaqueRecoveryFinishRegistration"
        private const val BEGIN_UNLOCK = "opaqueRecoveryBeginUnlock"
        private const val FINISH_UNLOCK = "opaqueRecoveryFinishUnlock"
        private const val ROTATE = "opaqueRecoveryRotate"
    }
}

/** Reads and writes only the ARK-encrypted account-keyset envelope; it never receives an ARK. */
internal class AccountKeysetRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    /**
     * Publishes a ciphertext-only envelope for the authenticated envelope owner.
     *
     * @param envelope The encrypted envelope carrying the account keyset.
     */
    suspend fun publish(envelope: AccountKeysetEnvelope) {
        require(auth.currentUser?.uid == envelope.accountId) { "Sign in again before saving account keys" }
        val payload = mapOf(
            "version" to envelope.version,
            "accountId" to envelope.accountId,
            "keyId" to envelope.keyId,
            "epoch" to envelope.epoch,
            "algorithm" to envelope.algorithm,
            "publicKey" to envelope.publicKeyBase64,
            "iv" to envelope.ivBase64,
            "ciphertext" to envelope.ciphertextBase64,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        val directory = mapOf(
            "ownerUid" to envelope.accountId,
            "keyId" to envelope.keyId,
            "epoch" to envelope.epoch,
            "algorithm" to envelope.algorithm,
            "publicKey" to envelope.publicKeyBase64,
            "updatedAt" to FieldValue.serverTimestamp(),
        )
        firestore.batch().apply {
            set(firestore.collection(COLLECTION).document(envelope.accountId), payload, SetOptions.merge())
            set(firestore.collection(DIRECTORY).document(envelope.accountId), directory)
        }.commit().await()
    }

    /**
     * Fetches the latest envelope from the server, bypassing Firestore's cache for recovery.
     *
     * @param accountId The account ID that owns the key or record.
     * @return The latest server envelope, or null if the account has not published a keyset.
     */
    suspend fun fetch(accountId: String): AccountKeysetEnvelope? {
        require(auth.currentUser?.uid == accountId) { "Sign in again before recovering account keys" }
        val snapshot = firestore.collection(COLLECTION).document(accountId).get(Source.SERVER).await()
        if (!snapshot.exists()) return null
        return try {
            AccountKeysetEnvelope.fromJson(JSONObject().apply {
                snapshot.data.orEmpty().forEach { (name, value) ->
                    if (name != "updatedAt") put(name, value)
                }
            })
        } catch (failure: Exception) {
            throw AccountKeysetException("Account keyset is invalid or unavailable", failure)
        }
    }

    /**
     * Checks from the server whether OPAQUE password recovery is enrolled for [accountId].
     *
     * @param accountId Firebase UID of the signed-in account.
     * @return True when the server account keyset contains a password recovery envelope.
     */
    suspend fun isPasswordRecoveryEnrolled(accountId: String): Boolean {
        require(auth.currentUser?.uid == accountId) { "Sign in again before checking recovery" }
        val snapshot = firestore.collection(COLLECTION).document(accountId).get(Source.SERVER).await()
        if (!snapshot.exists()) return false
        val data = snapshot.data ?: return false
        return data["passwordRecovery"] is Map<*, *>
    }

    companion object {
        private const val COLLECTION = "accountKeysets"
        private const val DIRECTORY = "accountKeyDirectories"
    }
}

internal fun interface AccountRecoveryCallable {
    /**
     * Invokes the named recovery Cloud Function with serialized request parameters.
     *
     * @param name The Firebase callable function name.
     * @param data The data payload to read or deserialize.
     * @return The payload returned by the callable function, which may be null.
     */
    suspend fun call(name: String, data: Map<String, Any?>): Any?
}

/** Confirms a sensitive recovery-factor action with Firebase without exposing a password to a callable. */
internal fun interface AccountRecoveryAuthenticator {
    /**
     * Reauthenticates [accountId] with Firebase using [password] before managing recovery.
     *
     * @param accountId Expected Firebase UID of the signed-in user.
     * @param password Account password used to verify fresh Firebase credentials.
     */
    suspend fun reauthenticate(accountId: String, password: String)
}

internal class FirebaseAccountRecoveryAuthenticator(
    private val authProvider: () -> FirebaseAuth = FirebaseAuth::getInstance,
) : AccountRecoveryAuthenticator {
    /**
     * Reauthenticates the active Firebase user with [password] and refreshes their ID token.
     *
     * @param accountId Expected Firebase UID of the signed-in user.
     * @param password Account password used to create an email credential.
     */
    override suspend fun reauthenticate(accountId: String, password: String) {
        require(password.isNotEmpty()) { "Enter your account password" }
        val auth = authProvider()
        val user = auth.currentUser ?: error("Sign in again before managing account recovery")
        check(user.uid == accountId) { "Sign in again before managing account recovery" }
        val email = user.email ?: error("This account has no sign-in email")
        user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
        // Force the newly-issued auth_time claim into the callable's token.
        user.getIdToken(true).await()
    }
}

/** Raised when Firebase App Check verification fails before invoking a recovery callable. */
internal class AccountRecoveryDeviceVerificationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class FirebaseAccountRecoveryCallable(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("northamerica-northeast1"),
    private val verifyAppCheck: suspend () -> Unit = {
        FirebaseAppCheck.getInstance().getAppCheckToken(false).await()
    },
) : AccountRecoveryCallable {
    /**
     * Verifies App Check, calls the named Firebase callable function, and returns its payload.
     *
     * @param name The Firebase callable function name.
     * @param data The data payload to read or deserialize.
     * @return The payload returned by the callable function, which may be null.
     */
    override suspend fun call(name: String, data: Map<String, Any?>): Any? {
        try {
            verifyAppCheck()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            throw AccountRecoveryDeviceVerificationException("Firebase App Check verification failed", failure)
        }
        return functions.getHttpsCallable(name).call(data).await().data
    }
}

internal enum class OpaqueBridgeMethod(internal val functionName: String) {
    START_REGISTRATION("startRegistration"),
    FINISH_REGISTRATION("finishRegistration"),
    START_LOGIN("startLogin"),
    FINISH_LOGIN("finishLogin"),
}

internal sealed class OpaqueBridgeResult {
    data object Pending : OpaqueBridgeResult()
    data class Success(val payload: String) : OpaqueBridgeResult()
    data object Failure : OpaqueBridgeResult()
}

internal object OpaqueBridgeScripts {
    /**
     * Builds one injection-safe asynchronous OPAQUE invocation.
     *
     * @param requestId unique identifier used to isolate concurrent bridge operations.
     * @param method allowlisted OPAQUE operation to execute.
     * @param arguments plaintext protocol inputs that remain local to the WebView.
     * @return JavaScript that records only the resolved response or a bounded failure marker.
     */
    fun invoke(requestId: String, method: OpaqueBridgeMethod, arguments: List<String>): String {
        val encodedId = JSONObject.quote(requestId)
        val encodedArguments = arguments.joinToString(",") { JSONObject.quote(it) }
        return """
            (() => {
              const results = window.__oliveOpaqueResults ||
                (window.__oliveOpaqueResults = Object.create(null));
              Promise.resolve()
                .then(() => window.opaqueBridge.${method.functionName}($encodedArguments))
                .then(value => { results[$encodedId] = { ok: true, value: value }; })
                .catch(() => { results[$encodedId] = { ok: false }; });
              return true;
            })()
        """.trimIndent()
    }

    /**
     * Builds a non-destructive lookup for one asynchronous bridge result.
     *
     * @param requestId identifier assigned when the bridge operation started.
     * @return JavaScript that serializes the isolated result or the pending marker.
     */
    fun read(requestId: String): String {
        val encodedId = JSONObject.quote(requestId)
        return "JSON.stringify((window.__oliveOpaqueResults || Object.create(null))[$encodedId] || null)"
    }

    /**
     * Builds cleanup JavaScript for one completed or cancelled bridge operation.
     *
     * @param requestId identifier whose result must be removed from WebView memory.
     * @return JavaScript that deletes only the matching result entry.
     */
    fun clear(requestId: String): String {
        val encodedId = JSONObject.quote(requestId)
        return """
            (() => {
              const results = window.__oliveOpaqueResults;
              if (results) delete results[$encodedId];
              return true;
            })()
        """.trimIndent()
    }

    /**
     * Decodes the nested JSON string returned by Android WebView evaluation.
     *
     * @param raw raw value supplied to the WebView evaluation callback.
     * @return pending, successful payload, or bounded failure without secret details.
     */
    fun decodeResult(raw: String): OpaqueBridgeResult {
        if (raw.isBlank() || raw == "null") return OpaqueBridgeResult.Pending
        val decoded = JSONArray("[$raw]").opt(0)
        if (decoded == null || decoded == JSONObject.NULL || decoded == "null") {
            return OpaqueBridgeResult.Pending
        }
        val result = JSONObject(decoded.toString())
        if (!result.optBoolean("ok", false) || !result.has("value")) {
            return OpaqueBridgeResult.Failure
        }
        return OpaqueBridgeResult.Success(result.getString("value"))
    }
}

/**
 * Awaits a Promise-backed OPAQUE response through a bounded polling callback.
 *
 * @param timeoutMillis maximum time allowed for the local cryptographic operation.
 * @param pollMillis delay between non-blocking result checks.
 * @param read obtains the latest isolated WebView result.
 * @return resolved OPAQUE payload parsed as JSON.
 */
internal suspend fun awaitOpaqueBridgePayload(
    timeoutMillis: Long,
    pollMillis: Long,
    read: suspend () -> String,
): JSONObject {
    require(timeoutMillis > 0) { "OPAQUE bridge timeout must be positive" }
    require(pollMillis > 0) { "OPAQUE bridge poll interval must be positive" }
    return try {
        withTimeout(timeoutMillis) {
            while (true) {
                when (val result = OpaqueBridgeScripts.decodeResult(read())) {
                    OpaqueBridgeResult.Pending -> delay(pollMillis)
                    OpaqueBridgeResult.Failure -> error("OPAQUE client operation failed")
                    is OpaqueBridgeResult.Success -> return@withTimeout JSONObject(result.payload)
                }
            }
            error("Unreachable OPAQUE bridge state")
        }
    } catch (_: TimeoutCancellationException) {
        error("OPAQUE client operation timed out")
    }
}

/**
 * Decodes a base64url-encoded OPAQUE export key returned by the WebView bridge payload.
 *
 * @param payload JSON payload produced by the bundled OPAQUE client bridge.
 * @return raw export key bytes used locally to wrap or unwrap the recovery envelope.
 */
internal fun decodeOpaqueExportKey(payload: JSONObject): ByteArray {
    val encoded = payload.optString("exportKey", "")
    require(encoded.isNotBlank()) { "Missing OPAQUE export key" }
    return try {
        java.util.Base64.getUrlDecoder().decode(encoded)
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid OPAQUE export key encoding", failure)
    }
}

/**
 * Android implementation of OpaqueClientProtocol using headless WebView and WASM.
 * Executes RustCrypto opaque-ke compiled WebAssembly bundle securely on the local device.
 */
internal class AndroidOpaqueClient(
    private val context: Context,
) : OpaqueClientProtocol {

    private var webView: WebView? = null

    /**
     * Lazily initializes the offscreen WebView hosting the bundled OPAQUE WebAssembly bridge.
     *
     * @return Ready [WebView] instance once `window.opaqueBridge.isReady` evaluates to true.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureReady(): WebView = withContext(Dispatchers.Main) {
        webView?.let { return@withContext it }
        val view = WebView(context.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
        }

        suspendCancellableCoroutine<Unit> { cont ->
            view.webViewClient = object : WebViewClient() {
                /**
                 * Resumes waiting coroutine when the local OPAQUE bridge HTML finishes loading.
                 *
                 * @param view WebView that finished loading.
                 * @param url Loaded page URL, if supplied by Android.
                 */
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            view.loadUrl("file:///android_asset/opaque/opaque.html")
        }

        // Wait until bridge confirms wasm readiness (up to 5 seconds for cold start)
        var ready = false
        var attempts = 0
        while (!ready && attempts < 100) {
            val eval = evalJs(view, "window.opaqueBridge && window.opaqueBridge.isReady")
            if (eval == "true") {
                ready = true
            } else {
                delay(50)
                attempts++
            }
        }
        check(ready) { "Failed to initialize OPAQUE WebAssembly runtime" }

        webView = view
        view
    }

    /**
     * Evaluates one local bridge expression without logging its inputs or result.
     *
     * @param view isolated WebView hosting the bundled OPAQUE runtime.
     * @param script JavaScript expression to evaluate.
     * @return raw value supplied by Android's evaluation callback.
     */
    private suspend fun evalJs(view: WebView, script: String): String = suspendCancellableCoroutine { cont ->
        view.evaluateJavascript(script) { result ->
            if (cont.isActive) cont.resume(result.orEmpty())
        }
    }

    /**
     * Executes one asynchronous OPAQUE operation and consumes its isolated result.
     *
     * @param method allowlisted bridge method.
     * @param arguments local protocol inputs passed to that method in order.
     * @return resolved JSON payload produced by the bundled OPAQUE client.
     */
    private suspend fun runBridge(method: OpaqueBridgeMethod, arguments: List<String>): JSONObject {
        val view = ensureReady()
        return withContext(Dispatchers.Main) {
            val requestId = UUID.randomUUID().toString()
            try {
                evalJs(view, OpaqueBridgeScripts.invoke(requestId, method, arguments))
                awaitOpaqueBridgePayload(BRIDGE_TIMEOUT_MILLIS, BRIDGE_POLL_MILLIS) {
                    evalJs(view, OpaqueBridgeScripts.read(requestId))
                }
            } finally {
                withContext(NonCancellable) {
                    try {
                        evalJs(view, OpaqueBridgeScripts.clear(requestId))
                    } catch (_: Exception) {
                        // Cleanup must not replace the original bridge failure or cancellation.
                    }
                }
            }
        }
    }

    /**
     * Starts an OPAQUE registration flow in the bundled WASM bridge.
     *
     * @param password User-chosen recovery password.
     * @return Serialized client registration state and registration request payload.
     */
    override suspend fun startRegistration(password: String): OpaqueClientProtocol.RegistrationStart {
        val res = runBridge(OpaqueBridgeMethod.START_REGISTRATION, listOf(password))
        return OpaqueClientProtocol.RegistrationStart(
            clientRegistrationState = res.getString("clientRegistrationState"),
            registrationRequest = res.getString("registrationRequest"),
        )
    }

    /**
     * Completes an OPAQUE registration flow and decodes the derived export key.
     *
     * @param state Serialized client state returned by [startRegistration].
     * @param response Server registration response string.
     * @param password User-chosen recovery password.
     * @return Serialized registration record and decoded 32/64-byte export key.
     */
    override suspend fun finishRegistration(
        state: String,
        response: String,
        password: String,
    ): OpaqueClientProtocol.RegistrationFinish {
        val res = runBridge(
            OpaqueBridgeMethod.FINISH_REGISTRATION,
            listOf(state, response, password),
        )
        return OpaqueClientProtocol.RegistrationFinish(
            registrationRecord = res.getString("registrationRecord"),
            exportKey = decodeOpaqueExportKey(res),
        )
    }

    /**
     * Starts an OPAQUE login flow in the bundled WASM bridge.
     *
     * @param password User-entered recovery password.
     * @return Serialized client login state and start-login request payload.
     */
    override suspend fun startLogin(password: String): OpaqueClientProtocol.LoginStart {
        val res = runBridge(OpaqueBridgeMethod.START_LOGIN, listOf(password))
        return OpaqueClientProtocol.LoginStart(
            clientLoginState = res.getString("clientLoginState"),
            startLoginRequest = res.getString("startLoginRequest"),
        )
    }

    /**
     * Completes an OPAQUE login flow and returns the decoded export key on password match.
     *
     * @param state Serialized client login state returned by [startLogin].
     * @param response Server login response string.
     * @param password User-entered recovery password.
     * @return Final login request and export key, or `null` when the password does not match.
     */
    override suspend fun finishLogin(
        state: String,
        response: String,
        password: String,
    ): OpaqueClientProtocol.LoginFinish? {
        val res = runBridge(
            OpaqueBridgeMethod.FINISH_LOGIN,
            listOf(state, response, password),
        )
        if (res.optString("error") == "incorrect_password" || !res.has("exportKey")) {
            return null
        }
        return OpaqueClientProtocol.LoginFinish(
            finishLoginRequest = res.getString("finishLoginRequest"),
            exportKey = decodeOpaqueExportKey(res),
        )
    }

    private companion object {
        const val BRIDGE_TIMEOUT_MILLIS = 15_000L
        const val BRIDGE_POLL_MILLIS = 50L
    }
}
