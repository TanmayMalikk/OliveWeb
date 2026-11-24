package com.olive.app.data.account

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
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume

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
 * Android implementation of OpaqueClientProtocol using headless WebView and WASM.
 * Executes RustCrypto opaque-ke compiled WebAssembly bundle securely on the local device.
 */
internal class AndroidOpaqueClient(
    private val context: Context,
) : OpaqueClientProtocol {

    private var webView: WebView? = null

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
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            view.loadUrl("file:///android_asset/opaque/opaque.html")
        }

        // Wait until bridge confirms wasm readiness
        var ready = false
        var attempts = 0
        while (!ready && attempts < 50) {
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

    override suspend fun startRegistration(password: String): OpaqueClientProtocol.RegistrationStart {
        val res = runBridge(OpaqueBridgeMethod.START_REGISTRATION, listOf(password))
        return OpaqueClientProtocol.RegistrationStart(
            clientRegistrationState = res.getString("clientRegistrationState"),
            registrationRequest = res.getString("registrationRequest"),
        )
    }

    override suspend fun finishRegistration(
        state: String,
        response: String,
        password: String,
    ): OpaqueClientProtocol.RegistrationFinish {
        val res = runBridge(
            OpaqueBridgeMethod.FINISH_REGISTRATION,
            listOf(state, response, password),
        )
        val keyArr = res.getJSONArray("exportKey")
        val exportKey = ByteArray(keyArr.length()) { i -> keyArr.getInt(i).toByte() }
        return OpaqueClientProtocol.RegistrationFinish(
            registrationRecord = res.getString("registrationRecord"),
            exportKey = exportKey,
        )
    }

    override suspend fun startLogin(password: String): OpaqueClientProtocol.LoginStart {
        val res = runBridge(OpaqueBridgeMethod.START_LOGIN, listOf(password))
        return OpaqueClientProtocol.LoginStart(
            clientLoginState = res.getString("clientLoginState"),
            startLoginRequest = res.getString("startLoginRequest"),
        )
    }

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
        val keyArr = res.getJSONArray("exportKey")
        val exportKey = ByteArray(keyArr.length()) { i -> keyArr.getInt(i).toByte() }
        return OpaqueClientProtocol.LoginFinish(
            finishLoginRequest = res.getString("finishLoginRequest"),
            exportKey = exportKey,
        )
    }

    private companion object {
        const val BRIDGE_TIMEOUT_MILLIS = 15_000L
        const val BRIDGE_POLL_MILLIS = 50L
    }
}
