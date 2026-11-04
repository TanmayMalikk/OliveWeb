// All safe-app roles: routes each account role to its allowed screen.
package com.olive.app.ui.users.shared.navigation

import com.olive.app.ui.shared.*

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.data.*
import com.olive.app.data.AccountIdentity
import com.olive.app.R
import com.olive.app.ui.covers.fitness.FitnessCover
import com.olive.app.ui.covers.GameCover
import com.olive.app.ui.covers.news.NewsCover
import com.olive.app.ui.covers.food.OliveCover
import com.olive.app.ui.covers.food.OliveViewModel
import com.olive.app.ui.users.admin.AdminPortal
import com.olive.app.ui.users.safecontact.SafeContactArea
import com.olive.app.ui.users.shared.auth.*
import com.olive.app.ui.users.shared.onboarding.ProfileSetup
import com.olive.app.ui.users.shared.organization.OrganizationProfileSetup
import com.olive.app.ui.users.staff.StaffPortal
import com.olive.app.ui.users.user.account.TriggerSetup
import com.olive.app.ui.users.user.dashboard.SafeArea
import com.olive.app.ui.users.user.dashboard.SafeSupportControls
import com.olive.app.data.messaging.VoipSessionState
import com.olive.app.data.messaging.VoipInvitation
import com.olive.app.ui.users.shared.messaging.ActiveCallDialog
import com.olive.app.ui.users.shared.messaging.IncomingCallDialog
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import java.util.Base64

// Keep the chosen cover loaded under the safe flow so Quick exit can reveal it fast.
// UI sizing/color: this root fills the window; the 12dp quick-exit inset and OliveGreen button styling live in its Scaffold.
internal fun shouldComposeCover(usesCover: Boolean) = usesCover

internal fun shouldShowCallOverlay(screen: SessionScreen, role: AccountRole): Boolean =
    screen == SessionScreen.Safe && role in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT)

internal fun resolveIncomingCaller(
    invitation: VoipInvitation,
    conversations: List<ChatConversation>,
    currentUid: String,
): VoipInvitation? {
    val callerUsername = conversations.firstOrNull { conversation ->
        invitation.callerId in conversation.participantIds && currentUid in conversation.participantIds
    }?.participantUsernames?.get(invitation.callerId).orEmpty()
    return invitation.copy(name = callerUsername).takeIf { callerUsername.isNotBlank() }
}

// Chooses the account, cover, onboarding, or Safe App root screen.
@Composable
internal fun OliveRoot(session: SessionViewModel, locationPermissionVersion: Int = 0) {
    val uiText = rememberResourceText()
    val callState by session.calls.state.collectAsState()
    val callAccountId = session.coverAccountId.orEmpty()
    val incomingCall = session.calls.invitations.firstOrNull()?.let {
        resolveIncomingCaller(it, session.messages.conversations, callAccountId)
    }
    val callOverlayVisible = shouldShowCallOverlay(session.screen, session.accountRole) &&
        (incomingCall != null || callState != VoipSessionState.Idle)
    val context = LocalContext.current
    var pendingCallPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        session.endCallPermissionRequest()
        val action = pendingCallPermissionAction
        pendingCallPermissionAction = null
        if (granted) action?.invoke()
        else session.calls.report(uiText(R.string.ui_microphone_permission_required))
    }
    val withMicrophonePermission: (() -> Unit) -> Unit = { action ->
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) action() else {
            pendingCallPermissionAction = action
            session.beginCallPermissionRequest()
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(callAccountId, session.accountRole) {
        if (callAccountId.isNotBlank()) {
            session.calls.observeInvitations(callAccountId, session.accountRole)
        }
    }
    LaunchedEffect(callOverlayVisible) {
        if (callOverlayVisible) session.pauseIdleTimerForCall()
        else session.resumeIdleTimerAfterCall()
    }
    val cover: OliveViewModel = com.olive.app.ui.covers.food.oliveViewModel()
    SideEffect {
        cover.persistCover = { cart, favorites, customizations ->
            session.saveFoodCover(
                cart,
                favorites,
                customizations
            )
        }
    }
    LaunchedEffect(session.coverAccountId, session.coverDataVersion) {
        cover.restoreCover(session.foodCart, session.foodFavorites, session.foodCustomizations)
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val isCover = session.screen == SessionScreen.Cover
    val view = LocalView.current
    val accessibilityRoot = (view.parent as? ComposeView) ?: view
    val privacyHidden = session.privacyBlackout
    SideEffect {
        accessibilityRoot.importantForAccessibility =
            if (privacyHidden) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }
    val exit: () -> Unit = {
        session.navigation.quickExit()
        keyboard?.hide()
        focus.clearFocus(force = true)
    }
    val coverTitle = session.coverType.localizedTitle(uiText)
    val inActiveChat = session.page == SafePage.Messages && session.messages.activeConversation != null
    val safeUser = session.screen == SessionScreen.Safe && session.accountRole == AccountRole.USER
    val showSupport = safeUser && !inActiveChat && session.page != SafePage.Camera
    val showQuickExit = (safeUser && !inActiveChat && session.page != SafePage.Camera) ||
            (session.screen == SessionScreen.Pin && session.usesCover)
    var supportExpanded by remember(
        session.screen,
        session.page,
        session.coverAccountId
    ) { mutableStateOf(false) }
    var observedUnlock by remember { mutableIntStateOf(session.unlockAnimationVersion) }
    var pinAnimationRunning by remember { mutableStateOf(false) }
    // Start the olive reveal only after a new successful private entry.
    LaunchedEffect(session.unlockAnimationVersion, session.screen) {
        val currentUnlock = session.unlockAnimationVersion
        if (currentUnlock > observedUnlock && session.screen == SessionScreen.Safe) {
            pinAnimationRunning = true
        } else if (session.screen != SessionScreen.Safe) {
            pinAnimationRunning = false
        }
        observedUnlock = currentUnlock
    }
    val showPinAnimation = pinAnimationRunning
    Box(Modifier.fillMaxSize().then(if (isCover) Modifier else Modifier.imePadding())) {
        // User accounts keep their cover loaded underneath; the other roles never create a cover screen.
        Box(
            if (isCover && !session.privacyBlackout) Modifier.fillMaxSize().testTag("cover_root")
                .semantics { paneTitle = coverTitle } else Modifier.fillMaxSize()
                .clearAndSetSemantics { }) {
            Box(Modifier.fillMaxSize()) {
                if (shouldComposeCover(session.usesCover)) {
                    // Keep the user's selected cover alive underneath private screens.
                    when (session.coverType) {
                        CoverType.FOOD -> OliveCover(
                            cover,
                            interactive = isCover,
                            onSearch = { value ->
                                if (session.account.interceptSearch(value)) {
                                    cover.search("")
                                    focus.clearFocus()
                                    keyboard?.hide()
                                } else cover.search(value)
                            },
                            onAccountSetup = session.account::openAccountSetup,
                            configured = session.account.configured,
                            session = session
                        )

                        CoverType.NEWS -> NewsCover(
                            session,
                            locationPermissionVersion,
                            isCover && !session.privacyBlackout
                        )

                        CoverType.FITNESS -> FitnessCover(session, isCover)
                        CoverType.GAME -> GameCover(
                            sessionViewModel = session,
                            interactive = isCover,
                            onEntryAttempt = session.account::interceptSearch,
                        )
                    }
                }
            }
            // Return TalkBack focus to the cover after a privacy exit finishes.
            if (isCover && !session.privacyBlackout && session.accessibilityExitVersion > 0) {
                key(session.accessibilityExitVersion) {
                    AccessibleMessage(
                        uiText(R.string.a11y_returned_to_cover, coverTitle),
                        Modifier.align(Alignment.CenterStart).size(1.dp), showText = false
                    )
                }
            }
        }
        if (!isCover) {
            // Hide the keyboard whenever the private PIN screen opens.
            LaunchedEffect(session.screen) {
                if (session.screen == SessionScreen.Pin) {
                    keyboard?.hide()
                    focus.clearFocus(force = true)
                }
            }
            BackHandler {
                keyboard?.hide()
                focus.clearFocus(force = true)
                session.navigation.back()
            }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize().accessibilityTraversalGroup()) {
                    Scaffold(
                        modifier = Modifier.testTag("session_screen_content")
                            .accessibilityTraversal(AccessibilityTraversalOrder.ScreenContent)
                            .then(if (supportExpanded || showPinAnimation) Modifier.clearAndSetSemantics {} else Modifier),
                        bottomBar = {
                            if (showQuickExit || showSupport) Spacer(
                                Modifier.fillMaxWidth().navigationBarsPadding().height(80.dp)
                            )
                        },
                        contentWindowInsets = if (session.screen == SessionScreen.Safe && session.page == SafePage.Camera)
                            WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
                    ) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding)) {
                            AnimatedContent(
                                targetState = session.screen, label = "session screen",
                                transitionSpec = {
                                    // Security boundaries replace old controls immediately, without an outgoing PIN/private screen.
                                    if (initialState in setOf(
                                            SessionScreen.Pin,
                                            SessionScreen.Safe
                                        ) || targetState in setOf(
                                            SessionScreen.Pin,
                                            SessionScreen.Safe
                                        )
                                    )
                                        EnterTransition.None.togetherWith(ExitTransition.None)
                                    else (fadeIn(tween(180)) + scaleIn(
                                        tween(180),
                                        initialScale = .985f
                                    ))
                                        .togetherWith(fadeOut(tween(120)))
                                }) { targetScreen ->
                                val pane = when (targetScreen) {
                                    SessionScreen.Pin -> uiText(if (session.account.usesDeviceLock || !session.account.canUseAppPin) R.string.entry_device_lock else R.string.enter_pin)
                                    SessionScreen.Safe -> when (session.page) {
                                        SafePage.Home -> uiText(R.string.home)
                                        SafePage.Notes -> uiText(
                                            R.string.ui_notes
                                        )

                                        SafePage.Messages -> uiText(R.string.messages)
                                        SafePage.Profile -> uiText(R.string.ui_profile)
                                        SafePage.Contacts -> uiText(
                                            R.string.ui_contacts
                                        )

                                        SafePage.Settings -> uiText(R.string.settings)
                                        SafePage.Activity -> uiText(
                                            R.string.ui_activity
                                        )

                                        SafePage.Hotlines -> uiText(R.string.support_hotlines)
                                        SafePage.SafetyPlan -> uiText(
                                            R.string.support_safety_plan
                                        )
                                        SafePage.StaffHelp -> uiText(R.string.support_staff_help)

                                        SafePage.Documents -> uiText(R.string.support_documents)
                                        SafePage.Camera -> uiText(R.string.camera_title)
                                        SafePage.Location -> uiText(
                                            R.string.location_map
                                        )
                                    }

                                    SessionScreen.Loading -> uiText(R.string.ui_loading)
                                    SessionScreen.Welcome -> uiText(R.string.welcome)
                                    SessionScreen.NewCredentials -> uiText(R.string.create_account)
                                    SessionScreen.ReturningCredentials -> uiText(R.string.sign_in)
                                    SessionScreen.ProfileSetup, SessionScreen.OrganizationProfileSetup -> uiText(
                                        R.string.your_profile
                                    )

                                    SessionScreen.TriggerSetup -> uiText(R.string.ui_set_your_private_entry)
                                    SessionScreen.DevicePin -> uiText(R.string.secure_this_device)
                                    SessionScreen.Portal -> uiText(R.string.ui_organization_portal)
                                    SessionScreen.StorageError -> uiText(R.string.unable_to_open_local_storage)
                                    SessionScreen.Cover -> session.coverType.localizedTitle(uiText)
                                }
                                CompositionLocalProvider(LocalAccessibilityMessagesVisible provides (targetScreen == session.screen && !supportExpanded)) {
                                    // Entry setup owns its pane title because choosing New PIN changes its local screen.
                                    Box((if (targetScreen == SessionScreen.DevicePin) Modifier else Modifier.semantics {
                                        paneTitle = pane
                                    }).then(if (targetScreen != session.screen) Modifier.clearAndSetSemantics {} else Modifier)) {
                                        // Opens the cover, account entry, onboarding, or Safe App for the current session.
                                        when (targetScreen) {
                                            SessionScreen.Loading -> Box(
                                                Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) { CircularProgressIndicator() }

                                            SessionScreen.Welcome -> Welcome(session)
                                            SessionScreen.NewCredentials -> Credentials(
                                                session,
                                                true
                                            )

                                            SessionScreen.ReturningCredentials -> Credentials(
                                                session,
                                                false
                                            )

                                            SessionScreen.ProfileSetup -> ProfileSetup(session)
                                            SessionScreen.TriggerSetup -> TriggerSetup(session)
                                            SessionScreen.OrganizationProfileSetup -> OrganizationProfileSetup(
                                                session
                                            )

                                            SessionScreen.DevicePin -> DevicePin(session)
                                            SessionScreen.Pin -> PinPrompt(session)
                                            SessionScreen.Safe -> if (session.accountRole == AccountRole.SAFE_CONTACT)
                                                SafeContactArea(session) else SafeArea(
                                                session,
                                                exit
                                            )

                                            SessionScreen.Portal -> when (session.accountRole) {
                                                AccountRole.ADMIN -> AdminPortal(session)
                                                AccountRole.STAFF -> StaffPortal(session)
                                                else -> Unit
                                            }
                                            SessionScreen.StorageError -> FormColumn {
                                                Heading(
                                                    uiText(R.string.unable_to_open_local_storage),
                                                    uiText(R.string.access_remains_locked_your_existing_data_has_not_been_replaced)
                                                )
                                                Button(
                                                    onClick = session.account::load,
                                                    enabled = !session.busy
                                                ) { Text(stringResource(R.string.retry)) }
                                            }

                                            SessionScreen.Cover -> Unit
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (showPinAnimation) PinSafeOliveTransition(
                        backgroundColor = MaterialTheme.colorScheme.background,
                        modifier = Modifier.fillMaxSize().testTag("pin_safe_animation")
                            .pointerInput(Unit) {
                                awaitPointerEventScope {
                                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            },
                        onAnimationEnd = { pinAnimationRunning = false },
                    )
                    SafeSupportControls(
                        session,
                        exit,
                        showQuickExit,
                        showSupport,
                        supportExpanded
                    ) { supportExpanded = it }
                }
            }
            session.idleCountdownSeconds?.let { seconds ->
                if (session.screen == SessionScreen.Safe && session.accountRole == AccountRole.USER) AlertDialog(
                    onDismissRequest = session::noteInteraction,
                    title = { Text(stringResource(R.string.locking_soon)) },
                    text = {
                        Text(
                            if (session.usesCover) uiText(
                                R.string.ui_no_activity_returning_to_your_cover_in_value_seconds,
                                seconds
                            ) else uiText(
                                R.string.ui_no_activity_locking_olive_branch_in_value_seconds,
                                seconds
                            )
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = session::noteInteraction) {
                            Text(
                                stringResource(R.string.stay_here)
                            )
                        }
                    },
                    dismissButton = { TextButton(onClick = exit) { Text(stringResource(R.string.quick_exit)) } }
                )
            }
        }
        if (session.privacyBlackout) Box(
            Modifier.fillMaxSize().background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {}
                .testTag("privacy_blackout"))
        if (shouldShowCallOverlay(session.screen, session.accountRole)) {
            incomingCall?.takeIf { callState == VoipSessionState.Idle }?.let { invitation ->
                IncomingCallDialog(
                    invitation = invitation,
                    onAccept = {
                        withMicrophonePermission {
                            session.calls.accept(invitation, callAccountId, session.accountRole)
                        }
                    },
                    onDecline = {
                        session.calls.decline(invitation, callAccountId, session.accountRole)
                    },
                )
            }
            if (callState != VoipSessionState.Idle) ActiveCallDialog(
                state = callState,
                currentUid = callAccountId,
                onMute = session.calls::setMuted,
                onSpeaker = session.calls::setSpeakerEnabled,
                onFinish = { session.calls.finish(callAccountId) },
            )
        }
    }
}

internal enum class SessionScreen { Loading, Welcome, NewCredentials, ReturningCredentials, ProfileSetup, TriggerSetup, OrganizationProfileSetup, DevicePin, Cover, Pin, Safe, Portal, StorageError }
internal enum class SafePage { Home, Notes, Messages, Profile, Contacts, Settings, Activity, Hotlines, SafetyPlan, StaffHelp, Documents, Camera, Location }
internal enum class ActivityCategory { Settings, Triggers, PinAttempts, Checkouts, Privacy }
internal enum class CredentialFieldError { InvalidUsername, InvalidPassword, UsernameTaken, IncorrectPassword }

// Creates the messaging identity used after account sign-in.
internal fun messagingIdentityMarker(identity: AccountIdentity, publicKey: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
    val keyId = Base64.getUrlEncoder().withoutPadding().encodeToString(digest).take(16)
    return "secure-v2:${identity.uid}:${identity.username.lowercase()}:$keyId"
}

// Registers an encryption identity before opening secure messaging.
internal fun shouldRegisterMessagingIdentity(
    savedMarker: String?,
    identity: AccountIdentity,
    publicKey: ByteArray
) =
    savedMarker != messagingIdentityMarker(identity, publicKey)
