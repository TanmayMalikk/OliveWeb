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
    var selection by rememberSaveable(catalog != null, address) {
        mutableStateOf(
            savedRegion ?: if (catalog?.isCanada(address) == true) "CA" else ""
        )
    }
    var city by rememberSaveable(address) { mutableStateOf(address.city) }
    var locating by remember { mutableStateOf(false) }
    var locationError by remember { mutableStateOf<Int?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    val requestLocationPermission = LocalLocationPermissionRequest.current
    val lookupLocation = LocalHotlineLocationLookup.current
    val scope = rememberCoroutineScope()
    val locate: () -> Unit = {
        if (!locating) {
            locating = true
            locationError = null
            permissionDenied = false
            scope.launch {
                try {
                    if (!requestLocationPermission()) {
                        permissionDenied = true
                        locationError = R.string.support_location_denied
                    } else {
                        val found = lookupLocation(context)
                        if (found == null) locationError = R.string.support_location_unavailable
                        else {
                            selection = catalog?.regionFor(found)
                                ?: if (catalog?.isCanada(found) == true) "CA" else ""
                            city = found.city
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    locationError = R.string.support_location_unavailable
                } finally {
                    locating = false
                }
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

    val matched = when {
        catalog == null -> emptyList()
        activeTab == HotlineTab.Saved -> catalog.matching(
            region = "CA",
            city = "",
            query = query,
            savedIds = savedIds,
            onlyAlwaysOpen = onlyAlwaysOpen,
        )
        selection.isBlank() -> emptyList()
        else -> catalog.matching(
            region = selection,
            city = city,
            query = query,
            kind = activeTab.resourceKind(),
            onlyAlwaysOpen = onlyAlwaysOpen,
        )
    }

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
                TextButton(onClick = {
                    open(
                        "https://www.canada.ca/en/women-gender-equality/gender-based-violence/get-help-now.html",
                        false,
                    )
                }) {
                    Text(stringResource(R.string.support_full_directory))
                }
            }
        }
        if (catalog != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                                        selection = savedRegion ?: if (catalog.isCanada(address)) "CA" else ""
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
                            onClick = locate,
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

                    if (selection.isNotBlank() || activeTab == HotlineTab.Saved) {
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

            if (selection.isNotBlank() || activeTab == HotlineTab.Saved) {
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
                    if (activeTab == HotlineTab.Saved && savedIds.isEmpty()) {
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
                    TextButton(onClick = {
                        open(
                            "https://www.canada.ca/en/women-gender-equality/gender-based-violence/get-help-now.html",
                            false,
                        )
                    }) {
                        Text(stringResource(R.string.support_full_directory))
                    }
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

/** Opens a validated `tel:` dialer or `https://` support website intent outside the app. */
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
