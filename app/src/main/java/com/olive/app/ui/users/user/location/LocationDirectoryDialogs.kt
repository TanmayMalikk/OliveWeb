// Safe-app users: directory map filter bar, place picker, and detail dialogs for map view.
package com.olive.app.ui.users.user.location

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveTextButton as TextButton
import android.os.Build
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.safety.HotlineResource
import com.olive.app.data.safety.HotlineResourceKind
import com.olive.app.ui.shared.Ink
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.OliveOutline
import com.olive.app.ui.shared.PaleOlive
import com.olive.app.ui.shared.Paper
import com.olive.app.ui.shared.WalkthroughInWindowDialog
import com.olive.app.ui.users.user.dashboard.hotlineKindLabel
import com.olive.app.ui.users.user.dashboard.hotlineRegionName
import kotlinx.coroutines.launch

/**
 * Filter chips below the map for saved-only directory markers, compass rotation mode, and place list.
 */
@Composable
internal fun DirectoryMapFilterRow(
    visibleCount: Int,
    savedMappedCount: Int,
    showSavedOnly: Boolean,
    headingUp: Boolean,
    onToggleSavedOnly: () -> Unit,
    onToggleHeadingUp: () -> Unit,
    onOpenDirectoryList: () -> Unit,
) {
    val filterColors = FilterChipDefaults.filterChipColors(
        containerColor = Paper,
        labelColor = Ink,
        iconColor = OliveGreen,
        selectedContainerColor = OliveGreen,
        selectedLabelColor = Paper,
        selectedLeadingIconColor = Paper,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = showSavedOnly,
                onClick = onToggleSavedOnly,
                label = {
                    Text(
                        stringResource(R.string.support_map_filter_saved_only, savedMappedCount),
                        maxLines = 1,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (showSavedOnly) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                colors = filterColors,
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = showSavedOnly,
                    borderColor = OliveOutline.copy(alpha = 0.65f),
                    selectedBorderColor = OliveGreen,
                ),
                modifier = Modifier.testTag("map_filter_saved_only"),
            )
            FilterChip(
                selected = headingUp,
                onClick = onToggleHeadingUp,
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
                colors = filterColors,
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = headingUp,
                    borderColor = OliveOutline.copy(alpha = 0.65f),
                    selectedBorderColor = OliveGreen,
                ),
                modifier = Modifier.testTag("map_heading_chip"),
            )
            if (visibleCount > 0) {
                AssistChip(
                    onClick = onOpenDirectoryList,
                    label = {
                        Text(
                            stringResource(
                                if (showSavedOnly) R.string.support_saved_map_locations
                                else R.string.support_directory_map_locations,
                                visibleCount,
                            ),
                            maxLines = 1,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = OliveGreen,
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = Paper,
                        labelColor = Ink,
                        leadingIconContentColor = OliveGreen,
                    ),
                    border = AssistChipDefaults.assistChipBorder(
                        enabled = true,
                        borderColor = OliveOutline.copy(alpha = 0.65f),
                    ),
                    modifier = Modifier.testTag("nearby_support_list"),
                )
            }
        }
        if (showSavedOnly && visibleCount == 0) {
            Text(
                stringResource(R.string.support_map_saved_empty),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.testTag("map_saved_empty_hint"),
            )
        }
    }
}

/**
 * Scrollable list of mapped hotline directory places with save toggles and quick inspection.
 */
@Composable
internal fun DirectoryPlacesListDialog(
    places: List<HotlineResource>,
    savedIds: Set<String>,
    onToggleSave: (HotlineResource) -> Unit,
    onSelect: (HotlineResource) -> Unit,
    onDismiss: () -> Unit,
    inWindow: Boolean = false,
) {
    var selectedKind by remember { mutableStateOf<HotlineResourceKind?>(null) }
    val filteredPlaces = remember(places, selectedKind) {
        places.filter { selectedKind == null || it.kind == selectedKind }
    }
    val bodyContent: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = selectedKind == null,
                    onClick = { selectedKind = null },
                    label = { Text(stringResource(R.string.support_tab_all)) },
                    modifier = Modifier.testTag("map_directory_filter_all"),
                )
                HotlineResourceKind.entries.forEach { kind ->
                    FilterChip(
                        selected = selectedKind == kind,
                        onClick = { selectedKind = kind },
                        label = { Text(hotlineKindLabel(kind)) },
                        modifier = Modifier.testTag("map_directory_filter_${kind.name.lowercase()}"),
                    )
                }
            }
            Text(
                stringResource(R.string.support_directory_map_locations, filteredPlaces.size),
                style = MaterialTheme.typography.labelMedium,
                color = Muted,
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = if (inWindow) 190.dp else 320.dp)) {
                items(filteredPlaces, key = { it.id }) { place ->
                    val saved = place.id in savedIds
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            TextButton(
                                onClick = { onSelect(place) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(place.name, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${hotlineKindLabel(place.kind)} · ${place.city}, ${place.region}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Muted,
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { onToggleSave(place) }) {
                            Icon(
                                imageVector = if (saved) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = stringResource(
                                    if (saved) R.string.support_unsave_resource else R.string.support_save_resource,
                                    place.name,
                                ),
                                tint = if (saved) MaterialTheme.colorScheme.error else Muted,
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
    if (inWindow) {
        WalkthroughInWindowDialog(
            dialogTargetId = "location_directory_dialog",
            title = { Text(stringResource(R.string.support_map_directory_title)) },
            onDismiss = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
            text = bodyContent,
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.support_map_directory_title)) },
            text = bodyContent,
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

/**
 * Detailed place card shown when tapping a directory marker on the map.
 */
@Composable
internal fun DirectoryPlaceDetailDialog(
    resource: HotlineResource,
    saved: Boolean,
    onToggleSave: () -> Unit,
    onDial: () -> Unit,
    onStartNavigation: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(resource.name, Modifier.weight(1f))
                IconButton(
                    onClick = onToggleSave,
                    modifier = Modifier.testTag("map_detail_save"),
                ) {
                    Icon(
                        imageVector = if (saved) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = stringResource(
                            if (saved) R.string.support_unsave_resource else R.string.support_save_resource,
                            resource.name,
                        ),
                        tint = if (saved) MaterialTheme.colorScheme.error else Muted,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = PaleOlive,
                    ) {
                        Text(
                            hotlineKindLabel(resource.kind),
                            style = MaterialTheme.typography.labelMedium,
                            color = Ink,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    if (resource.is247) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Text(
                                stringResource(R.string.support_badge_247),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                Text(
                    "${resource.city}, ${hotlineRegionName(resource.region)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Text(resource.description, style = MaterialTheme.typography.bodyMedium)
                if (resource.address.isNotBlank()) {
                    Text(
                        stringResource(R.string.support_address_label, resource.address),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (resource.hours.isNotBlank()) {
                    Text(
                        stringResource(R.string.resource_hours, resource.hours),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
                Text(resource.phone, style = MaterialTheme.typography.titleMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onStartNavigation) {
                Icon(Icons.Outlined.Navigation, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.location_start_navigation))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDial) {
                    Icon(Icons.Outlined.Phone, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.support_open_dialer))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

/**
 * Renders the background location button composable.
 *
 * @param requestPermission Function used to transform or provide request permission.
 * @param autoExplain Whether to apply auto explain.
 * @param onExplained Callback invoked when explained changes or occurs.
 */
@Composable
internal fun BackgroundLocationButton(
    requestPermission: suspend () -> Boolean,
    autoExplain: Boolean = false,
    onExplained: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var explain by remember { mutableStateOf(false) }
    var requesting by remember { mutableStateOf(false) }
    val pulse = rememberInfiniteTransition(label = "background_location_hint")
    val border by pulse.animateColor(
        initialValue = MaterialTheme.colorScheme.primary,
        targetValue = MaterialTheme.colorScheme.primaryContainer,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "location_button_border",
    )
    LaunchedEffect(autoExplain) {
        if (autoExplain) { explain = true; onExplained() }
    }
    val title = stringResource(R.string.location_background_access)
    OutlinedButton(onClick = { explain = true }, enabled = !requesting,
        border = BorderStroke(3.dp, border),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth().testTag("background_location")) { Text(title) }
    if (explain) BackgroundLocationPermissionDialog(
        onDismiss = { explain = false },
        onContinue = {
            explain = false
            requesting = true
            scope.launch { try { requestPermission() } finally { requesting = false } }
        },
    )
}

/**
 * Displays the background location permission dialog overlay.
 *
 * @param onDismiss Callback invoked when the dialog or sheet is dismissed.
 * @param onContinue Callback invoked when continue changes or occurs.
 */
@Composable
internal fun BackgroundLocationPermissionDialog(onDismiss: () -> Unit, onContinue: () -> Unit) {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
    val help = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        stringResource(R.string.location_background_settings, context.packageManager.backgroundPermissionOptionLabel)
    } else stringResource(R.string.location_background_dialog)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.location_background_access)) },
        text = { Text(help) },
        confirmButton = {
            Button(onClick = onContinue,
                modifier = Modifier.focusRequester(focus).testTag("background_location_continue")) {
                Text(stringResource(R.string.next))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
