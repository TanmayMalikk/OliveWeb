package com.olive.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal val LocalHotlineLocationLookup=staticCompositionLocalOf<suspend (Context)->DeliveryAddress?> { DeviceLocation::address }
internal val LocalLocationPermissionRequest=staticCompositionLocalOf<suspend ()->Boolean> { { false } }

// Start from the saved address. Device lookup is explicit and never writes the detected address to the account.
@Composable internal fun SafeResourcesScreen(session:SessionViewModel,profile:UserProfile) {
    val context=LocalContext.current
    val directory by produceState<Result<HotlineDirectory>?>(null,context) {
        value=withContext(Dispatchers.IO) { runCatching {
            val files=context.assets.list("hotlines/provinces").orEmpty().associate { name ->
                name.removePrefix("Resources").removeSuffix(".json") to
                    context.assets.open("hotlines/provinces/$name").bufferedReader().use { it.readText() }
            }
            HotlineDirectory.parseProvinceFiles(files)
        } }
    }
    val catalog=directory?.getOrNull()
    val address=profile.address
    val savedRegion=catalog?.regionFor(address)
    var selection by rememberSaveable(catalog!=null,address) { mutableStateOf(savedRegion ?: if(catalog?.isCanada(address)==true) "CA" else "") }
    var city by rememberSaveable(address) { mutableStateOf(address.city) }
    var devicePlace by remember { mutableStateOf<String?>(null) }
    var locating by remember { mutableStateOf(false) }
    var locationError by remember { mutableStateOf<Int?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    val requestLocationPermission=LocalLocationPermissionRequest.current
    val lookupLocation=LocalHotlineLocationLookup.current
    val scope=rememberCoroutineScope()
    val locate:()->Unit={
        if(!locating) {
            locating=true;locationError=null;permissionDenied=false
            scope.launch {
                try {
                    if(!requestLocationPermission()) {
                        permissionDenied=true;locationError=R.string.support_location_denied
                    } else {
                        val found=lookupLocation(context)
                        if(found==null) locationError=R.string.support_location_unavailable
                        else {
                            selection=catalog?.regionFor(found) ?: if(catalog?.isCanada(found)==true) "CA" else ""
                            city=found.city
                            devicePlace=listOf(found.city,found.province,found.country).filter(String::isNotBlank).joinToString(", ")
                        }
                    }
                } catch(cancelled:CancellationException) { throw cancelled }
                catch(_:Exception) { locationError=R.string.support_location_unavailable }
                finally { locating=false }
            }
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var choosingRegion by remember { mutableStateOf(false) }
    var openFailed by remember { mutableStateOf(false) }
    val open:(String,Boolean)->Unit={ url,dial -> openFailed=!openHotlineDestination(context,url,dial) }
    val matched=catalog?.matching(selection,city,query).orEmpty().takeIf { selection.isNotBlank() }.orEmpty()
    val text=rememberResourceText()
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState).testTag("hotline_page"),state=listState,contentPadding=PaddingValues(24.dp),
        verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {
            SafeBack(session)
            Heading(stringResource(R.string.support_hotlines),stringResource(R.string.support_location_description))
        }
        if(catalog==null) item {
            if(directory==null) CircularProgressIndicator() else {
                AccessibleMessage(stringResource(R.string.support_load_failed))
                TextButton(onClick={open("https://www.canada.ca/en/women-gender-equality/gender-based-violence/get-help-now.html",false)}) {
                    Text(stringResource(R.string.support_full_directory))
                }
            }
        }
        if(catalog!=null) {
            item {
                Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.support_show_region),style=MaterialTheme.typography.titleMedium,modifier=Modifier.semantics { heading() })
                    Box {
                        OutlinedButton(onClick={choosingRegion=true},enabled=!locating,modifier=Modifier.fillMaxWidth().testTag("hotline_region")) {
                            Text(if(selection.isBlank()) stringResource(R.string.support_choose_region) else hotlineRegionName(selection))
                        }
                        DropdownMenu(expanded=choosingRegion,onDismissRequest={choosingRegion=false}) {
                            DropdownMenuItem(text={Text(stringResource(R.string.support_saved_location))},onClick={
                                selection=savedRegion ?: if(catalog.isCanada(address)) "CA" else "";city=address.city;devicePlace=null;locationError=null;choosingRegion=false
                            })
                            (listOf("CA")+CanadianRegion.entries.map { it.code }).forEach { code ->
                                DropdownMenuItem(text={Text(hotlineRegionName(code))},onClick={selection=code;city=if(code==savedRegion) address.city else "";devicePlace=null;locationError=null;choosingRegion=false},
                                    modifier=Modifier.testTag("hotline_region_$code"))
                            }
                        }
                    }
                    Button(onClick=locate,enabled=!locating,modifier=Modifier.fillMaxWidth().testTag("hotline_use_device_location")) {
                        Text(stringResource(if(locating) R.string.support_finding_location else R.string.support_use_device_location))
                    }
                    if(devicePlace!=null || (selection==savedRegion && city.isNotBlank())) {
                        Text(stringResource(if(devicePlace!=null) R.string.support_device_location_current else R.string.support_saved_location_current,
                            devicePlace ?: city),modifier=Modifier.testTag("hotline_location_summary").semantics { liveRegion=LiveRegionMode.Polite })
                    }
                    locationError?.let { error -> AccessibleMessage(stringResource(error),modifier=Modifier.testTag("hotline_location_error")) }
                    if(permissionDenied) TextButton(onClick={
                        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",context.packageName,null))) }
                    },modifier=Modifier.testTag("hotline_location_permissions")) { Text(stringResource(R.string.support_location_permissions)) }
                    if(selection.isNotBlank()) {
                        Field(text(R.string.support_search),query,"hotline_search") { query=it.take(160) }
                    } else Text(stringResource(R.string.support_outside_canada))
                }
            }
            if(selection.isNotBlank()) {
                item { Text(stringResource(R.string.support_result_count,matched.size),modifier=Modifier.semantics { heading();liveRegion=LiveRegionMode.Polite }) }
                if(matched.isEmpty()) item { Text(stringResource(R.string.support_no_results)) }
                items(matched,key={it.id}) { resource -> HotlineResourceCard(resource,open) }
            }
            item {
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    val date=LocalDate.parse(catalog.checkedOn).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
                    Text(stringResource(R.string.support_checked_on,date),style=MaterialTheme.typography.bodySmall,color=Muted)
                    TextButton(onClick={open("https://www.canada.ca/en/women-gender-equality/gender-based-violence/get-help-now.html",false)}) {
                        Text(stringResource(R.string.support_full_directory))
                    }
                    TextButton(onClick={open("https://lila.help/",false)}) { Text(stringResource(R.string.support_world_directory)) }
                }
            }
        }
        if(openFailed) item { AccessibleMessage(stringResource(R.string.support_open_failed),modifier=Modifier.testTag("hotline_open_failed")) }
    }
}
