// Safe-app users: shows live Google-Maps-style Mapbox GL map and controls.
package com.olive.app.ui.users.user.location

import com.olive.app.ui.shared.OliveSmallFloatingActionButton as SmallFloatingActionButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveTextButton as TextButton
import android.annotation.SuppressLint
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.olive.app.BuildConfig
import com.olive.app.R
import com.olive.app.data.location.LocationRoute
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.location.NearbySupportService
import com.olive.app.ui.shared.Ink
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.OliveOutline
import com.olive.app.ui.shared.OlivePill
import com.olive.app.ui.shared.PaleOlive
import com.olive.app.ui.shared.Paper
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray
import org.json.JSONObject

/**
 * Interactive 60fps Mapbox GL location map with native vector rotation, pins, and route rendering.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun InteractiveLocationMap(
    points: List<MapLocationPoint>,
    modifier: Modifier = Modifier,
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
    headingUp: Boolean = false,
    headingDegrees: Float? = null,
    headingState: State<Float?>? = null,
    onToggleHeadingUp: (() -> Unit)? = null,
    compassEnabled: Boolean = true,
    fitSupportServices: Boolean = false,
    bottomControlsPadding: Dp = 28.dp,
    onUserGesture: (() -> Unit)? = null,
    showControls: Boolean = true,
) {
    val description = stringResource(R.string.location_map_accessibility)
    val lastKnownLabel = stringResource(R.string.location_last_known)
    val webView = remember { arrayOfNulls<WebView>(1) }
    var loadState by remember { mutableStateOf(MapLoadState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    var following by remember { mutableStateOf(initialFollowUid != null) }
    var selectedFollowUid by remember { mutableStateOf(initialFollowUid) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var routeWasShown by remember { mutableStateOf(false) }
    var supportWereFitted by remember { mutableStateOf(false) }
    val mapBearingState = remember { mutableFloatStateOf(0f) }
    val hasRotatedBearing by remember { derivedStateOf { abs(mapBearingState.floatValue) >= 2f } }
    val lastCssSize = remember { intArrayOf(0, 0) }

    val usesInternalCompass = onToggleHeadingUp == null && compassEnabled
    var internalHeadingUp by remember { mutableStateOf(false) }
    val headingReference = points.firstOrNull { it.uid == ownPointUid } ?: points.firstOrNull()
    val internalHeadingState = rememberDeviceHeadingState(
        enabled = usesInternalCompass && (internalHeadingUp || ownPointUid != null),
        latitude = headingReference?.latitude,
        longitude = headingReference?.longitude,
    )
    val fallbackHeadingState = rememberUpdatedState(headingDegrees)
    val activeHeadingState: State<Float?> =
        if (usesInternalCompass) internalHeadingState else (headingState ?: fallbackHeadingState)
    val effectiveHeadingUp = if (usesInternalCompass) internalHeadingUp else headingUp

    val currentSupportServices by rememberUpdatedState(supportServices)
    val currentSupportSelection by rememberUpdatedState(onSupportSelected)
    val currentViewportCallback by rememberUpdatedState(onViewportCenterChanged)
    val currentUserGestureCallback by rememberUpdatedState(onUserGesture)
    val currentHeadingUp by rememberUpdatedState(effectiveHeadingUp)
    val currentHeadingState by rememberUpdatedState(activeHeadingState)
    val currentNavigationActive by rememberUpdatedState(navigationActive)
    val currentUsesInternalCompass by rememberUpdatedState(usesInternalCompass)

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
            if (loadState == MapLoadState.Ready) {
                webView[0]?.evaluateJavascript(
                    "window.oliveSetFollow(true,${JSONObject.quote(initialFollowUid)},true);",
                    null,
                )
            }
        }
    }

    LaunchedEffect(attempt) {
        delay(15.seconds)
        if (loadState == MapLoadState.Loading) loadState = MapLoadState.Error
    }

    fun updatePoints(view: WebView, moveCamera: Boolean) {
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
        view.evaluateJavascript(
            "window.oliveSetPoints($encoded,$moveCamera,$detailsOnlyForSelectedPoint);",
            null,
        )
    }

    fun updateSupportServices(view: WebView) {
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
        val fit = fitSupportServices && !supportWereFitted && supportServices.isNotEmpty() && !navigationActive
        view.evaluateJavascript("window.oliveSetServices($encoded,$fit);", null)
        if (fit) supportWereFitted = true
    }

    fun updateRoute(view: WebView, fitRoute: Boolean) {
        val encoded = JSONArray().apply {
            route?.coordinates?.forEach { coordinate ->
                put(JSONArray().put(coordinate.latitude).put(coordinate.longitude))
            }
        }
        view.evaluateJavascript("window.oliveSetRoute($encoded,$fitRoute);", null)
    }

    fun resizeMapIfChanged(view: WebView) {
        if (view.height <= 0 || view.width <= 0) return
        val density = view.resources.displayMetrics.density
        val cssWidth = (view.width / density).roundToInt().coerceAtLeast(1)
        val cssHeight = (view.height / density).roundToInt().coerceAtLeast(1)
        if (lastCssSize[0] == cssWidth && lastCssSize[1] == cssHeight) return
        lastCssSize[0] = cssWidth
        lastCssSize[1] = cssHeight
        view.evaluateJavascript("window.oliveResizeTo&&window.oliveResizeTo($cssHeight);", null)
    }

    fun updateSelection(view: WebView) {
        val latitude = selectedLocation?.first ?: 0.0
        val longitude = selectedLocation?.second ?: 0.0
        view.evaluateJavascript(
            "window.oliveSetSelection(${onSelectedLocationChange != null},$latitude,$longitude);",
            null,
        )
    }

    // Targeted synchronization prevents 60ms compass updates from triggering WebGL resize/redraft churn.
    LaunchedEffect(points, ownPointUid, showOwnPointInBlue, detailsOnlyForSelectedPoint, loadState, attempt) {
        if (loadState == MapLoadState.Ready) webView[0]?.let { updatePoints(it, false) }
    }

    LaunchedEffect(fitSupportServices) {
        if (fitSupportServices) supportWereFitted = false
    }

    LaunchedEffect(supportServices, fitSupportServices, navigationActive, loadState, attempt) {
        if (loadState == MapLoadState.Ready) webView[0]?.let { updateSupportServices(it) }
    }

    LaunchedEffect(selectedLocation, onSelectedLocationChange != null, loadState, attempt) {
        if (loadState == MapLoadState.Ready) webView[0]?.let { updateSelection(it) }
    }

    LaunchedEffect(following, followTargetUid, loadState, attempt) {
        if (loadState == MapLoadState.Ready) webView[0]?.evaluateJavascript(
            "window.oliveSetFollow(" +
                "$following,${JSONObject.quote(followTargetUid.orEmpty())}," +
                "${selectedFollowUid != null && (userSearchEnabled || initialFollowUid != null)});",
            null,
        )
    }

    LaunchedEffect(navigationActive) {
        if (initialFollowUid == null || navigationActive) following = navigationActive
        if (!navigationActive) routeWasShown = false
    }

    LaunchedEffect(points, selectedFollowUid) {
        if (selectedFollowUid != null && selectedFollowUid != initialFollowUid && points.none { it.uid == selectedFollowUid }) {
            selectedFollowUid = null
            following = false
        }
    }

    LaunchedEffect(route, loadState, attempt) {
        if (loadState == MapLoadState.Ready) webView[0]?.let {
            updateRoute(it, route != null && !routeWasShown)
            routeWasShown = route != null
        }
    }

    LaunchedEffect(loadState, attempt, effectiveHeadingUp, navigationActive, activeHeadingState) {
        if (loadState != MapLoadState.Ready) return@LaunchedEffect
        snapshotFlow { activeHeadingState.value }
            .distinctUntilChanged()
            .collect { degrees ->
                val degArg = degrees?.takeIf { it.isFinite() }?.toString() ?: "null"
                webView[0]?.evaluateJavascript(
                    "window.oliveSetHeading($degArg,$effectiveHeadingUp,$navigationActive);",
                    null,
                )
                if (effectiveHeadingUp && degrees != null && degrees.isFinite()) {
                    mapBearingState.floatValue = degrees
                }
            }
    }

    val showCompassControl = compassEnabled || onToggleHeadingUp != null || hasRotatedBearing

    @Composable
    fun CompassFab() {
        SmallFloatingActionButton(
            onClick = {
                if (!effectiveHeadingUp && abs(mapBearingState.floatValue) >= 2f) {
                    mapBearingState.floatValue = 0f
                    webView[0]?.evaluateJavascript("window.oliveResetNorth();", null)
                } else {
                    if (!effectiveHeadingUp && !userSearchEnabled && followTargetUid != null) {
                        following = true
                    }
                    if (usesInternalCompass) {
                        if (internalHeadingUp) mapBearingState.floatValue = 0f
                        internalHeadingUp = !internalHeadingUp
                    } else {
                        if (effectiveHeadingUp) mapBearingState.floatValue = 0f
                        onToggleHeadingUp?.invoke()
                    }
                }
            },
            containerColor = if (effectiveHeadingUp) PaleOlive else Paper,
            contentColor = Ink,
            modifier = Modifier
                .border(
                    BorderStroke(1.dp, if (effectiveHeadingUp) OliveGreen else OliveOutline.copy(alpha = 0.55f)),
                    OlivePill,
                )
                .testTag("map_compass_mode"),
        ) {
            CompassNeedleIcon(
                bearingDegrees = mapBearingState.floatValue,
                contentDescription = stringResource(
                    if (effectiveHeadingUp) R.string.location_compass_toggle_heading_desc
                    else R.string.location_compass_toggle_north_desc
                ),
            )
        }
    }

    BoxWithConstraints(modifier.semantics { contentDescription = description }) {
        val compactHeight = maxHeight < 310.dp || (maxHeight - bottomControlsPadding) < 250.dp
        key(attempt) {
            AndroidView(
                factory = { context ->
                    object : WebView(context) {
                        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
                        private var touchDownX = 0f
                        private var touchDownY = 0f

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

                        override fun performClick(): Boolean {
                            super.performClick()
                            return true
                        }
                    }.apply {
                        webView[0] = this
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
                            },
                            "OliveMapViewport",
                        )
                        if (onSupportSelected != null) addJavascriptInterface(
                            SupportSelectionBridge { id ->
                                currentSupportServices.firstOrNull { it.id == id }?.let { currentSupportSelection?.invoke(it) }
                            },
                            "OliveSupportSelection",
                        )
                        if (onSelectedLocationChange != null) addJavascriptInterface(
                            LocationSelectionBridge(onSelectedLocationChange),
                            "OliveLocationSelection",
                        )
                        addJavascriptInterface(
                            MapCameraBridge(
                                onGesture = {
                                    following = false
                                    if (currentUsesInternalCompass) internalHeadingUp = false
                                    currentUserGestureCallback?.invoke()
                                },
                                onBearing = { bearing ->
                                    mapBearingState.floatValue = bearing
                                },
                            ),
                            "OliveMapCamera",
                        )
                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(view: WebView, title: String?) {
                                if (webView[0] !== view) return
                                if (title == "olive-map-error") loadState = MapLoadState.Error
                                if (title == MAP_READY_TITLE) {
                                    loadState = MapLoadState.Ready
                                    if (following && followTargetUid != null) {
                                        view.evaluateJavascript(
                                            "window.oliveSetFollow(true,${JSONObject.quote(followTargetUid)},${selectedFollowUid != null && (userSearchEnabled || initialFollowUid != null)});",
                                            null,
                                        )
                                    }
                                    updatePoints(view, moveCamera = !following || followTargetUid == null)
                                    updateSupportServices(view)
                                    updateRoute(view, route != null)
                                    updateSelection(view)
                                    val degArg = currentHeadingState.value?.takeIf { it.isFinite() }?.toString() ?: "null"
                                    view.evaluateJavascript(
                                        "window.oliveSetHeading($degArg,$currentHeadingUp,$currentNavigationActive);",
                                        null,
                                    )
                                    if (onViewportCenterChanged != null) {
                                        view.evaluateJavascript("window.oliveReportMapCenter();", null)
                                    }
                                }
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? = bundledMapAsset(context, request.url, request.method)

                            override fun onPageFinished(view: WebView, url: String) {
                                view.post { resizeMapIfChanged(view) }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ) = true

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                if (webView[0] === view && request.isForMainFrame) loadState = MapLoadState.Error
                            }
                        }
                        addOnLayoutChangeListener { changed, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                            if ((right - left) != (oldRight - oldLeft) || (bottom - top) != (oldBottom - oldTop)) {
                                resizeMapIfChanged(changed as WebView)
                            }
                        }
                        val publicToken = BuildConfig.MAPBOX_ACCESS_TOKEN.trim().takeIf { it.startsWith("pk.") }
                        val url = if (publicToken == null) MAP_ASSET_URL else "$MAP_ASSET_URL#mapbox=${Uri.encode(publicToken)}"
                        loadUrl(url)
                    }
                },
                update = { },
                onRelease = { view ->
                    view.stopLoading()
                    view.destroy()
                    if (webView[0] === view) webView[0] = null
                },
                modifier = Modifier.fillMaxSize().testTag("location_map_view"),
            )
        }

        when {
            loadState == MapLoadState.Error -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.location_map_unavailable), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = {
                        loadState = MapLoadState.Loading
                        lastCssSize[0] = 0
                        lastCssSize[1] = 0
                        attempt++
                    },
                    modifier = Modifier.testTag("map_retry"),
                ) {
                    Text(stringResource(R.string.retry))
                }
            }

            loadState == MapLoadState.Ready -> if (showControls) {
                val mapFabModifier = Modifier.border(
                    BorderStroke(1.dp, OliveOutline.copy(alpha = 0.55f)),
                    OlivePill,
                )
                Column(
                    Modifier.align(Alignment.TopEnd).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SmallFloatingActionButton(
                        onClick = { webView[0]?.evaluateJavascript("window.oliveZoomIn();", null) },
                        containerColor = Paper,
                        contentColor = Ink,
                        modifier = mapFabModifier.testTag("map_zoom_in"),
                    ) {
                        Icon(Icons.Outlined.Add, stringResource(R.string.location_zoom_in))
                    }
                    SmallFloatingActionButton(
                        onClick = { webView[0]?.evaluateJavascript("window.oliveZoomOut();", null) },
                        containerColor = Paper,
                        contentColor = Ink,
                        modifier = mapFabModifier.testTag("map_zoom_out"),
                    ) {
                        Icon(Icons.Outlined.Remove, stringResource(R.string.location_zoom_out))
                    }
                    if (userSearchEnabled) {
                        SmallFloatingActionButton(
                            onClick = { searchOpen = true },
                            containerColor = Paper,
                            contentColor = Ink,
                            modifier = mapFabModifier.testTag("search_user_location"),
                        ) {
                            Icon(Icons.Outlined.Search, stringResource(R.string.location_search_user))
                        }
                    }
                    if (showCompassControl && (!compactHeight || userSearchEnabled)) {
                        CompassFab()
                    }
                }

                if (!userSearchEnabled) {
                    @Composable
                    fun RecenterFab() {
                        SmallFloatingActionButton(
                            onClick = {
                                if (followTargetUid != null) following = true
                                webView[0]?.evaluateJavascript("window.oliveRecenter();", null)
                            },
                            containerColor = Paper,
                            contentColor = Ink,
                            modifier = mapFabModifier.testTag("recenter_map"),
                        ) {
                            Icon(Icons.Outlined.MyLocation, stringResource(R.string.recenter_map))
                        }
                    }

                    @Composable
                    fun FollowFab() {
                        SmallFloatingActionButton(
                            onClick = { if (followTargetUid != null) following = !following },
                            containerColor = if (following) OliveGreen else Paper,
                            contentColor = if (following) Paper else Ink,
                            modifier = Modifier
                                .border(
                                    BorderStroke(1.dp, if (following) OliveGreen else OliveOutline.copy(alpha = 0.55f)),
                                    OlivePill,
                                )
                                .testTag("follow_user_location"),
                        ) {
                            Icon(
                                if (following) Icons.Outlined.CenterFocusStrong else Icons.Outlined.LocationSearching,
                                stringResource(if (following) R.string.location_stop_following else R.string.location_follow_user),
                            )
                        }
                    }

                    if (compactHeight) {
                        Row(
                            Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = bottomControlsPadding),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (showCompassControl) CompassFab()
                            RecenterFab()
                            FollowFab()
                        }
                    } else {
                        Column(
                            Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = bottomControlsPadding),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            RecenterFab()
                            FollowFab()
                        }
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
                    color = Paper,
                    contentColor = Ink,
                    border = BorderStroke(1.dp, OliveOutline.copy(alpha = 0.55f)),
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
                                            webView[0]?.evaluateJavascript(
                                                "window.oliveSetFollow(true,${JSONObject.quote(point.uid)},true);",
                                                null,
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth().testTag("location_user_${point.uid}"),
                                    ) {
                                        Text(point.name, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { searchOpen = false }) { Text(stringResource(R.string.cancel)) }
                },
                dismissButton = {
                    if (selectedFollowUid != null) {
                        TextButton(onClick = {
                            selectedFollowUid = null
                            following = false
                            searchOpen = false
                            webView[0]?.evaluateJavascript("window.oliveSetFollow(false,'');", null)
                        }) {
                            Text(stringResource(R.string.location_stop_following))
                        }
                    }
                },
            )
        }
    }
}

private class LocationSelectionBridge(private val onChange: (Double, Double) -> Unit) {
    private val mainHandler = Handler(Looper.getMainLooper())
    @JavascriptInterface
    fun selected(latitude: Double, longitude: Double) {
        if (latitude in -90.0..90.0 && longitude in -180.0..180.0) mainHandler.post { onChange(latitude, longitude) }
    }
}

private class SupportSelectionBridge(private val onSelected: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    @JavascriptInterface
    fun selected(id: String) { if (id.length in 1..512) handler.post { onSelected(id) } }
}

private class MapCameraBridge(private val onGesture: () -> Unit, private val onBearing: (Float) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    @JavascriptInterface fun onUserGesture() { handler.post(onGesture) }
    @JavascriptInterface fun onBearingChanged(bearing: Double) { if (bearing.isFinite()) handler.post { onBearing(bearing.toFloat()) } }
}

private enum class MapLoadState { Loading, Ready, Error }

private const val MAP_READY_TITLE = "olive-map-ready"

@Composable
private fun CompassNeedleIcon(bearingDegrees: Float, contentDescription: String) {
    Box(
        modifier = Modifier.size(26.dp).semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = -bearingDegrees }) {
            val cx = size.width / 2f; val cy = size.height / 2f
            val dialRadius = size.minDimension * 0.44f; val needleLength = size.minDimension * 0.36f; val halfWidth = size.minDimension * 0.14f
            drawCircle(OliveGreen.copy(alpha = 0.28f), dialRadius, Offset(cx, cy), style = Stroke(width = 1.5.dp.toPx()))
            drawPath(Path().apply { moveTo(cx, cy - needleLength); lineTo(cx + halfWidth, cy); lineTo(cx - halfWidth, cy); close() }, ComposeColor(0xFFD32F2F))
            drawPath(Path().apply { moveTo(cx, cy + needleLength); lineTo(cx + halfWidth, cy); lineTo(cx - halfWidth, cy); close() }, Muted.copy(alpha = 0.72f))
            drawCircle(ComposeColor.White, 2.dp.toPx(), Offset(cx, cy))
            drawCircle(OliveGreen, 1.dp.toPx(), Offset(cx, cy))
        }
    }
}
