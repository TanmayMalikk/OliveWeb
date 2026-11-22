// Safe-app users: renders category tabs, cards, badges, and favorites UI for the hotline directory.
package com.olive.app.ui.users.user.dashboard

import com.olive.app.ui.shared.OliveCard as Card
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveTextButton as TextButton
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.safety.HotlineResource
import com.olive.app.data.safety.HotlineResourceKind
import com.olive.app.ui.shared.Ink
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen

internal enum class HotlineTab {
    All,
    Saved,
    Hotlines,
    Shelters,
    LegalAid,
    Counseling,
    CrisisServices,
}

/**
 * Maps a directory tab to its optional [HotlineResourceKind] filter.
 */
internal fun HotlineTab.resourceKind(): HotlineResourceKind? = when (this) {
    HotlineTab.All, HotlineTab.Saved -> null
    HotlineTab.Hotlines -> HotlineResourceKind.Hotline
    HotlineTab.Shelters -> HotlineResourceKind.Shelter
    HotlineTab.LegalAid -> HotlineResourceKind.LegalAid
    HotlineTab.Counseling -> HotlineResourceKind.Counseling
    HotlineTab.CrisisServices -> HotlineResourceKind.CrisisService
}

/**
 * Horizontally scrollable directory category tabs including Saved favorites.
 */
@Composable
internal fun HotlineCategoryTabs(
    selected: HotlineTab,
    savedCount: Int,
    onSelect: (HotlineTab) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().testTag("hotline_tabs"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
    ) {
        items(HotlineTab.entries, key = { it.name }) { tab ->
            val isSelected = selected == tab
            val label = when (tab) {
                HotlineTab.All -> stringResource(R.string.support_tab_all)
                HotlineTab.Saved -> stringResource(R.string.support_tab_saved, savedCount)
                HotlineTab.Hotlines -> stringResource(R.string.support_tab_hotlines)
                HotlineTab.Shelters -> stringResource(R.string.support_tab_shelters)
                HotlineTab.LegalAid -> stringResource(R.string.support_tab_legal_aid)
                HotlineTab.Counseling -> stringResource(R.string.support_tab_counseling)
                HotlineTab.CrisisServices -> stringResource(R.string.support_tab_crisis_services)
            }
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(tab) },
                label = { Text(label) },
                leadingIcon = if (tab == HotlineTab.Saved) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                } else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                modifier = Modifier.testTag("hotline_tab_${tab.name.lowercase()}"),
            )
        }
    }
}

/**
 * Displays a support directory entry with category badge, 24/7 indicator, heart toggle,
 * public address (when known), hours, phone/text info, and safe action buttons.
 */
@Composable
internal fun HotlineResourceCard(
    resource: HotlineResource,
    saved: Boolean,
    onToggleSave: () -> Unit,
    onOpen: (String, Boolean) -> Unit,
    onNavigate: (() -> Unit)? = null,
) {
    val kindColor = when (resource.kind) {
        HotlineResourceKind.Hotline -> OliveGreen
        HotlineResourceKind.Shelter -> MaterialTheme.colorScheme.secondary
        HotlineResourceKind.LegalAid -> MaterialTheme.colorScheme.tertiary
        HotlineResourceKind.Counseling -> MaterialTheme.colorScheme.primary
        HotlineResourceKind.CrisisService -> MaterialTheme.colorScheme.error
    }
    Card(
        Modifier.fillMaxWidth().testTag("hotline_${resource.id}"),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = Ink,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = kindColor.copy(alpha = 0.14f),
                ) {
                    Text(
                        text = hotlineKindLabel(resource.kind),
                        color = kindColor,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                if (resource.is247) HotlineAlwaysOpenBadge()
                Spacer(Modifier.weight(1f))
                val saveDesc = stringResource(
                    if (saved) R.string.support_unsave_resource else R.string.support_save_resource,
                    resource.name,
                )
                IconButton(
                    onClick = onToggleSave,
                    modifier = Modifier.testTag("hotline_save_${resource.id}")
                        .semantics { contentDescription = saveDesc },
                ) {
                    Icon(
                        imageVector = if (saved) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = saveDesc,
                        tint = if (saved) MaterialTheme.colorScheme.error else Muted,
                    )
                }
            }

            Column(
                Modifier.semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    resource.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "${resource.city}, ${hotlineRegionName(resource.region)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
                Text(resource.description, style = MaterialTheme.typography.bodyMedium)
                if (resource.address.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Outlined.LocationOn,
                            contentDescription = null,
                            tint = OliveGreen,
                            modifier = Modifier.size(18.dp).padding(top = 2.dp),
                        )
                        SelectionContainer {
                            Text(
                                stringResource(R.string.support_address_label, resource.address),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (resource.hours.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = Muted,
                            modifier = Modifier.size(18.dp).padding(top = 2.dp),
                        )
                        Text(
                            stringResource(R.string.resource_hours, resource.hours),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(
                    resource.phone,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                )
                if (resource.textNumber.isNotBlank()) {
                    Text(
                        stringResource(R.string.support_text_number, resource.textNumber),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val dialLabel = stringResource(R.string.support_dial_label, resource.name, resource.phone)
                OutlinedButton(
                    onClick = { onOpen(resource.phoneUri, true) },
                    modifier = Modifier.heightIn(min = 48.dp)
                        .semantics { contentDescription = dialLabel },
                ) {
                    Icon(Icons.Outlined.Phone, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.support_open_dialer))
                }
                val detailsLabel = stringResource(R.string.support_details_label, resource.name)
                TextButton(
                    onClick = { onOpen(resource.website, false) },
                    modifier = Modifier.heightIn(min = 48.dp)
                        .semantics { contentDescription = detailsLabel },
                ) {
                    Text(stringResource(R.string.support_service_details))
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, null)
                }
                if (onNavigate != null) {
                    TextButton(
                        onClick = onNavigate,
                        modifier = Modifier.heightIn(min = 48.dp)
                            .testTag("hotline_route_${resource.id}"),
                    ) {
                        Icon(Icons.Outlined.Navigation, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.support_route_on_map))
                    }
                }
            }
        }
    }
}

/**
 * Empty state shown in the Saved tab when the account has not hearted any resources yet.
 */
@Composable
internal fun SavedResourcesEmptyCard() {
    Card(
        Modifier.fillMaxWidth().testTag("hotline_saved_empty"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(28.dp),
            )
            Text(
                stringResource(R.string.support_saved_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.support_saved_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
            )
        }
    }
}

@Composable
private fun HotlineAlwaysOpenBadge() {
    val description = stringResource(R.string.hotline_available_247)
    val transition = rememberInfiniteTransition(label = "24/7 hotline availability")
    val opacity by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "Availability light opacity",
    )
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = OliveGreen.copy(alpha = 0.14f),
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                Modifier.size(8.dp).alpha(opacity).background(OliveGreen, CircleShape),
            )
            Text(
                stringResource(R.string.support_badge_247),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = OliveGreen,
            )
        }
    }
}

@Composable
internal fun hotlineKindLabel(kind: HotlineResourceKind): String = stringResource(
    when (kind) {
        HotlineResourceKind.Hotline -> R.string.support_resource_hotline
        HotlineResourceKind.Shelter -> R.string.support_resource_shelter
        HotlineResourceKind.LegalAid -> R.string.support_resource_legal_aid
        HotlineResourceKind.Counseling -> R.string.support_resource_counseling
        HotlineResourceKind.CrisisService -> R.string.support_resource_crisis_service
    }
)

/**
 * Changes a Canadian region code into the province or territory name shown on the hotline card.
 */
@Composable
internal fun hotlineRegionName(code: String): String = stringResource(
    when (code) {
        "AB" -> R.string.support_region_ab
        "BC" -> R.string.support_region_bc
        "MB" -> R.string.support_region_mb
        "NB" -> R.string.support_region_nb
        "NL" -> R.string.support_region_nl
        "NS" -> R.string.support_region_ns
        "NT" -> R.string.support_region_nt
        "NU" -> R.string.support_region_nu
        "ON" -> R.string.support_region_on
        "PE" -> R.string.support_region_pe
        "QC" -> R.string.support_region_qc
        "SK" -> R.string.support_region_sk
        "YT" -> R.string.support_region_yt
        else -> R.string.support_canada
    }
)
