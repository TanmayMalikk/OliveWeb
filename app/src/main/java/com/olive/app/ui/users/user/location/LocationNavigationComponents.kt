// Safe-app users: provides full-screen route guidance and location-sharing dialogs.
package com.olive.app.ui.users.user.location

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.location.LocationRoute
import com.olive.app.data.location.MapLocationPoint
import kotlin.math.ceil

// Keeps the map awake and shows the next direction while a safe contact is navigating.
@Composable
internal fun FullScreenNavigationMap(
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
                if (routeWorking) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
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

// Formats route length as metres or kilometres for the navigation summary.
internal fun formatRouteDistance(meters: Double): String =
    if (meters < 1_000) "${meters.toInt()} m"
    else String.format(java.util.Locale.getDefault(), "%.1f km", meters / 1_000)

// Rounds the remaining route time up to a whole number of minutes.
internal fun formatRouteDuration(seconds: Double): String =
    "${ceil(seconds / 60.0).toInt().coerceAtLeast(1)} min"

// Lets a user choose how long trusted contacts may see their live location.
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

// Formats a location-sharing expiry time using the phone's time style.
@Composable
internal fun formatShareTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val formatter = remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    return formatter.format(java.util.Date(timestamp))
}
