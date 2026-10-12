package com.olive.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// SAFE CONTACT ONLY: Olive Branch has its own connection-focused interface and never exposes User notes or cover controls.
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
            else -> SafeContactHome(session)
        }
    }
}

@Composable private fun SafeContactHome(session:SessionViewModel) {
    FormColumn {
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(end=56.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                LocalizedText("Olive Branch",fontFamily=FontFamily.Serif,fontSize=30.sp,color=OliveGreen)
                LocalizedText("Safe Contact",style=MaterialTheme.typography.headlineMedium)
                LocalizedText("Stay connected with people who trust you for support.",color=Muted)
            }
            IconButton(onClick={session.navigate(SafePage.Settings)},modifier=Modifier.align(Alignment.TopEnd).testTag("safe_contact_settings")) {
                Icon(Icons.Outlined.Settings,"Account settings")
            }
        }
        ModeNotice(session)
        Button(onClick={session.navigate(SafePage.Profile)},modifier=Modifier.fillMaxWidth().testTag("safe_contact_profile")) {
            Icon(Icons.Outlined.PersonOutline,null);Spacer(Modifier.width(10.dp));LocalizedText("My profile")
        }
        LocalizedText("Private features",style=MaterialTheme.typography.titleLarge)
        Button(onClick={session.navigate(SafePage.Notes)},modifier=Modifier.fillMaxWidth().heightIn(min=92.dp).testTag("safe_contact_notes"),
            colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF718669))) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Description,"Notes and incidents",Modifier.size(32.dp))
                LocalizedText("Notes & incidents",fontSize=18.sp)
            }
        }
        Button(onClick={session.navigate(SafePage.Messages)},modifier=Modifier.fillMaxWidth().heightIn(min=92.dp).testTag("safe_contact_messages"),
            colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF718669))) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                BadgedBox(badge={if(session.pendingMessageActionCount>0) Badge {
                    Text(if(session.pendingMessageActionCount>99) "99+" else session.pendingMessageActionCount.toString())
                }}) { Icon(Icons.Outlined.ChatBubbleOutline,"Messages",Modifier.size(34.dp)) }
                LocalizedText("Messages",fontSize=18.sp,textAlign=TextAlign.Center)
            }
        }
        listOf(3,4).forEach { number ->
            OutlinedButton(onClick={},enabled=false,modifier=Modifier.fillMaxWidth().heightIn(min=92.dp).testTag("safe_contact_future_feature_$number"),
                colors=ButtonDefaults.outlinedButtonColors(disabledContainerColor=Color(0xFFE7EDE2),disabledContentColor=Muted)) {
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.AddCircleOutline,"Future feature",Modifier.size(30.dp))
                    LocalizedText("Future feature $number",fontSize=17.sp)
                }
            }
        }
        Status(session)
        TextButton(onClick=session::signOut,enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("sign_out")) {
            LocalizedText("Sign out on this device")
        }
    }
}

@Composable private fun SafeContactProfile(session:SessionViewModel,profile:com.olive.app.data.UserProfile) {
    FormColumn {
        TextButton(onClick=session::back) { LocalizedText("Back") }
        Heading("My profile","Your Safe Contact account information.")
        listOf(
            "Username" to profile.username,
            "Full name" to profile.fullName,
            "Date of birth" to profile.dob,
            "Preferred language" to profile.language,
            "Region / city / state" to profile.region,
        ).forEach { (label,value) ->
            LocalizedText(label,style=MaterialTheme.typography.labelLarge,color=OliveGreen)
            LocalizedText(value.ifBlank { "Not provided" })
        }
    }
}
