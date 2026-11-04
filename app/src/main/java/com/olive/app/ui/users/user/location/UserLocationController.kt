// Safe-app users: manages user location actions.
package com.olive.app.ui.users.user.location


import android.content.Context
import android.location.Location
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olive.app.data.*
import com.olive.app.location.LiveLocationController
import com.olive.app.location.LiveLocationState
import kotlinx.coroutines.*
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
    val route: LocationRoute? = null,
    val navigating: Boolean = false,
    val routeWorking: Boolean = false,
    val routeError: String? = null,
) {
    // Chooses the user's own map marker from live or saved location data.
    fun displayedOwn(role: AccountRole) =
        if (role == AccountRole.USER) livePoint ?: ownPoint else ownPoint
}

/** Coordinates map data, subscriptions and sharing while keeping Android/Firebase work out of Compose. */
internal class LocationMapController(
    context: Context,
    private val scope: CoroutineScope,
    private val repository: LocationSharingDataSource?,
) {
    private val applicationContext = context.applicationContext
    private var locationJob: Job? = null
    private var navigationJob: Job? = null
    private var expiryJob: Job? = null
    private var subscription: ChatSubscription? = null
    private var generation = 0
    var state by mutableStateOf(LocationMapState())
        private set

    init {
        scope.launch {
            LiveLocationState.current.collectLatest {
                state = state.copy(livePoint = it)
            }
        }
    }

    // Opens a saved user or trusted-contact marker on the map.
    fun open(configuration: LocationMapConfiguration, requestPermission: suspend () -> Boolean) {
        closeMap()
        val token = ++generation
        val uid = configuration.identity.uid
        val friendIds = configuration.friends.mapTo(mutableSetOf()) { it.uid }
        state = LocationMapState(
            ownerUid = uid,
            livePoint = LiveLocationState.current.value,
            selected = LiveLocationController.selected(applicationContext).intersect(friendIds),
            liveActive = LiveLocationController.active(applicationContext),
            expiresAt = LiveLocationController.expiresAt(applicationContext),
            staticSharing = repository?.staticSharingEnabled(uid) == true,
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
                        it.any { point -> point.uid == targetUid && point.live }
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
            if (requestPermission()) {
                // User location controller: Keeps open active while is active.
                while (isActive) {
                    DeviceLocation.currentCoordinates(applicationContext)?.let { fix ->
                        // Apply the map result only if it belongs to the newest open request.
                        if (token == generation) {
                            val previous = state.ownPoint
                            val trail = previous?.trail.orEmpty()
                                .filter { it.updatedAt >= System.currentTimeMillis() - TRAIL_WINDOW_MILLIS }
                                .toMutableList()
                            val moved = previous == null || FloatArray(1).also { result ->
                                Location.distanceBetween(
                                    previous.latitude,
                                    previous.longitude,
                                    fix.latitude,
                                    fix.longitude,
                                    result
                                )
                            }[0] >= MIN_TRAIL_DISTANCE_METERS
                            if (moved && trail.lastOrNull()?.updatedAt != fix.timestamp)
                                trail += LocationTrailPoint(
                                    fix.latitude,
                                    fix.longitude,
                                    fix.timestamp
                                )
                            state = state.copy(
                                ownPoint = MapLocationPoint(
                                    uid,
                                    configuration.youLabel,
                                    fix.latitude,
                                    fix.longitude,
                                    fix.timestamp,
                                    true,
                                    fix.accuracy,
                                    trail = trail.takeLast(MAX_TRAIL_POINTS)
                                )
                            )
                        }
                    }
                    delay(MAP_LOCATION_REFRESH_MILLIS.milliseconds)
                }
            } else if (configuration.profile.role == AccountRole.SAFE_CONTACT) {
                val coordinates = GeoapifyClient.coordinates(configuration.profile.address)
                    ?: DeviceLocation.coordinatesForAddress(
                        applicationContext,
                        configuration.profile.address
                    )
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
        }
        if (
            configuration.profile.role == AccountRole.SAFE_CONTACT &&
            state.staticSharing &&
            !configuration.localMode
        ) updateStaticSharing(configuration, true)
        watchExpiry()
    }

    // User location controller: Stops map.
    fun closeMap() {
        generation++
        locationJob?.cancel()
        locationJob = null
        navigationJob?.cancel()
        navigationJob = null
        expiryJob?.cancel()
        expiryJob = null
        subscription?.close()
        subscription = null
    }

    // Clears the selected route, target, and navigation message.
    fun clear() {
        closeMap()
        state = LocationMapState()
    }

    // Selects which trusted contact or user the map should route toward.
    fun selectNavigationTarget(uid: String) {
        if (!state.navigating && state.contacts.any { it.uid == uid && it.live })
            state = state.copy(navigationTargetUid = uid, routeError = null)
    }

    // Requests a route to the selected person and starts map navigation.
    fun startNavigation(
        notConfiguredMessage: String,
        unavailableMessage: String,
        failedMessage: String,
    ) {
        if (state.navigating || state.routeWorking) return
        if (!MapboxDirectionsClient.configured) {
            state = state.copy(routeError = notConfiguredMessage)
            return
        }
        val targetUid = state.navigationTargetUid ?: state.contacts.firstOrNull { it.live }?.uid
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
                val target = state.contacts.firstOrNull {
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
                        MapboxDirectionsClient.drivingRoute(
                            origin.latitude,
                            origin.longitude,
                            target.latitude,
                            target.longitude,
                        )
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
                        state = state.copy(routeWorking = false, routeError = failedMessage)
                    }
                } else {
                    state = state.copy(routeWorking = false)
                }
                delay(NAVIGATION_CHECK_MILLIS.milliseconds)
            }
        }
    }

    // Stops route guidance and clears the selected destination.
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

    // Routes needs refresh for user location controller.
    private fun routeNeedsRefresh(
        origin: MapLocationPoint,
        target: MapLocationPoint,
        lastTarget: MapLocationPoint?,
    ): Boolean {
        val route = state.route ?: return true
        if (lastTarget == null || distanceBetween(target, lastTarget) >= TARGET_REROUTE_METERS)
            return true
        return distanceFromRouteMeters(origin.latitude, origin.longitude, route) >= OFF_ROUTE_METERS
    }

    // Calculates the straight-line distance between two map points.
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

    // Adds or removes one safe contact from the next live-location share.
    fun selectContact(uid: String, enabled: Boolean) {
        if (state.liveActive) return
        val updated = if (enabled) state.selected + uid else state.selected - uid
        if (updated.size <= MAX_SHARED_CONTACTS) state = state.copy(selected = updated)
    }

    // Starts the user's live-location sharing session.
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

    // Starts live sharing with the selected trusted contact.
    fun startSharingWith(
        configuration: LocationMapConfiguration, recipients: List<Friend>, durationMillis: Long,
        requestPermission: suspend () -> Boolean, permissionError: String, startError: String,
        onError: (String) -> Unit = {}
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
                val end = System.currentTimeMillis() + durationMillis
                val startedAt = System.currentTimeMillis()
                val key = checkNotNull(
                    checkNotNull(repository).setLiveRecipients(
                        configuration.identity.uid,
                        recipients
                    )
                )
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

    // User location controller: Stops sharing.
    fun stopSharing() {
        LiveLocationController.stop(applicationContext)
        state = state.copy(liveActive = false, expiresAt = 0L)
        expiryJob?.cancel()
        expiryJob = null
    }

    // Publishes or removes the user's saved-address marker for approved safe contacts.
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

    // Counts down live sharing and stops it when its expiry time arrives.
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
        const val MAX_SHARED_CONTACTS = 10
        const val MAP_LOCATION_REFRESH_MILLIS = 2_000L
        const val NAVIGATION_CHECK_MILLIS = 2_000L
        const val MIN_ROUTE_REQUEST_INTERVAL_MILLIS = 20_000L
        const val TARGET_REROUTE_METERS = 25f
        const val OFF_ROUTE_METERS = 45.0
        const val TRAIL_WINDOW_MILLIS = 30 * 60_000L
        const val MIN_TRAIL_DISTANCE_METERS = 2f
        const val MAX_TRAIL_POINTS = 1_000
    }
}
