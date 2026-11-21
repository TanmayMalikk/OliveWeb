// Safe-app users: shows live user location and navigation controls.
package com.olive.app.ui.users.user.location


import com.olive.app.ui.shared.OliveSmallFloatingActionButton as SmallFloatingActionButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveTextButton as TextButton
import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.graphics.Color
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.os.Handler
import android.os.Looper
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.olive.app.R
import com.olive.app.BuildConfig
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.olive.app.data.location.LocationRoute
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.location.NearbySupportService
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.collections.sortedBy
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/**
 * User location map shows this interactive location map view so the user can view its details and use its actions.
 *
 * @param points Collection of points values to process.
 * @param modifier Compose modifier applied to the root layout.
 * @param ownPointUid Optional own point user ID supplied to [InteractiveLocationMap].
 * @param showOwnPointInBlue Whether to show own point in blue.
 * @param route Optional route supplied to [InteractiveLocationMap].
 * @param navigationActive Whether to apply navigation active.
 * @param detailsOnlyForSelectedPoint Whether to apply details only for selected point.
 * @param userSearchEnabled Whether to apply user search enabled.
 * @param initialFollowUid Optional initial follow user ID supplied to [InteractiveLocationMap].
 * @param selectedLocation Optional selected location supplied to [InteractiveLocationMap].
 * @param onSelectedLocationChange Callback invoked when selected location change changes or occurs.
 * @param supportServices Collection of support services values to process.
 * @param onSupportSelected Callback invoked when support selected changes or occurs.
 * @param onViewportCenterChanged Callback invoked when viewport center changed changes or occurs.
 */
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
    initialFollowUid: String? = null,
    selectedLocation: Pair<Double, Double>? = null,
    onSelectedLocationChange: ((Double, Double) -> Unit)? = null,
    supportServices: List<NearbySupportService> = emptyList(),
    onSupportSelected: ((NearbySupportService) -> Unit)? = null,
    onViewportCenterChanged: ((Double, Double) -> Unit)? = null,
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
    var supportWereFitted by remember { mutableStateOf(false) }
    val currentSupportServices by rememberUpdatedState(supportServices)
    val currentSupportSelection by rememberUpdatedState(onSupportSelected)
    val currentViewportCallback by rememberUpdatedState(onViewportCenterChanged)
    val followTargetUid = selectedFollowUid ?: if (navigationActive) ownPointUid
    else points.firstOrNull { it.live || it.lastKnown }?.uid ?: ownPointUid
    val matchingUsers = remember(points, searchQuery) {
        points.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
    }
    LaunchedEffect(initialFollowUid) {
        if (initialFollowUid != null) {
            selectedFollowUid = initialFollowUid
            following = true
        }
    }

    LaunchedEffect(attempt) {
        delay(15.seconds)
        if (loadState == MapLoadState.Loading) loadState = MapLoadState.Error
    }

    /**
     * Sends the latest user and safe-contact markers to the embedded map and optionally recentres it.
     *
     * @param view View supplied to [updatePoints].
     * @param moveCamera Whether to apply move camera.
     */
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

    /**
     * Updates support services.
     *
     * @param view View supplied to [updateSupportServices].
     */
    fun updateSupportServices(view: WebView) {
        val encoded = JSONArray().apply {
            supportServices.forEach { service -> put(JSONObject().apply {
                put("id", service.id); put("name", service.name)
                put("latitude", service.latitude); put("longitude", service.longitude)
            }) }
        }
        val fit = !supportWereFitted && supportServices.isNotEmpty() && !navigationActive
        view.evaluateJavascript("window.oliveSetServices($encoded,$fit);", null)
        if (fit) supportWereFitted = true
    }

    /**
     * Draws the current navigation route and can fit the camera around it.
     *
     * @param view View supplied to [updateRoute].
     * @param fitRoute Whether to apply fit route.
     */
    fun updateRoute(view: WebView, fitRoute: Boolean) {
        val encoded = JSONArray().apply {
            route?.coordinates?.forEach { coordinate ->
                put(JSONArray().put(coordinate.latitude).put(coordinate.longitude))
            }
        }
        view.evaluateJavascript("window.oliveSetRoute($encoded,$fitRoute);", null)
    }

    /**
     * Tells the map its visible height after the Compose layout changes.
     *
     * @param view View supplied to [resizeMap].
     */
    fun resizeMap(view: WebView) {
        if (view.height <= 0) return
        val cssHeight =
            (view.height / view.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
        view.evaluateJavascript("window.oliveResizeTo&&window.oliveResizeTo($cssHeight);", null)
    }

    /**
     * Keeps the incident pushpin in sync when an address suggestion changes the coordinates.
     *
     * @param view View supplied to [updateSelection].
     */
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
                "${selectedFollowUid != null && (userSearchEnabled || initialFollowUid != null)});",
            null
        )
    }

    LaunchedEffect(navigationActive) {
        if (initialFollowUid == null || navigationActive) following = navigationActive
        if (!navigationActive) routeWasShown = false
    }

    // Stops following when the selected user is no longer available in the organization feed.
    LaunchedEffect(points, selectedFollowUid) {
        if (selectedFollowUid != null && selectedFollowUid != initialFollowUid && points.none { it.uid == selectedFollowUid }) {
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

                        /**
                         * Lets map gestures work without dragging the Compose screen behind them.
                         *
                         * @param event Sensor, UI, or lifecycle event being processed.
                         * @return `true` if on touch event, or `false` otherwise.
                         */
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

                        /**
                         * Reports simple map taps as proper Android click events.
                         *
                         * @return `true` if perform click, or `false` otherwise.
                         */
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
                        settings.allowFileAccess = false
                        settings.setSupportZoom(false)
                        if (onViewportCenterChanged != null) addJavascriptInterface(
                            LocationSelectionBridge { latitude, longitude ->
                                currentViewportCallback?.invoke(latitude, longitude)
                            }, "OliveMapViewport",
                        )
                        if (onSupportSelected != null) addJavascriptInterface(
                            SupportSelectionBridge { id ->
                                currentSupportServices.firstOrNull { it.id == id }?.let { currentSupportSelection?.invoke(it) }
                            }, "OliveSupportSelection",
                        )
                        if (onSelectedLocationChange != null) {
                            addJavascriptInterface(
                                LocationSelectionBridge(onSelectedLocationChange),
                                "OliveLocationSelection",
                            )
                        }
                        webChromeClient = object : WebChromeClient() {
                            /**
                             * The bundled map uses its page title to report that JavaScript is ready.
                             *
                             * @param view View supplied to [onReceivedTitle].
                             * @param title Primary display title text, or null when not provided.
                             */
                            override fun onReceivedTitle(view: WebView, title: String?) {
                                if (webView[0] !== view) return
                                if (title == "olive-map-error") loadState = MapLoadState.Error
                                if (title == MAP_READY_TITLE) loadState = MapLoadState.Ready
                                if (title == MAP_READY_TITLE && onViewportCenterChanged != null) {
                                    view.evaluateJavascript("window.oliveReportMapCenter();", null)
                                }
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            /**
                             * Determines whether the app should intercept request.
                             *
                             * @param view View supplied to [shouldInterceptRequest].
                             * @param request Request supplied to [shouldInterceptRequest].
                             * @return `true` or `false` indicating should intercept request, or `null` if unknown.
                             */
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? = bundledMapAsset(context, request.url)

                            /**
                             * Matches the map height to its container after the page loads.
                             *
                             * @param view View supplied to [onPageFinished].
                             * @param url HTTPS URL to validate, fetch, or open.
                             */
                            override fun onPageFinished(view: WebView, url: String) {
                                view.post { resizeMap(view) }
                            }

                            /**
                             * Blocks the bundled map from opening any outside link.
                             *
                             * @param view View supplied to [shouldOverrideUrlLoading].
                             * @param request Request supplied to [shouldOverrideUrlLoading].
                             * @return `true` if should override URL loading, or `false` otherwise.
                             */
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ) = true

                            /**
                             * Shows the retry state when the main map page cannot load.
                             *
                             * @param view View supplied to [onReceivedError].
                             * @param request Request supplied to [onReceivedError].
                             * @param error Throwable or error message describing a failure.
                             */
                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError
                            ) {
                                if (webView[0] === view && request.isForMainFrame) loadState = MapLoadState.Error
                            }
                        }
                        addOnLayoutChangeListener { changed, _, top, _, bottom, _, oldTop, _, oldBottom ->
                            if (bottom - top != oldBottom - oldTop) resizeMap(changed as WebView)
                        }
                        val publicToken = BuildConfig.MAPBOX_ACCESS_TOKEN.trim().takeIf { it.startsWith("pk.") }
                        val url = if (publicToken == null) MAP_ASSET_URL else "$MAP_ASSET_URL#mapbox=${Uri.encode(publicToken)}"
                        loadUrl(url)
                    }
                },
                update = { view ->
                    resizeMap(view)
                    if (loadState == MapLoadState.Ready) {
                        updatePoints(view, false)
                        updateSupportServices(view)
                        if ((userSearchEnabled || initialFollowUid != null) && selectedFollowUid != null) {
                            view.evaluateJavascript(
                                "window.oliveSetFollow($following,${JSONObject.quote(selectedFollowUid)},false);",
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
        // Keep the map surface visible while it initializes; only show controls when ready.
        when {
            loadState == MapLoadState.Error -> Column(
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

            loadState == MapLoadState.Ready -> Column(
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

        if (userSearchEnabled && following) {
            points.firstOrNull { it.uid == selectedFollowUid }?.let { point ->
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth()
                        .testTag("followed_user_coordinates"),
                    shape = MaterialTheme.shapes.extraLarge,
                    shadowElevation = 6.dp,
                ) {
                    Column(
                        Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(point.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(if (point.live && !point.lastKnown) R.string.organization_map_live else R.string.location_last_known),
                            color = if (point.live && !point.lastKnown) OliveGreen else Muted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            stringResource(R.string.incident_latitude) + ": " + point.latitude.toString(),
                            modifier = Modifier.testTag("followed_user_latitude"),
                        )
                        Text(
                            stringResource(R.string.incident_longitude) + ": " + point.longitude.toString(),
                            modifier = Modifier.testTag("followed_user_longitude"),
                        )
                        point.accuracy?.takeIf { it.isFinite() && it >= 0 }?.let {
                            Text(stringResource(R.string.organization_map_accuracy, it.toDouble()), style = MaterialTheme.typography.bodySmall)
                        }
                        if (point.updatedAt > 0) Text(
                            stringResource(
                                R.string.organization_map_updated,
                                DateTimeFormatter.ofPattern("MMM d, HH:mm:ss").withZone(ZoneId.systemDefault())
                                    .format(Instant.ofEpochMilli(point.updatedAt)),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Muted,
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

    /**
     * Computes selected.
     *
     * @param latitude Latitude supplied to [selected].
     * @param longitude Longitude supplied to [selected].
     */
    @JavascriptInterface
    fun selected(latitude: Double, longitude: Double) {
        if (latitude in -90.0..90.0 && longitude in -180.0..180.0) {
            mainHandler.post { onChange(latitude, longitude) }
        }
    }

}

private class SupportSelectionBridge(private val onSelected: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    /**
     * Computes selected.
     *
     * @param id ID supplied to [selected].
     */
    @JavascriptInterface fun selected(id: String) {
        if (id.length in 1..512) handler.post { onSelected(id) }
    }
}

private enum class MapLoadState { Loading, Ready, Error }

private const val MAP_READY_TITLE = "olive-map-ready"
