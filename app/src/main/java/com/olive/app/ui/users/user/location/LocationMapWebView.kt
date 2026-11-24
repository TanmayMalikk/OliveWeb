package com.olive.app.ui.users.user.location

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.olive.app.BuildConfig
import com.olive.app.data.location.LocationRoute
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.location.NearbySupportService
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Creates the configured WebView for Olive's bundled map. The caller assigns it before loading the page.
 *
 * @param context The Android context that owns the WebView.
 * @param onViewportCenter Receives updated center coordinates when enabled.
 * @param onSelectSupport Receives the selected directory resource ID when enabled.
 * @param onSelectLocation Receives the selected map coordinates when enabled.
 * @param onGesture Runs after the user moves or rotates the map.
 * @param onBearing Receives the map bearing in degrees.
 * @param onResize Updates the canvas dimensions after the WebView layout changes.
 * @param onError Receives the WebView that reported a map error.
 * @param onReady Receives the WebView once the map is ready to accept commands.
 * @return The map WebView before navigation starts.
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun createLocationMapWebView(
    context: Context,
    onViewportCenter: ((Double, Double) -> Unit)?,
    onSelectSupport: ((String) -> Unit)?,
    onSelectLocation: ((Double, Double) -> Unit)?,
    onGesture: () -> Unit,
    onBearing: (Float) -> Unit,
    onResize: (WebView) -> Unit,
    onError: (WebView) -> Unit,
    onReady: (WebView) -> Unit,
): WebView =
    object : WebView(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var touchDownX = 0f
        private var touchDownY = 0f

        /**
         * Claims parent touch interception during map drags and dispatches clicks on tap.
         *
         * @param event The touch event dispatched to the map WebView.
         * @return True when the map consumes the touch event.
         */
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.x
                    touchDownY = event.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_POINTER_DOWN ->
                    parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP -> {
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (abs(event.x - touchDownX) <= touchSlop &&
                        abs(event.y - touchDownY) <= touchSlop
                    ) {
                        performClick()
                    }
                }
                MotionEvent.ACTION_CANCEL ->
                    parent?.requestDisallowInterceptTouchEvent(false)
            }
            return super.onTouchEvent(event)
        }

        /**
         * Completes accessibility click handling for the map WebView.
         *
         * @return Whether the WebView handled the accessibility click.
         */
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }.apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        setBackgroundColor(Color.rgb(241, 237, 228))
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowContentAccess = false
        settings.allowFileAccess = false
        settings.setSupportZoom(false)
        onViewportCenter?.let { addJavascriptInterface(LocationSelectionBridge(it), "OliveMapViewport") }
        onSelectSupport?.let { addJavascriptInterface(SupportSelectionBridge(it), "OliveSupportSelection") }
        onSelectLocation?.let { addJavascriptInterface(LocationSelectionBridge(it), "OliveLocationSelection") }
        addJavascriptInterface(MapCameraBridge(onGesture, onBearing), "OliveMapCamera")
        webChromeClient = object : WebChromeClient() {
            /**
             * Uses the bundled page's title to signal map readiness or failure.
             *
             * @param view The WebView that reported the title.
             * @param title The title reported by the bundled page.
             */
            override fun onReceivedTitle(view: WebView, title: String?) {
                when (title) {
                    "olive-map-error" -> onError(view)
                    "olive-map-ready" -> onReady(view)
                }
            }
        }
        webViewClient = object : WebViewClient() {
            /**
             * Serves bundled map assets and cached tiles.
             *
             * @param view The WebView requesting the resource.
             * @param request The asset request.
             * @return The resource response if available.
             */
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                bundledMapAsset(context, request.url, request.method)

            /**
             * Updates the canvas size when the map page finishes loading.
             *
             * @param view The WebView that finished loading the page.
             * @param url The page URL.
             */
            override fun onPageFinished(view: WebView, url: String) {
                view.post { onResize(view) }
            }

            /**
             * Keeps map navigation within the bundled HTML.
             *
             * @param view The WebView where navigation was attempted.
             * @param request The navigation request to stop.
             * @return Always true to consume the request.
             */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            /**
             * Reports a main-frame load failure to the map screen.
             *
             * @param view The WebView reporting the failure.
             * @param request The resource that did not load.
             * @param error Android's details about the failure.
             */
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) onError(view)
            }
        }
        addOnLayoutChangeListener { view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) {
                onResize(view as WebView)
            }
        }
    }

/**
 * Loads the bundled map with the configured public Mapbox token when one is available.
 *
 * @receiver The WebView configured for the bundled map.
 */
internal fun WebView.loadOliveMap() {
    val publicToken = BuildConfig.MAPBOX_ACCESS_TOKEN.trim().takeIf { it.startsWith("pk.") }
    val url = if (publicToken == null) MAP_ASSET_URL else "$MAP_ASSET_URL#mapbox=${Uri.encode(publicToken)}"
    loadUrl(url)
}

/**
 * Sends user and contact points, including their trails, to the map.
 *
 * @receiver The WebView displaying the map.
 * @param points The points to draw.
 * @param ownPointUid The UID identifying the current user's point.
 * @param showOwnPointInBlue Whether to use a blue icon for the current user's live point.
 * @param lastKnownLabel The label appended to points that only have a last known location.
 * @param moveCamera Whether the map should reposition its camera.
 * @param detailsOnlyForSelectedPoint Whether to show details only for the selected point.
 */
internal fun WebView.updateMapPoints(
    points: List<MapLocationPoint>, ownPointUid: String?, showOwnPointInBlue: Boolean,
    lastKnownLabel: String, moveCamera: Boolean, detailsOnlyForSelectedPoint: Boolean,
) {
    val encoded = JSONArray().apply {
        points.forEach { point ->
            put(JSONObject().apply {
                put("uid", point.uid)
                put("name", point.name)
                put("latitude", point.latitude)
                put("longitude", point.longitude)
                put("label", if (point.lastKnown) "${point.name} · $lastKnownLabel" else point.name)
                put("live", point.live)
                put("lastKnown", point.lastKnown)
                put("own", point.uid == ownPointUid)
                put("blue", showOwnPointInBlue && point.uid == ownPointUid && point.live)
                point.accuracy?.let { put("accuracy", it.toDouble()) }
                put("trail", JSONArray().apply {
                    point.trail.forEach { trailPoint ->
                        put(JSONObject().apply {
                            put("latitude", trailPoint.latitude)
                            put("longitude", trailPoint.longitude)
                            put("updatedAt", trailPoint.updatedAt)
                        })
                    }
                })
            })
        }
    }
    evaluateJavascript(
        "window.oliveSetPoints($encoded,$moveCamera,$detailsOnlyForSelectedPoint);",
        null,
    )
}

/**
 * Sends support markers to the map.
 *
 * @receiver The WebView displaying the map.
 * @param supportServices The services to render as markers.
 * @param fit Whether to fit the map camera to those markers.
 */
internal fun WebView.updateMapServices(supportServices: List<NearbySupportService>, fit: Boolean) {
    val encoded = JSONArray().apply {
        supportServices.forEach { service ->
            put(JSONObject().apply {
                put("id", service.id)
                put("name", service.name)
                put("latitude", service.latitude)
                put("longitude", service.longitude)
                put("saved", service.saved)
            })
        }
    }
    evaluateJavascript("window.oliveSetServices($encoded,$fit);", null)
}

/**
 * Sends route coordinates to the map, or clears the route when none is active.
 *
 * @receiver The WebView displaying the map.
 * @param route The route to display, if there is one.
 * @param fitRoute Whether to fit the camera to the route.
 */
internal fun WebView.updateMapRoute(route: LocationRoute?, fitRoute: Boolean) {
    val encoded = JSONArray().apply {
        route?.coordinates?.forEach { coordinate ->
            put(JSONArray().put(coordinate.latitude).put(coordinate.longitude))
        }
    }
    evaluateJavascript("window.oliveSetRoute($encoded,$fitRoute);", null)
}

private class LocationSelectionBridge(private val onChange: (Double, Double) -> Unit) {
    private val mainHandler = Handler(Looper.getMainLooper())
    /**
     * Receives map coordinate selection callbacks from JavaScript after bounds validation.
     *
     * @param latitude The latitude in decimal degrees.
     * @param longitude The longitude in decimal degrees.
     */
    @JavascriptInterface
    fun selected(latitude: Double, longitude: Double) {
        if (latitude in -90.0..90.0 && longitude in -180.0..180.0) mainHandler.post { onChange(latitude, longitude) }
    }
}

private class SupportSelectionBridge(private val onSelected: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    /**
     * Dispatches a selected support directory resource ID from JavaScript to the main thread.
     *
     * @param id The identifier of the item to update or look up.
     */
    @JavascriptInterface
    fun selected(id: String) { if (id.length in 1..512) handler.post { onSelected(id) } }
}

private class MapCameraBridge(private val onGesture: () -> Unit, private val onBearing: (Float) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    /** Notifies the Compose host when the user pans or rotates the map manually. */
    @JavascriptInterface fun onUserGesture() { handler.post(onGesture) }
    /**
     * Forwards map bearing updates in degrees from JavaScript to update the compass needle.
     *
     * @param bearing The current bearing used to orient the map or marker.
     */
    @JavascriptInterface fun onBearingChanged(bearing: Double) { if (bearing.isFinite()) handler.post { onBearing(bearing.toFloat()) } }
}
