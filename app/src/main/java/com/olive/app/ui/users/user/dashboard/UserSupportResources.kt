// Safe-app users: lists safety resources and tile helpers on the user dashboard.
package com.olive.app.ui.users.user.dashboard

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveTextButton as TextButton
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.olive.app.R
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.UserProfile
import com.olive.app.data.location.DeviceLocation
import com.olive.app.data.safety.CanadianRegion
import com.olive.app.data.safety.HotlineDirectory
import com.olive.app.data.safety.SavedHotlineStore
import com.olive.app.data.safety.hotlineDialNumber
import com.olive.app.ui.shared.AccessibleMessage
import com.olive.app.ui.shared.LoadingSkeleton
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.SkeletonLayout
import com.olive.app.ui.shared.oliveScrollbar
import com.olive.app.ui.shared.rememberResourceText
import com.olive.app.ui.shared.walkthroughTarget
import com.olive.app.ui.users.shared.navigation.Field
import com.olive.app.ui.users.shared.navigation.Heading
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.users.user.account.SafeBack
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.olive.app.ui.shared.OliveCard as Card
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import com.olive.app.data.safety.HotlineResource
import com.olive.app.data.safety.HotlineResourceKind
import com.olive.app.ui.shared.Ink
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.Paper

internal val LocalHotlineLocationLookup =
    staticCompositionLocalOf<suspend (Context) -> DeliveryAddress?> { DeviceLocation::address }
internal val LocalLocationPermissionRequest =
    staticCompositionLocalOf<suspend () -> Boolean> { { false } }
internal val LocalBackgroundLocationPermissionRequest =
    staticCompositionLocalOf<(suspend () -> Boolean)?> { null }

/**
 * Start from the saved address. Device lookup is explicit and never writes the detected address to the account.
 *
 * @param session Shared session view model owning application state and actions.
 * @param profile Account profile details.
 */
@Composable
internal fun SafeResourcesScreen(session: SessionViewModel, profile: UserProfile) {
    val context = LocalContext.current
    val directory by produceState<Result<HotlineDirectory>?>(null, context) {
        value = withContext(Dispatchers.IO) {
            runCatching { HotlineDirectory.readBundled(context) }
        }
    }
    val catalog = directory?.getOrNull()
    val address = profile.address
    val savedRegion = catalog?.regionFor(address)
    val savedSelection = savedRegion ?: if (catalog?.isCanada(address) == true) "CA" else ""
    var selection by rememberSaveable(catalog != null, address) {
        mutableStateOf(savedSelection)
    }
    var city by rememberSaveable(address) { mutableStateOf(address.city) }
    var locating by remember { mutableStateOf(false) }
    var locationError by remember { mutableStateOf<Int?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    val requestLocationPermission = LocalLocationPermissionRequest.current
    val lookupLocation = LocalHotlineLocationLookup.current
    val scope = rememberCoroutineScope()
    /** Requests location access, then updates the directory search area. */
    fun locate() {
        if (locating) return
        locating = true
        locationError = null
        permissionDenied = false
        scope.launch {
            try {
                if (!requestLocationPermission()) {
                    permissionDenied = true
                    locationError = R.string.support_location_denied
                    return@launch
                }
                val found = lookupLocation(context)
                if (found == null) {
                    locationError = R.string.support_location_unavailable
                    return@launch
                }
                selection = catalog?.regionFor(found) ?: if (catalog?.isCanada(found) == true) "CA" else ""
                city = found.city
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                locationError = R.string.support_location_unavailable
            } finally {
                locating = false
            }
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var onlyAlwaysOpen by rememberSaveable { mutableStateOf(false) }
    var activeTab by rememberSaveable { mutableStateOf(HotlineTab.All) }
    var choosingRegion by remember { mutableStateOf(false) }
    var openFailed by remember { mutableStateOf(false) }
    val open: (String, Boolean) -> Unit =
        { url, dial -> openFailed = !openHotlineDestination(context, url, dial) }

    val savedVersion by SavedHotlineStore.updates.collectAsState()
    val accountId = session.coverAccountId.orEmpty()
    val savedIds = remember(catalog, accountId, savedVersion) {
        SavedHotlineStore.savedIds(context, accountId, catalog?.resources.orEmpty())
    }

    val savedOnly = activeTab == HotlineTab.Saved
    val hasSearchRegion = savedOnly || selection.isNotBlank()
    val matched = if (hasSearchRegion) {
        catalog?.matching(
            region = if (savedOnly) "CA" else selection,
            city = if (savedOnly) "" else city,
            query = query,
            kind = activeTab.resourceKind(),
            savedIds = savedIds.takeIf { savedOnly },
            onlyAlwaysOpen = onlyAlwaysOpen,
        ).orEmpty()
    } else emptyList()

    val text = rememberResourceText()
    val listState = rememberLazyListState()
    var lastFilter by remember { mutableStateOf(Triple(selection, activeTab, onlyAlwaysOpen)) }
    LaunchedEffect(selection, activeTab, onlyAlwaysOpen) {
        val current = Triple(selection, activeTab, onlyAlwaysOpen)
        if (current != lastFilter) {
            lastFilter = current
            listState.scrollToItem(0)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().oliveScrollbar(listState).testTag("hotline_page"),
        state = listState,
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SafeBack(session)
            Heading(stringResource(R.string.support_hotlines), "")
        }
        if (catalog == null) item {
            if (directory == null) LoadingSkeleton(SkeletonLayout.HotlineDirectory) else {
                AccessibleMessage(stringResource(R.string.support_load_failed))
                CanadaDirectoryButton(open)
            }
        }
        if (catalog != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().walkthroughTarget("hotline_location_bar"),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { choosingRegion = true },
                                enabled = !locating,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .testTag("hotline_region"),
                            ) {
                                Text(
                                    text = if (selection.isBlank()) stringResource(R.string.support_choose_region)
                                    else hotlineRegionName(selection),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            DropdownMenu(
                                expanded = choosingRegion,
                                onDismissRequest = { choosingRegion = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.support_saved_location)) },
                                    onClick = {
                                        selection = savedSelection
                                        city = address.city
                                        locationError = null
                                        choosingRegion = false
                                    },
                                )
                                (listOf("CA") + CanadianRegion.entries.map { it.code }).forEach { code ->
                                    DropdownMenuItem(
                                        text = { Text(hotlineRegionName(code)) },
                                        onClick = {
                                            selection = code
                                            city = if (code == savedRegion) address.city else ""
                                            locationError = null
                                            choosingRegion = false
                                        },
                                        modifier = Modifier.testTag("hotline_region_$code"),
                                    )
                                }
                            }
                        }
                        Button(
                            onClick = ::locate,
                            enabled = !locating,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                .testTag("hotline_use_device_location"),
                        ) {
                            Text(
                                text = stringResource(
                                    if (locating) R.string.support_finding_location
                                    else R.string.support_use_device_location
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    locationError?.let { error ->
                        AccessibleMessage(
                            stringResource(error),
                            modifier = Modifier.testTag("hotline_location_error"),
                        )
                    }
                    if (permissionDenied) TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null),
                                    )
                                )
                            }
                        },
                        modifier = Modifier.testTag("hotline_location_permissions"),
                    ) {
                        Text(stringResource(R.string.support_location_permissions))
                    }

                    HotlineCategoryTabs(
                        selected = activeTab,
                        savedCount = savedIds.size,
                        onSelect = { activeTab = it },
                    )

                    if (hasSearchRegion) {
                        Field(text(R.string.support_search), query, "hotline_search") {
                            query = it.take(160)
                        }
                        FilterChip(
                            selected = onlyAlwaysOpen,
                            onClick = { onlyAlwaysOpen = !onlyAlwaysOpen },
                            label = { Text(stringResource(R.string.hotline_filter_247)) },
                            modifier = Modifier.testTag("hotline_filter_247"),
                        )
                    } else {
                        Text(stringResource(R.string.support_outside_canada))
                    }
                }
            }

            if (hasSearchRegion) {
                item {
                    Text(
                        stringResource(R.string.support_result_count, matched.size),
                        modifier = Modifier.semantics {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                }
                if (matched.isEmpty()) item {
                    if (savedOnly && savedIds.isEmpty()) {
                        SavedResourcesEmptyCard()
                    } else {
                        Text(stringResource(R.string.support_no_results))
                    }
                }
                items(matched, key = { it.id }) { resource ->
                    val onNavigate: (() -> Unit)? = if (resource.hasMapLocation) {
                        {
                            session.locationMap.queuePrivateDestination(
                                resource.name,
                                resource.latitude!!,
                                resource.longitude!!,
                            )
                            session.navigation.openSafePage(SafePage.Location)
                        }
                    } else null
                    HotlineResourceCard(
                        resource = resource,
                        saved = resource.id in savedIds,
                        onToggleSave = {
                            if (accountId.isNotBlank()) {
                                SavedHotlineStore.toggle(context, accountId, resource.id)
                            }
                        },
                        onOpen = open,
                        onNavigate = onNavigate,
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val date = LocalDate.parse(catalog.checkedOn)
                        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                    Text(
                        stringResource(R.string.support_checked_on, date),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                    CanadaDirectoryButton(open)
                    TextButton(onClick = { open("https://lila.help/", false) }) {
                        Text(stringResource(R.string.support_world_directory))
                    }
                }
            }
        }
        if (openFailed) item {
            AccessibleMessage(
                stringResource(R.string.support_open_failed),
                modifier = Modifier.testTag("hotline_open_failed"),
            )
        }
    }
}

/**
 * Links to the Government of Canada's support directory.
 *
 * @param open Opens the directory URL using the screen's link handler.
 */
@Composable
private fun CanadaDirectoryButton(open: (String, Boolean) -> Unit) {
    TextButton(onClick = { open(CANADA_SUPPORT_DIRECTORY_URL, false) }) {
        Text(stringResource(R.string.support_full_directory))
    }
}

private const val CANADA_SUPPORT_DIRECTORY_URL =
    "https://www.canada.ca/en/women-gender-equality/gender-based-violence/get-help-now.html"

/**
 * Opens a validated `tel:` dialer or `https://` support website intent outside the app.
 *
 * @param context The Android context used for resources or system services.
 * @param value The telephone number or HTTPS link from the hotline entry.
 * @param dial The number or destination passed to the dialer.
 * @return True when an approved dialer or website intent was started.
 */
private fun openHotlineDestination(context: Context, value: String, dial: Boolean): Boolean {
    val uri = value.toUri()
    if (if (dial) uri.scheme != "tel" else uri.scheme != "https" || uri.host.isNullOrBlank()) return false
    val destination =
        if (dial) Uri.fromParts("tel", hotlineDialNumber(value) ?: return false, null) else uri
    return try {
        context.startActivity(
            Intent(if (dial) Intent.ACTION_DIAL else Intent.ACTION_VIEW, destination)
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

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
 *
 * @receiver The directory tab that determines the resource filter.
 * @return The category associated with this tab, or null when the tab shows all categories.
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
 *
 * @param selected Whether this item is currently selected.
 * @param savedCount The number of currently saved items.
 * @param onSelect Runs when the user chooses an option.
 */
@Composable
internal fun HotlineCategoryTabs(
    selected: HotlineTab,
    savedCount: Int,
    onSelect: (HotlineTab) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().walkthroughTarget("hotline_tabs").testTag("hotline_tabs"),
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
 *
 * @param resource The hotline resource to display or act on.
 * @param saved The existing saved record, if one is available.
 * @param onToggleSave Runs when the user changes whether the item is saved.
 * @param onOpen Runs when the user opens the item.
 * @param onNavigate Runs when the user chooses to start navigation.
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
                    color = Paper,
                    border = BorderStroke(1.dp, kindColor.copy(alpha = 0.4f)),
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

/** Renders an accessible pulsing 24/7 availability badge for hotline cards. */
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
        color = Paper,
        border = BorderStroke(1.dp, OliveGreen.copy(alpha = 0.4f)),
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

/**
 * Returns the localized display label for a [HotlineResourceKind] category.
 *
 * @param kind The category of resource or record.
 * @return The localized name of the resource category.
 */
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
 *
 * @param code The region or category code to look up.
 * @return The display name for the Canadian province or territory.
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
