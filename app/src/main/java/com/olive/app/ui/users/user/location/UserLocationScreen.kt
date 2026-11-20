// Safe-app users: shows live-location sharing and the user's map.
package com.olive.app.ui.users.user.location

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountRole
import com.olive.app.data.location.LoadedSupportArea
import com.olive.app.data.location.NearbySupportService
import com.olive.app.data.location.NearbySupportServices
import com.olive.app.data.location.SupportMapCenter
import com.olive.app.data.messaging.Friend
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.oliveScrollbar
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.users.user.dashboard.LocalBackgroundLocationPermissionRequest
import com.olive.app.ui.users.user.dashboard.LocalLocationPermissionRequest
import kotlinx.coroutines.launch

/**
 * Renders the private route controls composable.
 *
 * @param controller Controller supplied to [PrivateRouteControls].
 * @param state Current state instance to read or render.
 */
@Composable
private fun PrivateRouteControls(controller: LocationMapController, state: LocationMapState) {
    if (state.navigating) {
        OutlinedButton(onClick = controller::stopNavigation, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.location_stop_navigation))
        }
        state.privateDestination?.let { destination ->
            Text(destination.name, style = MaterialTheme.typography.labelLarge)
            state.route?.let { route ->
                state.ownPoint?.let { origin -> route.nextDirection(origin.latitude, origin.longitude) }
                    ?.let { Text(stringResource(R.string.location_next_direction, it.text)) }
            }
        }
    }
}

/**
 * Shows the user and trusted-contact map, sharing controls, and route tools.
 *
 * @param session Shared session view model owning application state and actions.
 */
@Composable
internal fun LocationMapScreen(session: SessionViewModel) {
    LocationMapScreen(session, session.locationMap)
}

/**
 * Renders the location map screen UI.
 *
 * @param session Shared session view model owning application state and actions.
 * @param controller Controller supplied to [LocationMapScreen].
 */
@Composable
internal fun LocationMapScreen(session: SessionViewModel, controller: LocationMapController) {
    val profile = session.profile ?: return
    val uid = session.coverAccountId ?: return
    val requestPermission = LocalLocationPermissionRequest.current
    val requestBackgroundPermission = LocalBackgroundLocationPermissionRequest.current
    val youLabel = stringResource(R.string.you)
    val savedLocationLabel = stringResource(R.string.your_saved_location)
    val permissionRequired = stringResource(R.string.location_permission_required)
    val startFailed = stringResource(R.string.location_start_failed)
    val routeUnavailable = stringResource(R.string.location_route_unavailable)
    val routeFailed = stringResource(R.string.location_route_failed)
    val routeNotConfigured = stringResource(R.string.location_route_not_configured)
    var permissionResult by remember(uid) { mutableStateOf<Boolean?>(null) }
    var foregroundDenied by remember(uid) { mutableStateOf(false) }
    var explainBackground by remember(uid) { mutableStateOf(false) }
    var offeredBackground by remember(uid) { mutableStateOf(false) }
    val permissionScope = rememberCoroutineScope()
    /**
     * Requests map access.
     *
     * @param retry Whether to apply retry.
     * @return `true` if request map access, or `false` otherwise.
     */
    suspend fun requestMapAccess(retry: Boolean = false): Boolean {
        val granted = if (retry || permissionResult == null) requestPermission() else permissionResult == true
        permissionResult = granted
        foregroundDenied = !granted
        if (granted && profile.role == AccountRole.USER && requestBackgroundPermission != null && !offeredBackground) {
            offeredBackground = true
            explainBackground = true
        }
        return granted
    }
    var durationDialog by remember { mutableStateOf(false) }
    var fullScreenMap by rememberSaveable(uid) { mutableStateOf(false) }
    val configuration = LocationMapConfiguration(
        AccountIdentity(uid, profile.username), profile, session.messages.friends,
        session.localMode, youLabel, savedLocationLabel
    )
    LaunchedEffect(controller, uid, profile, session.messages.friends, youLabel, savedLocationLabel, session.localMode) {
        controller.open(configuration) { requestMapAccess() }
    }
    DisposableEffect(controller) { onDispose(controller::closeMap) }
    val state = controller.state
    val displayedOwn = state.displayedOwn(profile.role)
    val points = listOfNotNull(displayedOwn, state.privateDestination) + state.contacts
    val liveUsers = state.contacts.filter { it.live }
    val origin = state.ownPoint
    val serviceSearchEnabled = !session.localMode && profile.role == AccountRole.USER
    var loadedAreas by remember(uid) { mutableStateOf(emptyList<LoadedSupportArea>()) }
    var viewportCenter by remember(uid) { mutableStateOf<SupportMapCenter?>(null) }
    var requestedArea by remember(uid) { mutableStateOf<SupportMapCenter?>(null) }
    var failedArea by remember(uid) { mutableStateOf<SupportMapCenter?>(null) }
    LaunchedEffect(uid, serviceSearchEnabled, origin != null) {
        if (serviceSearchEnabled && origin != null && state.ownerUid == uid &&
            loadedAreas.isEmpty() && requestedArea == null && failedArea == null
        ) {
            requestedArea = SupportMapCenter(origin.latitude, origin.longitude)
        }
    }
    LaunchedEffect(uid, requestedArea) {
        val center = requestedArea ?: return@LaunchedEffect
        failedArea = null
        try {
            val result = NearbySupportServices.find(center.latitude, center.longitude)
            loadedAreas = loadedAreas + LoadedSupportArea(center, result)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failedArea = center
        } finally {
            requestedArea = null
        }
    }
    val supportServices = remember(loadedAreas) { loadedAreas.flatMap { it.result.services }.distinctBy { it.id } }
    val uncoveredCenter = viewportCenter?.takeIf { center ->
        loadedAreas.isNotEmpty() && loadedAreas.none { it.covers(center) }
    }
    val onViewportChanged: ((Double, Double) -> Unit)? = if (serviceSearchEnabled) {
        { latitude, longitude -> viewportCenter = SupportMapCenter(latitude, longitude) }
    } else null
    val loadMore: (() -> Unit)? = uncoveredCenter?.let { center ->
        if (requestedArea == null) ({ requestedArea = center }) else null
    }
    var selectedService by remember(uid) { mutableStateOf<NearbySupportService?>(null) }
    var showServiceList by remember(uid) { mutableStateOf(false) }
    if (showServiceList) AlertDialog(
        onDismissRequest = { showServiceList = false },
        title = { Text(stringResource(R.string.nearby_support_title)) },
        text = {
            LazyColumn { items(supportServices, key = { it.id }) { service ->
                TextButton(onClick = { selectedService = service; showServiceList = false }) { Text(service.name) }
            } }
        },
        confirmButton = { TextButton(onClick = { showServiceList = false }) { Text(stringResource(R.string.close)) } },
    )
    selectedService?.let { service -> AlertDialog(
        onDismissRequest = { selectedService = null },
        title = { Text(service.name) },
        text = { Text(service.address) },
        confirmButton = {
            TextButton(onClick = {
                selectedService = null
                controller.stopNavigation()
                controller.selectPrivateDestination(service.name, service.latitude, service.longitude)
                controller.startNavigation(routeNotConfigured, routeUnavailable, routeFailed)
            }) { Text(stringResource(R.string.location_start_navigation)) }
        },
        dismissButton = { TextButton(onClick = { selectedService = null }) { Text(stringResource(R.string.cancel)) } },
    ) }
    val navigationTarget = state.privateDestination ?: liveUsers.firstOrNull { it.uid == state.navigationTargetUid }
    if (fullScreenMap) {
        BackHandler { fullScreenMap = false }
        FullScreenLocationMap(
            points = points,
            ownPoint = displayedOwn,
            route = state.route,
            destinationName = navigationTarget?.name.orEmpty(),
            routeWorking = state.routeWorking,
            routeError = state.routeError,
            navigating = state.navigating,
            showOwnPointInBlue = state.navigating || profile.role == AccountRole.SAFE_CONTACT,
            onExitFullScreen = { fullScreenMap = false },
            onStopNavigation = controller::stopNavigation,
            supportServices = supportServices,
            onSupportSelected = { selectedService = it },
            onViewportCenterChanged = onViewportChanged,
            onLoadMoreSupport = loadMore,
            loadingMoreSupport = requestedArea != null && loadedAreas.isNotEmpty(),
        )
        return
    }

    Column(
        Modifier.fillMaxSize()
            .then(if (profile.role == AccountRole.USER) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .then(if (profile.role == AccountRole.USER) Modifier.padding(bottom = 88.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = session.navigation::back, modifier = Modifier.testTag("location_back")) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    stringResource(R.string.ui_previous_safe_page)
                )
            }
            Text(
                stringResource(R.string.location_map),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() })
        }
        Text(
            stringResource(if (profile.role == AccountRole.USER) R.string.location_map_user_help else R.string.location_map_contact_help),
            style = MaterialTheme.typography.bodyMedium, color = Muted
        )
        if (foregroundDenied) Button(
            onClick = { permissionScope.launch { if (requestMapAccess(retry = true)) controller.open(configuration) { true } } },
            modifier = Modifier.fillMaxWidth().testTag("map_allow_location"),
        ) { Text(stringResource(R.string.app_permissions_request, stringResource(R.string.app_permissions_location))) }
        if (profile.role == AccountRole.USER && requestBackgroundPermission != null) BackgroundLocationButton(
            requestBackgroundPermission, explainBackground, { explainBackground = false },
        )
        Surface(
            Modifier.fillMaxWidth().then(
                if (profile.role == AccountRole.USER) Modifier.height(300.dp)
                else Modifier.weight(1f).heightIn(min = 280.dp)
            ),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 2.dp
        ) {
            Box(Modifier.fillMaxSize()) {
                InteractiveLocationMap(
                    points,
                    Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)),
                    displayedOwn?.uid,
                    showOwnPointInBlue = profile.role == AccountRole.SAFE_CONTACT,
                    route = state.route,
                    navigationActive = state.navigating,
                    supportServices = supportServices,
                    onSupportSelected = { selectedService = it },
                    onViewportCenterChanged = onViewportChanged,
                )
                IconButton(
                    onClick = { fullScreenMap = true },
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp).testTag("location_expand_map"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Icon(Icons.Outlined.Fullscreen, stringResource(R.string.location_expand_map))
                }
            }
        }
        if (profile.role == AccountRole.USER) {
            when {
                requestedArea != null -> LoadingSkeleton(SkeletonLayout.NearbySupport)
                failedArea != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.nearby_support_failed), Modifier.weight(1f))
                    TextButton(onClick = { requestedArea = uncoveredCenter ?: failedArea }) {
                        Text(stringResource(R.string.retry))
                    }
                }
                loadMore != null -> OutlinedButton(onClick = loadMore, modifier = Modifier.testTag("load_more_hotline_locations")) {
                    Text(stringResource(R.string.load_more_hotline_locations))
                }
                loadedAreas.isEmpty() && serviceSearchEnabled && (origin != null || state.locating || state.ownerUid != uid) ->
                    LoadingSkeleton(SkeletonLayout.NearbySupport)
                loadedAreas.isEmpty() && origin == null -> Text(
                    stringResource(R.string.nearby_support_location_required), style = MaterialTheme.typography.bodySmall,
                )
                supportServices.isEmpty() -> Text(stringResource(R.string.nearby_support_empty), style = MaterialTheme.typography.bodySmall)
            }
            if (supportServices.isNotEmpty()) TextButton(
                onClick = { showServiceList = true },
                modifier = Modifier.testTag("nearby_support_list"),
            ) { Text(stringResource(R.string.loaded_hotline_locations, supportServices.size)) }
            PrivateRouteControls(controller, state)
            if (state.navigating) OutlinedButton(onClick = { fullScreenMap = true }) {
                Text(stringResource(R.string.location_full_screen_navigation))
            }
            Surface(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = SoftCardSurface
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.share_live_location_with),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.location_contacts_selected, state.selected.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted
                    )
                    if (session.messages.loadingIndex) LoadingSkeleton(SkeletonLayout.Contacts, rows = 1)
                    else {
                        LocationShareFriendPicker(
                            accountId = uid,
                            friends = session.messages.friends,
                            contactNames = profile.contacts.associate { it.linkedUid to it.name },
                            selected = state.selected,
                            enabled = !state.liveActive && !state.working,
                            onSelectionChange = controller::selectContact,
                        )
                    }
                    if (state.liveActive) Text(
                        stringResource(
                            R.string.location_sharing_until,
                            formatShareTime(state.expiresAt)
                        ),
                        style = MaterialTheme.typography.bodySmall, color = OliveGreen
                    )
                }
            }
            if (!state.liveActive) Button(
                onClick = { durationDialog = true },
                enabled = state.selected.isNotEmpty() && !state.working && !session.localMode,
                modifier = Modifier.fillMaxWidth().testTag("start_location_sharing")
            ) {
                Icon(Icons.Outlined.LocationOn, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.start_sharing_location)
                )
            } else Button(
                onClick = controller::stopSharing,
                modifier = Modifier.fillMaxWidth().testTag("stop_location_sharing"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text(stringResource(R.string.stop_sharing_location)) }
            if (durationDialog) ShareDurationDialog(
                onDismiss = { durationDialog = false },
                onSelected = { durationMillis ->
                    durationDialog = false
                    controller.startSharing(
                        configuration,
                        durationMillis,
                        requestPermission,
                        permissionRequired,
                        startFailed
                    )
                })
            if (session.localMode) Text(
                stringResource(R.string.location_firebase_required),
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
        } else {
            Surface(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = SoftCardSurface,
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.safe_contact_live_location),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.safe_contact_live_location_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted
                    )
                    if (liveUsers.isEmpty()) Text(
                        stringResource(R.string.location_no_shared_users),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    ) else {
                        Text(
                            stringResource(R.string.location_navigation_destination),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            liveUsers.forEach { point ->
                                FilterChip(
                                    selected = point.uid == state.navigationTargetUid,
                                    onClick = { controller.selectNavigationTarget(point.uid) },
                                    label = { Text(point.name) },
                                    enabled = !state.navigating,
                                )
                            }
                        }
                    }
                    state.route?.takeIf { state.navigating }?.let { route ->
                        val progress = displayedOwn?.let {
                            route.progress(it.latitude, it.longitude)
                        }
                        Text(
                            stringResource(
                                R.string.location_route_summary,
                                formatRouteDistance(progress?.distanceMeters ?: route.distanceMeters),
                                formatRouteDuration(progress?.durationSeconds ?: route.durationSeconds),
                            ),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        displayedOwn?.let { own ->
                            route.nextDirection(own.latitude, own.longitude)
                        }?.let { direction ->
                            Text(
                                stringResource(R.string.location_next_direction, direction.text),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    if (state.routeWorking) LoadingSkeleton(SkeletonLayout.Directions)
                    Button(
                        onClick = {
                            if (state.navigating) controller.stopNavigation()
                            else controller.startNavigation(
                                notConfiguredMessage = routeNotConfigured,
                                unavailableMessage = routeUnavailable,
                                failedMessage = routeFailed,
                            )
                        },
                        enabled = state.navigating || (!state.routeWorking && state.ownPoint != null && state.navigationTargetUid != null),
                        modifier = Modifier.fillMaxWidth().testTag("location_navigate"),
                    ) {
                        Icon(Icons.Outlined.Navigation, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (state.navigating) R.string.location_stop_navigation else R.string.location_start_navigation))
                    }
                    if (state.navigating) OutlinedButton(
                        onClick = { fullScreenMap = true },
                        modifier = Modifier.fillMaxWidth().testTag("location_full_screen_navigation"),
                    ) {
                        Icon(Icons.Outlined.Fullscreen, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.location_full_screen_navigation))
                    }
                    Text(
                        stringResource(R.string.location_route_privacy),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.share_saved_location), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.share_saved_location_help), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                Switch(state.staticSharing, { controller.updateStaticSharing(configuration, it) }, enabled = !state.working && !session.localMode, modifier = Modifier.testTag("share_saved_location"))
            }
        }
        state.error?.let { AccessibleMessage(it) }
        state.routeError?.let { AccessibleMessage(it) }
    }
}

/**
 * Renders the location share friend picker composable.
 *
 * @param accountId Account ID supplied to [LocationShareFriendPicker].
 * @param friends List of friend records.
 * @param contactNames Key-value mapping of contact names entries.
 * @param selected Collection of selected values to process.
 * @param enabled Whether this feature or control is currently enabled.
 * @param onSelectionChange Callback invoked when selection change changes or occurs.
 */
@Composable
internal fun LocationShareFriendPicker(
    accountId: String,
    friends: List<Friend>,
    contactNames: Map<String, String>,
    selected: Set<String>,
    enabled: Boolean,
    onSelectionChange: (String, Boolean) -> Unit,
) {
    if (friends.isEmpty()) {
        Text(stringResource(R.string.location_no_friends), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    var query by rememberSaveable(accountId) { mutableStateOf("") }
    val search = query.trim().removePrefix("@")
    val matches = friends.filter { friend ->
        friend.username.contains(search, ignoreCase = true) ||
            contactNames[friend.uid].orEmpty().contains(search, ignoreCase = true)
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it.take(120) },
        modifier = Modifier.fillMaxWidth().testTag("location_friend_search"),
        label = { Text(stringResource(R.string.location_search_friends)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        singleLine = true,
    )
    if (matches.isEmpty()) {
        Text(stringResource(R.string.location_no_matching_friends), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(search) { listState.scrollToItem(0) }
    val rowHeight = 56.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    LazyColumn(
        modifier = Modifier.fillMaxWidth().height(rowHeight * minOf(3, matches.size))
            .oliveScrollbar(listState).testTag("location_friend_list"),
        state = listState,
    ) {
        items(matches, key = { it.uid }) { friend ->
            val checked = friend.uid in selected
            val contactName = contactNames[friend.uid]?.takeIf(String::isNotBlank)
            Row(
                Modifier.fillMaxWidth().height(rowHeight).testTag("location_friend_${friend.uid}")
                    .toggleable(checked, enabled = enabled, role = Role.Checkbox) {
                        onSelectionChange(friend.uid, it)
                    }.padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                }
                Column(Modifier.weight(1f)) {
                    Text(contactName ?: friend.username, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (contactName != null) Text(
                        "@${friend.username}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
