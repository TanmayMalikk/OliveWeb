// Safe-app users: shows live-location sharing, directory map places, free-use GPS routing, and compass view.
package com.olive.app.ui.users.user.location

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountRole
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.location.NearbySupportService
import com.olive.app.data.location.supportDistanceMeters
import com.olive.app.data.messaging.Friend
import com.olive.app.data.safety.HotlineDirectory
import com.olive.app.data.safety.HotlineResource
import com.olive.app.data.safety.SavedHotlineStore
import com.olive.app.data.safety.hotlineDialNumber
import com.olive.app.ui.shared.AccessibleMessage
import com.olive.app.ui.shared.Ink
import com.olive.app.ui.shared.LoadingSkeleton
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.OliveOutline
import com.olive.app.ui.shared.Paper
import com.olive.app.ui.shared.SkeletonLayout
import com.olive.app.ui.shared.SoftCardSurface
import com.olive.app.ui.shared.oliveScrollbar
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.users.user.dashboard.LocalBackgroundLocationPermissionRequest
import com.olive.app.ui.users.user.dashboard.LocalLocationPermissionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun PrivateRouteControls(
    controller: LocationMapController,
    state: LocationMapState,
    displayedOwn: MapLocationPoint?,
    onOpenFullScreen: () -> Unit,
) {
    if (!state.navigating) return
    Surface(
        Modifier.fillMaxWidth().testTag("active_route_card"),
        shape = RoundedCornerShape(18.dp),
        color = SoftCardSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val progress = state.route?.let { r -> displayedOwn?.let { r.progress(it.latitude, it.longitude) } }
            TripSummaryMetrics(
                route = state.route,
                progress = progress,
                destinationName = state.privateDestination?.name.orEmpty(),
                routeProfile = state.routeProfile,
            )
            state.route?.let { route ->
                displayedOwn?.let { origin -> route.nextDirection(origin.latitude, origin.longitude) }
                    ?.let {
                        Text(
                            stringResource(R.string.location_next_direction, it.text),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
            }
            if (state.routeWorking) LoadingSkeleton(SkeletonLayout.Directions)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onOpenFullScreen,
                    modifier = Modifier.weight(1f).height(44.dp).testTag("location_full_screen_navigation"),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) {
                    Icon(Icons.Outlined.Fullscreen, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.location_full_screen_navigation),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = controller::stopNavigation,
                    modifier = Modifier.weight(1f).height(44.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Outlined.Close, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.location_stop_navigation),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Shows the user and trusted-contact map, sharing controls, directory places, and route tools.
 */
@Composable
internal fun LocationMapScreen(session: SessionViewModel) {
    LocationMapScreen(session, session.locationMap)
}

@Composable
internal fun LocationMapScreen(session: SessionViewModel, controller: LocationMapController) {
    val context = LocalContext.current
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
    var showSavedOnly by rememberSaveable(uid) { mutableStateOf(false) }
    var headingUp by rememberSaveable(uid) { mutableStateOf(false) }

    val configuration = LocationMapConfiguration(
        AccountIdentity(uid, profile.username), profile, session.messages.friends,
        session.localMode, youLabel, savedLocationLabel,
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

    LaunchedEffect(origin != null, state.privateDestination) {
        if (controller.consumePendingAutoStart()) {
            controller.startNavigation(routeNotConfigured, routeUnavailable, routeFailed)
        }
    }

    val liveHeadingState = rememberDeviceHeadingState(
        enabled = true,
        latitude = origin?.latitude,
        longitude = origin?.longitude,
        gpsBearing = state.ownBearing,
        gpsSpeedMetersPerSecond = state.ownSpeedMps,
    )

    val directory by produceState<HotlineDirectory?>(null, context) {
        value = withContext(Dispatchers.IO) {
            runCatching { HotlineDirectory.readBundled(context) }.getOrNull()
        }
    }
    val savedVersion by SavedHotlineStore.updates.collectAsState()
    val savedIds = remember(directory, uid, savedVersion) {
        SavedHotlineStore.savedIds(context, uid, directory?.resources.orEmpty())
    }
    val directoryPlaces = remember(directory, origin?.latitude, origin?.longitude) {
        val mapped = directory?.mapLocations().orEmpty()
        if (origin == null) mapped
        else mapped.sortedBy {
            supportDistanceMeters(origin.latitude, origin.longitude, it.latitude!!, it.longitude!!)
        }
    }
    val savedMappedCount = remember(directoryPlaces, savedIds) {
        directoryPlaces.count { it.id in savedIds }
    }
    val visibleDirectoryPlaces = remember(directoryPlaces, showSavedOnly, savedIds) {
        if (showSavedOnly) directoryPlaces.filter { it.id in savedIds } else directoryPlaces
    }
    val supportServices = remember(profile.role, visibleDirectoryPlaces, savedIds) {
        if (profile.role == AccountRole.USER) {
            visibleDirectoryPlaces.map { place ->
                NearbySupportService(
                    id = place.id,
                    name = place.name,
                    address = place.address,
                    latitude = place.latitude!!,
                    longitude = place.longitude!!,
                    saved = place.id in savedIds,
                )
            }
        } else emptyList()
    }

    var selectedResource by remember(uid) { mutableStateOf<HotlineResource?>(null) }
    var showDirectoryList by remember(uid) { mutableStateOf(false) }

    if (showDirectoryList) {
        DirectoryPlacesListDialog(
            places = visibleDirectoryPlaces,
            savedIds = savedIds,
            onToggleSave = { SavedHotlineStore.toggle(context, uid, it.id) },
            onSelect = {
                selectedResource = it
                showDirectoryList = false
            },
            onDismiss = { showDirectoryList = false },
        )
    }
    selectedResource?.let { resource ->
        DirectoryPlaceDetailDialog(
            resource = resource,
            saved = resource.id in savedIds,
            onToggleSave = { SavedHotlineStore.toggle(context, uid, resource.id) },
            onDial = {
                hotlineDialNumber(resource.phoneUri)?.let { dial ->
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", dial, null)))
                    }
                }
            },
            onStartNavigation = {
                val lat = resource.latitude ?: return@DirectoryPlaceDetailDialog
                val lng = resource.longitude ?: return@DirectoryPlaceDetailDialog
                selectedResource = null
                controller.stopNavigation()
                controller.selectPrivateDestination(resource.name, lat, lng)
                controller.startNavigation(routeNotConfigured, routeUnavailable, routeFailed)
            },
            onDismiss = { selectedResource = null },
        )
    }

    val selectSupportMarker: (NearbySupportService) -> Unit = { service ->
        selectedResource = directoryPlaces.firstOrNull { it.id == service.id }
    }
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
            onSupportSelected = selectSupportMarker,
            headingUp = headingUp,
            headingState = liveHeadingState,
            onToggleHeadingUp = { headingUp = !headingUp },
            showSavedOnly = showSavedOnly,
            savedCount = savedMappedCount,
            onToggleSavedOnly = if (profile.role == AccountRole.USER) ({ showSavedOnly = !showSavedOnly }) else null,
            routeProfile = state.routeProfile,
            onUserGesture = { if (headingUp) headingUp = false },
        )
        return
    }

    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .padding(bottom = if (profile.role == AccountRole.USER) 88.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = session.navigation::back, modifier = Modifier.testTag("location_back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_previous_safe_page))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.location_map),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(if (profile.role == AccountRole.USER) R.string.location_map_user_help else R.string.location_map_contact_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (foregroundDenied) Button(
            onClick = { permissionScope.launch { if (requestMapAccess(retry = true)) controller.open(configuration) { true } } },
            modifier = Modifier.fillMaxWidth().testTag("map_allow_location"),
        ) { Text(stringResource(R.string.app_permissions_request, stringResource(R.string.app_permissions_location))) }
        if (profile.role == AccountRole.USER && requestBackgroundPermission != null) BackgroundLocationButton(
            requestBackgroundPermission, explainBackground, { explainBackground = false },
        )
        FreeUseGpsDestinationCard(
            controller = controller,
            state = state,
            directoryPlaces = if (profile.role == AccountRole.USER) directoryPlaces else emptyList(),
            routeNotConfigured = routeNotConfigured,
            routeUnavailable = routeUnavailable,
            routeFailed = routeFailed,
        )
        Surface(
            Modifier.fillMaxWidth().height(if (profile.role == AccountRole.USER) 360.dp else 250.dp),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 2.dp,
        ) {
            Box(Modifier.fillMaxSize()) {
                InteractiveLocationMap(
                    points = points,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)),
                    ownPointUid = displayedOwn?.uid,
                    showOwnPointInBlue = profile.role == AccountRole.SAFE_CONTACT,
                    route = state.route,
                    navigationActive = state.navigating,
                    supportServices = supportServices,
                    onSupportSelected = selectSupportMarker,
                    headingUp = headingUp,
                    headingState = liveHeadingState,
                    onToggleHeadingUp = { headingUp = !headingUp },
                    fitSupportServices = showSavedOnly && savedMappedCount > 0,
                    onUserGesture = { if (headingUp) headingUp = false },
                )
                IconButton(
                    onClick = { fullScreenMap = true },
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        .shadow(4.dp, CircleShape)
                        .border(BorderStroke(1.dp, OliveOutline.copy(alpha = 0.55f)), CircleShape)
                        .testTag("location_expand_map"),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Paper,
                        contentColor = Ink,
                    ),
                ) {
                    Icon(Icons.Outlined.Fullscreen, stringResource(R.string.location_expand_map))
                }
            }
        }
        if (profile.role == AccountRole.USER) {
            DirectoryMapFilterRow(
                visibleCount = visibleDirectoryPlaces.size,
                savedMappedCount = savedMappedCount,
                showSavedOnly = showSavedOnly,
                headingUp = headingUp,
                onToggleSavedOnly = { showSavedOnly = !showSavedOnly },
                onToggleHeadingUp = { headingUp = !headingUp },
                onOpenDirectoryList = { showDirectoryList = true },
            )
            PrivateRouteControls(
                controller = controller,
                state = state,
                displayedOwn = displayedOwn,
                onOpenFullScreen = { fullScreenMap = true },
            )
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = SoftCardSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.share_live_location_with), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.location_contacts_selected, state.selected.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = Muted,
                            )
                        }
                    }
                    if (session.messages.loadingIndex) LoadingSkeleton(SkeletonLayout.Contacts, rows = 1)
                    else LocationShareFriendPicker(
                        accountId = uid,
                        friends = session.messages.friends,
                        contactNames = profile.contacts.associate { it.linkedUid to it.name },
                        selected = state.selected,
                        enabled = !state.liveActive && !state.working,
                        onSelectionChange = controller::selectContact,
                    )
                    if (state.liveActive) Text(
                        stringResource(R.string.location_sharing_until, formatShareTime(state.expiresAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = OliveGreen,
                    )
                    if (!state.liveActive) Button(
                        onClick = { durationDialog = true },
                        enabled = state.selected.isNotEmpty() && !state.working && !session.localMode,
                        modifier = Modifier.fillMaxWidth().testTag("start_location_sharing"),
                    ) {
                        Icon(Icons.Outlined.LocationOn, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.start_sharing_location))
                    } else Button(
                        onClick = controller::stopSharing,
                        modifier = Modifier.fillMaxWidth().testTag("stop_location_sharing"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.stop_sharing_location)) }
                    if (session.localMode) Text(
                        stringResource(R.string.location_firebase_required),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
            }
            if (durationDialog) ShareDurationDialog(
                onDismiss = { durationDialog = false },
                onSelected = { durationMillis ->
                    durationDialog = false
                    controller.startSharing(configuration, durationMillis, requestPermission, permissionRequired, startFailed)
                },
            )
        } else {
            Surface(
                Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = SoftCardSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.safe_contact_live_location),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = headingUp,
                            onClick = { headingUp = !headingUp },
                            label = {
                                Text(
                                    stringResource(
                                        if (headingUp) R.string.location_compass_heading_up
                                        else R.string.location_compass_north_up
                                    ),
                                    maxLines = 1,
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Outlined.Explore, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Paper,
                                labelColor = Ink,
                                iconColor = OliveGreen,
                                selectedContainerColor = OliveGreen,
                                selectedLabelColor = Paper,
                                selectedLeadingIconColor = Paper,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = headingUp,
                                borderColor = OliveOutline.copy(alpha = 0.65f),
                                selectedBorderColor = OliveGreen,
                            ),
                            modifier = Modifier.testTag("map_heading_chip"),
                        )
                    }
                    Text(stringResource(R.string.safe_contact_live_location_help), style = MaterialTheme.typography.bodySmall, color = Muted)
                    state.privateDestination?.let { destination ->
                        Text(
                            destination.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (liveUsers.isEmpty() && state.privateDestination == null) Text(
                        stringResource(R.string.location_no_shared_users),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    ) else if (liveUsers.isNotEmpty()) {
                        Text(stringResource(R.string.location_navigation_destination), style = MaterialTheme.typography.labelLarge)
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            liveUsers.forEach { point ->
                                FilterChip(
                                    selected = state.privateDestination == null && point.uid == state.navigationTargetUid,
                                    onClick = { controller.selectNavigationTarget(point.uid) },
                                    label = { Text(point.name) },
                                    enabled = !state.navigating,
                                )
                            }
                        }
                    }
                    state.route?.takeIf { state.navigating }?.let { route ->
                        val progress = displayedOwn?.let { route.progress(it.latitude, it.longitude) }
                        TripSummaryMetrics(
                            route = route,
                            progress = progress,
                            destinationName = navigationTarget?.name.orEmpty(),
                            routeProfile = state.routeProfile,
                        )
                        displayedOwn?.let { route.nextDirection(it.latitude, it.longitude) }?.let { direction ->
                            Text(
                                stringResource(R.string.location_next_direction, direction.text),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (state.routeWorking) LoadingSkeleton(SkeletonLayout.Directions)
                    if (state.navigating) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { fullScreenMap = true },
                                modifier = Modifier.weight(1f).height(44.dp).testTag("location_full_screen_navigation"),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            ) {
                                Icon(Icons.Outlined.Fullscreen, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.location_full_screen_navigation),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Button(
                                onClick = controller::stopNavigation,
                                modifier = Modifier.weight(1f).height(44.dp).testTag("location_navigate"),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            ) {
                                Icon(Icons.Outlined.Close, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.location_stop_navigation),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = { controller.startNavigation(routeNotConfigured, routeUnavailable, routeFailed) },
                            enabled = !state.routeWorking && state.ownPoint != null && (state.navigationTargetUid != null || state.privateDestination != null),
                            modifier = Modifier.fillMaxWidth().testTag("location_navigate"),
                        ) {
                            Icon(Icons.Outlined.Navigation, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.location_start_navigation))
                        }
                    }
                    Text(stringResource(R.string.location_route_privacy), style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
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
