// Safe-app users: provides full-screen route guidance and location-sharing dialogs.
package com.olive.app.ui.users.user.location

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveTextButton as TextButton
import com.olive.app.ui.shared.SkeletonLayout
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.RoundaboutRight
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Straight
import androidx.compose.material.icons.outlined.TurnLeft
import androidx.compose.material.icons.outlined.TurnRight
import androidx.compose.material.icons.outlined.TurnSlightLeft
import androidx.compose.material.icons.outlined.TurnSlightRight
import androidx.compose.material.icons.outlined.UTurnLeft
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.location.LocationRoute
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.location.RouteDirection
import com.olive.app.data.location.RouteProfile
import com.olive.app.data.location.supportDistanceMeters
import com.olive.app.ui.shared.Ink
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.OliveOutline
import com.olive.app.ui.shared.OlivePrivateLayout
import com.olive.app.ui.shared.PaleOlive
import com.olive.app.ui.shared.Paper
import com.olive.app.ui.users.shared.navigation.LocalQuickExit
import kotlin.math.ceil

/**
 * Expands the same map for browsing or route guidance.
 *
 * @param points Collection of points values to process.
 * @param ownPoint Optional own point supplied to [FullScreenLocationMap].
 * @param route Optional route supplied to [FullScreenLocationMap].
 * @param destinationName Destination name supplied to [FullScreenLocationMap].
 * @param routeWorking Whether to apply route working.
 * @param routeError Optional route error supplied to [FullScreenLocationMap].
 * @param navigating Whether to apply navigating.
 * @param showOwnPointInBlue Whether to show own point in blue.
 * @param onExitFullScreen Callback invoked when exit full screen changes or occurs.
 * @param onStopNavigation Callback invoked when stop navigation changes or occurs.
 * @param supportServices Collection of support services values to process.
 * @param onSupportSelected Callback invoked when support selected changes or occurs.
 * @param onViewportCenterChanged Callback invoked when viewport center changed changes or occurs.
 * @param onLoadMoreSupport Callback invoked when load more support changes or occurs.
 * @param loadingMoreSupport Whether to apply loading more support.
 */
@Composable
internal fun FullScreenLocationMap(
    points: List<MapLocationPoint>,
    ownPoint: MapLocationPoint?,
    route: LocationRoute?,
    destinationName: String,
    routeWorking: Boolean,
    routeError: String?,
    navigating: Boolean,
    showOwnPointInBlue: Boolean,
    onExitFullScreen: () -> Unit,
    onStopNavigation: () -> Unit,
    supportServices: List<com.olive.app.data.location.NearbySupportService> = emptyList(),
    onSupportSelected: ((com.olive.app.data.location.NearbySupportService) -> Unit)? = null,
    onViewportCenterChanged: ((Double, Double) -> Unit)? = null,
    onLoadMoreSupport: (() -> Unit)? = null,
    loadingMoreSupport: Boolean = false,
    headingUp: Boolean = false,
    headingDegrees: Float? = null,
    headingState: State<Float?>? = null,
    onToggleHeadingUp: (() -> Unit)? = null,
    showSavedOnly: Boolean = false,
    savedCount: Int = 0,
    onToggleSavedOnly: (() -> Unit)? = null,
    routeProfile: RouteProfile = RouteProfile.DRIVING,
    onUserGesture: (() -> Unit)? = null,
) {
    val direction = ownPoint?.let { route?.nextDirection(it.latitude, it.longitude) }
    val upcoming = route?.let { upcomingRouteDirection(it, direction) }
    val distanceToNextTurn = if (route != null && ownPoint != null && direction != null) {
        distanceToDirectionMeters(route, ownPoint.latitude, ownPoint.longitude, direction)
    } else null
    val progress = ownPoint?.let { route?.progress(it.latitude, it.longitude) }
    val view = LocalView.current
    val density = LocalDensity.current
    val hasFloatingChrome = LocalQuickExit.current != null
    val bottomCardClearance = if (hasFloatingChrome) OlivePrivateLayout.FloatingActionClearance else 14.dp
    val showBottomCard = navigating || loadingMoreSupport || onLoadMoreSupport != null || routeError != null
    var bottomOverlayHeight by remember { mutableStateOf(0.dp) }
    DisposableEffect(view, navigating) {
        val previous = view.keepScreenOn
        if (navigating) view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    Box(Modifier.fillMaxSize().testTag(if (navigating) "full_screen_navigation" else "full_screen_location_map")) {
        InteractiveLocationMap(
            points = points,
            modifier = Modifier.fillMaxSize(),
            ownPointUid = ownPoint?.uid,
            showOwnPointInBlue = showOwnPointInBlue,
            route = route,
            navigationActive = navigating,
            supportServices = supportServices,
            onSupportSelected = onSupportSelected,
            onViewportCenterChanged = onViewportCenterChanged,
            headingUp = headingUp,
            headingDegrees = headingDegrees,
            headingState = headingState,
            onToggleHeadingUp = onToggleHeadingUp,
            fitSupportServices = (onLoadMoreSupport == null && ownPoint == null) || (showSavedOnly && savedCount > 0),
            bottomControlsPadding = if (showBottomCard) (bottomOverlayHeight + 8.dp)
            else if (hasFloatingChrome) OlivePrivateLayout.FloatingActionClearance else 28.dp,
            onUserGesture = onUserGesture,
        )
        IconButton(
            onClick = onExitFullScreen,
            modifier = Modifier.statusBarsPadding().padding(12.dp).align(Alignment.TopStart)
                .shadow(4.dp, CircleShape)
                .border(BorderStroke(1.dp, OliveOutline.copy(alpha = 0.55f)), CircleShape)
                .testTag("exit_full_screen_navigation"),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Paper,
                contentColor = Ink,
            ),
        ) {
            Icon(Icons.Outlined.FullscreenExit, stringResource(R.string.location_exit_full_screen))
        }
        if (!navigating && onToggleSavedOnly != null) {
            FilterChip(
                selected = showSavedOnly,
                onClick = onToggleSavedOnly,
                label = {
                    Text(
                        stringResource(R.string.support_map_filter_saved_only, savedCount),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                leadingIcon = {
                    Icon(
                        if (showSavedOnly) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
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
                    selected = showSavedOnly,
                    borderColor = OliveOutline.copy(alpha = 0.7f),
                    selectedBorderColor = OliveGreen,
                    borderWidth = 1.dp,
                    selectedBorderWidth = 1.dp,
                ),
                elevation = FilterChipDefaults.elevatedFilterChipElevation(elevation = 4.dp),
                modifier = Modifier.statusBarsPadding().padding(start = 68.dp, top = 12.dp)
                    .align(Alignment.TopStart).testTag("full_screen_saved_filter"),
            )
        }
        if (navigating) Surface(
            modifier = Modifier.statusBarsPadding().padding(start = 64.dp, end = 64.dp, top = 10.dp)
                .fillMaxWidth().align(Alignment.TopCenter),
            shape = RoundedCornerShape(20.dp),
            color = OliveGreen,
            contentColor = Color.White,
            shadowElevation = 8.dp,
        ) {
            Column {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.16f),
                        contentColor = Color.White,
                        modifier = Modifier.size(42.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(maneuverIconFor(direction?.text), null, Modifier.size(26.dp))
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (routeWorking) com.olive.app.ui.shared.LoadingSkeleton(SkeletonLayout.Directions)
                        else {
                            if (distanceToNextTurn != null && distanceToNextTurn >= 15.0) Text(
                                formatRouteDistance(distanceToNextTurn),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.88f),
                            )
                            Text(
                                direction?.text ?: stringResource(R.string.location_finding_route),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (!routeWorking && upcoming != null) {
                    Row(
                        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.18f))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            maneuverIconFor(upcoming.text),
                            null,
                            Modifier.size(16.dp),
                            tint = Color.White.copy(alpha = 0.9f),
                        )
                        Text(
                            stringResource(R.string.location_next_direction, upcoming.text),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.92f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (showBottomCard) Box(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { bottomOverlayHeight = with(density) { it.height.toDp() } }
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = bottomCardClearance),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shadowElevation = 10.dp,
            ) {
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        Modifier.align(Alignment.CenterHorizontally)
                            .width(36.dp).height(4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    if (navigating) TripSummaryMetrics(
                        route = route,
                        progress = progress,
                        destinationName = destinationName,
                        routeProfile = routeProfile,
                    )
                    routeError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (loadingMoreSupport) com.olive.app.ui.shared.LoadingSkeleton(SkeletonLayout.NearbySupport)
                    else if (onLoadMoreSupport != null) OutlinedButton(
                        onClick = onLoadMoreSupport,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.load_more_hotline_locations))
                    }
                    if (navigating) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onExitFullScreen,
                            modifier = Modifier.weight(1f).height(44.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        ) {
                            Icon(Icons.Outlined.FullscreenExit, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.location_map_view),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Button(
                            onClick = onStopNavigation,
                            modifier = Modifier.weight(1f).height(44.dp).testTag("full_screen_stop_navigation"),
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
    }
}

/**
 * Shared trip summary metrics showing ETA duration, remaining distance, arrival clock badge,
 * travel mode, destination name, and trip progress bar.
 */
@Composable
internal fun TripSummaryMetrics(
    route: LocationRoute?,
    progress: com.olive.app.data.location.RouteProgress?,
    destinationName: String,
    routeProfile: RouteProfile,
) {
    val remainingMeters = progress?.distanceMeters ?: route?.distanceMeters
    val remainingSeconds = progress?.durationSeconds ?: route?.durationSeconds
    if (route != null && remainingMeters != null && remainingSeconds != null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    formatRouteDuration(remainingSeconds),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = OliveGreen,
                    maxLines = 1,
                )
                Text(
                    "· ${formatRouteDistance(remainingMeters)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = PaleOlive,
                contentColor = Ink,
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(14.dp))
                    Text(
                        formatArrivalTime(remainingSeconds),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (routeProfile == RouteProfile.WALKING) Icons.AutoMirrored.Outlined.DirectionsWalk
            else Icons.Outlined.DirectionsCar,
            contentDescription = null,
            tint = OliveGreen,
            modifier = Modifier.size(16.dp),
        )
        Text(
            destinationName.ifBlank { stringResource(R.string.location_finding_route) },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (route != null && remainingMeters != null) {
        LinearProgressIndicator(
            progress = { routeCompletionProgress(route.distanceMeters, remainingMeters) },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
            color = OliveGreen,
            trackColor = OliveGreen.copy(alpha = 0.14f),
        )
    }
}

/** Computes normalized [0f..1f] completion ratio along the active route. */
internal fun routeCompletionProgress(totalMeters: Double, remainingMeters: Double): Float {
    if (!totalMeters.isFinite() || !remainingMeters.isFinite() || totalMeters <= 0.0) return 0f
    val completed = (totalMeters - remainingMeters).coerceIn(0.0, totalMeters)
    return (completed / totalMeters).toFloat()
}

/** Returns the next navigation instruction following [current], or null at the final leg. */
internal fun upcomingRouteDirection(route: LocationRoute, current: RouteDirection?): RouteDirection? {
    if (current == null) return null
    val index = route.directions.indexOf(current)
    return if (index in 0 until route.directions.lastIndex) route.directions[index + 1] else null
}

/** Estimates remaining distance along [route] from ([latitude], [longitude]) to the end of [direction]. */
internal fun distanceToDirectionMeters(
    route: LocationRoute,
    latitude: Double,
    longitude: Double,
    direction: RouteDirection,
): Double? {
    val coords = route.coordinates
    if (coords.isEmpty()) return null
    val targetIndex = direction.toIndex.coerceIn(0, coords.lastIndex)
    val nearestIndex = coords.indices.minByOrNull { index ->
        val c = coords[index]
        val dLat = c.latitude - latitude
        val dLon = c.longitude - longitude
        dLat * dLat + dLon * dLon
    } ?: 0
    if (nearestIndex >= targetIndex) return 0.0
    var sum = 0.0
    for (i in nearestIndex until targetIndex) {
        val a = coords[i]
        val b = coords[i + 1]
        sum += supportDistanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
    }
    return sum
}

/** Formats the estimated arrival clock time from [remainingSeconds] and [nowMillis]. */
internal fun formatArrivalTime(
    remainingSeconds: Double,
    nowMillis: Long = System.currentTimeMillis(),
): String {
    val safeSeconds = remainingSeconds.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    val arrivalMillis = nowMillis + (safeSeconds * 1_000.0).toLong()
    return java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(arrivalMillis))
}

/** Selects the directional icon matching a turn-by-turn [instruction] text string. */
private fun maneuverIconFor(instruction: String?): ImageVector {
    val text = instruction?.lowercase().orEmpty()
    return when {
        text.isBlank() -> Icons.Outlined.Navigation
        "roundabout" in text || "traffic circle" in text || "rotary" in text -> Icons.Outlined.RoundaboutRight
        "u-turn" in text || "make a u" in text -> Icons.Outlined.UTurnLeft
        "slight left" in text || "keep left" in text || "bear left" in text -> Icons.Outlined.TurnSlightLeft
        "slight right" in text || "keep right" in text || "bear right" in text -> Icons.Outlined.TurnSlightRight
        "left" in text -> Icons.Outlined.TurnLeft
        "right" in text -> Icons.Outlined.TurnRight
        "arrive" in text || "destination" in text || "you have arrived" in text -> Icons.Outlined.Flag
        "continue" in text || "straight" in text || "head " in text -> Icons.Outlined.Straight
        else -> Icons.Outlined.Navigation
    }
}

/**
 * Formats route length as metres or kilometres for the navigation summary.
 *
 * @param meters Meters supplied to [formatRouteDistance].
 * @return The resulting format route distance string.
 */
internal fun formatRouteDistance(meters: Double): String =
    if (meters < 1_000) "${meters.toInt()} m"
    else String.format(java.util.Locale.getDefault(), "%.1f km", meters / 1_000)

/**
 * Rounds the remaining route time up to a whole number of minutes.
 *
 * @param seconds Seconds supplied to [formatRouteDuration].
 * @return The resulting format route duration string.
 */
internal fun formatRouteDuration(seconds: Double): String =
    "${ceil(seconds / 60.0).toInt().coerceAtLeast(1)} min"

/**
 * Lets a user choose how long trusted contacts may see their live location.
 *
 * @param onDismiss Callback invoked when the dialog or sheet is dismissed.
 * @param onSelected Callback invoked when selected changes or occurs.
 */
@Composable
internal fun ShareDurationDialog(onDismiss: () -> Unit, onSelected: (Long) -> Unit) {
    var custom by remember { mutableStateOf("") }
    val customDuration = ShareDurationPolicy.customDurationMilliseconds(custom)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.location_choose_duration)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onSelected(ShareDurationPolicy.TEST_DURATION_MILLISECONDS) },
                    modifier = Modifier.fillMaxWidth().testTag("location_duration_10_seconds"),
                ) {
                    Text(stringResource(R.string.location_ten_seconds_testing))
                }
                listOf(1L, 2L, 8L, 24L).forEach { hours ->
                    OutlinedButton(
                        onClick = {
                            ShareDurationPolicy.millisecondsForHours(hours)?.let(onSelected)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (hours == 1L) stringResource(R.string.location_one_hour)
                            else pluralStringResource(R.plurals.location_hours, hours.toInt(), hours)
                        )
                    }
                }
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it.filter(Char::isDigit).take(2) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.location_custom_hours)) },
                    supportingText = { Text(stringResource(R.string.location_custom_range)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("location_custom_hours"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { customDuration?.let(onSelected) },
                enabled = customDuration != null,
                modifier = Modifier.testTag("location_custom_confirm"),
            ) {
                Text(stringResource(R.string.location_share_for_custom))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * Formats a location-sharing expiry time using the phone's time style.
 *
 * @param timestamp Epoch timestamp in milliseconds.
 * @return The resulting format share time string.
 */
@Composable
internal fun formatShareTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val formatter = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    return formatter.format(java.util.Date(timestamp))
}
