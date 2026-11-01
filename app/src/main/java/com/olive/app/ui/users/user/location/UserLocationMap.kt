// Safe-app users: shows live user location and navigation controls.
package com.olive.app.ui.users.user.location


import android.annotation.SuppressLint
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.olive.app.R
import com.olive.app.data.LocationRoute
import com.olive.app.data.MapLocationPoint
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

// user location map shows this interactive location map view so the user can view its details and use its actions.
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun InteractiveLocationMap(
    points: List<MapLocationPoint>, modifier: Modifier = Modifier,
    ownPointUid: String? = points.firstOrNull()?.uid,
    showOwnPointInBlue: Boolean = false,
    route: LocationRoute? = null,
    navigationActive: Boolean = false,
) {
    val description = stringResource(R.string.location_map_accessibility)
    val lastKnownLabel = stringResource(R.string.location_last_known)
    val webView = remember { arrayOfNulls<WebView>(1) }
    var loadState by remember { mutableStateOf(MapLoadState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    var following by remember { mutableStateOf(false) }
    var routeWasShown by remember { mutableStateOf(false) }
    val followTargetUid = if (navigationActive) ownPointUid
    else points.firstOrNull { it.live || it.lastKnown }?.uid ?: ownPointUid

    LaunchedEffect(attempt) {
        delay(15.seconds)
        if (loadState == MapLoadState.Loading) loadState = MapLoadState.Error
    }

    // Sends the latest user and safe-contact markers to the embedded map and optionally recentres it.
    fun updatePoints(view: WebView, moveCamera: Boolean) {
        val encoded = JSONArray().apply {
            points.forEach { point ->
                put(JSONObject().apply {
                    put("uid", point.uid)
                    put("name", point.name)
                    put(
                        "latitude",
                        point.latitude
                    )
                    put("longitude", point.longitude)
                    put(
                        "label",
                        if (point.lastKnown) "${point.name} · $lastKnownLabel" else point.name
                    )
                    put("live", point.live)
                    put("lastKnown", point.lastKnown)
                    put(
                        "own",
                        point.uid == ownPointUid
                    )
                    put("blue", showOwnPointInBlue && point.uid == ownPointUid && point.live)
                    point.accuracy?.let { put("accuracy", it.toDouble()) }
                    put("trail", JSONArray().apply {
                        point.trail.forEach { trailPoint ->
                            put(JSONObject().apply {
                                put("latitude", trailPoint.latitude)
                                put(
                                    "longitude",
                                    trailPoint.longitude
                                )
                                put("updatedAt", trailPoint.updatedAt)
                            })
                        }
                    })
                })
            }
        }
        view.evaluateJavascript("window.oliveSetPoints($encoded,$moveCamera);", null)
    }

    // Updates route for user location map.
    fun updateRoute(view: WebView, fitRoute: Boolean) {
        val encoded = JSONArray().apply {
            route?.coordinates?.forEach { coordinate ->
                put(JSONArray().put(coordinate.latitude).put(coordinate.longitude))
            }
        }
        view.evaluateJavascript("window.oliveSetRoute($encoded,$fitRoute);", null)
    }

    // User location map: Resizes the map to its current container.
    fun resizeMap(view: WebView) {
        if (view.height <= 0) return
        val cssHeight =
            (view.height / view.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        view.evaluateJavascript("window.oliveResizeTo&&window.oliveResizeTo($cssHeight);", null)
    }

    // Update resize map when information used by this screen changes.
    LaunchedEffect(following, followTargetUid, loadState, attempt) {
        if (loadState == MapLoadState.Ready) webView[0]?.evaluateJavascript(
            "window.oliveSetFollow($following,${JSONObject.quote(followTargetUid.orEmpty())});",
            null
        )
    }

    LaunchedEffect(navigationActive) {
        following = navigationActive
        if (!navigationActive) routeWasShown = false
    }

    // Update resize map when information used by this screen changes.
    LaunchedEffect(route, loadState, attempt) {
        if (loadState == MapLoadState.Ready) webView[0]?.let {
            updateRoute(it, route != null && !routeWasShown)
            routeWasShown = route != null
        }
    }

    Box(modifier.semantics { contentDescription = description }) {
        key(attempt) {
            AndroidView(
                factory = { context ->
                    object : WebView(context) {
                        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
                        private var touchDownX = 0f
                        private var touchDownY = 0f

                        // Responds when touch event for user location map.
                        override fun onTouchEvent(event: MotionEvent): Boolean {
                            // Keep map drags and zoom gestures from scrolling the page behind it.
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

                        // Performs click for user location map.
                        override fun performClick(): Boolean {
                            super.performClick()
                            return true
                        }
                    }.apply {
                        webView[0] = this
                        // The native Compose controls provide the map actions. Hiding the WebView's
                        // virtual nodes prevents touch exploration from bouncing between two layers.
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        setBackgroundColor(Color.rgb(241, 237, 228))
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = false
                        settings.allowContentAccess = false
                        settings.allowFileAccess = true
                        settings.setSupportZoom(false)
                        webChromeClient = object : WebChromeClient() {
                            // User location map: Responds when received title.
                            override fun onReceivedTitle(view: WebView, title: String?) {
                                if (title == MAP_READY_TITLE) loadState = MapLoadState.Ready
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            // Responds when page finished for user location map.
                            override fun onPageFinished(view: WebView, url: String) {
                                view.post { resizeMap(view) }
                            }

                            // Checks whether override url loading for user location map.
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ) = true

                            // User location map: Responds when received error.
                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError
                            ) {
                                if (request.isForMainFrame) loadState = MapLoadState.Error
                            }
                        }
                        addOnLayoutChangeListener { changed, _, top, _, bottom, _, oldTop, _, oldBottom ->
                            if (bottom - top != oldBottom - oldTop) resizeMap(changed as WebView)
                        }
                        loadUrl("file:///android_asset/map/map.html")
                    }
                },
                update = { view ->
                    resizeMap(view)
                    if (loadState == MapLoadState.Ready) updatePoints(
                        view,
                        false
                    )
                    if (loadState == MapLoadState.Ready) updateRoute(view, false)
                },
                onRelease = { view ->
                    view.stopLoading()
                    view.destroy()
                    if (webView[0] === view) webView[0] = null
                },
                modifier = Modifier.fillMaxSize().testTag("location_map_view")
            )
        }
        // Choose what on received error should do for the current value.
        when (loadState) {
            MapLoadState.Loading -> CircularProgressIndicator(
                Modifier.align(Alignment.Center).testTag("map_loading")
            )

            MapLoadState.Error -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.location_map_unavailable),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(
                    onClick = {
                        loadState = MapLoadState.Loading
                        attempt++
                    },
                    modifier = Modifier.testTag("map_retry")
                ) {
                    Text(stringResource(R.string.retry))
                }
            }

            MapLoadState.Ready -> Column(
                Modifier.align(Alignment.TopEnd).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = { webView[0]?.evaluateJavascript("window.oliveZoomIn();", null) },
                    modifier = Modifier.testTag("map_zoom_in")
                ) {
                    Icon(Icons.Outlined.Add, stringResource(R.string.location_zoom_in))
                }
                SmallFloatingActionButton(
                    onClick = { webView[0]?.evaluateJavascript("window.oliveZoomOut();", null) },
                    modifier = Modifier.testTag("map_zoom_out")
                ) {
                    Icon(Icons.Outlined.Remove, stringResource(R.string.location_zoom_out))
                }
                SmallFloatingActionButton(
                    onClick = { webView[0]?.evaluateJavascript("window.oliveRecenter();", null) },
                    modifier = Modifier.testTag("recenter_map")
                ) {
                    Icon(Icons.Outlined.MyLocation, stringResource(R.string.recenter_map))
                }
                SmallFloatingActionButton(
                    onClick = { if (followTargetUid != null) following = !following },
                    modifier = Modifier.testTag("follow_user_location")
                ) {
                    Icon(
                        if (following) Icons.Outlined.CenterFocusStrong else Icons.Outlined.LocationSearching,
                        stringResource(if (following) R.string.location_stop_following else R.string.location_follow_user)
                    )
                }
            }
        }
    }
}

private enum class MapLoadState { Loading, Ready, Error }

private const val MAP_READY_TITLE = "olive-map-ready"
