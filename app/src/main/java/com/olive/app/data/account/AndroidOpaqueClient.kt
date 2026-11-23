package com.olive.app.data.account

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android implementation of OpaqueClientProtocol using headless WebView and WASM.
 * Executes RustCrypto opaque-ke compiled WebAssembly bundle securely on the local device.
 */
internal class AndroidOpaqueClient(
    private val context: Context,
) : OpaqueClientProtocol {

    private var webView: WebView? = null
    private var isReady = false

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
                    cont.resume(Unit)
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
                kotlinx.coroutines.delay(50)
                attempts++
            }
        }
        check(ready) { "Failed to initialize OPAQUE WebAssembly runtime" }

        webView = view
        isReady = true
        view
    }

    private suspend fun evalJs(view: WebView, script: String): String = suspendCancellableCoroutine { cont ->
        view.evaluateJavascript(script) { result ->
            cont.resume(result?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"").orEmpty())
        }
    }

    private suspend fun runBridge(call: String): JSONObject {
        val view = ensureReady()
        return withContext(Dispatchers.Main) {
            val raw = evalJs(view, call)
            if (raw.isBlank() || raw == "null") JSONObject()
            else JSONObject(raw)
        }
    }

    override suspend fun startRegistration(password: String): OpaqueClientProtocol.RegistrationStart {
        val quoted = JSONObject.quote(password)
        val res = runBridge("window.opaqueBridge.startRegistration()")
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
        val qState = JSONObject.quote(state)
        val qResp = JSONObject.quote(response)
        val qPass = JSONObject.quote(password)
        val res = runBridge("window.opaqueBridge.finishRegistration(, , )")
        val keyArr = res.getJSONArray("exportKey")
        val exportKey = ByteArray(keyArr.length()) { i -> keyArr.getInt(i).toByte() }
        return OpaqueClientProtocol.RegistrationFinish(
            registrationRecord = res.getString("registrationRecord"),
            exportKey = exportKey,
        )
    }

    override suspend fun startLogin(password: String): OpaqueClientProtocol.LoginStart {
        val quoted = JSONObject.quote(password)
        val res = runBridge("window.opaqueBridge.startLogin()")
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
        val qState = JSONObject.quote(state)
        val qResp = JSONObject.quote(response)
        val qPass = JSONObject.quote(password)
        val res = runBridge("window.opaqueBridge.finishLogin(, , )")
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
}
