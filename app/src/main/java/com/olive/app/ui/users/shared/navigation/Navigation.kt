// Contains the navigation code for this part of Olive.
package com.olive.app.ui.users.shared.navigation

import com.olive.app.ui.shared.*


import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.data.*
import com.olive.app.data.AccountIdentity
import com.olive.app.R
import com.olive.app.ui.covers.calculator.CalculatorCover
import com.olive.app.ui.covers.game.GameCover
import com.olive.app.ui.covers.news.NewsCover
import com.olive.app.ui.covers.olive.OliveCover
import com.olive.app.ui.covers.olive.OliveViewModel
import com.olive.app.ui.users.admin.portal.AdminPortal
import com.olive.app.ui.users.safecontact.SafeContactArea
import com.olive.app.ui.users.shared.auth.*
import com.olive.app.ui.users.shared.onboarding.ProfileSetup
import com.olive.app.ui.users.shared.organization.OrganizationProfileSetup
import com.olive.app.ui.users.staff.portal.StaffPortal
import com.olive.app.ui.users.user.account.TriggerSetup
import com.olive.app.ui.users.user.home.SafeArea
import com.olive.app.ui.users.user.home.SafeSupportControls
import com.olive.app.ui.shared.LocalizedIcon as Icon
import java.security.MessageDigest
import java.util.Base64

// Keep the chosen cover loaded under the safe flow so Quick exit can reveal it fast.
// UI sizing/color: this root fills the window; the 12dp quick-exit inset and OliveGreen button styling live in its Scaffold.
internal fun shouldComposeCover(usesCover: Boolean) = usesCover

@Composable
internal fun OliveRoot(session: SessionViewModel, locationPermissionVersion: Int = 0) {
    val uiText = rememberResourceText()
    val cover: OliveViewModel = com.olive.app.ui.covers.olive.oliveViewModel()
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
        session.quickExit()
        keyboard?.hide()
        focus.clearFocus(force = true)
    }
    val coverTitle = session.coverType.localizedTitle(uiText)
    val inActiveChat = session.page == SafePage.Messages && session.activeConversation != null
    val safeUser = session.screen == SessionScreen.Safe && session.accountRole == AccountRole.USER
    val showSupport = safeUser && !inActiveChat && session.page != SafePage.Camera
    val showQuickExit = (safeUser && !inActiveChat && session.page != SafePage.Camera) ||
            (session.screen == SessionScreen.Pin && session.usesCover)
    var supportExpanded by remember(
        session.screen,
        session.page,
        session.coverAccountId
    ) { mutableStateOf(false) }
    var observedScreen by remember { mutableStateOf(session.screen) }
    var pinAnimationRunning by remember { mutableStateOf(false) }
    val correctPinJustUnlocked =
        observedScreen == SessionScreen.Pin && session.screen == SessionScreen.Safe
    val showPinAnimation = correctPinJustUnlocked || pinAnimationRunning
    SideEffect {
        if (correctPinJustUnlocked) pinAnimationRunning = true
        observedScreen = session.screen
        if (session.screen != SessionScreen.Safe) pinAnimationRunning = false
    }
    Box(Modifier.fillMaxSize().then(if (isCover) Modifier else Modifier.imePadding())) {
        // User accounts keep their cover loaded underneath; the other roles never create a cover screen.
        Box(
            if (isCover && !session.privacyBlackout) Modifier.fillMaxSize().testTag("cover_root")
                .semantics { paneTitle = coverTitle } else Modifier.fillMaxSize()
                .clearAndSetSemantics { }) {
            Box(Modifier.fillMaxSize()) {
                if (shouldComposeCover(session.usesCover)) {
                    when (session.coverType) {
                        CoverType.FOOD -> OliveCover(
                            cover,
                            interactive = isCover,
                            onSearch = { value ->
                                if (session.interceptSearch(value)) {
                                    cover.search("")
                                    focus.clearFocus()
                                    keyboard?.hide()
                                } else cover.search(value)
                            },
                            onAccountSetup = session::setup,
                            configured = session.configured,
                            session = session
                        )

                        CoverType.NEWS -> NewsCover(
                            session,
                            locationPermissionVersion,
                            isCover && !session.privacyBlackout
                        )

                        CoverType.CALCULATOR -> CalculatorCover(session, isCover)
                        CoverType.GAME -> GameCover(
                            sessionViewModel = session,
                            interactive = isCover,
                            onEntryAttempt = session::interceptSearch,
                        )
                    }
                }
            }
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
            LaunchedEffect(session.screen) {
                if (session.screen == SessionScreen.Pin) {
                    keyboard?.hide()
                    focus.clearFocus(force = true)
                }
            }
            BackHandler {
                keyboard?.hide()
                focus.clearFocus(force = true)
                session.back()
            }
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize()) {
                    Scaffold(
                        modifier = if (supportExpanded || showPinAnimation) Modifier.clearAndSetSemantics {} else Modifier,
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
                                    SessionScreen.Pin -> uiText(if (session.usesDeviceLock || !session.canUseAppPin) R.string.entry_device_lock else R.string.enter_pin)
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
                                                    onClick = session::load,
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
                            .clearAndSetSemantics {}
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
    }
}

// UI sizing: shared forms use 24dp side/top padding, 16dp row gaps, and 112dp bottom room for Quick exit.
@Composable
internal fun FormColumn(content: @Composable ColumnScope.() -> Unit) {
    val scrollState = rememberScrollState()
    Column(
        Modifier.fillMaxSize().oliveScrollbar(scrollState).verticalScroll(scrollState)
            .padding(horizontal = 24.dp).padding(top = 24.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), content = content
    )
}

// Text/color: shared headings use a 30sp serif app label, Material headline text, and Muted helper copy.
@Composable
internal fun Heading(
    title: String,
    subtitle: String,
    appName: String = stringResource(R.string.olive_branch)
) {
    Text(appName, fontFamily = FontFamily.Serif, fontSize = 30.sp, color = OliveGreen)
    Text(
        title,
        modifier = Modifier.semantics { heading() },
        style = MaterialTheme.typography.headlineMedium
    )
    Text(subtitle, color = Muted)
}

// UI color/spacing: local-mode notices use the theme primary container with 14dp inner padding.
@Composable
internal fun ModeNotice(session: SessionViewModel) {
    if (session.localMode) Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium
    ) {
        Text(
            stringResource(R.string.local_test_mode_accounts_stay_on_this_installation_firebase_is_not_connected_use_fictional_information_while_testing),
            Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// Status messages use native TalkBack focus; busy work shows a centered 26dp spinner.
@Composable
internal fun Status(session: SessionViewModel, showMessage: Boolean = true) {
    if (showMessage) session.message?.let {
        AccessibleMessage(
            it,
            modifier = Modifier.testTag("session_message")
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.primary
        )
    }
    AnimatedVisibility(
        session.busy,
        enter = fadeIn(tween(120)) + expandVertically(),
        exit = fadeOut(tween(100)) + shrinkVertically()
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
        }
    }
}

// Full-width fields reveal themselves above the keyboard and expose validation errors to TalkBack.
// Callers supply label text; multiline fields start at three lines.
@Composable
internal fun Field(
    label: String,
    value: String,
    tag: String,
    secret: Boolean = false,
    numeric: Boolean = false,
    showSecretToggle: Boolean = false,
    multiline: Boolean = false,
    enabled: Boolean = true,
    error: String? = null,
    onFocus: (() -> Unit)? = null,
    onChange: (String) -> Unit
) {
    val uiText = rememberResourceText()
    var secretVisible by rememberSaveable(tag) { mutableStateOf(false) }
    val secretDescription = when {
        numeric -> uiText(R.string.a11y_pin)
        tag.contains("trigger", ignoreCase = true) -> uiText(R.string.a11y_trigger_word)
        else -> uiText(R.string.a11y_password)
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics { error?.let { error(it) } }
            .onFocusChanged { if (it.isFocused) onFocus?.invoke() }.keepVisibleWhileTyping(),
        enabled = enabled,
        singleLine = !multiline,
        minLines = if (multiline) 3 else 1,
        isError = error != null,
        supportingText = error?.let { message ->
            {
                AccessibleMessage(
                    message,
                    modifier = Modifier.testTag("${tag}_error"),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        visualTransformation = if (secret && !secretVisible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret && showSecretToggle) {
            {
                IconButton(
                    onClick = { secretVisible = !secretVisible },
                    modifier = Modifier.testTag("${tag}_visibility")
                ) {
                    Icon(
                        if (secretVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (secretVisible) uiText(
                            R.string.ui_hide_value,
                            secretDescription
                        ) else uiText(R.string.ui_show_value, secretDescription)
                    )
                }
            }
        } else null,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false, keyboardType = when {
                numeric -> KeyboardType.NumberPassword
                secret -> KeyboardType.Password
                else -> KeyboardType.Text
            }
        )
    )
}

// Groups the data and actions for session screen.
internal enum class SessionScreen { Loading, Welcome, NewCredentials, ReturningCredentials, ProfileSetup, TriggerSetup, OrganizationProfileSetup, DevicePin, Cover, Pin, Safe, Portal, StorageError }
// Groups the data and actions for safe page.
internal enum class SafePage { Home, Notes, Messages, Profile, Contacts, Settings, Activity, Hotlines, SafetyPlan, Documents, Camera, Location }
// Groups the data and actions for activity category.
internal enum class ActivityCategory { Settings, Triggers, PinAttempts, Checkouts, Privacy }
// Groups the data and actions for credential field error.
internal enum class CredentialFieldError { InvalidUsername, InvalidPassword, UsernameTaken, IncorrectPassword }

internal fun messagingIdentityMarker(identity: AccountIdentity, publicKey: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
    val keyId = Base64.getUrlEncoder().withoutPadding().encodeToString(digest).take(16)
    return "secure-v2:${identity.uid}:${identity.username.lowercase()}:$keyId"
}

internal fun shouldRegisterMessagingIdentity(
    savedMarker: String?,
    identity: AccountIdentity,
    publicKey: ByteArray
) =
    savedMarker != messagingIdentityMarker(identity, publicKey)
