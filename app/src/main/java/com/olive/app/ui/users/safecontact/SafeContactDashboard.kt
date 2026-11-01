// Safe contacts: shows the user’s shared safety information to a safe contact.
package com.olive.app.ui.users.safecontact

import com.olive.app.ui.shared.*


import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.ui.users.shared.camera.CameraScreen
import com.olive.app.ui.users.shared.messaging.MessagesScreen
import com.olive.app.ui.users.shared.safetyplan.SafeContactSafetyPlansScreen
import com.olive.app.ui.users.user.account.SettingsEditor
import com.olive.app.ui.users.user.documents.SafetyDocumentsScreen
import com.olive.app.ui.users.user.location.LocationMapScreen
import com.olive.app.ui.users.user.notes.PrivateNotes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.olive.app.data.UserProfile
import com.olive.app.R
import com.olive.app.ui.shared.LocalizedIcon as Icon

// Safe-contact accounts use a connection-focused dashboard and can open only records explicitly shared with them.
@Composable
internal fun SafeContactArea(session: SessionViewModel) {
    val profile = session.profile ?: return
    LaunchedEffect(session.coverAccountId) { session.messages.ensureMessagingObservation() }
    AnimatedContent(
        session.page, label = "safe contact page",
        transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(120))) }) { page ->
        when (page) {
            SafePage.Home -> SafeContactHome(session)
            SafePage.Messages -> MessagesScreen(session)
            SafePage.Notes -> PrivateNotes(session)
            SafePage.Profile -> SafeContactProfile(session, profile)
            SafePage.Settings -> SettingsEditor(session, profile)
            SafePage.Location -> LocationMapScreen(session)
            SafePage.SafetyPlan -> SafeContactSafetyPlansScreen(session)
            SafePage.Documents -> SafetyDocumentsScreen(session)
            SafePage.Camera -> CameraScreen(session)
            else -> SafeContactHome(session)
        }
    }
    if (session.viewedSharedEntry != null) SharedEntryViewer(session)
}

@Composable
private fun SafeContactHome(session: SessionViewModel) {
    val uiText = rememberResourceText()
    FormColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.olive_branch),
                    fontFamily = FontFamily.Serif,
                    fontSize = 30.sp,
                    color = OliveGreen
                )
                Text(
                    stringResource(R.string.safe_contact),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.safe_contact_home_help), color = Muted)
            }
            Row {
                IconButton(
                    onClick = { session.navigation.openSafePage(SafePage.Camera) }, enabled = !session.localMode,
                    modifier = Modifier.testTag("safe_contact_camera")
                ) {
                    Icon(Icons.Outlined.PhotoCamera, stringResource(R.string.camera_title))
                }
                IconButton(
                    onClick = { session.navigation.openSafePage(SafePage.Settings) },
                    modifier = Modifier.testTag("safe_contact_settings")
                ) {
                    Icon(Icons.Outlined.Settings, uiText(R.string.account_settings))
                }
            }
        }
        ModeNotice(session)
        Button(
            onClick = { session.navigation.openSafePage(SafePage.Profile) },
            modifier = Modifier.fillMaxWidth().testTag("safe_contact_profile")
        ) {
            Icon(Icons.Outlined.PersonOutline, null)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.my_profile)
            )
        }
        OutlinedButton(
            onClick = { session.navigation.openSafePage(SafePage.Location) },
            modifier = Modifier.fillMaxWidth().testTag("safe_contact_location")
        ) {
            Icon(
                Icons.Outlined.LocationOn,
                null
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.location_map))
        }
        Text(
            stringResource(R.string.private_features), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() })
        SafeContactActionCard(
            stringResource(R.string.notes_incidents),
            stringResource(R.string.safe_contact_notes_help),
            Icons.Outlined.Description,
            "safe_contact_notes",
            0,
            { session.navigation.openSafePage(SafePage.Notes) })
        SafeContactActionCard(
            stringResource(R.string.messages),
            stringResource(R.string.safe_contact_messages_help),
            Icons.Outlined.ChatBubbleOutline,
            "safe_contact_messages",
            session.messages.pendingActionCount,
            { session.navigation.openSafePage(SafePage.Messages) })
        SafeContactActionCard(
            stringResource(R.string.support_safety_plan),
            stringResource(R.string.safe_contact_safety_plan_help),
            Icons.Outlined.HealthAndSafety,
            "safe_contact_safety_plan",
            0,
            { session.navigation.openSafePage(SafePage.SafetyPlan) })
        SafeContactActionCard(
            stringResource(R.string.support_documents),
            stringResource(R.string.safe_contact_documents_help),
            Icons.Outlined.FolderOpen,
            "safe_contact_documents",
            0,
            { session.navigation.openSafePage(SafePage.Documents) })
        if (session.pendingSync) Surface(
            Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Row(
                Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Outlined.CloudUpload, null)
                Text(
                    stringResource(R.string.safe_contact_changes_waiting),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(
                    onClick = session::sync,
                    enabled = !session.busy
                ) { Text(stringResource(R.string.sync_changes)) }
            }
        }
        Status(session)
    }
}

@Composable
private fun SafeContactProfile(session: SessionViewModel, profile: UserProfile) {
    val uiText = rememberResourceText()
    FormColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = session.navigation::back,
                modifier = Modifier.testTag("safe_contact_profile_back")
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, uiText(R.string.ui_previous_safe_page))
            }
            Text(
                stringResource(R.string.my_profile),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() })
        }
        Text(stringResource(R.string.safe_contact_profile_intro), color = Muted)
        SafeContactIdentityCard(profile) { session.navigation.openSafePage(SafePage.Settings) }
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SafeContactProfileRow(
                    uiText(R.string.username),
                    profile.username,
                    Icons.Outlined.AlternateEmail
                )
                HorizontalDivider()
                SafeContactProfileRow(
                    uiText(R.string.date_of_birth),
                    profile.dob,
                    Icons.Outlined.Cake
                )
                HorizontalDivider()
                SafeContactProfileRow(
                    uiText(R.string.region_city_state),
                    profile.region,
                    Icons.Outlined.LocationOn
                )
            }
        }
        Button(
            onClick = { session.navigation.openSafePage(SafePage.Settings) },
            modifier = Modifier.fillMaxWidth().testTag("safe_contact_edit_profile")
        ) {
            Icon(
                Icons.Outlined.Edit,
                null
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.safe_contact_edit_profile))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharedEntryViewer(session: SessionViewModel) {
    val entry = session.viewedSharedEntry ?: return

    // Immediately clear the state if the user uses the system back swipe
    BackHandler {
        session.messages.closeSharedPrivateEntry()
    }

    Dialog(
        onDismissRequest = { session.messages.closeSharedPrivateEntry() },
        properties = DialogProperties(usePlatformDefaultWidth = false) // Allows full-screen
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header Toolbar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.secure_shared_entry),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 12.dp).semantics { heading() },
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(onClick = { session.messages.closeSharedPrivateEntry() }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.close_shared_entry)
                        )
                    }
                }

                HorizontalDivider()

                // Decrypted Content Payload
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp)
                ) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = entry.body,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}
