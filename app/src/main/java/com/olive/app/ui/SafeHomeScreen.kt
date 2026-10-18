package com.olive.app.ui

import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.olive.app.ui.LocalizedIcon as Icon
import androidx.compose.ui.res.stringResource
import com.olive.app.R

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.data.*

// The safe home screen owns feature discovery and pending activity.
@Composable internal fun SafeHomeScreen(session:SessionViewModel,profile:UserProfile) {
    val uiText=rememberResourceText()
    FormColumn {
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(end=56.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.olive_branch),fontFamily=FontFamily.Serif,fontSize=30.sp,color=OliveGreen)
                    Text(if(profile.role==AccountRole.SAFE_CONTACT) uiText(R.string.safe_contact_space) else uiText(R.string.your_safe_space),style=MaterialTheme.typography.headlineMedium,modifier=Modifier.semantics { heading() })
                    Text(if(profile.role==AccountRole.SAFE_CONTACT) uiText(R.string.ui_use_quick_exit_from_any_page_to_lock_olive_branch) else uiText(R.string.use_quick_exit_from_any_page_to_lock_this_area_and_return_to_your_cover_app),color=Muted)
                }
                IconButton(onClick={session.navigate(SafePage.Settings)},modifier=Modifier.align(Alignment.TopEnd).testTag("safe_settings")) {
                    Icon(Icons.Outlined.Settings,uiText(R.string.settings))
                }
            }
            ModeNotice(session)
            Button(onClick={session.navigate(SafePage.Profile)},modifier=Modifier.fillMaxWidth().testTag("safe_profile")) { Text(stringResource(R.string.my_profile)) }
            Button(onClick={session.navigate(SafePage.Activity)},modifier=Modifier.fillMaxWidth().testTag("safe_activity")) { Text(stringResource(R.string.account_activity)) }
            Text(stringResource(R.string.private_features),style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics { heading() })
            // UI sizing/color: feature tiles are two-wide, at least 112dp tall, use 30–32dp icons, and 17–18sp centered labels.
            // Change the two container colors here if these tiles need different shades from the shared theme.
            SafeFeatureGrid(session)
            if(session.pendingSync) {
                Text(stringResource(R.string.profile_changes_are_saved_on_this_device_and_awaiting_cloud_sync))
                OutlinedButton(onClick=session::sync,enabled=!session.busy) { Text(stringResource(R.string.sync_changes)) }
            }
            Text(if(profile.role==AccountRole.SAFE_CONTACT) uiText(R.string.ui_leaving_the_app_locks_olive_branch) else uiText(R.string.leaving_the_app_locks_this_area_a_cover_app_does_not_make_olive_branch_impossible_to_discover),style=MaterialTheme.typography.bodySmall,color=Muted)
            Status(session)
            TextButton(onClick=session::signOut,enabled=!session.busy,modifier=Modifier.testTag("sign_out")) { Text(stringResource(R.string.sign_out_on_this_device)) }
        }
}

@Composable private fun SafeFeatureGrid(session:SessionViewModel) {
    val uiText=rememberResourceText()
    listOf(1,3).forEach { first ->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            listOf(first,first+1).forEach { number ->
                when(number) {
                    1 -> Button(onClick={session.navigate(SafePage.Notes)},
                        colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF718669),contentColor=Color.White),
                        modifier=Modifier.weight(1f).heightIn(min=112.dp).testTag("safe_notes")) {
                        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.Description,uiText(R.string.ui_notes_and_incidents),Modifier.size(32.dp))
                            Text(stringResource(R.string.notes_incidents),fontSize=18.sp,lineHeight=22.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center,maxLines=2)
                        }
                    }
                    2 -> Button(onClick={session.navigate(SafePage.Messages)},
                        colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF7D9274),contentColor=Color.White),
                        modifier=Modifier.weight(1f).heightIn(min=112.dp).testTag("safe_messages")) {
                        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            BadgedBox(badge={
                                if(session.pendingMessageActionCount>0) Badge {
                                    Text(if(session.pendingMessageActionCount>99) "99+" else session.pendingMessageActionCount.toString())
                                }
                            }) { Icon(Icons.Outlined.ChatBubbleOutline,uiText(R.string.messages),Modifier.size(32.dp)) }
                            Text(stringResource(R.string.messages),fontSize=18.sp,lineHeight=22.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center,maxLines=2)
                        }
                    }
                    else -> OutlinedButton(onClick={},enabled=false,
                        colors=ButtonDefaults.outlinedButtonColors(disabledContainerColor=Color(0xFFE7EDE2),disabledContentColor=Muted),
                        modifier=Modifier.weight(1f).heightIn(min=112.dp).testTag("future_feature_$number")) {
                        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.AddCircleOutline,uiText(R.string.ui_future_feature),Modifier.size(30.dp))
                            Text(uiText(R.string.ui_future_feature_value , number),fontSize=17.sp,lineHeight=21.sp,textAlign=androidx.compose.ui.text.style.TextAlign.Center,maxLines=2)
                        }
                    }
                }
            }
        }
    }
}
