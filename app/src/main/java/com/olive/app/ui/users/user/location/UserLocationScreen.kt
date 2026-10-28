// Safe-app users: shows live-location sharing and the user's map.
package com.olive.app.ui.users.user.location

import com.olive.app.ui.shared.*


import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.ui.users.user.dashboard.LocalLocationPermissionRequest

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.olive.app.R
import com.olive.app.data.AccountIdentity
import com.olive.app.data.AccountRole
import com.olive.app.data.LocationRoute
import com.olive.app.data.MapLocationPoint
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import kotlin.math.ceil

@Composable
internal fun LocationMapScreen(session: SessionViewModel) {
    val profile = session.profile ?: return
    val uid = session.coverAccountId ?: return
    val controller = session.locationMap
    val requestPermission = LocalLocationPermissionRequest.current
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
    val points = listOfNotNull(displayedOwn) + state.contacts
    val liveUsers = state.contacts.filter { it.live }
    val navigationTarget = liveUsers.firstOrNull { it.uid == state.navigationTargetUid }
    LaunchedEffect(state.navigating) {
        if (!state.navigating) fullScreenNavigation = false
    }

    if (
        fullScreenNavigation &&
        profile.role == AccountRole.SAFE_CONTACT &&
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
            Surface(
                Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = Color(0xFFF3F6F0)
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
                color = Color(0xFFF3F6F0),
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

@Composable
private fun FullScreenNavigationMap(
    points: List<MapLocationPoint>,
    ownPoint: MapLocationPoint?,
    route: LocationRoute?,
    destinationName: String,
    routeWorking: Boolean,
    routeError: String?,
    onExitFullScreen: () -> Unit,
    onStopNavigation: () -> Unit,
) {
    val direction = ownPoint?.let { route?.nextDirection(it.latitude, it.longitude) }
    val progress = ownPoint?.let { route?.progress(it.latitude, it.longitude) }
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    Box(Modifier.fillMaxSize().testTag("full_screen_navigation")) {
        InteractiveLocationMap(
            points = points,
            modifier = Modifier.fillMaxSize(),
            ownPointUid = ownPoint?.uid,
            showOwnPointInBlue = true,
            route = route,
            navigationActive = true,
        )
        IconButton(
            onClick = onExitFullScreen,
            modifier = Modifier.statusBarsPadding().padding(12.dp).align(Alignment.TopStart)
                .testTag("exit_full_screen_navigation"),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Icon(Icons.Outlined.FullscreenExit, stringResource(R.string.location_exit_full_screen))
        }
        Surface(
            modifier = Modifier.statusBarsPadding().padding(start = 68.dp, end = 72.dp, top = 12.dp)
                .fillMaxWidth().align(Alignment.TopCenter),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Text(
                    direction?.text ?: stringResource(R.string.location_finding_route),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (routeWorking) LinearProgressIndicator(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Surface(
            modifier = Modifier.navigationBarsPadding().padding(14.dp).fillMaxWidth()
                .align(Alignment.BottomCenter),
            shape = MaterialTheme.shapes.extraLarge,
            shadowElevation = 10.dp,
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.location_navigating_to, destinationName),
                    style = MaterialTheme.typography.titleMedium,
                )
                route?.let {
                    Text(
                        stringResource(
                            R.string.location_route_summary,
                            formatRouteDistance(progress?.distanceMeters ?: it.distanceMeters),
                            formatRouteDuration(progress?.durationSeconds ?: it.durationSeconds),
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                routeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onExitFullScreen, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.FullscreenExit, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.location_map_view))
                    }
                    Button(
                        onClick = onStopNavigation,
                        modifier = Modifier.weight(1f).testTag("full_screen_stop_navigation"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Outlined.Close, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.location_stop_navigation))
                    }
                }
            }
        }
    }
}

private fun formatRouteDistance(meters: Double): String =
    if (meters < 1_000) "${meters.toInt()} m"
    else String.format(java.util.Locale.getDefault(), "%.1f km", meters / 1_000)

private fun formatRouteDuration(seconds: Double): String =
    "${ceil(seconds / 60.0).toInt().coerceAtLeast(1)} min"

private const val MAX_SHARED_CONTACTS = 10

@Composable
internal fun ShareDurationDialog(onDismiss: () -> Unit, onSelected: (Long) -> Unit) {
    var custom by remember { mutableStateOf("") }
    val customHours = custom.toLongOrNull()?.takeIf { it in 1..72 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.location_choose_duration)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onSelected(10_000L) },
                    modifier = Modifier.fillMaxWidth().testTag("location_duration_10_seconds")
                ) {
                    Text(stringResource(R.string.location_ten_seconds_testing))
                }
                listOf(1L, 2L, 8L, 24L).forEach { hours ->
                    OutlinedButton(
                        onClick = { onSelected(hours * 60 * 60 * 1000) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (hours == 1L) stringResource(R.string.location_one_hour)
                            else pluralStringResource(
                                R.plurals.location_hours,
                                hours.toInt(),
                                hours
                            )
                        )
                    }
                }
                OutlinedTextField(
                    custom, { custom = it.filter(Char::isDigit).take(2) }, singleLine = true,
                    label = { Text(stringResource(R.string.location_custom_hours)) },
                    supportingText = { Text(stringResource(R.string.location_custom_range)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("location_custom_hours")
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { customHours?.let { onSelected(it * 60 * 60 * 1000) } },
                enabled = customHours != null,
                modifier = Modifier.testTag("location_custom_confirm")
            ) {
                Text(stringResource(R.string.location_share_for_custom))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
// Formats share time.
private fun formatShareTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val formatter = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    return formatter.format(java.util.Date(timestamp))
}
