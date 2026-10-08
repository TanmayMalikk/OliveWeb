package com.olive.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.KeyboardArrowDown
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.olive.app.data.*
import com.olive.app.security.InputPolicy
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

// Keep the chosen cover loaded under the safe flow so Quick exit can reveal it fast.
// UI sizing/color: this root fills the window; the 12dp quick-exit inset and OliveGreen button styling live in its Scaffold.
internal fun shouldComposeCover(configured:Boolean,usesCover:Boolean)=configured && usesCover

@Composable fun OliveRoot(session: SessionViewModel) {
    val cover: OliveViewModel = viewModel()
    SideEffect { cover.persistCover={ cart,favorites,customizations -> session.saveFoodCover(cart,favorites,customizations) } }
    LaunchedEffect(session.coverAccountId,session.coverDataVersion) {
        cover.restoreCover(session.foodCart,session.foodFavorites,session.foodCustomizations)
    }
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    val isCover=session.screen == SessionScreen.Cover
    val context=androidx.compose.ui.platform.LocalContext.current
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
    CompositionLocalProvider(LocalOliveLanguage provides session.language) {
    Box(Modifier.fillMaxSize()) {
        // User accounts keep their cover loaded underneath; the other roles never create a cover screen.
        Box(if(isCover) Modifier.fillMaxSize().testTag("cover_root") else Modifier.fillMaxSize().clearAndSetSemantics { }) {
            if(shouldComposeCover(session.configured,session.usesCover)) {
                when(session.coverType) {
                    CoverType.FOOD -> OliveCover(cover,interactive=isCover,onSearch={ value ->
                        if(session.interceptSearch(value)) { cover.search(""); focus.clearFocus(); keyboard?.hide() }
                        else cover.search(value)
                    },onAccountSetup=session::setup,configured=session.configured, session=session)
                    CoverType.NEWS -> NewsCover(session,locationPermissionVersion,isCover)
                    CoverType.CALCULATOR -> CalculatorCover(session,isCover)
                    CoverType.GAME -> GameCover(isCover)
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
                        ExtendedFloatingActionButton(onClick=exit,modifier=Modifier.testTag("quick_exit"),
                            containerColor=OliveGreen,contentColor=MaterialTheme.colorScheme.onPrimary,
                            icon={Icon(Icons.Outlined.Close,null)},text={LocalizedText("Quick exit")})
                        }
                    } },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        AnimatedContent(targetState=session.screen,label="session screen",
                            transitionSpec={
                                (fadeIn(tween(180))+scaleIn(tween(180),initialScale=.985f))
                                    .togetherWith(fadeOut(tween(120)))
                            }) { targetScreen -> when(targetScreen) {
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
                                Heading("Unable to open local storage", "Access remains locked. Your existing data has not been replaced.")
                                Button(onClick=session::load,enabled=!session.busy) { LocalizedText("Retry") }
                            }
                            SessionScreen.Cover -> Unit
                        } }
                    }
                }
            }
            session.idleCountdownSeconds?.let { seconds ->
                if(session.screen==SessionScreen.Safe && session.accountRole==AccountRole.USER) AlertDialog(
                    onDismissRequest=session::noteInteraction,
                    title={LocalizedText("Locking soon")},
                    text={LocalizedText(if(session.usesCover) "No activity. Returning to your cover in $seconds seconds." else "No activity. Locking Olive Branch in $seconds seconds.")},
                    confirmButton={TextButton(onClick=session::noteInteraction) { LocalizedText("Stay here") }},
                    dismissButton={TextButton(onClick=exit) { LocalizedText("Quick exit") }}
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
@Composable internal fun Heading(title: String, subtitle: String, appName: String="Olive Branch") {
    LocalizedText(appName,fontFamily=FontFamily.Serif,fontSize=30.sp,color=OliveGreen)
    LocalizedText(title,style=MaterialTheme.typography.headlineMedium)
    LocalizedText(subtitle,color=Muted)
}
// UI color/spacing: local-mode notices use the theme primary container with 14dp inner padding.
@Composable internal fun ModeNotice(session: SessionViewModel) {
    if(session.localMode) Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.medium) {
        LocalizedText("Local test mode: accounts stay on this installation. Firebase is not connected. Use fictional information while testing.",Modifier.padding(14.dp),style=MaterialTheme.typography.bodySmall)
    }
}
// UI color/size: status copy uses the theme primary color and progress bars stretch to the available width.
@Composable internal fun Status(session: SessionViewModel,showMessage:Boolean=true) {
    if(showMessage) AnimatedContent(targetState=session.message,label="status message",
        transitionSpec={fadeIn(tween(160)).togetherWith(fadeOut(tween(100)))}) { status ->
        status?.let { LocalizedText(it,modifier=Modifier.testTag("session_message"),color=MaterialTheme.colorScheme.primary) }
    }
    AnimatedVisibility(session.busy,enter=fadeIn(tween(120))+expandVertically(),exit=fadeOut(tween(100))+shrinkVertically()) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(26.dp),strokeWidth=3.dp)
        }
    }
}
// UI sizing/text: shared fields fill their column; multiline fields start at three lines and all labels use localized Material text.
@Composable internal fun Field(label: String, value: String, tag: String, secret: Boolean=false, numeric: Boolean=false,
                             showSecretToggle: Boolean=false, multiline: Boolean=false, enabled: Boolean=true, error: String?=null,
                             onFocus: (() -> Unit)?=null, onChange: (String)->Unit) {
    var secretVisible by rememberSaveable(tag) { mutableStateOf(false) }
    val secretDescription=when {
        label.contains("pin",ignoreCase=true) -> "PIN"
        label.contains("trigger",ignoreCase=true) -> "trigger word"
        else -> "password"
    }
    OutlinedTextField(value=value,onValueChange=onChange,label={LocalizedText(label)},
        modifier=Modifier.fillMaxWidth().testTag(tag).onFocusChanged { if(it.isFocused) onFocus?.invoke() },
        enabled=enabled,singleLine=!multiline,minLines=if(multiline) 3 else 1,isError=error!=null,
        supportingText=error?.let { message -> { LocalizedText(message,color=MaterialTheme.colorScheme.error) } },
        visualTransformation=if(secret && !secretVisible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon=if(secret && showSecretToggle) {{
            IconButton(onClick={secretVisible=!secretVisible},modifier=Modifier.testTag("${tag}_visibility")) {
                Icon(if(secretVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription=if(secretVisible) "Hide $secretDescription" else "Show $secretDescription")
            }
        }} else null,
        keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,keyboardType=when { numeric -> KeyboardType.NumberPassword; secret -> KeyboardType.Password; else -> KeyboardType.Text }))
}
