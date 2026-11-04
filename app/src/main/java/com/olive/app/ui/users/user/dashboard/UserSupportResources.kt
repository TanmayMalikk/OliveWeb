// Safe-app users: lists safety resources on the user dashboard.
package com.olive.app.ui.users.user.dashboard

import com.olive.app.ui.shared.*


import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.ui.users.user.account.SafeBack

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.olive.app.data.*
import com.olive.app.data.hotlineDialNumber
import com.olive.app.data.HotlineResource
import com.olive.app.data.HotlineResourceKind
import com.olive.app.R
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val LocalHotlineLocationLookup =
    staticCompositionLocalOf<suspend (Context) -> DeliveryAddress?> { DeviceLocation::address }
internal val LocalLocationPermissionRequest =
    staticCompositionLocalOf<suspend () -> Boolean> { { false } }

// Start from the saved address. Device lookup is explicit and never writes the detected address to the account.
@Composable
internal fun SafeResourcesScreen(session: SessionViewModel, profile: UserProfile) {
    val context = LocalContext.current
    val directory by produceState<Result<HotlineDirectory>?>(null, context) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val files = context.assets.list("hotlines/provinces").orEmpty().associate { name ->
                    name.removePrefix("Resources").removeSuffix(".json") to
                            context.assets.open("hotlines/provinces/$name").bufferedReader()
                                .use { it.readText() }
                }
                HotlineDirectory.parseProvinceFiles(files)
            }
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
    var devicePlace by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }
    var locationError by remember { mutableStateOf<Int?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    val requestLocationPermission = LocalLocationPermissionRequest.current
    val lookupLocation = LocalHotlineLocationLookup.current
    val scope = rememberCoroutineScope()
    val locate: () -> Unit = {
        // Ignore another location tap while the first lookup is still running.
        if (!locating) {
            locating = true
            locationError = null
            permissionDenied = false
            scope.launch {
                // A location lookup can fail, but the hotline list should stay open.
                try {
                    // Ask for permission before checking the phone's location.
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
                            devicePlace = listOf(
                                found.city,
                                found.province,
                                found.country
                            ).filter(String::isNotBlank).joinToString(", ")
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { // Location is optional here, so show the unavailable message and keep the support screen usable.
                    locationError = R.string.support_location_unavailable
                } finally {
                    locating = false
                }
            }
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var choosingRegion by remember { mutableStateOf(false) }
    var openFailed by remember { mutableStateOf(false) }
    val open: (String, Boolean) -> Unit =
        { url, dial -> openFailed = !openHotlineDestination(context, url, dial) }
    val matched =
        catalog?.matching(selection, city, query).orEmpty().takeIf { selection.isNotBlank() }
            .orEmpty()
    val text = rememberResourceText()
    val listState = rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().oliveScrollbar(listState).testTag("hotline_page"),
        state = listState,
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            SafeBack(session)
            Heading(
                stringResource(R.string.support_hotlines),
                stringResource(R.string.support_location_description)
            )
        }
        // Show a loading sign or a useful message while the hotline list loads.
        if (catalog == null) item {
            // The directory is still loading, so keep the user informed.
            if (directory == null) CircularProgressIndicator() else {
                AccessibleMessage(stringResource(R.string.support_load_failed))
                TextButton(onClick = {
                    open(
                        "https://www.canada.ca/en/women-gender-equality/gender-based-violence/get-help-now.html",
                        false
                    )
                }) {
                    Text(stringResource(R.string.support_full_directory))
                }
            }
        }
        // Show search and region controls after the hotline list is ready.
        if (catalog != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.support_show_region),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() })
                    Box {
                        OutlinedButton(
                            onClick = { choosingRegion = true },
                            enabled = !locating,
                            modifier = Modifier.fillMaxWidth().testTag("hotline_region")
                        ) {
                            Text(
                                if (selection.isBlank()) stringResource(R.string.support_choose_region) else hotlineRegionName(
                                    selection
                                )
                            )
                        }
                        DropdownMenu(
                            expanded = choosingRegion,
                            onDismissRequest = { choosingRegion = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.support_saved_location)) },
                                onClick = {
                                    selection = savedRegion
                                        ?: if (catalog.isCanada(address)) "CA" else ""
                                    city =
                                        address.city
                                    devicePlace = null
                                    locationError =
                                        null
                                    choosingRegion = false
                                })
                            // List Canada first, followed by each province and territory.
                            (listOf("CA") + CanadianRegion.entries.map { it.code }).forEach { code ->
                                DropdownMenuItem(
                                    text = { Text(hotlineRegionName(code)) },
                                    onClick = {
                                        selection = code
                                        city =
                                            if (code == savedRegion) address.city else ""
                                        devicePlace =
                                            null
                                        locationError = null
                                        choosingRegion = false
                                    },
                                    modifier = Modifier.testTag("hotline_region_$code")
                                )
                            }
                        }
                    }
                    Button(
                        onClick = locate,
                        enabled = !locating,
                        modifier = Modifier.fillMaxWidth().testTag("hotline_use_device_location")
                    ) {
                        Text(stringResource(if (locating) R.string.support_finding_location else R.string.support_use_device_location))
                    }
                    // Tell the user whether results use their saved or detected location.
                    if (devicePlace != null || (selection == savedRegion && city.isNotBlank())) {
                        Text(
                            stringResource(
                                if (devicePlace != null) R.string.support_device_location_current else R.string.support_saved_location_current,
                                devicePlace ?: city
                            ),
                            modifier = Modifier.testTag("hotline_location_summary")
                                .semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    locationError?.let { error ->
                        AccessibleMessage(
                            stringResource(error),
                            modifier = Modifier.testTag("hotline_location_error")
                        )
                    }
                    // Offer a link to app settings if location permission was denied.
                    if (permissionDenied) TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                )
                            )
                        }
                    }, modifier = Modifier.testTag("hotline_location_permissions")) {
                        Text(
                            stringResource(R.string.support_location_permissions)
                        )
                    }
                    if (selection.isNotBlank()) {
                        Field(text(R.string.support_search), query, "hotline_search") {
                            query = it.take(160)
                        }
                    } else Text(stringResource(R.string.support_outside_canada))
                }
            }
            // Search results only make sense after a region has been chosen.
            if (selection.isNotBlank()) {
                item {
                    Text(
                        stringResource(R.string.support_result_count, matched.size),
                        modifier = Modifier.semantics {
                            heading()
                            liveRegion = LiveRegionMode.Polite
                        })
                }
                if (matched.isEmpty()) item { Text(stringResource(R.string.support_no_results)) }
                items(matched, key = { it.id }) { resource -> HotlineResourceCard(resource, open) }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val date = LocalDate.parse(catalog.checkedOn)
                        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                    Text(
                        stringResource(R.string.support_checked_on, date),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted
                    )
                    TextButton(onClick = {
                        open(
                            "https://www.canada.ca/en/women-gender-equality/gender-based-violence/get-help-now.html",
                            false
                        )
                    }) {
                        Text(stringResource(R.string.support_full_directory))
                    }
                    TextButton(onClick = { open("https://lila.help/", false) }) {
                        Text(
                            stringResource(R.string.support_world_directory)
                        )
                    }
                }
            }
        }
        if (openFailed) item {
            AccessibleMessage(
                stringResource(R.string.support_open_failed),
                modifier = Modifier.testTag("hotline_open_failed")
            )
        }
    }
}

// Shows one hotline or shelter with its phone number, details, and safe action buttons.
@Composable
private fun HotlineResourceCard(resource: HotlineResource, onOpen: (String, Boolean) -> Unit) {
    Card(
        Modifier.fillMaxWidth().testTag("hotline_${resource.id}"),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = Ink
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier.semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    resource.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
                Text(
                    stringResource(if (resource.kind == HotlineResourceKind.Hotline) R.string.support_resource_hotline else R.string.support_resource_shelter),
                    color = OliveGreen, style = MaterialTheme.typography.labelLarge
                )
                Text(
                    "${resource.city}, ${hotlineRegionName(resource.region)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
                Text(resource.description, style = MaterialTheme.typography.bodyMedium)
                Text(resource.phone, style = MaterialTheme.typography.headlineSmall)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val dialLabel =
                    stringResource(R.string.support_dial_label, resource.name, resource.phone)
                OutlinedButton(
                    onClick = { onOpen(resource.phoneUri, true) },
                    modifier = Modifier.heightIn(min = 48.dp)
                        .semantics { contentDescription = dialLabel }) {
                    Icon(Icons.Outlined.Phone, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.support_open_dialer)
                    )
                }
                val detailsLabel = stringResource(R.string.support_details_label, resource.name)
                TextButton(
                    onClick = { onOpen(resource.website, false) },
                    modifier = Modifier.heightIn(min = 48.dp)
                        .semantics { contentDescription = detailsLabel }) {
                    Text(stringResource(R.string.support_service_details))
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.AutoMirrored.Outlined.OpenInNew,
                        null
                    )
                }
            }
        }
    }
}

// Opening a dialer never calls automatically and needs no phone permission. Android leaving the app locks the safe area.
private fun openHotlineDestination(context: Context, value: String, dial: Boolean): Boolean {
    val uri = value.toUri()
    if (if (dial) uri.scheme != "tel" else uri.scheme != "https" || uri.host.isNullOrBlank()) return false
    val destination =
        if (dial) Uri.fromParts("tel", hotlineDialNumber(value) ?: return false, null) else uri
    return try {
        context.startActivity(
            Intent(
                if (dial) Intent.ACTION_DIAL else Intent.ACTION_VIEW,
                destination
            )
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

// Changes a Canadian region code into the province or territory name shown on the hotline card.
@Composable
private fun hotlineRegionName(code: String) = stringResource(
    // Match each short region code to its translated name. Unknown codes fall back to Canada.
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
