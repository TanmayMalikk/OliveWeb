package com.olive.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.R
import com.olive.app.data.UserProfile
import com.olive.app.ui.LocalizedIcon as Icon

// Safe-contact accounts use a connection-focused dashboard and can open only records explicitly shared with them.
@Composable internal fun SafeContactArea(session:SessionViewModel) {
    val profile=session.profile ?: return
    LaunchedEffect(session.coverAccountId) { session.ensureMessagingObservation() }
    AnimatedContent(session.page,label="safe contact page",
        transitionSpec={fadeIn(tween(180)).togetherWith(fadeOut(tween(120)))}) { page ->
        when(page) {
            SafePage.Home -> SafeContactHome(session)
            SafePage.Messages -> MessagesScreen(session)
            SafePage.Notes -> PrivateNotes(session)
            SafePage.Profile -> SafeContactProfile(session,profile)
            SafePage.Settings -> SettingsEditor(session,profile)
            SafePage.Location -> LocationMapScreen(session)
            SafePage.SafetyPlan -> SafeContactSafetyPlansScreen(session)
            SafePage.Documents -> SafetyDocumentsScreen(session)
            SafePage.Camera -> CameraScreen(session)
            else -> SafeContactHome(session)
        }
    }
    if(session.viewedSharedEntry!=null) SharedEntryViewer(session)
}

@Composable private fun SafeContactHome(session:SessionViewModel) {
    val uiText=rememberResourceText()
    FormColumn {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.olive_branch),fontFamily=FontFamily.Serif,fontSize=30.sp,color=OliveGreen)
                Text(stringResource(R.string.safe_contact),style=MaterialTheme.typography.headlineMedium,
                    modifier=Modifier.semantics { heading() })
                Text(stringResource(R.string.safe_contact_home_help),color=Muted)
            }
            Row {
                IconButton(onClick={session.navigate(SafePage.Camera)},enabled=!session.localMode,
                    modifier=Modifier.testTag("safe_contact_camera")) {
                    Icon(Icons.Outlined.PhotoCamera,stringResource(R.string.camera_title))
                }
                IconButton(onClick={session.navigate(SafePage.Settings)},modifier=Modifier.testTag("safe_contact_settings")) {
                    Icon(Icons.Outlined.Settings,uiText(R.string.account_settings))
                }
            }
        }
        ModeNotice(session)
        Button(onClick={session.navigate(SafePage.Profile)},modifier=Modifier.fillMaxWidth().testTag("safe_contact_profile")) {
            Icon(Icons.Outlined.PersonOutline,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.my_profile))
        }
        OutlinedButton(onClick={session.navigate(SafePage.Location)},modifier=Modifier.fillMaxWidth().testTag("safe_contact_location")) {
            Icon(Icons.Outlined.LocationOn,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.location_map))
        }
        Text(stringResource(R.string.private_features),style=MaterialTheme.typography.titleLarge,
            modifier=Modifier.semantics { heading() })
        SafeContactActionCard(stringResource(R.string.notes_incidents),stringResource(R.string.safe_contact_notes_help),
            Icons.Outlined.Description,"safe_contact_notes",0,{ session.navigate(SafePage.Notes) })
        SafeContactActionCard(stringResource(R.string.messages),stringResource(R.string.safe_contact_messages_help),
            Icons.Outlined.ChatBubbleOutline,"safe_contact_messages",session.pendingMessageActionCount,{ session.navigate(SafePage.Messages) })
        SafeContactActionCard(stringResource(R.string.support_safety_plan),stringResource(R.string.safe_contact_safety_plan_help),
            Icons.Outlined.HealthAndSafety,"safe_contact_safety_plan",0,{ session.navigate(SafePage.SafetyPlan) })
        SafeContactActionCard(stringResource(R.string.support_documents),stringResource(R.string.safe_contact_documents_help),
            Icons.Outlined.FolderOpen,"safe_contact_documents",0,{ session.navigate(SafePage.Documents) })
        if(session.pendingSync) Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,
            color=MaterialTheme.colorScheme.primaryContainer) {
            Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.CloudUpload,null)
                Text(stringResource(R.string.safe_contact_changes_waiting),Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                TextButton(onClick=session::sync,enabled=!session.busy) { Text(stringResource(R.string.sync_changes)) }
            }
        }
        Status(session)
    }
}

@Composable private fun SafeContactIdentityCard(profile:UserProfile,onClick:()->Unit) {
    Surface(onClick=onClick,modifier=Modifier.fillMaxWidth().testTag("safe_contact_identity"),
        shape=RoundedCornerShape(24.dp),color=Color(0xFFF0F4EC),contentColor=MaterialTheme.colorScheme.onSurface,
        border=BorderStroke(1.dp,OliveGreen.copy(alpha=.18f))) {
        Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            Surface(shape=CircleShape,color=OliveGreen,contentColor=MaterialTheme.colorScheme.onPrimary) {
                Box(Modifier.size(52.dp),contentAlignment=Alignment.Center) {
                    Text(profile.fullName.trim().firstOrNull()?.uppercase() ?: "?",fontSize=22.sp,fontWeight=FontWeight.SemiBold)
                }
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(profile.fullName.ifBlank { profile.username },style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                Text("@${profile.username}",style=MaterialTheme.typography.bodyMedium,color=Muted)
                Text(stringResource(R.string.safe_contact),style=MaterialTheme.typography.labelLarge,color=OliveGreen)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,stringResource(R.string.my_profile))
        }
    }
}

@Composable private fun SafeContactActionCard(label:String,description:String,icon:ImageVector,tag:String,badgeCount:Int,
    onClick:()->Unit,modifier:Modifier=Modifier) {
    Surface(onClick=onClick,modifier=modifier.fillMaxWidth().heightIn(min=104.dp).testTag(tag),shape=RoundedCornerShape(24.dp),
        color=Color(0xFFF4F6EF),contentColor=MaterialTheme.colorScheme.onSurface,
        border=BorderStroke(1.dp,OliveGreen.copy(alpha=.22f)),tonalElevation=1.dp) {
        Row(Modifier.fillMaxSize().padding(16.dp),verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(14.dp)) {
            BadgedBox(badge={if(badgeCount>0) Badge { Text(if(badgeCount>99) "99+" else badgeCount.toString()) }}) {
                Surface(shape=CircleShape,color=Color(0xFFDDE7D6),contentColor=OliveGreen) {
                    Box(Modifier.size(44.dp),contentAlignment=Alignment.Center) { Icon(icon,null,Modifier.size(25.dp)) }
                }
            }
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(label,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold)
                Text(description,style=MaterialTheme.typography.bodySmall,color=Muted,maxLines=2)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight,null,tint=OliveGreen)
        }
    }
}

@Composable private fun SafeContactProfile(session:SessionViewModel,profile:UserProfile) {
    val uiText=rememberResourceText()
    FormColumn {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=session::back,modifier=Modifier.testTag("safe_contact_profile_back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack,uiText(R.string.ui_previous_safe_page))
            }
            Text(stringResource(R.string.my_profile),style=MaterialTheme.typography.headlineMedium,
                modifier=Modifier.semantics { heading() })
        }
        Text(stringResource(R.string.safe_contact_profile_intro),color=Muted)
        SafeContactIdentityCard(profile) { session.navigate(SafePage.Settings) }
        Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                SafeContactProfileRow(uiText(R.string.username),profile.username,Icons.Outlined.AlternateEmail)
                HorizontalDivider()
                SafeContactProfileRow(uiText(R.string.date_of_birth),profile.dob,Icons.Outlined.Cake)
                HorizontalDivider()
                SafeContactProfileRow(uiText(R.string.region_city_state),profile.region,Icons.Outlined.LocationOn)
            }
        }
        Button(onClick={session.navigate(SafePage.Settings)},modifier=Modifier.fillMaxWidth().testTag("safe_contact_edit_profile")) {
            Icon(Icons.Outlined.Edit,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.safe_contact_edit_profile))
        }
    }
}

@Composable private fun SafeContactProfileRow(label:String,value:String,icon:ImageVector) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Icon(icon,null,tint=OliveGreen)
        Column(Modifier.weight(1f)) {
            Text(label,style=MaterialTheme.typography.labelLarge,color=Muted)
            Text(value.ifBlank { stringResource(R.string.not_provided) },style=MaterialTheme.typography.bodyLarge)
        }
    }
}
