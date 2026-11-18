// Safe-app users: shows the user’s main safe-app actions.
package com.olive.app.ui.users.user.dashboard

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.UserProfile
import com.olive.app.data.location.DeviceLocation
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.users.safecontact.SharedEntryViewer
import com.olive.app.ui.users.shared.camera.CameraScreen
import com.olive.app.ui.users.shared.messaging.MessagesScreen
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.shared.safetyplan.*
import com.olive.app.ui.users.user.account.*
import com.olive.app.ui.users.user.documents.EmergencyReportLocation
import com.olive.app.ui.users.user.documents.SafetyDocumentsScreen
import com.olive.app.ui.users.user.location.LocationMapScreen
import com.olive.app.ui.users.user.notes.PrivateNotes
import kotlinx.coroutines.launch

// Route account-owner pages to focused screen files; Safe Contacts use their separate workspace.
// SafeHomeScreen owns the feature tiles; individual screens own their layout and accessibility semantics.
@Composable
internal fun SafeArea(session: SessionViewModel, exit: () -> Unit) {
    val profile = session.profile ?: return
    LaunchedEffect(session.coverAccountId) { session.messages.ensureMessagingObservation() }
    AnimatedContent(
        targetState = session.page, label = "safe page",
        transitionSpec = {
            (fadeIn(tween(180)) + slideInHorizontally(tween(180)) { it / 12 })
                .togetherWith(fadeOut(tween(120)) + slideOutHorizontally(tween(120)) { -it / 16 })
        }) { targetPage ->
        // Opens the selected user Safe App feature.
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
            SafePage.StaffHelp -> StaffHelpScreen(session)
            SafePage.Documents -> SafetyDocumentsScreen(session)
            SafePage.Camera -> CameraScreen(session)
            SafePage.Location -> LocationMapScreen(session)
        }
    }
    if (session.viewedSharedEntry != null) SharedEntryViewer(session)
}

// Requests a staff assignment, keeps the user informed, then opens the assigned support chat.
@Composable
private fun StaffHelpScreen(session: SessionViewModel) {
    val controller = session.staffSupport
    LaunchedEffect(session.coverAccountId) {
        session.messages.ensureMessagingObservation()
        controller.requestHelp()
    }
    // Watch the support request until staff are assigned or the user cancels.
    LaunchedEffect(controller.conversationId, session.messages.conversations) {
        val chat = session.messages.conversations.firstOrNull {
            it.id == controller.conversationId
        } ?: session.messages.findRequestedConversation(controller.conversationId) ?: return@LaunchedEffect
        if (chat.staffUid.isBlank() || chat.closedAt > 0L) return@LaunchedEffect
        session.navigation.back()
        session.navigation.openSafePage(SafePage.Messages)
        session.messages.openConversation(chat)
    }
    BackHandler { session.navigation.back() }
    FormColumn {
        IconButton(onClick = session.navigation::back, modifier = Modifier.testTag("staff_help_back")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.ui_previous_safe_page))
        }
        Heading(
            stringResource(R.string.support_staff_help),
            stringResource(R.string.support_staff_search_help)
        )
        if (controller.staffUid.isBlank()) {
            LoadingSkeleton(SkeletonLayout.SupportAssignment, Modifier.fillMaxWidth().padding(16.dp))
        }
        Text(
            if (controller.staffUid.isBlank()) stringResource(R.string.support_looking_for_staff)
            else stringResource(R.string.support_staff_ready),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        controller.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        // Show the matching status after the user requests staff help.
        if (controller.requested) {
            OutlinedButton(
                onClick = {
                    controller.cancelRequest()
                    session.navigation.back()
                },
                enabled = controller.saving == 0,
                modifier = Modifier.fillMaxWidth().testTag("cancel_staff_help")
            ) { Text(stringResource(R.string.support_cancel_staff_request)) }
        }
    }
}

// The overlay owns both corners so an expanded menu never blocks the real Quick exit control.
@Composable
internal fun SafeSupportControls(
    session: SessionViewModel, exit: () -> Unit, showExit: Boolean,
    showSupport: Boolean, expanded: Boolean, onExpanded: (Boolean) -> Unit
) {
    if (!showExit && !showSupport) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var emergencyExportBusy by remember { mutableStateOf(false) }
    val emergencySavedMessage = stringResource(R.string.emergency_report_saved)
    val emergencyFailureMessage = stringResource(R.string.emergency_report_failed)
    val emergencyVerificationFailureMessage = stringResource(R.string.emergency_report_device_verification_failed)

    // The emergency action adds the completed report to the user's encrypted Documents list.
    fun createEmergencyReport() {
        if (emergencyExportBusy) return
        val profile = session.profile ?: return
        val ownerUid = session.coverAccountId ?: return
        emergencyExportBusy = true
        scope.launch {
            if (!session.localMode) {
                runCatching {
                    com.olive.app.location.AdminLocationService.start(context, ownerUid, profile.fullName)
                }
            }
            session.documents.createEmergencyReport(
                identity = AccountIdentity(ownerUid, session.coverUsername),
                profile = profile,
                entries = session.privateEntries,
                location = {
                    DeviceLocation.currentCoordinates(context)?.let {
                        EmergencyReportLocation(it.latitude, it.longitude, it.accuracy, it.timestamp)
                    }
                },
            ) { result ->
                emergencyExportBusy = false
                result.onSuccess {
                    Toast.makeText(
                        context,
                        emergencySavedMessage,
                        Toast.LENGTH_LONG,
                    ).show()
                }.onFailure { error ->
                    Toast.makeText(
                        context,
                        if (error is com.olive.app.data.media.MediaDeviceVerificationException)
                            emergencyVerificationFailureMessage else emergencyFailureMessage,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    val requestLocationPermission = LocalLocationPermissionRequest.current
    fun beginEmergencyReport() {
        scope.launch {
            requestLocationPermission()
            if (session.screen == SessionScreen.Safe) createEmergencyReport()
        }
    }
    val firstActionFocus = remember { FocusRequester() }
    // Keep support-menu focus and expanded state in sync for TalkBack.
    LaunchedEffect(expanded) {
        if (expanded) {
            withFrameNanos { }
            firstActionFocus.requestFocus()
        }
    }
    BackHandler(enabled = expanded) { onExpanded(false) }
    val menuTitle = stringResource(R.string.support_menu)
    val iconRotation by animateFloatAsState(
        if (expanded) 45f else 0f,
        animationSpec = tween(240, easing = FastOutSlowInEasing), label = "support button rotation"
    )
    BoxWithConstraints(
        Modifier.fillMaxSize().testTag("safe_support_controls")
            .accessibilityTraversal(AccessibilityTraversalOrder.PersistentActions)
            .accessibilityTraversalGroup()
            .then(if (expanded) Modifier.semantics { paneTitle = menuTitle } else Modifier)
    ) {
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
                .widthIn(max = 360.dp).heightIn(max = (maxHeight - 116.dp).coerceAtLeast(0.dp))
                .testTag("support_action_list")
                .accessibilityTraversal(AccessibilityTraversalOrder.OverlayContent),
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
                    150,
                    Modifier.focusRequester(firstActionFocus)
                ) {
                    if (expanded) {
                        onExpanded(false)
                        session.navigation.openSafePage(SafePage.Location)
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
                    true,
                    90
                ) {
                    if (expanded) {
                        onExpanded(false)
                        session.navigation.openSafePage(SafePage.StaffHelp)
                    }
                }
                SupportAction(
                    if (emergencyExportBusy) R.string.emergency_report_creating else R.string.support_emergency,
                    Icons.Outlined.WarningAmber,
                    "support_emergency",
                    true,
                    60
                ) {
                    if (expanded && !emergencyExportBusy) {
                        onExpanded(false)
                        beginEmergencyReport()
                    }
                }
                SupportAction(
                    R.string.support_hotlines,
                    Icons.Outlined.PhoneInTalk,
                    "support_hotlines",
                    true,
                    30
                ) {
                    if (expanded) {
                        onExpanded(false)
                        session.navigation.openSafePage(SafePage.Hotlines)
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
                        session.navigation.openSafePage(SafePage.Contacts)
                    }
                }
            }
        }
        Box(
            Modifier.fillMaxSize().testTag("support_persistent_actions")
                .accessibilityTraversal(AccessibilityTraversalOrder.PersistentActions)
        ) {
            if (showSupport) {
                val label = stringResource(if (expanded) R.string.support_close_menu else R.string.support_open_menu)
                val state = stringResource(if (expanded) R.string.support_expanded else R.string.support_collapsed)
                val shape = MaterialTheme.shapes.large
                FloatingActionButton(
                    onClick = { onExpanded(!expanded) },
                    shape = shape,
                    containerColor = OliveGreen,
                    contentColor = Paper,
                    modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(12.dp)
                        .border(1.dp, SageGreen, shape)
                        .testTag("support_menu_toggle").semantics {
                            contentDescription = label
                            stateDescription = state
                        },
                ) { Icon(Icons.Outlined.Add, null, Modifier.rotate(iconRotation)) }
            }
            if (showExit || expanded) {
                val topExit = session.screen != SessionScreen.Safe
                FloatingQuickExit(
                    onClick = {
                        onExpanded(false)
                        exit()
                    },
                    modifier = if (topExit) Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp)
                    else Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(12.dp),
                    compact = topExit,
                )
            }
        }
    }
}

// Draws one support-menu button and delays its entrance so the list opens smoothly.
@Composable
private fun AnimatedVisibilityScope.SupportAction(
    labelId: Int, icon: ImageVector, tag: String, enabled: Boolean,
    delayMillis: Int, modifier: Modifier = Modifier, onClick: () -> Unit
) {
    val label = stringResource(labelId)
    val title = if (enabled) label else stringResource(R.string.support_coming_soon, label)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag)
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
    OlivePrivateDesign {
        val uiText = rememberResourceText()
        val scroll = rememberScrollState()
        var headingHeight by remember { mutableIntStateOf(Int.MAX_VALUE) }
        val compact by remember { derivedStateOf { scroll.value >= headingHeight } }
        val title = uiText(
            if (profile.role == AccountRole.SAFE_CONTACT) R.string.safe_contact_space else R.string.your_safe_space
        )

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SafeHomeBar(session, title, compact)
                Box(
                    Modifier.weight(1f).widthIn(max = 640.dp).fillMaxWidth()
                ) {
                    Column(
                        Modifier.fillMaxSize().testTag("safe_home_content").oliveScrollbar(scroll).verticalScroll(scroll)
                            .padding(horizontal = OlivePrivateLayout.ScreenPadding)
                            .padding(top = 4.dp, bottom = OlivePrivateLayout.FloatingActionClearance),
                        verticalArrangement = Arrangement.spacedBy(OlivePrivateLayout.SectionSpacing)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().onSizeChanged { headingHeight = it.height },
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                title,
                                style = MaterialTheme.typography.displaySmall,
                                modifier = Modifier.semantics { heading() }
                            )
                            Text(
                                if (profile.role == AccountRole.SAFE_CONTACT)
                                    uiText(R.string.ui_use_quick_exit_from_any_page_to_lock_olive_branch)
                                else uiText(R.string.use_quick_exit_from_any_page_to_lock_this_area_and_return_to_your_cover_app),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        ModeNotice(session)
                        SafeHomeShortcuts(session)
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                stringResource(R.string.private_features),
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.semantics { heading() }
                            )
                            SafeFeatureGrid(session)
                        }
                        if (session.pendingSync) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.profile_changes_are_saved_on_this_device_and_awaiting_cloud_sync))
                                OutlinedButton(onClick = session::sync, enabled = !session.busy) {
                                    Text(stringResource(R.string.sync_changes))
                                }
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                if (profile.role == AccountRole.SAFE_CONTACT)
                                    uiText(R.string.ui_leaving_the_app_locks_olive_branch)
                                else uiText(R.string.leaving_the_app_locks_this_area_a_cover_app_does_not_make_olive_branch_impossible_to_discover),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Status(session)
                            OutlinedButton(
                                onClick = session::signOut,
                                enabled = !session.busy,
                                modifier = Modifier.testTag("sign_out")
                                    .fillMaxWidth()
                            ) { Text(stringResource(R.string.sign_out_on_this_device)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SafeHomeBar(session: SessionViewModel, title: String, compact: Boolean) {
    val color by animateColorAsState(
        if (compact) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.background,
        label = "Safe Home bar color"
    )
    Surface(color = color, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = OlivePrivateLayout.ScreenPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f)) {
                if (compact) Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.clearAndSetSemantics {}
                ) else Text(
                    stringResource(R.string.olive_branch),
                    fontFamily = FontFamily.Serif,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            SafeAppUpdateReminder()
            FilledTonalIconButton(
                onClick = { session.navigation.openSafePage(SafePage.Camera) },
                enabled = !session.localMode,
                modifier = Modifier.size(OlivePrivateLayout.HeaderActionSize).testTag("safe_camera")
            ) { Icon(Icons.Outlined.PhotoCamera, stringResource(R.string.camera_title)) }
            FilledTonalIconButton(
                onClick = { session.navigation.openSafePage(SafePage.Settings) },
                modifier = Modifier.size(OlivePrivateLayout.HeaderActionSize).testTag("safe_settings")
            ) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings)) }
        }
    }
}

private enum class SafeHomeFeature(val page: SafePage, val label: Int, val icon: ImageVector, val tag: String) {
    Notes(SafePage.Notes, R.string.notes_incidents, Icons.Outlined.Description, "safe_notes"),
    Messages(SafePage.Messages, R.string.messages, Icons.Outlined.ChatBubbleOutline, "safe_messages"),
    SafetyPlan(SafePage.SafetyPlan, R.string.support_safety_plan, Icons.Outlined.HealthAndSafety, "safe_safety_plan"),
    Documents(SafePage.Documents, R.string.support_documents, Icons.Outlined.FolderOpen, "safe_documents")
}

@Composable
private fun SafeFeatureGrid(session: SessionViewModel) {
    val uiText = rememberResourceText()
    val fontScale = LocalDensity.current.fontScale
    val cardGap = 12.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 300.dp && fontScale < 1.4f) 2 else 1
        Column(verticalArrangement = Arrangement.spacedBy(cardGap)) {
            SafeHomeFeature.entries.chunked(columns).forEach { features ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(cardGap)
                ) {
                    features.forEach { feature ->
                        SafeFeatureCard(
                            label = uiText(feature.label),
                            feature = feature,
                            pendingMessages = session.messages.pendingActionCount,
                            enabled = session.accountRole == AccountRole.USER ||
                                feature == SafeHomeFeature.Notes || feature == SafeHomeFeature.Messages,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = { session.navigation.openSafePage(feature.page) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SafeFeatureCard(
    label: String,
    feature: SafeHomeFeature,
    pendingMessages: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.testTag(feature.tag).semantics { role = Role.Button },
        shape = MaterialTheme.shapes.extraLarge,
        color = if (enabled) PaleOlive else colors.surfaceContainer,
        contentColor = if (enabled) colors.onSurface else colors.onSurfaceVariant,
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) {
        Column(
            Modifier.heightIn(min = 124.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val icon: @Composable () -> Unit = {
                Surface(
                    shape = CircleShape,
                    color = if (enabled) SageGreen else colors.surfaceVariant,
                    contentColor = if (enabled) DarkOlive else colors.onSurfaceVariant
                ) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(feature.icon, null, Modifier.size(26.dp))
                    }
                }
            }
            if (feature == SafeHomeFeature.Messages && pendingMessages > 0) {
                BadgedBox(
                    badge = { Badge { Text(if (pendingMessages > 99) "99+" else pendingMessages.toString()) } },
                    content = { icon() }
                )
            } else icon()
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SafeHomeShortcuts(session: SessionViewModel) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val sideBySide = maxWidth >= 280.dp && fontScale < 1.4f
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            maxItemsInEachRow = if (sideBySide) 2 else 1,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SafeHomeShortcut(
                label = stringResource(R.string.my_profile),
                icon = Icons.Outlined.PersonOutline,
                modifier = Modifier.weight(1f).testTag("safe_profile"),
                onClick = { session.navigation.openSafePage(SafePage.Profile) }
            )
            SafeHomeShortcut(
                label = stringResource(R.string.account_activity),
                icon = Icons.Outlined.History,
                modifier = Modifier.weight(1f).testTag("safe_activity"),
                onClick = { session.navigation.openSafePage(SafePage.Activity) }
            )
        }
    }
}

@Composable
private fun SafeHomeShortcut(
    label: String,
    icon: ImageVector,
    modifier: Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 50.dp),
        shape = MaterialTheme.shapes.large,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, textAlign = TextAlign.Center)
    }
}

