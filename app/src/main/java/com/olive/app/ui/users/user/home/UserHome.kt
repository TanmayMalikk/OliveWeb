// Contains the user home code for this part of Olive.
package com.olive.app.ui.users.user.home

import com.olive.app.ui.shared.*


import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.ui.users.safecontact.SharedEntryViewer
import com.olive.app.ui.users.shared.camera.CameraScreen
import com.olive.app.ui.users.shared.messaging.MessagesScreen
import com.olive.app.ui.users.shared.safetyplan.SafetyPlanScreen
import com.olive.app.ui.users.user.account.*
import com.olive.app.ui.users.user.documents.SafetyDocumentsScreen
import com.olive.app.ui.users.user.location.LocationMapScreen
import com.olive.app.ui.users.user.notes.PrivateNotes

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.data.*
import com.olive.app.R
import com.olive.app.ui.shared.LocalizedIcon as Icon

// Route account-owner pages to focused screen files; Safe Contacts use their separate workspace.
// SafeHomeScreen owns the feature tiles; individual screens own their layout and accessibility semantics.
@Composable
internal fun SafeArea(session: SessionViewModel, exit: () -> Unit) {
    val profile = session.profile ?: return
    LaunchedEffect(session.coverAccountId) { session.ensureMessagingObservation() }
    AnimatedContent(
        targetState = session.page, label = "safe page",
        transitionSpec = {
            (fadeIn(tween(180)) + slideInHorizontally(tween(180)) { it / 12 })
                .togetherWith(fadeOut(tween(120)) + slideOutHorizontally(tween(120)) { -it / 16 })
        }) { targetPage ->
        when (targetPage) {
            SafePage.Home -> SafeHomeScreen(session, profile)
            SafePage.Profile -> SafeProfileScreen(session, profile)
            SafePage.Notes -> PrivateNotes(session)
            SafePage.Messages -> MessagesScreen(session, exit)
            SafePage.Settings -> SettingsEditor(session, profile)
            SafePage.Contacts -> ContactEditor(session, profile)
            SafePage.Activity -> ActivityHistory(session)
            SafePage.Hotlines -> SafeResourcesScreen(session, profile)
            SafePage.SafetyPlan -> SafetyPlanScreen(session)
            SafePage.Documents -> SafetyDocumentsScreen(session)
            SafePage.Camera -> CameraScreen(session)
            SafePage.Location -> LocationMapScreen(session)
        }
    }
    if (session.viewedSharedEntry != null) SharedEntryViewer(session)
}

// The overlay owns both corners so an expanded menu never blocks the real Quick exit control.
@Composable
internal fun SafeSupportControls(
    session: SessionViewModel, exit: () -> Unit, showExit: Boolean,
    showSupport: Boolean, expanded: Boolean, onExpanded: (Boolean) -> Unit
) {
    if (!showExit && !showSupport) return
    BackHandler(enabled = expanded) { onExpanded(false) }
    val menuTitle = stringResource(R.string.support_menu)
    val iconRotation by animateFloatAsState(
        if (expanded) 45f else 0f,
        animationSpec = tween(240, easing = FastOutSlowInEasing), label = "support button rotation"
    )
    BoxWithConstraints(Modifier.fillMaxSize().then(if (expanded) Modifier.semantics {
        paneTitle = menuTitle
        isTraversalGroup = true
    } else Modifier)) {
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(140))
        ) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = .22f))
                    .clickable(enabled = expanded) { onExpanded(false) }.clearAndSetSemantics {})
        }
        // Grow from the trigger, then reveal the nearest option first. Compose respects the system animation scale.
        AnimatedVisibility(
            visible = expanded,
            modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, bottom = 92.dp)
                .widthIn(max = 360.dp).heightIn(max = (maxHeight - 116.dp).coerceAtLeast(0.dp)),
            enter = fadeIn(tween(160)) + scaleIn(
                tween(260, easing = FastOutSlowInEasing), initialScale = .92f,
                transformOrigin = TransformOrigin(0f, 1f)
            ),
            exit = fadeOut(tween(120)) + scaleOut(
                tween(140),
                targetScale = .96f,
                transformOrigin = TransformOrigin(0f, 1f)
            )
        ) {
            // Closing options immediately leave the accessibility tree even while their visual exit finishes.
            Column(
                Modifier.then(if (expanded) Modifier else Modifier.clearAndSetSemantics {})
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SupportAction(
                    R.string.location_map,
                    Icons.Outlined.LocationOn,
                    "support_location",
                    true,
                    150
                ) {
                    if (expanded) {
                        onExpanded(false)
                        session.navigate(SafePage.Location)
                    }
                }
                SupportAction(
                    R.string.support_walkthrough,
                    Icons.AutoMirrored.Outlined.MenuBook,
                    "support_walkthrough",
                    false,
                    120
                ) {}
                SupportAction(
                    R.string.support_staff_help,
                    Icons.Outlined.SupportAgent,
                    "support_staff_help",
                    false,
                    90
                ) {}
                SupportAction(
                    R.string.support_emergency,
                    Icons.Outlined.WarningAmber,
                    "support_emergency",
                    false,
                    60
                ) {}
                SupportAction(
                    R.string.support_hotlines,
                    Icons.Outlined.PhoneInTalk,
                    "support_hotlines",
                    true,
                    30
                ) {
                    if (expanded) {
                        onExpanded(false)
                        session.navigate(SafePage.Hotlines)
                    }
                }
                SupportAction(
                    R.string.safe_contacts,
                    Icons.Outlined.Contacts,
                    "support_contacts",
                    true,
                    0
                ) {
                    if (expanded) {
                        onExpanded(false)
                        session.navigate(SafePage.Contacts)
                    }
                }
            }
        }
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(12.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (showSupport) {
                val label =
                    stringResource(if (expanded) R.string.support_close_menu else R.string.support_open_menu)
                val state =
                    stringResource(if (expanded) R.string.support_expanded else R.string.support_collapsed)
                FloatingActionButton(
                    onClick = { onExpanded(!expanded) },
                    containerColor = OliveGreen,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("support_menu_toggle").semantics {
                        contentDescription = label
                        stateDescription = state
                    }) { Icon(Icons.Outlined.Add, null, Modifier.rotate(iconRotation)) }
            } else Spacer(Modifier.width(0.dp))
            if (showExit || expanded) {
                val label = stringResource(R.string.a11y_quick_exit)
                ExtendedFloatingActionButton(
                    onClick = {
                        onExpanded(false)
                        exit()
                    },
                    containerColor = OliveGreen,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("quick_exit")
                        .semantics { contentDescription = label },
                    icon = { Icon(Icons.Outlined.Close, null) },
                    text = {
                        Text(
                            stringResource(R.string.quick_exit),
                            modifier = Modifier.clearAndSetSemantics {})
                    })
            }
        }
    }
}

@Composable
private fun AnimatedVisibilityScope.SupportAction(
    labelId: Int, icon: ImageVector, tag: String, enabled: Boolean,
    delayMillis: Int, onClick: () -> Unit
) {
    val label = stringResource(labelId)
    val title = if (enabled) label else stringResource(R.string.support_coming_soon, label)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)
            .animateEnterExit(
                enter = fadeIn(tween(160, delayMillis)) + slideInVertically(
                    tween(
                        220,
                        delayMillis,
                        FastOutSlowInEasing
                    )
                ) { it / 3 },
                exit = fadeOut(tween(90))
            ),
        shape = MaterialTheme.shapes.large,
        contentPadding = PaddingValues(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = LightSage,
            disabledContentColor = Ink
        )
    ) {
        Icon(icon, null, Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
    }
}

// The safe home screen owns feature discovery and pending activity.
@Composable
private fun SafeHomeScreen(session: SessionViewModel, profile: UserProfile) {
    val uiText = rememberResourceText()
    FormColumn {
        Box(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(end = 104.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    stringResource(R.string.olive_branch),
                    fontFamily = FontFamily.Serif,
                    fontSize = 30.sp,
                    color = OliveGreen
                )
                Text(
                    if (profile.role == AccountRole.SAFE_CONTACT) uiText(R.string.safe_contact_space) else uiText(
                        R.string.your_safe_space
                    ),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() })
                Text(
                    if (profile.role == AccountRole.SAFE_CONTACT) uiText(R.string.ui_use_quick_exit_from_any_page_to_lock_olive_branch) else uiText(
                        R.string.use_quick_exit_from_any_page_to_lock_this_area_and_return_to_your_cover_app
                    ), color = Muted
                )
            }
            Row(Modifier.align(Alignment.TopEnd)) {
                IconButton(
                    onClick = { session.navigate(SafePage.Camera) }, enabled = !session.localMode,
                    modifier = Modifier.testTag("safe_camera")
                ) {
                    Icon(Icons.Outlined.PhotoCamera, stringResource(R.string.camera_title))
                }
                IconButton(
                    onClick = { session.navigate(SafePage.Settings) },
                    modifier = Modifier.testTag("safe_settings")
                ) {
                    Icon(Icons.Outlined.Settings, uiText(R.string.settings))
                }
            }
        }
        ModeNotice(session)
        Button(
            onClick = { session.navigate(SafePage.Profile) },
            modifier = Modifier.fillMaxWidth().testTag("safe_profile")
        ) { Text(stringResource(R.string.my_profile)) }
        Button(
            onClick = { session.navigate(SafePage.Activity) },
            modifier = Modifier.fillMaxWidth().testTag("safe_activity")
        ) { Text(stringResource(R.string.account_activity)) }
        Text(
            stringResource(R.string.private_features),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() })
        // UI sizing/color: feature tiles are two-wide, at least 112dp tall, use 30–32dp icons, and 17–18sp centered labels.
        // Change the two container colors here if these tiles need different shades from the shared theme.
        SafeFeatureGrid(session)
        if (session.pendingSync) {
            Text(stringResource(R.string.profile_changes_are_saved_on_this_device_and_awaiting_cloud_sync))
            OutlinedButton(
                onClick = session::sync,
                enabled = !session.busy
            ) { Text(stringResource(R.string.sync_changes)) }
        }
        Text(
            if (profile.role == AccountRole.SAFE_CONTACT) uiText(R.string.ui_leaving_the_app_locks_olive_branch) else uiText(
                R.string.leaving_the_app_locks_this_area_a_cover_app_does_not_make_olive_branch_impossible_to_discover
            ), style = MaterialTheme.typography.bodySmall, color = Muted
        )
        Status(session)
        TextButton(
            onClick = session::signOut,
            enabled = !session.busy,
            modifier = Modifier.testTag("sign_out")
        ) { Text(stringResource(R.string.sign_out_on_this_device)) }
    }
}

@Composable
private fun SafeFeatureGrid(session: SessionViewModel) {
    val uiText = rememberResourceText()
    listOf(0, 2).forEach { first ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(first, first + 1).forEach { index ->
                val page = listOf(
                    SafePage.Notes,
                    SafePage.Messages,
                    SafePage.SafetyPlan,
                    SafePage.Documents
                )[index]
                val label = listOf(
                    R.string.notes_incidents,
                    R.string.messages,
                    R.string.support_safety_plan,
                    R.string.support_documents
                )[index]
                val icon = listOf(
                    Icons.Outlined.Description,
                    Icons.Outlined.ChatBubbleOutline,
                    Icons.Outlined.HealthAndSafety,
                    Icons.Outlined.FolderOpen
                )[index]
                val tag = listOf(
                    "safe_notes",
                    "safe_messages",
                    "safe_safety_plan",
                    "safe_documents"
                )[index]
                SafeFeatureTile(
                    label = uiText(label),
                    icon = {
                        if (page == SafePage.Messages) BadgedBox(badge = {
                            if (session.pendingMessageActionCount > 0) Badge {
                                Text(if (session.pendingMessageActionCount > 99) "99+" else session.pendingMessageActionCount.toString())
                            }
                        }) { Icon(icon, uiText(label), Modifier.size(27.dp)) }
                        else Icon(icon, uiText(label), Modifier.size(27.dp))
                    },
                    enabled = index < 2 || session.accountRole == AccountRole.USER,
                    onClick = { session.navigate(page) },
                    modifier = Modifier.weight(1f).testTag(tag)
                )
            }
        }
    }
}

@Composable
private fun SafeFeatureTile(
    label: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 112.dp),
        shape = RoundedCornerShape(24.dp),
        color = if (enabled) Color(0xFFF4F6EF) else Color(0xFFE9EDE5),
        contentColor = if (enabled) OliveGreen else Muted,
        border = BorderStroke(
            1.dp,
            if (enabled) OliveGreen.copy(alpha = .25f) else Muted.copy(alpha = .18f)
        ),
        tonalElevation = if (enabled) 2.dp else 0.dp
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = if (enabled) Color(0xFFDDE7D6) else Color(0xFFDDE2D9),
                contentColor = if (enabled) OliveGreen else Muted
            ) {
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) { icon() }
            }
            Spacer(Modifier.height(9.dp))
            Text(
                label,
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}
