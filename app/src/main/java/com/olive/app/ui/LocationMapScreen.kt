package com.olive.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.olive.app.R
import com.olive.app.data.*
import com.olive.app.location.LiveLocationController
import com.olive.app.location.LiveLocationState
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import androidx.compose.ui.platform.testTag

@Composable internal fun LocationMapScreen(session:SessionViewModel) {
    val context=LocalContext.current;val profile=session.profile ?: return;val uid=session.coverAccountId ?: return
    val repository=remember(context) { LocationSharingRepository(context.applicationContext) }
    val requestPermission=LocalLocationPermissionRequest.current
    val youLabel=stringResource(R.string.you)
    val savedLocationLabel=stringResource(R.string.your_saved_location)
    val permissionRequired=stringResource(R.string.location_permission_required)
    val startFailed=stringResource(R.string.location_start_failed)
    val scope=rememberCoroutineScope()
    val ownLive by LiveLocationState.current.collectAsState()
    var ownPoint by remember(uid) { mutableStateOf<MapLocationPoint?>(null) }
    var contactPoints by remember(uid) { mutableStateOf(emptyList<MapLocationPoint>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var selected by remember(uid) { mutableStateOf(LiveLocationController.selected(context).intersect(session.friends.map { it.uid }.toSet())) }
    var liveActive by remember(uid) { mutableStateOf(LiveLocationController.active(context)) }
    var expiresAt by remember(uid) { mutableLongStateOf(LiveLocationController.expiresAt(context)) }
    var durationDialog by remember { mutableStateOf(false) }
    var staticSharing by remember(uid) { mutableStateOf(repository.staticSharingEnabled(uid)) }

    LaunchedEffect(uid,profile.role,youLabel,savedLocationLabel) {
        if(profile.role==AccountRole.USER && requestPermission()) {
            while(currentCoroutineContext().isActive) {
                DeviceLocation.currentCoordinates(context)?.let { fix ->
                    ownPoint=MapLocationPoint(uid,youLabel,fix.latitude,fix.longitude,fix.timestamp,true,fix.accuracy)
                }
                delay(10_000L)
            }
        } else if(profile.role==AccountRole.SAFE_CONTACT) {
            val coordinates=GeoapifyClient.coordinates(profile.address) ?: DeviceLocation.coordinatesForAddress(context,profile.address)
            coordinates?.let { (lat,lon) ->
                ownPoint=MapLocationPoint(uid,savedLocationLabel,lat,lon,System.currentTimeMillis(),false)
            }
            if(staticSharing && !session.localMode) runCatching { repository.setStaticSharing(AccountIdentity(uid,profile.username),profile,session.friends,true) }
        }
    }
    DisposableEffect(uid,session.friends,profile.role) {
        val subscription=if(session.localMode) ChatSubscription { } else if(profile.role==AccountRole.USER)
            repository.observeStaticContacts(uid,session.friends) { contactPoints=it }
        else repository.observeLiveFriends(uid,session.friends) { contactPoints=it }
        onDispose { subscription.close() }
    }
    val displayedOwn=if(profile.role==AccountRole.USER) ownLive ?: ownPoint else ownPoint
    val points=listOfNotNull(displayedOwn)+contactPoints

    LaunchedEffect(liveActive,expiresAt) {
        while(liveActive) {
            if(expiresAt<=System.currentTimeMillis()) liveActive=false
            delay(1_000L)
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal=18.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=session::back,modifier=Modifier.testTag("location_back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack,stringResource(R.string.ui_previous_safe_page))
            }
            Text(stringResource(R.string.location_map),style=MaterialTheme.typography.headlineMedium,
                modifier=Modifier.semantics { heading() })
        }
        Text(stringResource(if(profile.role==AccountRole.USER) R.string.location_map_user_help else R.string.location_map_contact_help),
            style=MaterialTheme.typography.bodyMedium,color=Muted)
        Surface(Modifier.fillMaxWidth().weight(1f).heightIn(min=280.dp),shape=RoundedCornerShape(20.dp),
            border=BorderStroke(1.dp,MaterialTheme.colorScheme.outlineVariant),shadowElevation=2.dp) {
            InteractiveLocationMap(points,Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)),displayedOwn?.uid)
        }
        if(profile.role==AccountRole.USER) {
            Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,color=Color(0xFFF3F6F0)) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.share_live_location_with),style=MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.location_contacts_selected,selected.size),style=MaterialTheme.typography.bodySmall,color=Muted)
                    if(session.friends.isEmpty()) Text(stringResource(R.string.location_no_friends),color=Muted)
                    else LazyColumn(Modifier.fillMaxWidth().heightIn(max=140.dp)) {
                        items(session.friends,key={it.uid}) { friend ->
                            val checked=friend.uid in selected
                            val trusted=profile.contacts.firstOrNull { it.linkedUid==friend.uid }
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Checkbox(checked,{ enabled -> selected=if(enabled) selected+friend.uid else selected-friend.uid },
                                    enabled=!liveActive && (checked || selected.size<MAX_SHARED_CONTACTS))
                                Column(Modifier.weight(1f)) {
                                    Text(trusted?.name ?: friend.username)
                                    if(trusted!=null) Text("@${friend.username}",style=MaterialTheme.typography.bodySmall,color=Muted)
                                }
                            }
                        }
                    }
                    if(liveActive) Text(stringResource(R.string.location_sharing_until,formatShareTime(expiresAt)),
                        style=MaterialTheme.typography.bodySmall,color=OliveGreen)
                }
            }
            if(!liveActive) Button(onClick={durationDialog=true},enabled=selected.isNotEmpty() && !working && !session.localMode,
                modifier=Modifier.fillMaxWidth().testTag("start_location_sharing")) {
                Icon(Icons.Outlined.LocationOn,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.start_sharing_location))
            } else Button(onClick={
                LiveLocationController.stop(context);liveActive=false;expiresAt=0L
            },modifier=Modifier.fillMaxWidth().testTag("stop_location_sharing"),
                colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.stop_sharing_location)) }
            if(durationDialog) ShareDurationDialog(onDismiss={durationDialog=false},onSelected={ durationMillis ->
                durationDialog=false
                scope.launch {
                working=true;error=null
                try {
                    if(!requestPermission()) error=permissionRequired
                    else try {
                        repository.setLiveRecipients(uid,session.friends.filter { it.uid in selected })
                        val end=System.currentTimeMillis()+durationMillis
                        LiveLocationController.start(context,uid,profile.fullName,selected,end)
                        expiresAt=end;liveActive=true
                    } catch(failure:Exception) {
                        runCatching { repository.setLiveRecipients(uid,emptyList()) }
                        error=failure.localizedMessage ?: startFailed
                    }
                } finally { working=false }
                }
            })
            if(session.localMode) Text(stringResource(R.string.location_firebase_required),style=MaterialTheme.typography.bodySmall,color=Muted)
        } else {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.share_saved_location),style=MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.share_saved_location_help),style=MaterialTheme.typography.bodySmall,color=Muted)
                }
                Switch(staticSharing,{ enabled -> scope.launch {
                    working=true;error=null
                    runCatching { repository.setStaticSharing(AccountIdentity(uid,profile.username),profile,session.friends,enabled) }
                        .onSuccess { staticSharing=enabled }.onFailure { error=it.localizedMessage }
                    working=false
                } },enabled=!working && !session.localMode,modifier=Modifier.testTag("share_saved_location"))
            }
        }
        error?.let { AccessibleMessage(it) }
    }
}

private const val MAX_SHARED_CONTACTS=10

@Composable internal fun ShareDurationDialog(onDismiss:()->Unit,onSelected:(Long)->Unit) {
    var custom by remember { mutableStateOf("") }
    val customHours=custom.toLongOrNull()?.takeIf { it in 1..720 }
    AlertDialog(onDismissRequest=onDismiss,title={Text(stringResource(R.string.location_choose_duration))},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={onSelected(10_000L)},modifier=Modifier.fillMaxWidth().testTag("location_duration_10_seconds")) {
                Text(stringResource(R.string.location_ten_seconds_testing))
            }
            listOf(1L,2L,8L,24L).forEach { hours ->
                OutlinedButton(onClick={onSelected(hours*60*60*1000)},modifier=Modifier.fillMaxWidth()) {
                    Text(if(hours==1L) stringResource(R.string.location_one_hour)
                        else stringResource(R.string.location_hours,hours))
                }
            }
            OutlinedTextField(custom,{custom=it.filter(Char::isDigit).take(3)},singleLine=true,
                label={Text(stringResource(R.string.location_custom_hours))},
                supportingText={Text(stringResource(R.string.location_custom_range))},
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
        }},confirmButton={TextButton(onClick={customHours?.let { onSelected(it*60*60*1000) }},enabled=customHours!=null) {
            Text(stringResource(R.string.location_share_for_custom))
        }},dismissButton={TextButton(onClick=onDismiss) { Text(stringResource(R.string.cancel)) }})
}

@Composable private fun formatShareTime(timestamp:Long):String {
    if(timestamp<=0L) return ""
    val formatter=remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    return formatter.format(java.util.Date(timestamp))
}
