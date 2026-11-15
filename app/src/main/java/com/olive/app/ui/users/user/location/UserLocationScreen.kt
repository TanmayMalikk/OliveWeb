// Safe-app users: shows live-location sharing and the user's map.
package com.olive.app.ui.users.user.location


import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountRole
import com.olive.app.data.safety.HotlineDirectory
import com.olive.app.data.safety.HotlineResourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.olive.app.ui.shared.*
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.users.user.dashboard.LocalBackgroundLocationPermissionRequest
import com.olive.app.ui.users.user.dashboard.LocalLocationPermissionRequest
import kotlinx.coroutines.launch

private const val MAX_SHARED_CONTACTS = 10

@Composable
private fun BackgroundLocationButton(requestPermission: suspend () -> Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var explain by remember { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    val title = stringResource(R.string.location_background_access)
    TextButton(onClick = { explain = true }, enabled = !requesting, modifier = Modifier.testTag("background_location")) {
        Text(title)
    }
    if (explain) {
        val help = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            stringResource(R.string.location_background_settings, context.packageManager.backgroundPermissionOptionLabel)
        } else {
            stringResource(R.string.location_background_dialog)
        }
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text(title) },
            text = { Text(help) },
            confirmButton = {
                TextButton(
                    onClick = {
                        explain = false
                        requesting = true
                        scope.launch {
                            try {
                                requestPermission()
                            } finally {
                                requesting = false
                            }
                        }
                    },
                    modifier = Modifier.testTag("background_location_continue"),
                ) { Text(stringResource(R.string.next)) }
            },
            dismissButton = {
                TextButton(onClick = { explain = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun PrivateRouteControls(
    controller: LocationMapController,
    state: LocationMapState,
    notConfigured: String,
    unavailable: String,
    failed: String,
    city: String,
) {
    var choosing by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var lookup by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var shelters by remember(city) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(choosing, city) {
        if (!choosing || city.isBlank()) return@LaunchedEffect
        shelters = withContext(Dispatchers.IO) {
            runCatching { HotlineDirectory.readBundled(context) }.getOrNull()?.resources.orEmpty()
                .filter { it.kind == HotlineResourceKind.Shelter && it.city.equals(city.trim(), ignoreCase = true) }
                .map { "${it.name}, ${it.city}" }
        }
    }
    OutlinedButton(
        onClick = {
            if (state.navigating) controller.stopNavigation()
            else {
                address = state.privateDestination?.name.orEmpty()
                error = false
                choosing = true
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(if (state.navigating) R.string.location_stop_navigation else R.string.private_route_destination))
    }
    state.privateDestination?.let { destination ->
        if (state.navigating) {
            Text(destination.name, style = MaterialTheme.typography.labelLarge)
            state.route?.let { route ->
                val origin = state.ownPoint
                val next = origin?.let { route.nextDirection(it.latitude, it.longitude) }
                next?.let { Text(stringResource(R.string.location_next_direction, it.text)) }
            }
        }
    }
    if (choosing) AlertDialog(
        onDismissRequest = { if (!lookup) choosing = false },
        title = { Text(stringResource(R.string.private_route_destination)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.private_route_help))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it.take(240); error = false },
                    label = { Text(stringResource(R.string.location_navigation_destination)) },
                    enabled = !lookup,
                    singleLine = true,
                )
                shelters.take(3).forEach { shelter ->
                    TextButton(enabled = !lookup, onClick = { address = shelter; error = false }) {
                        Text(shelter)
                    }
                }
                if (lookup) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error) AccessibleMessage(stringResource(R.string.private_route_address_not_found))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !lookup && address.isNotBlank(),
                onClick = {
                    lookup = true
                    error = false
                    scope.launch {
                        try {
                            if (controller.resolvePrivateDestination(address.trim())) {
                                choosing = false
                                controller.startNavigation(notConfigured, unavailable, failed)
                            } else error = true
                        } finally {
                            lookup = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.location_start_navigation)) }
        },
        dismissButton = {
            TextButton(enabled = !lookup, onClick = { choosing = false }) { Text(stringResource(R.string.cancel)) }
        },
    )
}

// Shows the user and trusted-contact map, sharing controls, and route tools.
@Composable
internal fun LocationMapScreen(session: SessionViewModel) {
    val profile = session.profile ?: return
    val uid = session.coverAccountId ?: return
    val controller = session.locationMap
    val requestPermission = LocalLocationPermissionRequest.current
    val requestBackgroundPermission = LocalBackgroundLocationPermissionRequest.current
    val youLabel = stringResource(R.string.you)
    val savedLocationLabel = stringResource(R.string.your_saved_location)
    val permissionRequired = stringResource(R.string.location_permission_required)
    val startFailed = stringResource(R.string.location_start_failed)
    val routeUnavailable = stringResource(R.string.location_route_unavailable)
    val routeFailed = stringResource(R.string.location_route_failed)
    val routeNotConfigured = stringResource(R.string.location_route_not_configured)
    var durationDialog by remember { mutableStateOf(false) }
    var fullScreenNavigation by rememberSaveable { mutableStateOf(false) }
    val configuration = LocationMapConfiguration(
        AccountIdentity(uid, profile.username), profile, session.messages.friends,
        session.localMode, youLabel, savedLocationLabel
    )
    LaunchedEffect(uid, profile, session.messages.friends, youLabel, savedLocationLabel, session.localMode) {
        controller.open(configuration, requestPermission)
    }
    DisposableEffect(controller) { onDispose(controller::closeMap) }
    val state = controller.state
    val displayedOwn = state.displayedOwn(profile.role)
    val points = listOfNotNull(displayedOwn, state.privateDestination) + state.contacts
    val liveUsers = state.contacts.filter { it.live }
    val navigationTarget = state.privateDestination ?: liveUsers.firstOrNull { it.uid == state.navigationTargetUid }
    LaunchedEffect(state.navigating) {
        if (!state.navigating) fullScreenNavigation = false
    }

    if (
        fullScreenNavigation &&
        state.navigating
    ) {
        BackHandler { fullScreenNavigation = false }
        FullScreenNavigationMap(
            points = points,
            ownPoint = displayedOwn,
            route = state.route,
            destinationName = navigationTarget?.name.orEmpty(),
            routeWorking = state.routeWorking,
            routeError = state.routeError,
            onExitFullScreen = { fullScreenNavigation = false },
            onStopNavigation = controller::stopNavigation,
        )
        return
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 8.dp),
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
        Surface(
            Modifier.fillMaxWidth().weight(1f).heightIn(min = 280.dp),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 2.dp
        ) {
            InteractiveLocationMap(
                points,
                Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)),
                displayedOwn?.uid,
                showOwnPointInBlue = profile.role == AccountRole.SAFE_CONTACT,
                route = state.route,
                navigationActive = state.navigating,
            )
        }
        if (profile.role == AccountRole.USER) {
            PrivateRouteControls(controller, state, routeNotConfigured, routeUnavailable, routeFailed, profile.address.city)
            if (requestBackgroundPermission != null) BackgroundLocationButton(requestBackgroundPermission)
            if (state.navigating) OutlinedButton(onClick = { fullScreenNavigation = true }) {
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
                    if (session.messages.friends.isEmpty()) Text(
                        stringResource(R.string.location_no_friends),
                        color = Muted
                    )
                    else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 140.dp)) {
                        items(session.messages.friends, key = { it.uid }) { friend ->
                            val checked = friend.uid in state.selected
                            val trusted =
                                profile.contacts.firstOrNull { it.linkedUid == friend.uid }
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked,
                                    { enabled -> controller.selectContact(friend.uid, enabled) },
                                    enabled = !state.liveActive && (checked || state.selected.size < MAX_SHARED_CONTACTS)
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(trusted?.name ?: friend.username)
                                    if (trusted != null) Text(
                                        "@${friend.username}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Muted
                                    )
                                }
                            }
                        }
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
                    if (state.routeWorking) LinearProgressIndicator(Modifier.fillMaxWidth())
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
                        onClick = { fullScreenNavigation = true },
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
