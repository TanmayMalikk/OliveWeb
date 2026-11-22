// Safe-app users: manages user location actions.
package com.olive.app.ui.users.user.location


import android.content.Context
import android.location.Location
import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.UserProfile
import com.olive.app.data.location.DeviceCoordinates
import com.olive.app.data.location.DeviceLocation
import com.olive.app.data.location.DeviceLocationUpdates
import com.olive.app.data.location.AddressCoordinateResolver
import com.olive.app.data.location.AndroidAddressCoordinateResolver
import com.olive.app.data.location.LocationRoute
import com.olive.app.data.location.LocationSharingDataSource
import com.olive.app.data.location.LocationTrailPoint
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.location.MapboxDirectionsClient
import com.olive.app.data.location.RouteProfile
import com.olive.app.data.location.SavedLocationUnavailableException
import com.olive.app.data.location.distanceFromRouteMeters
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.messaging.Friend
import com.olive.app.location.LiveLocationController
import com.olive.app.location.LiveLocationState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds


internal data class LocationMapConfiguration(
    val identity: AccountIdentity,
    val profile: UserProfile,
    val friends: List<Friend>,
    val localMode: Boolean,
    val youLabel: String,
    val savedLocationLabel: String,
)

internal data class LocationMapState(
    val ownerUid: String? = null,
    val ownPoint: MapLocationPoint? = null,
    val livePoint: MapLocationPoint? = null,
    val contacts: List<MapLocationPoint> = emptyList(),
    val selected: Set<String> = emptySet(),
    val liveActive: Boolean = false,
    val expiresAt: Long = 0L,
    val staticSharing: Boolean = false,
    val working: Boolean = false,
    val error: String? = null,
    val navigationTargetUid: String? = null,
    val privateDestination: MapLocationPoint? = null,
    val route: LocationRoute? = null,
    val navigating: Boolean = false,
    val routeWorking: Boolean = false,
    val routeError: String? = null,
    val locating: Boolean = false,
    val ownBearing: Float? = null,
    val ownSpeedMps: Float? = null,
    val routeProfile: RouteProfile = RouteProfile.DRIVING,
) {
    /**
     * Chooses the user's own map marker from live or saved location data.
     *
     * @param role Account role determining workspace permissions.
     * @return The resulting displayed own.
     */
    fun displayedOwn(role: AccountRole) =
        if (role == AccountRole.USER) livePoint ?: ownPoint else ownPoint
}

/** Coordinates map data, subscriptions and sharing while keeping Android/Firebase work out of Compose. */
internal class LocationMapController(
    context: Context,
    private val scope: CoroutineScope,
    private val repository: LocationSharingDataSource?,
    private val addressResolver: AddressCoordinateResolver = AndroidAddressCoordinateResolver(context.applicationContext),
    private val requestRoute: (suspend (MapLocationPoint, MapLocationPoint) -> LocationRoute?)? = DEFAULT_ROUTE_REQUEST,
) {
    private val applicationContext = context.applicationContext
    private var locationJob: Job? = null
    private var liveLocationUpdates: DeviceLocationUpdates? = null
    private var navigationJob: Job? = null
    private var expiryJob: Job? = null
    private var subscription: ChatSubscription? = null
    private var generation = 0
    private var pendingDestination: MapLocationPoint? = null
    private var pendingAutoStart = false
    private val stateMutableFlow = MutableStateFlow(LocationMapState())
    val stateFlow: StateFlow<LocationMapState> = stateMutableFlow.asStateFlow()
    private var stateSnapshot by mutableStateOf(stateMutableFlow.value)
    var state: LocationMapState
        get() = stateSnapshot
        private set(value) {
            stateMutableFlow.value = value
            stateSnapshot = value
        }

    init {
        scope.launch {
            LiveLocationState.current.collectLatest {
                state = state.copy(livePoint = it)
            }
        }
    }

    /**
     * Opens a saved user or trusted-contact marker on the map.
     *
     * @param configuration Configuration supplied to [open].
     * @param requestPermission Function used to transform or provide request permission.
     */
    fun open(configuration: LocationMapConfiguration, requestPermission: suspend () -> Boolean) {
        closeMap()
        val token = ++generation
        val uid = configuration.identity.uid
        val friendIds = configuration.friends.mapTo(mutableSetOf()) { it.uid }
        val queuedDestination = pendingDestination
        pendingDestination = null
        state = LocationMapState(
            ownerUid = uid,
            locating = true,
            livePoint = LiveLocationState.current.value,
            selected = LiveLocationController.selected(applicationContext).intersect(friendIds),
            liveActive = LiveLocationController.active(applicationContext),
            expiresAt = LiveLocationController.expiresAt(applicationContext),
            staticSharing = repository?.staticSharingEnabled(uid) == true,
            privateDestination = queuedDestination,
        )
        if (!configuration.localMode) {
            subscription = if (configuration.profile.role == AccountRole.USER)
                repository?.observeStaticContacts(
                    uid,
                    configuration.friends
                ) { if (token == generation) state = state.copy(contacts = it) }
            else repository?.observeLiveFriends(
                uid,
                configuration.friends
            ) {
                if (token == generation) {
                    val target = state.navigationTargetUid?.takeIf { targetUid ->
                        targetUid == state.privateDestination?.uid || it.any { point -> point.uid == targetUid && point.live }
                    } ?: it.firstOrNull { point -> point.live }?.uid
                    state = state.copy(
                        contacts = it,
                        navigationTargetUid = target,
                        route = state.route.takeIf { target != null },
                        navigating = state.navigating && target != null,
                        routeWorking = state.routeWorking && target != null,
                    )
                }
            }
        }
        locationJob = scope.launch {
            try {
                if (requestPermission()) {
                    DeviceLocation.currentCoordinates(applicationContext)?.let { fix ->
                        applyLocationFix(token, uid, configuration.youLabel, fix)
                    }
                    if (token == generation && state.locating) state = state.copy(locating = false)
                    val updates = runCatching {
                        DeviceLocation.startUpdates(
                            applicationContext,
                            intervalMillis = 1_500L,
                            minimumDistanceMeters = 1f,
                        ) { fix ->
                            applyLocationFix(token, uid, configuration.youLabel, fix)
                        }
                    }.getOrNull()
                    if (updates != null) {
                        liveLocationUpdates = updates
                        awaitCancellation()
                    } else {
                        while (isActive) {
                            delay(MAP_LOCATION_REFRESH_MILLIS.milliseconds)
                            DeviceLocation.currentCoordinates(applicationContext)?.let { fix ->
                                applyLocationFix(token, uid, configuration.youLabel, fix)
                            }
                            if (token == generation && state.locating) state = state.copy(locating = false)
                        }
                    }
                } else if (configuration.profile.role == AccountRole.SAFE_CONTACT) {
                    val coordinates = addressResolver.coordinates(configuration.profile.address)
                    coordinates?.let { (latitude, longitude) ->
                        // Apply the map result only if it belongs to the newest open request.
                        if (token == generation) {
                            state = state.copy(
                                ownPoint = MapLocationPoint(
                                    uid,
                                    configuration.savedLocationLabel,
                                    latitude,
                                    longitude,
                                    System.currentTimeMillis(),
                                    false
                                )
                            )
                        }
                    }
                }
            } finally {
                if (token == generation && state.locating) state = state.copy(locating = false)
            }
        }
        if (
            configuration.profile.role == AccountRole.SAFE_CONTACT &&
            state.staticSharing &&
            !configuration.localMode
        ) updateStaticSharing(configuration, true)
        watchExpiry()
    }

    private fun applyLocationFix(token: Int, uid: String, youLabel: String, fix: DeviceCoordinates) {
        if (token != generation) return
        val previous = state.ownPoint
        val trail = previous?.trail.orEmpty()
            .filter { it.updatedAt >= System.currentTimeMillis() - TRAIL_WINDOW_MILLIS }
            .toMutableList()
        val moved = previous == null || FloatArray(1).also { result ->
            Location.distanceBetween(previous.latitude, previous.longitude, fix.latitude, fix.longitude, result)
        }[0] >= MIN_TRAIL_DISTANCE_METERS
        if (moved && trail.lastOrNull()?.updatedAt != fix.timestamp) {
            trail += LocationTrailPoint(fix.latitude, fix.longitude, fix.timestamp)
        }
        state = state.copy(
            ownPoint = MapLocationPoint(
                uid,
                youLabel,
                fix.latitude,
                fix.longitude,
                fix.timestamp,
                true,
                fix.accuracy,
                trail = trail.takeLast(MAX_TRAIL_POINTS),
            ),
            ownBearing = fix.bearing ?: state.ownBearing,
            ownSpeedMps = fix.speedMetersPerSecond ?: state.ownSpeedMps,
        )
    }

    /**
     * User location controller: Stops map.
     */
    fun closeMap() {
        generation++
        runCatching { liveLocationUpdates?.stop() }
        liveLocationUpdates = null
        locationJob?.cancel()
        locationJob = null
        navigationJob?.cancel()
        navigationJob = null
        expiryJob?.cancel()
        expiryJob = null
        subscription?.close()
        subscription = null
        state = state.copy(privateDestination = null, route = null, navigating = false, routeWorking = false)
    }

    /**
     * Clears the selected route, target, and navigation message.
     */
    fun clear() {
        closeMap()
        state = LocationMapState()
    }

    /**
     * Selects which trusted contact or user the map should route toward.
     *
     * @param uid Firebase UID of the target user account.
     */
    fun selectNavigationTarget(uid: String) {
        if (!state.navigating && state.contacts.any { it.uid == uid && it.live })
            state = state.copy(navigationTargetUid = uid, privateDestination = null, routeError = null)
    }

    /**
     * Queues a private destination before navigating to the map screen so [open] preserves it.
     */
    fun queuePrivateDestination(
        name: String,
        latitude: Double,
        longitude: Double,
        autoStart: Boolean = true,
    ) {
        pendingDestination = MapLocationPoint(
            "private-route-destination",
            name,
            latitude,
            longitude,
            0L,
            false,
            null,
        )
        pendingAutoStart = autoStart
    }

    /**
     * Returns true once if a queued destination is ready for automatic route guidance.
     */
    fun consumePendingAutoStart(): Boolean {
        if (!pendingAutoStart || state.privateDestination == null || state.ownPoint == null) return false
        pendingAutoStart = false
        return true
    }

    /**
     * Switches between driving and walking directions.
     */
    fun setRouteProfile(profile: RouteProfile) {
        if (state.navigating || state.routeProfile == profile) return
        state = state.copy(routeProfile = profile)
    }

    /**
     * Selects private destination.
     *
     * @param name Field, key, or display name.
     * @param latitude Latitude supplied to [selectPrivateDestination].
     * @param longitude Longitude supplied to [selectPrivateDestination].
     */
    fun selectPrivateDestination(name: String, latitude: Double, longitude: Double) {
        if (state.navigating) return
        state = state.copy(
            privateDestination = MapLocationPoint("private-route-destination", name, latitude, longitude, 0L, false, null),
            routeError = null,
        )
    }

    /**
     * Clears any manually entered destination and restores a live-sender target when available.
     */
    fun clearPrivateDestination() {
        if (state.navigating) return
        val restoredTarget = if (state.navigationTargetUid == state.privateDestination?.uid) {
            state.contacts.firstOrNull { it.live }?.uid
        } else {
            state.navigationTargetUid
        }
        state = state.copy(
            privateDestination = null,
            navigationTargetUid = restoredTarget,
            routeError = null,
        )
    }

    /**
     * Requests a route to the selected person and starts map navigation.
     *
     * @param notConfiguredMessage Not configured message supplied to [startNavigation].
     * @param unavailableMessage Unavailable message supplied to [startNavigation].
     * @param failedMessage Failed message supplied to [startNavigation].
     */
    fun startNavigation(
        notConfiguredMessage: String,
        unavailableMessage: String,
        failedMessage: String,
    ) {
        if (state.navigating || state.routeWorking) return
        val useDefaultDirections = requestRoute === DEFAULT_ROUTE_REQUEST
        val directions: (suspend (MapLocationPoint, MapLocationPoint) -> LocationRoute?)? = if (useDefaultDirections) {
            if (MapboxDirectionsClient.configured) {
                { origin, target ->
                    MapboxDirectionsClient.route(
                        origin.latitude,
                        origin.longitude,
                        target.latitude,
                        target.longitude,
                        state.routeProfile,
                    )
                }
            } else null
        } else requestRoute
        if (directions == null) {
            state = state.copy(routeError = notConfiguredMessage)
            return
        }
        val targetUid = state.privateDestination?.uid ?: state.navigationTargetUid ?: state.contacts.firstOrNull { it.live }?.uid
        if (state.ownPoint == null || targetUid == null) {
            state = state.copy(routeError = unavailableMessage)
            return
        }
        state = state.copy(
            navigating = true,
            navigationTargetUid = targetUid,
            routeWorking = true,
            routeError = null,
        )
        navigationJob?.cancel()
        navigationJob = scope.launch {
            var lastRoutedTarget: MapLocationPoint? = null
            var lastRequestAt = 0L
            // User location controller: Keeps start navigation active while is active and state navigating.
            while (isActive && state.navigating) {
                val origin = state.ownPoint
                val target = state.privateDestination ?: state.contacts.firstOrNull {
                    it.uid == state.navigationTargetUid && it.live
                }
                if (origin == null || target == null) {
                    state = state.copy(routeWorking = false, routeError = unavailableMessage)
                } else if (routeNeedsRefresh(origin, target, lastRoutedTarget) &&
                    System.currentTimeMillis() - lastRequestAt >= MIN_ROUTE_REQUEST_INTERVAL_MILLIS
                ) {
                    lastRequestAt = System.currentTimeMillis()
                    state = state.copy(routeWorking = true, routeError = null)
                    runCatching {
                        directions(origin, target)
                    }.onSuccess { route ->
                        state = if (route == null) state.copy(
                            routeWorking = false,
                            routeError = failedMessage,
                        ) else state.copy(
                            route = route,
                            routeWorking = false,
                            routeError = null,
                        ).also {
                            lastRoutedTarget = target
                        }
                    }.onFailure {
                        if (it is CancellationException) throw it
                        state = state.copy(routeWorking = false, routeError = failedMessage)
                    }
                } else {
                    state = state.copy(routeWorking = false)
                }
                delay(NAVIGATION_CHECK_MILLIS.milliseconds)
            }
        }
    }

    /**
     * Stops route guidance and clears the selected destination.
     */
    fun stopNavigation() {
        navigationJob?.cancel()
        navigationJob = null
        state = state.copy(
            navigating = false,
            routeWorking = false,
            route = null,
            routeError = null,
        )
    }

    /**
     * Routes needs refresh for user location controller.
     *
     * @param origin Origin supplied to [routeNeedsRefresh].
     * @param target Target destination or output object to write to.
     * @param lastTarget Optional last target supplied to [routeNeedsRefresh].
     * @return `true` if route needs refresh, or `false` otherwise.
     */
    private fun routeNeedsRefresh(
        origin: MapLocationPoint,
        target: MapLocationPoint,
        lastTarget: MapLocationPoint?,
    ): Boolean {
        val route = state.route ?: return true
        return lastTarget == null || distanceBetween(target, lastTarget) >= TARGET_REROUTE_METERS || distanceFromRouteMeters(origin.latitude, origin.longitude, route) >= OFF_ROUTE_METERS
    }

    /**
     * Calculates the straight-line distance between two map points.
     *
     * @param first First supplied to [distanceBetween].
     * @param second Second supplied to [distanceBetween].
     * @return The computed distance between numeric value.
     */
    private fun distanceBetween(first: MapLocationPoint, second: MapLocationPoint): Float {
        val result = FloatArray(1)
        Location.distanceBetween(
            first.latitude,
            first.longitude,
            second.latitude,
            second.longitude,
            result,
        )
        return result[0]
    }

    /**
     * Adds or removes one safe contact from the next live-location share.
     *
     * @param uid Firebase UID of the target user account.
     * @param enabled Whether this feature or control is currently enabled.
     */
    fun selectContact(uid: String, enabled: Boolean) {
        if (state.liveActive || state.working) return
        val updated = if (enabled) state.selected + uid else state.selected - uid
        state = state.copy(selected = updated)
    }

    /**
     * Starts the user's live-location sharing session.
     *
     * @param configuration Configuration supplied to [startSharing].
     * @param durationMillis Duration or timestamp for duration, in milliseconds.
     * @param requestPermission Function used to transform or provide request permission.
     * @param permissionError Permission error supplied to [startSharing].
     * @param startError Start error supplied to [startSharing].
     */
    fun startSharing(
        configuration: LocationMapConfiguration,
        durationMillis: Long,
        requestPermission: suspend () -> Boolean,
        permissionError: String,
        startError: String
    ) {
        val recipients = configuration.friends.filter { it.uid in state.selected }
        startSharingWith(
            configuration,
            recipients,
            durationMillis,
            requestPermission,
            permissionError,
            startError
        )
    }

    /**
     * Starts live sharing with the selected trusted contact.
     *
     * @param configuration Configuration supplied to [startSharingWith].
     * @param recipients Collection of recipients values to process.
     * @param durationMillis Duration or timestamp for duration, in milliseconds.
     * @param requestPermission Function used to transform or provide request permission.
     * @param permissionError Permission error supplied to [startSharingWith].
     * @param startError Start error supplied to [startSharingWith].
     * @param onError Callback invoked when an error occurs.
     * @param onStarted Callback invoked when started changes or occurs.
     */
    fun startSharingWith(
        configuration: LocationMapConfiguration, recipients: List<Friend>, durationMillis: Long,
        requestPermission: suspend () -> Boolean, permissionError: String, startError: String,
        onError: (String) -> Unit = {},
        onStarted: (Long) -> Unit = {},
    ) {
        if (configuration.localMode || recipients.isEmpty() || state.working) return
        scope.launch {
            state = state.copy(
                working = true,
                error = null,
                selected = recipients.mapTo(mutableSetOf()) { it.uid })
            if (!requestPermission()) {
                state = state.copy(working = false, error = permissionError)
                onError(
                    permissionError
                )
            } else try {
                val key = checkNotNull(
                    checkNotNull(repository).setLiveRecipients(
                        configuration.identity.uid,
                        recipients
                    )
                )
                val startedAt = System.currentTimeMillis()
                val end = startedAt + durationMillis
                LiveLocationController.start(
                    applicationContext,
                    configuration.identity.uid,
                    configuration.profile.fullName,
                    state.selected,
                    end,
                    key
                )
                withTimeout(20.seconds) {
                    LiveLocationState.current.filterNotNull()
                        .first { it.uid == configuration.identity.uid && it.updatedAt >= startedAt }
                }
                state = state.copy(working = false, liveActive = true, expiresAt = end)
                onStarted(end)
                watchExpiry()
            } catch (failure: Exception) {
                LiveLocationController.stop(applicationContext)
                runCatching {
                    repository?.setLiveRecipients(
                        configuration.identity.uid,
                        emptyList()
                    )
                }
                val text = failure.localizedMessage ?: startError
                state = state.copy(working = false, error = text)
                onError(text)
            }
        }
    }

    /**
     * User location controller: Stops sharing.
     */
    fun stopSharing() {
        LiveLocationController.stop(applicationContext)
        state = state.copy(liveActive = false, expiresAt = 0L)
        expiryJob?.cancel()
        expiryJob = null
    }

    /**
     * Publishes or removes the user's saved-address marker for approved safe contacts.
     *
     * @param configuration Configuration supplied to [updateStaticSharing].
     * @param enabled Whether this feature or control is currently enabled.
     */
    fun updateStaticSharing(configuration: LocationMapConfiguration, enabled: Boolean) {
        if (configuration.localMode || state.working) return
        scope.launch {
            state = state.copy(working = true, error = null)
            runCatching {
                checkNotNull(repository).setStaticSharing(
                    configuration.identity,
                    configuration.profile,
                    configuration.friends,
                    enabled
                )
            }
                .onSuccess { state = state.copy(staticSharing = enabled) }
                .onFailure { failure ->
                    state = state.copy(
                        error = if (failure is SavedLocationUnavailableException)
                            applicationContext.getString(com.olive.app.R.string.location_saved_address_unavailable) else failure.localizedMessage
                    )
                }
            state = state.copy(working = false)
        }
    }

    /**
     * Counts down live sharing and stops it when its expiry time arrives.
     */
    private fun watchExpiry() {
        expiryJob?.cancel()
        expiryJob = scope.launch {
            while (state.liveActive && isActive) {
                if (state.expiresAt <= System.currentTimeMillis()) state =
                    state.copy(liveActive = false)
                delay(1.seconds)
            }
        }
    }

    private companion object {
        const val MAP_LOCATION_REFRESH_MILLIS = 2_000L
        const val NAVIGATION_CHECK_MILLIS = 2_000L
        const val MIN_ROUTE_REQUEST_INTERVAL_MILLIS = 20_000L
        const val TARGET_REROUTE_METERS = 25f
        const val OFF_ROUTE_METERS = 45.0
        const val TRAIL_WINDOW_MILLIS = 30 * 60_000L
        const val MIN_TRAIL_DISTANCE_METERS = 2f
        const val MAX_TRAIL_POINTS = 1_000
        private val DEFAULT_ROUTE_REQUEST: suspend (MapLocationPoint, MapLocationPoint) -> LocationRoute? = { _, _ -> null }
    }
}

// User location sharing: validates the selected sharing period and converts it to milliseconds.

internal object ShareDurationPolicy {
    const val TEST_DURATION_MILLISECONDS = 10_000L
    const val MAX_CUSTOM_HOURS = 72L
    private const val MILLISECONDS_PER_HOUR = 60L * 60L * 1_000L

    /**
     * Converts an allowed number of hours to milliseconds; values outside the supported range are rejected.
     *
     * @param hours Hours supplied to [millisecondsForHours].
     * @return The computed milliseconds for hours number, or `null` if unavailable.
     */
    fun millisecondsForHours(hours: Long): Long? =
        hours.takeIf { it in 1..MAX_CUSTOM_HOURS }?.times(MILLISECONDS_PER_HOUR)

    /**
     * Reads custom hours from the field and rejects text, zero, negative values, and overflow.
     *
     * @param value Input value to store, parse, or format.
     * @return The computed custom duration milliseconds number, or `null` if unavailable.
     */
    fun customDurationMilliseconds(value: String): Long? =
        value.toLongOrNull()?.let(::millisecondsForHours)
}

private const val MAP_ASSET_HOST = "appassets.androidplatform.net"
internal const val MAP_ASSET_URL = "https://$MAP_ASSET_HOST/map/map.html"

@Volatile
private var mapTileClient: OkHttpClient? = null
private val mapClientLock = Any()

private fun getOrCreateMapClient(context: Context): OkHttpClient {
    return mapTileClient ?: synchronized(mapClientLock) {
        mapTileClient ?: run {
            val cacheDir = File(context.applicationContext.cacheDir, "map-tiles")
            val cache = Cache(cacheDir, 50L * 1024 * 1024)
            OkHttpClient.Builder()
                .cache(cache)
                .build()
                .also { mapTileClient = it }
        }
    }
}

private fun isMapTileHost(host: String): Boolean =
    host == "api.mapbox.com" ||
        host == "tile.openstreetmap.org" ||
        host.endsWith(".tile.openstreetmap.org")

private fun fetchCachedMapResource(context: Context, uri: Uri): WebResourceResponse? {
    return try {
        val client = getOrCreateMapClient(context)
        val request = Request.Builder().url(uri.toString()).build()
        val response = try {
            client.newCall(request).execute()
        } catch (_: IOException) {
            val staleRequest = Request.Builder()
                .url(uri.toString())
                .cacheControl(CacheControl.FORCE_CACHE)
                .build()
            client.newCall(staleRequest).execute()
        }
        if (!response.isSuccessful) {
            response.close()
            return null
        }
        val body = response.body
        val contentType = response.header("Content-Type") ?: "application/octet-stream"
        val mimeType = contentType.substringBefore(';').trim()
        val encoding = if (contentType.contains("charset=", ignoreCase = true)) {
            contentType.substringAfter("charset=", "").substringBefore(';').trim()
        } else if (mimeType.startsWith("text/") || mimeType.contains("json")) {
            "UTF-8"
        } else null

        val responseHeaders = mutableMapOf<String, String>()
        for (i in 0 until response.headers.size) {
            responseHeaders[response.headers.name(i)] = response.headers.value(i)
        }
        responseHeaders["access-control-allow-origin"] = "*"

        WebResourceResponse(
            mimeType,
            encoding,
            response.code,
            response.message.ifBlank { "OK" },
            responseHeaders,
            body.byteStream()
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * A local HTTPS origin lets the vector renderer use workers without granting file access.
 *
 * @param context Android context used to access system services, resources, or storage.
 * @param url HTTPS URL to validate, fetch, or open.
 * @param method HTTP method for the request.
 * @return The resulting [WebResourceResponse], or `null` if unavailable.
 */
internal fun bundledMapAsset(context: Context, url: Uri, method: String = "GET"): WebResourceResponse? {
    if (url.scheme != "https") return null
    if (url.host == MAP_ASSET_HOST) {
        val path = url.path.orEmpty().removePrefix("/")
        val mime = when (path) {
            "map/map.html" -> "text/html"
            "map/leaflet.css", "map/mapbox-gl.css" -> "text/css"
            "map/leaflet.js", "map/map.js", "map/mapbox-basemap.js", "map/mapbox-gl.js" -> "text/javascript"
            "map/images/layers.png", "map/images/layers-2x.png", "map/images/marker-icon.png",
            "map/images/marker-icon-2x.png", "map/images/marker-shadow.png" -> "image/png"
            else -> null
        }
        if (mime != null) {
            try {
                return WebResourceResponse(mime, if (mime.startsWith("text/")) "UTF-8" else null, context.assets.open(path))
            } catch (_: IOException) {
                // Handle missing bundled assets without contacting the reserved host.
            }
        }
        return WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), null)
    }
    if (url.host == "events.mapbox.com") {
        return WebResourceResponse("text/plain", "UTF-8", 204, "No Content", emptyMap(), null)
    }
    if (method.equals("GET", ignoreCase = true) && url.host?.let(::isMapTileHost) == true) {
        return fetchCachedMapResource(context, url)
    }
    return null
}
