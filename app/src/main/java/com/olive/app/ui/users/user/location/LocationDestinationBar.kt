// Safe-app users: provides free-use GPS destination entry with 50ms/as-you-type autofill suggestions.
package com.olive.app.ui.users.user.location

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveTextButton as TextButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.location.DestinationAutocompleteClient
import com.olive.app.data.location.DestinationSuggestion
import com.olive.app.data.location.DestinationSuggestionKind
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.location.RouteProfile
import com.olive.app.data.location.localDestinationSuggestions
import com.olive.app.data.location.parseCoordinates
import com.olive.app.data.safety.HotlineResource
import com.olive.app.ui.shared.AccessibleMessage
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.SoftCardSurface
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Free-use GPS destination card accepting arbitrary addresses or latitude/longitude coordinates
 * with immediate local suggestions + 50ms debounced remote autocomplete.
 */
@Composable
internal fun FreeUseGpsDestinationCard(
    controller: LocationMapController,
    state: LocationMapState,
    directoryPlaces: List<HotlineResource>,
    routeNotConfigured: String,
    routeUnavailable: String,
    routeFailed: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable(state.ownerUid) { mutableStateOf("") }
    var suppressNextAutocomplete by remember { mutableStateOf(false) }
    var searchingRemote by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<DestinationSuggestion>>(emptyList()) }
    var resolveError by remember { mutableStateOf<String?>(null) }
    val latestOwnPoint by rememberUpdatedState(state.ownPoint)

    val coordFormat = stringResource(R.string.location_gps_coordinates_label, "%1\$s")
    val coordSubtitle = stringResource(R.string.location_gps_coordinates_hint)
    val noMatchMessage = stringResource(R.string.location_gps_no_match)

    fun startRouteTo(title: String, lat: Double, lon: Double) {
        suppressNextAutocomplete = true
        suggestions = emptyList()
        resolveError = null
        controller.stopNavigation()
        if (latestOwnPoint != null) {
            controller.selectPrivateDestination(title, lat, lon)
            controller.startNavigation(routeNotConfigured, routeUnavailable, routeFailed)
        } else {
            controller.queuePrivateDestination(title, lat, lon, autoStart = true)
            controller.selectPrivateDestination(title, lat, lon)
        }
    }

    LaunchedEffect(query, directoryPlaces) {
        if (suppressNextAutocomplete) {
            suppressNextAutocomplete = false
            searchingRemote = false
            suggestions = emptyList()
            return@LaunchedEffect
        }
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            searchingRemote = false
            suggestions = emptyList()
            return@LaunchedEffect
        }
        val local = localDestinationSuggestions(
            query = trimmed,
            directoryPlaces = directoryPlaces,
            coordinateTitle = { formatted -> String.format(Locale.getDefault(), coordFormat, formatted) },
            coordinateSubtitle = coordSubtitle,
        )
        suggestions = local
        if (trimmed.length < 2 || parseCoordinates(trimmed) != null) {
            searchingRemote = false
            return@LaunchedEffect
        }
        delay(50.milliseconds)
        searchingRemote = true
        try {
            val proximity = latestOwnPoint
            val remote = DestinationAutocompleteClient.suggest(
                query = trimmed,
                proximityLatitude = proximity?.latitude,
                proximityLongitude = proximity?.longitude,
            )
            val seen = local.map { "${it.latitude},${it.longitude}" }.toMutableSet()
            val merged = local + remote.filter { seen.add("${it.latitude},${it.longitude}") }
            suggestions = merged.take(6)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Keep instant local matches if remote lookup fails.
        } finally {
            searchingRemote = false
        }
    }

    fun submitFreeText() {
        val currentQuery = query.trim()
        if (currentQuery.isEmpty()) return
        val origin = latestOwnPoint
        resolveError = null
        suggestions.firstOrNull()?.let { first ->
            val targetTitle = if (first.kind == DestinationSuggestionKind.Coordinates) currentQuery else first.title
            startRouteTo(targetTitle, first.latitude, first.longitude)
            return
        }
        scope.launch {
            searchingRemote = true
            try {
                val resolved = DestinationAutocompleteClient.resolveFreeText(
                    context = context,
                    query = currentQuery,
                    directoryPlaces = directoryPlaces,
                    proximityLatitude = origin?.latitude,
                    proximityLongitude = origin?.longitude,
                )
                if (resolved != null) {
                    query = resolved.title
                    startRouteTo(resolved.title, resolved.latitude, resolved.longitude)
                } else {
                    resolveError = noMatchMessage
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                resolveError = noMatchMessage
            } finally {
                searchingRemote = false
            }
        }
    }

    Surface(
        Modifier.fillMaxWidth().testTag("map_gps_destination_card"),
        shape = MaterialTheme.shapes.large,
        color = SoftCardSurface,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.location_gps_destination_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (state.privateDestination != null && !state.navigating) {
                    TextButton(
                        onClick = {
                            query = ""
                            resolveError = null
                            controller.stopNavigation()
                        },
                    ) {
                        Text(stringResource(R.string.location_gps_clear_destination))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.routeProfile == RouteProfile.DRIVING,
                    onClick = { controller.setRouteProfile(RouteProfile.DRIVING) },
                    enabled = !state.navigating,
                    label = { Text(stringResource(R.string.location_route_driving)) },
                    leadingIcon = { Icon(Icons.Outlined.DirectionsCar, null, Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("map_route_profile_driving"),
                )
                FilterChip(
                    selected = state.routeProfile == RouteProfile.WALKING,
                    onClick = { controller.setRouteProfile(RouteProfile.WALKING) },
                    enabled = !state.navigating,
                    label = { Text(stringResource(R.string.location_route_walking)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DirectionsWalk, null, Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("map_route_profile_walking"),
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { next ->
                    suppressNextAutocomplete = false
                    resolveError = null
                    query = next.take(180)
                },
                label = { Text(stringResource(R.string.location_gps_destination_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = ""; suggestions = emptyList(); resolveError = null }) {
                            Icon(Icons.Outlined.Clear, stringResource(R.string.location_gps_clear_destination))
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submitFreeText() }),
                modifier = Modifier.fillMaxWidth().testTag("map_gps_destination_input"),
            )
            if (searchingRemote && suggestions.isEmpty()) {
                Text(
                    stringResource(R.string.location_gps_searching),
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
            if (suggestions.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 2.dp,
                ) {
                    Column {
                        suggestions.forEachIndexed { index, item ->
                            if (index > 0) HorizontalDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        query = item.title
                                        startRouteTo(item.title, item.latitude, item.longitude)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .testTag("map_gps_suggestion_$index"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = when (item.kind) {
                                        DestinationSuggestionKind.Coordinates -> Icons.Outlined.MyLocation
                                        DestinationSuggestionKind.DirectoryPlace -> Icons.Outlined.LocationOn
                                        DestinationSuggestionKind.Address -> Icons.Outlined.Navigation
                                    },
                                    contentDescription = null,
                                    tint = if (item.kind == DestinationSuggestionKind.DirectoryPlace) OliveGreen
                                    else MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (item.subtitle.isNotBlank()) {
                                        Text(
                                            item.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Muted,
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
            resolveError?.let { AccessibleMessage(it) }
            if (query.isNotBlank() && !state.navigating) {
                Button(
                    onClick = { submitFreeText() },
                    enabled = !state.routeWorking,
                    modifier = Modifier.fillMaxWidth().testTag("map_gps_start_route"),
                ) {
                    Icon(Icons.Outlined.Navigation, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.location_gps_start_route))
                }
            }
        }
    }
}
