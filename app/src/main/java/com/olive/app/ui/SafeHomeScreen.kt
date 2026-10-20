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
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
            OutlinedButton(onClick={session.navigate(SafePage.OcrTest)},modifier=Modifier.fillMaxWidth().testTag("safe_ocr_test")) { Text("Test PaddleOCR Document Checking") }
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
    listOf(0,2).forEach { first ->
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            listOf(first,first+1).forEach { index ->
                val page=listOf(SafePage.Notes,SafePage.Messages,SafePage.SafetyPlan,SafePage.Documents)[index]
                val label=listOf(R.string.notes_incidents,R.string.messages,R.string.support_safety_plan,R.string.support_documents)[index]
                val icon=listOf(Icons.Outlined.Description,Icons.Outlined.ChatBubbleOutline,Icons.Outlined.HealthAndSafety,Icons.Outlined.FolderOpen)[index]
                val tag=listOf("safe_notes","safe_messages","safe_safety_plan","safe_documents")[index]
                SafeFeatureTile(label=uiText(label),icon={
                    if(page==SafePage.Messages) BadgedBox(badge={
                        if(session.pendingMessageActionCount>0) Badge {
                            Text(if(session.pendingMessageActionCount>99) "99+" else session.pendingMessageActionCount.toString())
                        }
                    }) { Icon(icon,uiText(label),Modifier.size(27.dp)) }
                    else Icon(icon,uiText(label),Modifier.size(27.dp))
                },enabled=index<2 || session.accountRole==AccountRole.USER,onClick={session.navigate(page)},
                    modifier=Modifier.weight(1f).testTag(tag))
            }
        }
    }
}

@Composable private fun SafeFeatureTile(label:String,icon:@Composable ()->Unit,enabled:Boolean,onClick:()->Unit,modifier:Modifier=Modifier) {
    Surface(onClick=onClick,enabled=enabled,modifier=modifier.heightIn(min=112.dp),shape=RoundedCornerShape(24.dp),
        color=if(enabled) Color(0xFFF4F6EF) else Color(0xFFE9EDE5),contentColor=if(enabled) OliveGreen else Muted,
        border=BorderStroke(1.dp,if(enabled) OliveGreen.copy(alpha=.25f) else Muted.copy(alpha=.18f)),tonalElevation=if(enabled) 2.dp else 0.dp) {
        Column(Modifier.fillMaxSize().padding(horizontal=12.dp,vertical=14.dp),horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.Center) {
            Surface(shape=CircleShape,color=if(enabled) Color(0xFFDDE7D6) else Color(0xFFDDE2D9),contentColor=if(enabled) OliveGreen else Muted) {
                Box(Modifier.size(46.dp),contentAlignment=Alignment.Center) { icon() }
            }
            Spacer(Modifier.height(9.dp))
            Text(label,fontSize=16.sp,lineHeight=20.sp,fontWeight=FontWeight.SemiBold,textAlign=TextAlign.Center,maxLines=2)
        }
    }
}
