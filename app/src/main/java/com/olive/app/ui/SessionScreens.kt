package com.olive.app.ui
import com.olive.app.R
import androidx.compose.ui.res.stringResource

import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import com.olive.app.ui.LocalizedIcon as Icon
import com.olive.app.ui.covers.calculator.CalculatorCover
import com.olive.app.ui.covers.game.GameCover
import com.olive.app.ui.covers.news.NewsCover
import com.olive.app.ui.covers.olive.OliveCover
import com.olive.app.ui.covers.olive.OliveViewModel

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.data.*

// Keep the chosen cover loaded under the safe flow so Quick exit can reveal it fast.
// UI sizing/color: this root fills the window; the 12dp quick-exit inset and OliveGreen button styling live in its Scaffold.
internal fun shouldComposeCover(configured:Boolean,usesCover:Boolean)=configured && usesCover

@Composable fun OliveRoot(session: SessionViewModel) {
    val uiText=rememberResourceText()
    val cover: OliveViewModel = com.olive.app.ui.covers.olive.oliveViewModel()
    SideEffect { cover.persistCover={ cart,favorites,customizations -> session.saveFoodCover(cart,favorites,customizations) } }
    LaunchedEffect(session.coverAccountId,session.coverDataVersion) {
        cover.restoreCover(session.foodCart,session.foodFavorites,session.foodCustomizations)
    }
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    val isCover=session.screen == SessionScreen.Cover
    val context=androidx.compose.ui.platform.LocalContext.current
    val view=LocalView.current
    val accessibilityScope=rememberCoroutineScope()
    val accessibilityFocus=remember(accessibilityScope) { AccessibilityFocusCoordinator(accessibilityScope) }
    val accessibilityRoot=(view.parent as? ComposeView) ?: view
    val privacyHidden=session.privacyBlackout
    SideEffect {
        accessibilityRoot.importantForAccessibility=if(privacyHidden) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
    }
    var locationAsked by rememberSaveable { mutableStateOf(false) }
    var locationPermissionVersion by remember { mutableIntStateOf(0) }
    val locationPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationAsked=true;locationPermissionVersion++
    }
    LaunchedEffect(isCover,session.configured) {
        // Ask once per app run; every cover can use location and still works if permission is denied.
        if(isCover && session.configured && !locationAsked &&
            ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            locationAsked=true
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }
    val exit: () -> Unit = {
        session.quickExit()
        keyboard?.hide()
        focus.clearFocus(force=true)
    }
    CompositionLocalProvider(LocalAccessibilityFocusCoordinator provides accessibilityFocus) {
    val coverTitle=session.coverType.localizedTitle(uiText)
    val quickExitLabel=uiText(R.string.a11y_quick_exit)
    Box(Modifier.fillMaxSize().then(if(isCover) Modifier else Modifier.imePadding())) {
        // User accounts keep their cover loaded underneath; the other roles never create a cover screen.
        Box(if(isCover && !session.privacyBlackout) Modifier.fillMaxSize().testTag("cover_root").semantics { paneTitle=coverTitle } else Modifier.fillMaxSize().clearAndSetSemantics { }) {
            Box(Modifier.fillMaxSize()) {
            if(shouldComposeCover(session.configured,session.usesCover)) {
                when(session.coverType) {
                    CoverType.FOOD -> OliveCover(cover,interactive=isCover,onSearch={ value ->
                        if(session.interceptSearch(value)) { cover.search(""); focus.clearFocus(); keyboard?.hide() }
                        else cover.search(value)
                    },onAccountSetup=session::setup,configured=session.configured, session=session)
                    CoverType.NEWS -> NewsCover(session,locationPermissionVersion,isCover && !session.privacyBlackout)
                    CoverType.CALCULATOR -> CalculatorCover(session,isCover)
                    CoverType.GAME -> GameCover(isCover)
                }
            }
            }
            if(isCover && !session.privacyBlackout && session.accessibilityExitVersion>0) {
                key(session.accessibilityExitVersion) {
                    AccessibleMessage(uiText(R.string.a11y_returned_to_cover,coverTitle),
                        Modifier.align(Alignment.CenterStart).size(1.dp),showText=false)
                }
            }
        }
        if(!isCover) {
            LaunchedEffect(session.screen) {
                if(session.screen==SessionScreen.Pin) { keyboard?.hide();focus.clearFocus(force=true) }
            }
            BackHandler { keyboard?.hide(); focus.clearFocus(force=true); session.back() }
            Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
                Scaffold(
                    bottomBar={ if((session.screen==SessionScreen.Safe && session.accountRole==AccountRole.USER &&
                            !(session.page==SafePage.Messages && session.activeConversation!=null)) ||
                            (session.screen==SessionScreen.Pin && session.usesCover)) {
                        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),horizontalArrangement=Arrangement.End) {
                        ExtendedFloatingActionButton(onClick=exit,modifier=Modifier.testTag("quick_exit").semantics { contentDescription=quickExitLabel },
                            containerColor=OliveGreen,contentColor=MaterialTheme.colorScheme.onPrimary,
                            icon={Icon(Icons.Outlined.Close,null)},text={Text(stringResource(R.string.quick_exit),modifier=Modifier.clearAndSetSemantics {})})
                        }
                    } },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        AnimatedContent(targetState=session.screen,label="session screen",
                            transitionSpec={
                                // Security boundaries replace old controls immediately, without an outgoing PIN/private screen.
                                if(initialState in setOf(SessionScreen.Pin,SessionScreen.Safe) || targetState in setOf(SessionScreen.Pin,SessionScreen.Safe))
                                    EnterTransition.None.togetherWith(ExitTransition.None)
                                else (fadeIn(tween(180))+scaleIn(tween(180),initialScale=.985f))
                                    .togetherWith(fadeOut(tween(120)))
                            }) { targetScreen ->
                            val pane=when(targetScreen) {
                                SessionScreen.Pin -> uiText(if(session.usesDeviceLock || !session.canUseAppPin) R.string.entry_device_lock else R.string.enter_pin)
                                SessionScreen.Safe -> when(session.page) {
                                    SafePage.Home -> uiText(R.string.home);SafePage.Notes -> uiText(R.string.ui_notes);SafePage.Messages -> uiText(R.string.messages)
                                    SafePage.Profile -> uiText(R.string.ui_profile);SafePage.Contacts -> uiText(R.string.ui_contacts);SafePage.Settings -> uiText(R.string.settings);SafePage.Activity -> uiText(R.string.ui_activity)
                                }
                                SessionScreen.Loading -> uiText(R.string.ui_loading)
                                SessionScreen.Welcome -> uiText(R.string.welcome)
                                SessionScreen.NewCredentials -> uiText(R.string.create_account)
                                SessionScreen.ReturningCredentials -> uiText(R.string.sign_in)
                                SessionScreen.ProfileSetup,SessionScreen.OrganizationProfileSetup -> uiText(R.string.your_profile)
                                SessionScreen.TriggerSetup -> uiText(R.string.ui_set_your_private_entry)
                                SessionScreen.DevicePin -> uiText(R.string.secure_this_device)
                                SessionScreen.Portal -> uiText(R.string.ui_organization_portal)
                                SessionScreen.StorageError -> uiText(R.string.unable_to_open_local_storage)
                                SessionScreen.Cover -> session.coverType.localizedTitle(uiText)
                            }
                            CompositionLocalProvider(LocalAccessibilityMessagesVisible provides (targetScreen==session.screen)) {
                            // Entry setup owns its pane title because choosing New PIN changes its local screen.
                            Box((if(targetScreen==SessionScreen.DevicePin) Modifier else Modifier.semantics { paneTitle=pane }).then(if(targetScreen!=session.screen) Modifier.clearAndSetSemantics {} else Modifier)) {
                            when(targetScreen) {
                            SessionScreen.Loading -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { CircularProgressIndicator() }
                            SessionScreen.Welcome -> Welcome(session)
                            SessionScreen.NewCredentials -> Credentials(session,true)
                            SessionScreen.ReturningCredentials -> Credentials(session,false)
                            SessionScreen.ProfileSetup -> ProfileSetup(session)
                            SessionScreen.TriggerSetup -> TriggerSetup(session)
                            SessionScreen.OrganizationProfileSetup -> OrganizationProfileSetup(session)
                            SessionScreen.DevicePin -> DevicePin(session)
                            SessionScreen.Pin -> PinPrompt(session)
                            SessionScreen.Safe -> if(session.accountRole==AccountRole.SAFE_CONTACT)
                                SafeContactArea(session) else SafeArea(session,exit)
                            SessionScreen.Portal -> OrganizationPortal(session)
                            SessionScreen.StorageError -> FormColumn {
                                Heading(uiText(R.string.unable_to_open_local_storage), uiText(R.string.access_remains_locked_your_existing_data_has_not_been_replaced))
                                Button(onClick=session::load,enabled=!session.busy) { Text(stringResource(R.string.retry)) }
                            }
                            SessionScreen.Cover -> Unit
                        } } } }
                    }
                }
            }
            session.idleCountdownSeconds?.let { seconds ->
                if(session.screen==SessionScreen.Safe && session.accountRole==AccountRole.USER) AlertDialog(
                    onDismissRequest=session::noteInteraction,
                    title={Text(stringResource(R.string.locking_soon))},
                    text={Text(if(session.usesCover) uiText(R.string.ui_no_activity_returning_to_your_cover_in_value_seconds , seconds) else uiText(R.string.ui_no_activity_locking_olive_branch_in_value_seconds , seconds))},
                    confirmButton={TextButton(onClick=session::noteInteraction) { Text(stringResource(R.string.stay_here)) }},
                    dismissButton={TextButton(onClick=exit) { Text(stringResource(R.string.quick_exit)) }}
                )
            }
        }
        if(session.privacyBlackout) Box(Modifier.fillMaxSize().background(Color.Black)
            .clickable(interactionSource=remember { MutableInteractionSource() },indication=null) {}
            .testTag("privacy_blackout"))
    }
    }
}

// UI sizing: shared forms use 24dp side/top padding, 16dp row gaps, and 112dp bottom room for Quick exit.
@Composable internal fun FormColumn(content: @Composable ColumnScope.() -> Unit) {
    val scrollState=rememberScrollState()
    Column(Modifier.fillMaxSize().oliveScrollbar(scrollState).verticalScroll(scrollState).padding(horizontal=24.dp).padding(top=24.dp,bottom=112.dp),
        verticalArrangement=Arrangement.spacedBy(16.dp),content=content)
}
// Text/color: shared headings use a 30sp serif app label, Material headline text, and Muted helper copy.
@Composable internal fun Heading(title: String, subtitle: String, appName: String=stringResource(R.string.olive_branch)) {
    Text(appName,fontFamily=FontFamily.Serif,fontSize=30.sp,color=OliveGreen)
    Text(title,modifier=Modifier.semantics { heading() },style=MaterialTheme.typography.headlineMedium)
    Text(subtitle,color=Muted)
}
// UI color/spacing: local-mode notices use the theme primary container with 14dp inner padding.
@Composable internal fun ModeNotice(session: SessionViewModel) {
    if(session.localMode) Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.medium) {
        Text(stringResource(R.string.local_test_mode_accounts_stay_on_this_installation_firebase_is_not_connected_use_fictional_information_while_testing),Modifier.padding(14.dp),style=MaterialTheme.typography.bodySmall)
    }
}
// Status messages use native TalkBack focus; busy work shows a centered 26dp spinner.
@Composable internal fun Status(session: SessionViewModel,showMessage:Boolean=true) {
    if(showMessage) session.message?.let {
        AccessibleMessage(it,modifier=Modifier.testTag("session_message").semantics { liveRegion=LiveRegionMode.Polite },color=MaterialTheme.colorScheme.primary)
    }
    AnimatedVisibility(session.busy,enter=fadeIn(tween(120))+expandVertically(),exit=fadeOut(tween(100))+shrinkVertically()) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(26.dp),strokeWidth=3.dp)
        }
    }
}
// Full-width fields reveal themselves above the keyboard and expose validation errors to TalkBack.
// Callers supply label text; multiline fields start at three lines.
@Composable internal fun Field(label: String, value: String, tag: String, secret: Boolean=false, numeric: Boolean=false,
                             showSecretToggle: Boolean=false, multiline: Boolean=false, enabled: Boolean=true, error: String?=null,
                             onFocus: (() -> Unit)?=null, onChange: (String)->Unit) {
    val uiText=rememberResourceText()
    var secretVisible by rememberSaveable(tag) { mutableStateOf(false) }
    val secretDescription=when {
        numeric -> uiText(R.string.a11y_pin)
        tag.contains("trigger",ignoreCase=true) -> uiText(R.string.a11y_trigger_word)
        else -> uiText(R.string.a11y_password)
    }
    val accessibleError=error
    OutlinedTextField(value=value,onValueChange=onChange,label={Text(label)},
        modifier=Modifier.fillMaxWidth().testTag(tag).semantics { if(accessibleError!=null) error(accessibleError) }.onFocusChanged { if(it.isFocused) onFocus?.invoke() }.keepVisibleWhileTyping(),
        enabled=enabled,singleLine=!multiline,minLines=if(multiline) 3 else 1,isError=error!=null,
        supportingText=error?.let { message -> { AccessibleMessage(message,modifier=Modifier.testTag("${tag}_error"),color=MaterialTheme.colorScheme.error) } },
        visualTransformation=if(secret && !secretVisible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon=if(secret && showSecretToggle) {{
            IconButton(onClick={secretVisible=!secretVisible},modifier=Modifier.testTag("${tag}_visibility")) {
                Icon(if(secretVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription=if(secretVisible) uiText(R.string.ui_hide_value , secretDescription) else uiText(R.string.ui_show_value , secretDescription))
            }
        }} else null,
        keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,keyboardType=when { numeric -> KeyboardType.NumberPassword; secret -> KeyboardType.Password; else -> KeyboardType.Text }))
}
