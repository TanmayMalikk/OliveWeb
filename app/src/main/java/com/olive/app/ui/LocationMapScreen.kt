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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.olive.app.R
import com.olive.app.data.AccountIdentity
import com.olive.app.data.AccountRole
import com.olive.app.ui.location.LocationMapConfiguration
import androidx.compose.ui.platform.testTag

@Composable internal fun LocationMapScreen(session:SessionViewModel) {
    val profile=session.profile ?: return;val uid=session.coverAccountId ?: return
    val controller=session.locationMap
    val requestPermission=LocalLocationPermissionRequest.current
    val youLabel=stringResource(R.string.you)
    val savedLocationLabel=stringResource(R.string.your_saved_location)
    val permissionRequired=stringResource(R.string.location_permission_required)
    val startFailed=stringResource(R.string.location_start_failed)
    var durationDialog by remember { mutableStateOf(false) }
    val configuration=LocationMapConfiguration(AccountIdentity(uid,profile.username),profile,session.friends,
        session.localMode,youLabel,savedLocationLabel)
    LaunchedEffect(uid,profile,session.friends,youLabel,savedLocationLabel,session.localMode) {
        controller.open(configuration,requestPermission)
    }
    DisposableEffect(controller) { onDispose(controller::closeMap) }
    val state=controller.state
    val displayedOwn=state.displayedOwn(profile.role)
    val points=listOfNotNull(displayedOwn)+state.contacts

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
                    Text(stringResource(R.string.location_contacts_selected,state.selected.size),style=MaterialTheme.typography.bodySmall,color=Muted)
                    if(session.friends.isEmpty()) Text(stringResource(R.string.location_no_friends),color=Muted)
                    else LazyColumn(Modifier.fillMaxWidth().heightIn(max=140.dp)) {
                        items(session.friends,key={it.uid}) { friend ->
                            val checked=friend.uid in state.selected
                            val trusted=profile.contacts.firstOrNull { it.linkedUid==friend.uid }
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Checkbox(checked,{ enabled -> controller.selectContact(friend.uid,enabled) },
                                    enabled=!state.liveActive && (checked || state.selected.size<MAX_SHARED_CONTACTS))
                                Column(Modifier.weight(1f)) {
                                    Text(trusted?.name ?: friend.username)
                                    if(trusted!=null) Text("@${friend.username}",style=MaterialTheme.typography.bodySmall,color=Muted)
                                }
                            }
                        }
                    }
                    if(state.liveActive) Text(stringResource(R.string.location_sharing_until,formatShareTime(state.expiresAt)),
                        style=MaterialTheme.typography.bodySmall,color=OliveGreen)
                }
            }
            if(!state.liveActive) Button(onClick={durationDialog=true},enabled=state.selected.isNotEmpty() && !state.working && !session.localMode,
                modifier=Modifier.fillMaxWidth().testTag("start_location_sharing")) {
                Icon(Icons.Outlined.LocationOn,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.start_sharing_location))
            } else Button(onClick=controller::stopSharing,modifier=Modifier.fillMaxWidth().testTag("stop_location_sharing"),
                colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.stop_sharing_location)) }
            if(durationDialog) ShareDurationDialog(onDismiss={durationDialog=false},onSelected={ durationMillis ->
                durationDialog=false
                controller.startSharing(configuration,durationMillis,requestPermission,permissionRequired,startFailed)
            })
            if(session.localMode) Text(stringResource(R.string.location_firebase_required),style=MaterialTheme.typography.bodySmall,color=Muted)
        } else {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.share_saved_location),style=MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.share_saved_location_help),style=MaterialTheme.typography.bodySmall,color=Muted)
                }
                Switch(state.staticSharing,{ enabled -> controller.updateStaticSharing(configuration,enabled) },
                    enabled=!state.working && !session.localMode,modifier=Modifier.testTag("share_saved_location"))
            }
        }
        state.error?.let { AccessibleMessage(it) }
    }
}

private const val MAX_SHARED_CONTACTS=10

@Composable internal fun ShareDurationDialog(onDismiss:()->Unit,onSelected:(Long)->Unit) {
    var custom by remember { mutableStateOf("") }
    val customHours=custom.toLongOrNull()?.takeIf { it in 1..72 }
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
            OutlinedTextField(custom,{custom=it.filter(Char::isDigit).take(2)},singleLine=true,
                label={Text(stringResource(R.string.location_custom_hours))},
                supportingText={Text(stringResource(R.string.location_custom_range))},
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),
                modifier=Modifier.fillMaxWidth().testTag("location_custom_hours"))
        }},confirmButton={TextButton(onClick={customHours?.let { onSelected(it*60*60*1000) }},enabled=customHours!=null,
            modifier=Modifier.testTag("location_custom_confirm")) {
            Text(stringResource(R.string.location_share_for_custom))
        }},dismissButton={TextButton(onClick=onDismiss) { Text(stringResource(R.string.cancel)) }})
}

@Composable private fun formatShareTime(timestamp:Long):String {
    if(timestamp<=0L) return ""
    val formatter=remember { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT) }
    return formatter.format(java.util.Date(timestamp))
}
