// Safe-app users: directory map filter bar, place picker, and detail dialogs for map view.
package com.olive.app.ui.users.user.location

import com.olive.app.ui.shared.OliveTextButton as TextButton
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.safety.HotlineResource
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.users.user.dashboard.hotlineKindLabel
import com.olive.app.ui.users.user.dashboard.hotlineRegionName

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
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.support_map_directory_title)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(places, key = { it.id }) { place ->
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
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = OliveGreen.copy(alpha = 0.14f),
                    ) {
                        Text(
                            hotlineKindLabel(resource.kind),
                            style = MaterialTheme.typography.labelMedium,
                            color = OliveGreen,
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
