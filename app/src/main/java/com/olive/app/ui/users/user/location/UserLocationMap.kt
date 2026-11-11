// Safe-app users: shows live user location and navigation controls.
package com.olive.app.ui.users.user.location


import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    detailsOnlyForSelectedPoint: Boolean = false,
    userSearchEnabled: Boolean = false,
    selectedLocation: Pair<Double, Double>? = null,
    onSelectedLocationChange: ((Double, Double) -> Unit)? = null,
) {
    val description = stringResource(R.string.location_map_accessibility)
    val lastKnownLabel = stringResource(R.string.location_last_known)
    val webView = remember { arrayOfNulls<WebView>(1) }
    var loadState by remember { mutableStateOf(MapLoadState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    var following by remember { mutableStateOf(false) }
    var selectedFollowUid by remember { mutableStateOf<String?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var routeWasShown by remember { mutableStateOf(false) }
    val followTargetUid = selectedFollowUid ?: if (navigationActive) ownPointUid
    else points.firstOrNull { it.live || it.lastKnown }?.uid ?: ownPointUid
    val matchingUsers = remember(points, searchQuery) {
        points.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
    }

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
        view.evaluateJavascript(
            "window.oliveSetPoints($encoded,$moveCamera,$detailsOnlyForSelectedPoint);",
            null,
        )
    }

    // Draws the current navigation route and can fit the camera around it.
    fun updateRoute(view: WebView, fitRoute: Boolean) {
        val encoded = JSONArray().apply {
            route?.coordinates?.forEach { coordinate ->
                put(JSONArray().put(coordinate.latitude).put(coordinate.longitude))
            }
        }
        view.evaluateJavascript("window.oliveSetRoute($encoded,$fitRoute);", null)
    }

    // Tells the map its visible height after the Compose layout changes.
    fun resizeMap(view: WebView) {
        if (view.height <= 0) return
        val cssHeight =
            (view.height / view.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        view.evaluateJavascript("window.oliveResizeTo&&window.oliveResizeTo($cssHeight);", null)
    }

    // Keeps the incident pushpin in sync when an address suggestion changes the coordinates.
    fun updateSelection(view: WebView) {
        val latitude = selectedLocation?.first ?: 0.0
        val longitude = selectedLocation?.second ?: 0.0
        view.evaluateJavascript(
            "window.oliveSetSelection(${onSelectedLocationChange != null},$latitude,$longitude);",
            null,
        )
    }

    // Tells the map which person to follow after navigation or loading changes.
    LaunchedEffect(following, followTargetUid, loadState, attempt) {
        if (loadState == MapLoadState.Ready) webView[0]?.evaluateJavascript(
            "window.oliveSetFollow(" +
                "$following,${JSONObject.quote(followTargetUid.orEmpty())}," +
                "${userSearchEnabled && selectedFollowUid != null});",
            null
        )
    }

    LaunchedEffect(navigationActive) {
        following = navigationActive
        if (!navigationActive) routeWasShown = false
    }

    // Stops following when the selected user is no longer available in the organization feed.
    LaunchedEffect(points, selectedFollowUid) {
        if (selectedFollowUid != null && points.none { it.uid == selectedFollowUid }) {
            selectedFollowUid = null
            following = false
        }
    }

    // Redraws a changed route and fits it once when navigation begins.
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

                        // Lets map gestures work without dragging the Compose screen behind them.
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

                        // Reports simple map taps as proper Android click events.
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
                        if (onSelectedLocationChange != null) {
                            addJavascriptInterface(
                                LocationSelectionBridge(onSelectedLocationChange),
                                "OliveLocationSelection",
                            )
                        }
                        webChromeClient = object : WebChromeClient() {
                            // The bundled map uses its page title to report that JavaScript is ready.
                            override fun onReceivedTitle(view: WebView, title: String?) {
                                if (title == MAP_READY_TITLE) loadState = MapLoadState.Ready
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            // Matches the map height to its container after the page loads.
                            override fun onPageFinished(view: WebView, url: String) {
                                view.post { resizeMap(view) }
                            }

                            // Blocks the bundled map from opening any outside link.
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ) = true

                            // Shows the retry state when the main map page cannot load.
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
                    if (loadState == MapLoadState.Ready) {
                        updatePoints(view, false)
                        if (userSearchEnabled && selectedFollowUid != null) {
                            view.evaluateJavascript(
                                "window.oliveSetFollow(true,${JSONObject.quote(selectedFollowUid)},false);",
                                null,
                            )
                        }
                    }
                    if (loadState == MapLoadState.Ready) updateRoute(view, false)
                    if (loadState == MapLoadState.Ready) updateSelection(view)
                },
                onRelease = { view ->
                    view.stopLoading()
                    view.destroy()
                    if (webView[0] === view) webView[0] = null
                },
                modifier = Modifier.fillMaxSize().testTag("location_map_view")
            )
        }
        // Shows map loading, retry, or nothing once the map is ready.
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
                if (userSearchEnabled) {
                    SmallFloatingActionButton(
                        onClick = { searchOpen = true },
                        modifier = Modifier.testTag("search_user_location")
                    ) {
                        Icon(Icons.Outlined.Search, stringResource(R.string.location_search_user))
                    }
                } else {
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

        if (searchOpen && userSearchEnabled) {
            AlertDialog(
                onDismissRequest = { searchOpen = false },
                title = { Text(stringResource(R.string.location_search_user)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it.take(80) },
                            label = { Text(stringResource(R.string.search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("location_user_search_query"),
                        )
                        if (matchingUsers.isEmpty()) {
                            Text(stringResource(R.string.location_search_no_users))
                        } else {
                            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                                items(matchingUsers, key = { it.uid }) { point ->
                                    TextButton(
                                        onClick = {
                                            selectedFollowUid = point.uid
                                            following = true
                                            searchOpen = false
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                            .testTag("location_user_${point.uid}"),
                                    ) {
                                        Text(point.name, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { searchOpen = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
                dismissButton = {
                    if (selectedFollowUid != null) {
                        TextButton(onClick = {
                            selectedFollowUid = null
                            following = false
                            searchOpen = false
                            webView[0]?.evaluateJavascript(
                                "window.oliveSetFollow(false,'');",
                                null,
                            )
                        }) {
                            Text(stringResource(R.string.location_stop_following))
                        }
                    }
                },
            )
        }
    }
}

// Passes a map tap or dragged pushpin back to the incident editor.
private class LocationSelectionBridge(
    private val onChange: (Double, Double) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun selected(latitude: Double, longitude: Double) {
        if (latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            mainHandler.post { onChange(latitude, longitude) }
        }
    }
}

private enum class MapLoadState { Loading, Ready, Error }

private const val MAP_READY_TITLE = "olive-map-ready"
