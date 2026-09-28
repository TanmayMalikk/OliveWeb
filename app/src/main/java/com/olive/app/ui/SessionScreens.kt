package com.olive.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
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
        // Keep the actual cover composition (including scroll position) alive beneath the opaque overlay.
        Box(if(isCover) Modifier.fillMaxSize().testTag("cover_root") else Modifier.fillMaxSize().clearAndSetSemantics { }) {
            when(session.coverType) {
                CoverType.FOOD, CoverType.GAME -> OliveApp(cover,interactive=isCover,onSearch={ value ->
                    if(session.interceptSearch(value)) { cover.search(""); focus.clearFocus(); keyboard?.hide() }
                    else cover.search(value)
                },onAccountSetup=session::setup,configured=session.configured, session=session)
                CoverType.NEWS -> NewsCover(session,locationPermissionVersion,isCover)
                CoverType.CALCULATOR -> CalculatorCover(session,isCover)
            }
        }
        if(!isCover) {
            LaunchedEffect(session.screen) {
                if(session.screen==SessionScreen.Pin) { keyboard?.hide();focus.clearFocus(force=true) }
            }
            BackHandler { keyboard?.hide(); focus.clearFocus(force=true); session.back() }
            Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
                Scaffold(
                    modifier=Modifier.imePadding(),
                    bottomBar={
                        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),horizontalArrangement=Arrangement.End) {
                        ExtendedFloatingActionButton(onClick=exit,modifier=Modifier.testTag("quick_exit"),
                            containerColor=OliveGreen,contentColor=MaterialTheme.colorScheme.onPrimary,
                            icon={Icon(Icons.Outlined.Close,null)},text={LocalizedText("Quick exit")})
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when(session.screen) {
                            SessionScreen.Loading -> Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { CircularProgressIndicator() }
                            SessionScreen.Welcome -> Welcome(session)
                            SessionScreen.NewCredentials -> Credentials(session,true)
                            SessionScreen.ReturningCredentials -> Credentials(session,false)
                            SessionScreen.ProfileSetup -> ProfileSetup(session)
                            SessionScreen.DevicePin -> DevicePin(session)
                            SessionScreen.Pin -> PinPrompt(session)
                            SessionScreen.Safe -> SafeArea(session)
                            SessionScreen.StorageError -> FormColumn {
                                Heading("Unable to open local storage", "Access remains locked. Your existing data has not been replaced.")
                                Button(onClick=session::load,enabled=!session.busy) { LocalizedText("Retry") }
                            }
                            SessionScreen.Cover -> Unit
                        }
                    }
                }
            }
            session.idleCountdownSeconds?.let { seconds ->
                if(session.screen==SessionScreen.Safe) AlertDialog(
                    onDismissRequest=session::noteInteraction,
                    title={LocalizedText("Locking soon")},
                    text={LocalizedText("No activity. Returning to your cover in $seconds seconds.")},
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

@Composable private fun FormColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(top=24.dp,bottom=112.dp),
        verticalArrangement=Arrangement.spacedBy(16.dp),content=content)
}
@Composable private fun Heading(title: String, subtitle: String) {
    LocalizedText("Olive Branch",fontFamily=FontFamily.Serif,fontSize=30.sp,color=OliveGreen)
    LocalizedText(title,style=MaterialTheme.typography.headlineMedium)
    LocalizedText(subtitle,color=Muted)
}
@Composable private fun ModeNotice(session: SessionViewModel) {
    if(session.localMode) Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.medium) {
        LocalizedText("Local test mode: accounts stay on this installation. Firebase is not connected. Use fictional information while testing.",Modifier.padding(14.dp),style=MaterialTheme.typography.bodySmall)
    }
}
@Composable private fun Status(session: SessionViewModel) {
    session.message?.let { LocalizedText(it,modifier=Modifier.testTag("session_message"),color=MaterialTheme.colorScheme.primary) }
    if(session.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
}
@Composable private fun Field(label: String, value: String, tag: String, secret: Boolean=false, numeric: Boolean=false,
                             multiline: Boolean=false, enabled: Boolean=true, onFocus: (() -> Unit)?=null, onChange: (String)->Unit) {
    OutlinedTextField(value=value,onValueChange=onChange,label={LocalizedText(label)},
        modifier=Modifier.fillMaxWidth().testTag(tag).onFocusChanged { if(it.isFocused) onFocus?.invoke() },
        enabled=enabled,singleLine=!multiline,minLines=if(multiline) 3 else 1,
        visualTransformation=if(secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions=KeyboardOptions(autoCorrectEnabled=false,keyboardType=when { numeric -> KeyboardType.NumberPassword; secret -> KeyboardType.Password; else -> KeyboardType.Text }))
}

// First launch starts here: pick a brand-new account or bring an old one onto this phone.
@Composable private fun Welcome(session: SessionViewModel) {
    FormColumn {
        Heading("Welcome to Olive", "Choose how you’d like to get started.")
        ModeNotice(session)
        Button(onClick=session::chooseNew,enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("new_user")) { LocalizedText("I’m a new user") }
        OutlinedButton(onClick=session::chooseReturning,enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("returning_user")) { LocalizedText("I’m a returning user") }
        LocalizedText("You can use Quick exit at any time to open the food menu. If setup is incomplete, return through Account.",color=Muted)
    }
}

// Username and password live here, no real email or phone number asked for.
@Composable private fun Credentials(session: SessionViewModel, create: Boolean) {
    // Deliberately not rememberSaveable: passwords never enter Android saved-state bundles.
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    FormColumn {
        Heading(if(create) "Create your account" else "Welcome back",if(create) "Use these credentials when setting up another device." else "Sign in with your existing username and password. Next, create a PIN for this device.")
        ModeNotice(session)
        Field("Username",username,"username",enabled=!session.busy) { username=it.take(if(create) 24 else 30) }
        Field("Password",password,"password",secret=true,enabled=!session.busy) { password=it.take(if(create) 24 else 128) }
        if(create) {
            LocalizedText("Passwords use 8–24 characters and are case-sensitive. Usernames are unique, ignore letter case, and use letters, numbers and underscores. No email or phone number needed.",style=MaterialTheme.typography.bodySmall,color=Muted)
            Field("Confirm password",confirm,"confirm_password",secret=true,enabled=!session.busy) { confirm=it.take(24) }
            LocalizedText("Keep your credentials somewhere safe. You can change your password later in safe Settings.",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
        Status(session)
        Button(onClick={
            if(create && password != confirm) session.error("The passwords do not match.")
            else session.authenticate(username,password,create)
        },enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("credentials_submit")) { LocalizedText(if(create) "Create account" else "Sign in") }
    }
}

// Pick a birthday in three taps; the saved value still uses YYYY-MM-DD.
@Composable private fun BirthdayPicker(value: String, tag: String, enabled: Boolean, onChange: (String)->Unit) {
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    val initial=remember { runCatching { LocalDate.parse(value) }.getOrNull() }
    var year by remember { mutableStateOf(initial?.year) }
    var month by remember { mutableStateOf(initial?.monthValue) }
    var day by remember { mutableStateOf(initial?.dayOfMonth) }
    val locale=if(LocalOliveLanguage.current=="French") java.util.Locale.CANADA_FRENCH else java.util.Locale.CANADA
    fun setDate(y: Int?,m: Int?,d: Int?) {
        if(y!=null && m!=null && d!=null) onChange("%04d-%02d-%02d".format(java.util.Locale.ROOT,y,m,d))
    }
    LocalizedText("Date of birth (required)",style=MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth().testTag(tag),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        @Composable fun Choice(label: String, selected: String, options: List<Pair<String,String>>, choiceTag: String, choose: (String)->Unit) {
            var open by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick={keyboard?.hide();focus.clearFocus(force=true);open=true},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag(choiceTag),contentPadding=PaddingValues(horizontal=8.dp)) {
                    LocalizedText(selected.ifBlank { label },maxLines=1)
                }
                DropdownMenu(expanded=open,onDismissRequest={open=false}) {
                    options.forEach { (id,text) ->
                        DropdownMenuItem(text={LocalizedText(text)},onClick={choose(id);open=false},modifier=Modifier.testTag("${choiceTag}_$id"))
                    }
                }
            }
        }
        Choice("Year",year?.toString().orEmpty(),(1950..LocalDate.now().year).reversed().map { it.toString() to it.toString() },"${tag}_year") {
            year=it.toInt();setDate(year,month,day)
        }
        Choice("Month",month?.let { Month.of(it).getDisplayName(java.time.format.TextStyle.FULL,locale) }.orEmpty(),
            (1..12).map { it.toString() to Month.of(it).getDisplayName(java.time.format.TextStyle.FULL,locale) },"${tag}_month") {
            month=it.toInt();setDate(year,month,day)
        }
        Choice("Day",day?.toString().orEmpty(),(1..31).map { it.toString() to it.toString() },"${tag}_day") {
            day=it.toInt();setDate(year,month,day)
        }
    }
}

// Add or rearrange onboarding fields here, then update UserProfile and saveSetupProfile too.
@Composable private fun ProfileSetup(session: SessionViewModel) {
    var fullName by remember { mutableStateOf("") }
    var affected by remember { mutableStateOf("") }
    var abuserName by remember { mutableStateOf("") }
    var dob by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("English") }
    var situation by remember { mutableStateOf("") }
    var address by remember { mutableStateOf(DeliveryAddress()) }
    var trigger by remember { mutableStateOf("") }
    var contactName by remember { mutableStateOf("") }
    var contactDetails by remember { mutableStateOf("") }
    var coverType by remember { mutableStateOf(CoverType.FOOD) }
    FormColumn {
        Heading("Your profile", "Full name, date of birth, language, city and a trigger word are required.")
        Field("Full name (required)",fullName,"full_name",enabled=!session.busy) { fullName=it.take(120) }
        Field("Names of people experiencing abuse (optional)",affected,"affected_names",enabled=!session.busy) { affected=it.take(500) }
        Field("Abuser's name (optional)",abuserName,"abuser_name",enabled=!session.busy) { abuserName=it.take(120) }
        BirthdayPicker(dob,"dob",!session.busy) { dob=it }
        LanguagePicker(language,!session.busy) { language=it }
        Field("Brief description of your situation (optional, recommended)",situation,"situation",multiline=true,enabled=!session.busy) { situation=it.take(2000) }
        HorizontalDivider()
        LocalizedText("Location",style=MaterialTheme.typography.titleLarge)
        LocalizedText("City is required. The rest of your delivery address is optional and can be added later.",style=MaterialTheme.typography.bodySmall,color=Muted)
        AddressFields(address,cityOnlyRequired=true) { address=it }
        HorizontalDivider()
        LocalizedText("Safe entry",style=MaterialTheme.typography.titleLarge)
        Field("Trigger word or phrase",trigger,"trigger",secret=true,enabled=!session.busy) { trigger=it.take(64) }
        LocalizedText("Olive and Daily Digest ignore spaces and letter case in your trigger. Other searches keep their spaces. Simple Calculator uses your PIN and two equals taps.",style=MaterialTheme.typography.bodySmall,color=Muted)
        LocalizedText("Safe contact · optional",style=MaterialTheme.typography.titleMedium)
        Field("Contact name",contactName,"setup_contact_name",enabled=!session.busy) { contactName=it.take(120) }
        Field("Contact phone or email",contactDetails,"setup_contact_details",enabled=!session.busy) { contactDetails=it.take(200) }
        LocalizedText("Contacts can be added and edited later. Olive never contacts anyone automatically.",style=MaterialTheme.typography.bodySmall,color=Muted)
        // Cover choice is the last onboarding option; the game explains its current status.
        CoverPicker(coverType,!session.busy) { chosen ->
            if(chosen == CoverType.GAME) session.error("In development") else coverType=chosen
        }
        Status(session)
        Button(onClick={
            when {
                fullName.isBlank() -> session.error("Enter your full name.")
                !InputPolicy.validDob(dob) -> session.error("Use a valid date of birth in YYYY-MM-DD format.")
                language.isBlank() -> session.error("Enter your preferred language.")
                !address.locationValid -> session.error("Enter your city.")
                !InputPolicy.validTrigger(trigger) -> session.error("Choose a trigger word or phrase of 3–64 characters.")
                contactName.isBlank() != contactDetails.isBlank() -> session.error("Enter both contact fields or leave both empty.")
                else -> session.saveSetupProfile(UserProfile(
                    username=session.setupUsername,fullName=fullName.trim(),affectedNames=affected.trim(),abuserName=abuserName.trim(),dob=dob.trim(),language=language.trim(),
                    situation=situation.trim(),region=address.generalLocation,triggerHash=InputPolicy.triggerHash(trigger),
                    contacts=if(contactName.isBlank()) emptyList() else listOf(SafeContact(UUID.randomUUID().toString(),contactName.trim(),contactDetails.trim())),address=address,coverType=coverType))
            }
        },enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("profile_submit")) { LocalizedText("Save profile and continue") }
    }
}

// Every device gets its own short PIN, because the account password has a different job.
@Composable private fun DevicePin(session: SessionViewModel) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    FormColumn {
        Heading("Secure this device", "Create a 4–6 digit PIN. Your account password signs you in on another device; this PIN opens the safe area here.")
        Field("4–6 digit PIN",pin,"new_pin",secret=true,numeric=true,enabled=!session.busy) { pin=it.filter(Char::isDigit).take(6) }
        Field("Confirm PIN",confirm,"confirm_pin",secret=true,numeric=true,enabled=!session.busy) { confirm=it.filter(Char::isDigit).take(6) }
        LocalizedText("Five incorrect attempts return you to the cover app. You can try again immediately. Failed attempts are recorded on this device.",color=Muted)
        Status(session)
        Button(onClick={session.createDevicePin(pin,confirm)},enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("pin_setup_submit")) { LocalizedText("Finish setup") }
    }
}

// Trigger found—this is the small locked door between any cover and the safe area.
@Composable private fun PinPrompt(session: SessionViewModel) {
    var pin by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal=24.dp).padding(top=48.dp,bottom=112.dp),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(18.dp)) {
        LocalizedText("Olive Branch",fontFamily=FontFamily.Serif,fontSize=26.sp,color=OliveGreen)
        Spacer(Modifier.height(28.dp))
        LocalizedText("Enter PIN",style=MaterialTheme.typography.headlineMedium)
        LocalizedText("Enter your PIN.",color=Muted)
        Row(Modifier.height(14.dp).testTag("unlock_pin"),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            repeat(pin.length) {
                Box(Modifier.size(14.dp).background(OliveGreen,CircleShape))
            }
        }
        Status(session)
        Spacer(Modifier.weight(1f))
        // This phone-style pad keeps PIN digits out of text fields and saved UI state.
        listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf("⌫","0","↪")).forEach { row ->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                row.forEach { key ->
                    val tag=when(key) { "⌫" -> "pin_backspace"; "↪" -> "unlock_submit"; else -> "pin_digit_$key" }
                    FilledTonalButton(onClick={
                        when(key) {
                            "⌫" -> pin=pin.dropLast(1)
                            "↪" -> { val attempt=pin;pin="";session.submitPin(attempt) }
                            else -> if(pin.length<6) pin+=key
                        }
                    },enabled=!session.busy && (key!="↪" || pin.length in 4..6),
                        modifier=Modifier.size(72.dp).testTag(tag),shape=CircleShape,
                        contentPadding=PaddingValues(0.dp)) {
                        LocalizedText(key,fontSize=if(key=="⌫" || key=="↪") 26.sp else 30.sp)
                    }
                }
            }
        }
    }
}

// The private side switches between home, profile, contacts, attempt history, and settings.
@Composable private fun SafeArea(session: SessionViewModel) {
    val profile=session.profile ?: return
    when(session.page) {
        SafePage.Home -> FormColumn {
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(end=56.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    LocalizedText("Olive Branch",fontFamily=FontFamily.Serif,fontSize=30.sp,color=OliveGreen)
                    LocalizedText("Your safe space",style=MaterialTheme.typography.headlineMedium)
                    LocalizedText("Use Quick exit from any page to lock this area and return to your cover app.",color=Muted)
                }
                IconButton(onClick={session.navigate(SafePage.Settings)},modifier=Modifier.align(Alignment.TopEnd).testTag("safe_settings")) {
                    Icon(Icons.Outlined.Settings,"Settings")
                }
            }
            ModeNotice(session)
            Button(onClick={session.navigate(SafePage.Profile)},modifier=Modifier.fillMaxWidth().testTag("safe_profile")) { LocalizedText("My profile") }
            Button(onClick={session.navigate(SafePage.Activity)},modifier=Modifier.fillMaxWidth().testTag("safe_activity")) { LocalizedText("Account activity") }
            LocalizedText("Private features",style=MaterialTheme.typography.titleLarge)
            // New safe tools live in this two-wide grid; keep every tile large enough for quick tapping.
            listOf(1,3).forEach { first ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    listOf(first,first+1).forEach { number ->
                        if(number==1) Button(onClick={session.navigate(SafePage.Notes)},
                            colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF718669),contentColor=Color.White),
                            modifier=Modifier.weight(1f).heightIn(min=112.dp).testTag("safe_notes")) {
                            LocalizedText("Notes & incidents")
                        } else OutlinedButton(onClick={},enabled=false,
                            colors=ButtonDefaults.outlinedButtonColors(disabledContainerColor=Color(0xFFE7EDE2),disabledContentColor=Muted),
                            modifier=Modifier.weight(1f).heightIn(min=112.dp).testTag("future_feature_$number")) {
                            LocalizedText("Future feature $number")
                        }
                    }
                }
            }
            if(session.pendingSync) {
                LocalizedText("Profile changes are saved on this device and awaiting cloud sync.")
                OutlinedButton(onClick=session::sync,enabled=!session.busy) { LocalizedText("Sync changes") }
            }
            LocalizedText("Leaving the app locks this area. A cover app does not make Olive Branch impossible to discover.",style=MaterialTheme.typography.bodySmall,color=Muted)
            Status(session)
            TextButton(onClick=session::signOut,enabled=!session.busy,modifier=Modifier.testTag("sign_out")) { LocalizedText("Sign out on this device") }
        }
        SafePage.Profile -> FormColumn {
            SafeBack(session)
            Heading("My profile", "Your saved account information.")
            listOf("Username" to profile.username,"Full name" to profile.fullName,"People experiencing abuse" to profile.affectedNames,"Abuser's name" to profile.abuserName,
                "Date of birth" to profile.dob,"Preferred language" to profile.language,"Situation" to profile.situation,"Region / city / state" to profile.region,"Cover app" to profile.coverType.title,"Delivery address" to listOf(profile.address.line1,profile.address.line2,profile.address.city,profile.address.province,profile.address.postalCode,profile.address.country).filter { it.isNotBlank() }.joinToString(", "))
                .forEach { (label,value) ->
                    LocalizedText(label,style=MaterialTheme.typography.labelLarge,color=OliveGreen)
                    if(value.isBlank()) LocalizedText("Not provided")
                    else if(label=="Preferred language") LocalizedText(value)
                    else Text(value)
                }
        }
        SafePage.Notes -> PrivateNotes(session)
        SafePage.Settings -> SettingsEditor(session,profile)
        SafePage.Contacts -> ContactEditor(session,profile)
        SafePage.Activity -> ActivityHistory(session)
    }
}

// Notes stay behind the PIN, with a searchable Keep-style board and a full-screen editor.
@Composable private fun PrivateNotes(session: SessionViewModel) {
    var editorOpen by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var kind by remember { mutableStateOf<PrivateEntryKind?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var logEntryId by remember { mutableStateOf<String?>(null) }
    var trashOpen by remember { mutableStateOf(false) }
    val language=LocalOliveLanguage.current
    val formatter=remember(language) { DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm z",
        if(language=="French") java.util.Locale.CANADA_FRENCH else java.util.Locale.CANADA).withZone(ZoneId.systemDefault()) }
    fun openEditor(entry: PrivateEntry?) {
        editingId=entry?.id;kind=entry?.kind;title=entry?.title.orEmpty();body=entry?.body.orEmpty();editorOpen=true
    }
    BackHandler(editorOpen || logEntryId!=null || trashOpen) {
        when {
            logEntryId!=null -> logEntryId=null
            editorOpen -> editorOpen=false
            else -> trashOpen=false
        }
    }
    val logEntry=session.privateEntries.firstOrNull { it.id==logEntryId }
    if(logEntry!=null) {
        PrivateEntryEditLog(logEntry,formatter,language) { logEntryId=null }
        return
    }
    if(editorOpen) {
        val entry=session.privateEntries.firstOrNull { it.id==editingId }
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=24.dp,end=24.dp,top=12.dp,bottom=112.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={editorOpen=false},modifier=Modifier.testTag("private_notes_back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"All notes") }
                LocalizedText(if(editingId==null) "New private entry" else "Edit private entry",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
                if(entry!=null) IconButton(onClick={session.togglePrivateEntryPin(entry.id)},enabled=!session.busy,modifier=Modifier.testTag("private_pin_editor")) {
                    Icon(Icons.Outlined.PushPin,if(entry.pinned) "Unpin entry" else "Pin entry",tint=if(entry.pinned) Color(0xFFFFC107) else Muted)
                }
                if(entry!=null) IconButton(onClick={session.trashPrivateEntry(entry.id);editorOpen=false},enabled=!session.busy,
                    modifier=Modifier.testTag("delete_private_entry")) { Icon(Icons.Outlined.DeleteOutline,"Move to ScratchPad") }
            } }
            item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                listOf(PrivateEntryKind.Note to "Note",PrivateEntryKind.Incident to "Incident").forEach { (value,label) ->
                    FilterChip(selected=kind==value,onClick={kind=if(kind==value) null else value},label={LocalizedText(label)},
                        modifier=Modifier.weight(1f).testTag("private_kind_${value.name.lowercase()}"))
                }
            } }
            item { Field("Title (optional)",title,"private_entry_title",enabled=!session.busy) { title=it.take(120) } }
            item { OutlinedTextField(value=body,onValueChange={body=it.take(10_000)},label={LocalizedText("Private details")},
                modifier=Modifier.fillMaxWidth().heightIn(min=300.dp).testTag("private_entry_body"),enabled=!session.busy) }
            item { Button(onClick={
                session.savePrivateEntry(editingId,kind,title,body)
                if(body.isNotBlank()) editorOpen=false
            },enabled=!session.busy && body.isNotBlank(),modifier=Modifier.fillMaxWidth().testTag("save_private_entry")) {
                LocalizedText(if(editingId==null) "Save private entry" else "Update private entry")
            } }
            if(entry?.edits?.isNotEmpty()==true) item { TextButton(onClick={logEntryId=entry.id},modifier=Modifier.fillMaxWidth().testTag("private_edit_log")) {
                Text("${translate("Edit log",language)} (${entry.edits.size})")
            } }
            item { Status(session) }
        }
        return
    }
    val trashed=session.privateEntries.filter { it.deletedAt!=null }.sortedByDescending { it.deletedAt }
    if(trashOpen) {
        ScratchPad(trashed,formatter,language,session::restorePrivateEntry) { trashOpen=false }
        return
    }
    val filtered=session.privateEntries.filter { it.deletedAt==null && (search.isBlank() || it.title.contains(search,true) || it.body.contains(search,true)) }
    val pinned=filtered.filter { it.pinned }.sortedByDescending { it.updatedAt }
    val others=filtered.filterNot { it.pinned }.sortedByDescending { it.updatedAt }
    LazyVerticalGrid(columns=GridCells.Adaptive(160.dp),modifier=Modifier.fillMaxSize(),
        contentPadding=PaddingValues(start=18.dp,end=18.dp,top=12.dp,bottom=112.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item(span={GridItemSpan(maxLineSpan)}) { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick=session::back,modifier=Modifier.testTag("safe_home")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Previous safe page") }
            LocalizedText("Notes & incidents",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.weight(1f))
            IconButton(onClick={trashOpen=true},modifier=Modifier.testTag("open_scratchpad")) {
                BadgedBox(badge={if(trashed.isNotEmpty()) Badge { Text(trashed.size.toString()) }}) {
                    Icon(Icons.Outlined.DeleteOutline,"ScratchPad")
                }
            }
            FilledTonalIconButton(onClick={openEditor(null)},modifier=Modifier.testTag("new_private_entry")) { Icon(Icons.Outlined.Add,"New note") }
        } }
        item(span={GridItemSpan(maxLineSpan)}) { OutlinedTextField(search,{search=it.take(200)},modifier=Modifier.fillMaxWidth().testTag("private_notes_search"),
            placeholder={LocalizedText("Search notes")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,
            trailingIcon=if(search.isNotEmpty()) {{IconButton(onClick={search=""}) { Icon(Icons.Outlined.Close,"Clear search") }}} else null) }
        if(filtered.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) { LocalizedText(if(search.isBlank()) "No private entries yet." else "No notes found.",color=Muted) }
        if(pinned.isNotEmpty()) item(span={GridItemSpan(maxLineSpan)}) { LocalizedText("Pinned",style=MaterialTheme.typography.titleMedium,color=OliveGreen) }
        gridItems(pinned,key={it.id}) { entry -> PrivateNoteCard(entry,formatter,language,{openEditor(entry)},{session.togglePrivateEntryPin(entry.id)},{logEntryId=entry.id}) }
        if(others.isNotEmpty()) item(span={GridItemSpan(maxLineSpan)}) { LocalizedText(if(pinned.isEmpty()) "All notes" else "Others",style=MaterialTheme.typography.titleMedium) }
        gridItems(others,key={it.id}) { entry -> PrivateNoteCard(entry,formatter,language,{openEditor(entry)},{session.togglePrivateEntryPin(entry.id)},{logEntryId=entry.id}) }
    }
}

@Composable private fun ScratchPad(entries: List<PrivateEntry>, formatter: DateTimeFormatter, language: String,
    restore: (String)->Unit, close: ()->Unit) {
    val now=System.currentTimeMillis()
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=12.dp,bottom=112.dp),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=close,modifier=Modifier.testTag("close_scratchpad")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"All notes") }
                LocalizedText("ScratchPad",style=MaterialTheme.typography.headlineSmall)
            }
            LocalizedText("Deleted notes stay here for 30 days, then their private contents are permanently erased.",color=Muted)
        }
        if(entries.isEmpty()) item { LocalizedText("ScratchPad is empty.",color=Muted) }
        items(entries,key={it.id}) { entry ->
            Card(Modifier.fillMaxWidth().testTag("scratchpad_entry"),border=BorderStroke(1.dp,Color.Black),
                colors=CardDefaults.cardColors(containerColor=Color(0xFFFFFDF4))) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    entry.kind?.let { LocalizedText(if(it==PrivateEntryKind.Incident) "Incident" else "Note",color=OliveGreen) }
                    if(entry.title.isNotBlank()) Text(entry.title,style=MaterialTheme.typography.titleMedium)
                    Text(entry.body,maxLines=5,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("${entry.trashDaysRemaining(now)} ${translate("days remaining",language)}",style=MaterialTheme.typography.bodySmall,color=Muted)
                    Text(formatter.format(Instant.ofEpochMilli(entry.deletedAt ?: entry.updatedAt)),style=MaterialTheme.typography.bodySmall,color=Muted)
                    OutlinedButton(onClick={restore(entry.id)},modifier=Modifier.fillMaxWidth().testTag("restore_private_entry")) {
                        Icon(Icons.Outlined.RestoreFromTrash,null);Spacer(Modifier.width(8.dp));LocalizedText("Restore")
                    }
                }
            }
        }
    }
}

@Composable private fun PrivateEntryEditLog(entry: PrivateEntry, formatter: DateTimeFormatter, language: String, close: ()->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=12.dp,bottom=112.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {
            TextButton(onClick=close,modifier=Modifier.testTag("close_private_edit_log")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack,null);LocalizedText("Close")
            }
            LocalizedText("Edit log",style=MaterialTheme.typography.headlineMedium)
            Text(entry.title.ifBlank { translate("Untitled entry",language) },style=MaterialTheme.typography.titleMedium,color=Muted)
            Text("${entry.edits.size} ${translate("revisions",language)}",style=MaterialTheme.typography.bodySmall,color=Muted)
        }
        items(entry.edits.asReversed(),key={it.id}) { edit ->
            val revision=entry.edits.indexOf(edit)+1
            Card(Modifier.fillMaxWidth(),border=BorderStroke(1.dp,Color.Black),colors=CardDefaults.cardColors(containerColor=Color.White)) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Surface(shape=CircleShape,color=Color(0xFFE7EDE2)) {
                            Text(revision.toString(),Modifier.padding(horizontal=11.dp,vertical=6.dp),fontWeight=androidx.compose.ui.text.font.FontWeight.Bold,color=OliveGreen)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text("${translate("Revision",language)} $revision",style=MaterialTheme.typography.titleMedium)
                            Text(formatter.format(Instant.ofEpochMilli(edit.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted)
                        }
                    }
                    edit.changes.forEach { change ->
                        Surface(Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium,color=Color(0xFFF5F3EC)) {
                            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                LocalizedText(change.field,style=MaterialTheme.typography.labelLarge,color=OliveGreen)
                                LocalizedText("Before",style=MaterialTheme.typography.labelSmall,color=Muted)
                                Text(translate(change.before.ifBlank { "Empty" },language))
                                HorizontalDivider(Modifier.padding(vertical=3.dp))
                                LocalizedText("After",style=MaterialTheme.typography.labelSmall,color=Muted)
                                Text(translate(change.after.ifBlank { "Empty" },language))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun PrivateNoteCard(entry: PrivateEntry, formatter: DateTimeFormatter, language: String,
    open: ()->Unit, pin: ()->Unit, openLog: ()->Unit) {
    Card(onClick=open,modifier=Modifier.fillMaxWidth().testTag("private_entry_row"),
        border=BorderStroke(1.dp,Color.Black),colors=CardDefaults.cardColors(containerColor=Color(0xFFFFFDF4))) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                entry.kind?.let { LocalizedText(if(it==PrivateEntryKind.Incident) "Incident" else "Note",style=MaterialTheme.typography.labelMedium,color=OliveGreen,modifier=Modifier.weight(1f)) }
                    ?: Spacer(Modifier.weight(1f))
                IconButton(onClick=pin,modifier=Modifier.size(34.dp).testTag("private_pin")) { Icon(Icons.Outlined.PushPin,if(entry.pinned) "Unpin entry" else "Pin entry",tint=if(entry.pinned) Color(0xFFFFC107) else Muted) }
            }
            if(entry.title.isNotBlank()) Text(entry.title,style=MaterialTheme.typography.titleMedium,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(entry.body,maxLines=8,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text((if(entry.edits.isNotEmpty()) "${translate("Edited",language)} · " else "")+formatter.format(Instant.ofEpochMilli(entry.updatedAt)),style=MaterialTheme.typography.bodySmall,color=Muted)
            if(entry.edits.isNotEmpty()) TextButton(onClick=openLog,modifier=Modifier.testTag("private_edit_log"),contentPadding=PaddingValues(0.dp)) {
                Text("${translate("Edit log",language)} (${entry.edits.size})")
            }
        }
    }
}
@Composable private fun SafeBack(session: SessionViewModel) {
    TextButton(onClick=session::back,modifier=Modifier.testTag("safe_home")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,null); LocalizedText("Back") }
}

// Trusted people can be added or cleaned up here, but the app never auto-calls or auto-messages them.
@Composable private fun ContactEditor(session: SessionViewModel, profile: UserProfile) {
    var editing by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    FormColumn {
        TextButton(onClick=session::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,null); LocalizedText("Settings") }
        Heading("Safe contacts", "Save trusted people here. Olive does not send messages or make calls.")
        profile.contacts.forEach { contact ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(contact.name,style=MaterialTheme.typography.titleMedium)
                    Text(contact.details)
                    Row {
                        TextButton(onClick={session.recordSettingsInteraction("Olive Branch","Opened contact editor");editing=contact.id; name=contact.name; details=contact.details},enabled=!session.busy) { LocalizedText("Edit") }
                        TextButton(onClick={session.recordSettingsInteraction("Olive Branch","Selected remove contact");session.saveContacts(profile.contacts.filterNot { it.id==contact.id }); if(editing==contact.id) {editing=null; name=""; details=""}},enabled=!session.busy) { LocalizedText("Remove") }
                    }
                }
            }
        }
        if(profile.contacts.isEmpty()) LocalizedText("No contacts yet. Adding one is optional.",color=Muted)
        LocalizedText(if(editing==null) "Add a contact" else "Edit contact",style=MaterialTheme.typography.titleMedium)
        Field("Contact name",name,"contact_name",enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused contact name")}) { name=it.take(120) }
        Field("Phone or email",details,"contact_details",enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused contact details")}) { details=it.take(200) }
        Status(session)
        Button(onClick={
            if(name.isBlank() || details.isBlank()) session.error("Enter a name and a phone number or email.")
            else if(editing==null && profile.contacts.size>=25) session.error("You can save up to 25 contacts.")
            else {
                val contact=SafeContact(editing ?: UUID.randomUUID().toString(),name.trim(),details.trim())
                session.saveContacts(if(editing==null) profile.contacts+contact else profile.contacts.map { if(it.id==editing) contact else it })
                editing=null; name=""; details=""
            }
        },enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("save_contact")) { LocalizedText("Save contact") }
        if(editing!=null) TextButton(onClick={editing=null; name=""; details=""}) { LocalizedText("Cancel edit") }
    }
}


// Same two-language picker on the safe side, kept separate so the layout stays simple.
@Composable private fun LanguagePicker(value: String, enabled: Boolean = true, onChange: (String)->Unit) {
    var open by remember { mutableStateOf(false) }
    val keyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    Box {
        OutlinedButton(onClick={keyboard?.hide();focus.clearFocus(force=true);open=true},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("language")) { LocalizedText("Language (required)"); Spacer(Modifier.weight(1f)); LocalizedText(if(value=="French") "Français" else "English") }
        DropdownMenu(expanded=open,onDismissRequest={open=false}) {
            listOf("English","French").forEach { language -> DropdownMenuItem(text={LocalizedText(if(language=="French") "Français" else "English")},onClick={onChange(language);open=false}) }
        }
    }
}
// Edit private profile, language, and PIN-guarded trigger settings in this section.
@Composable private fun SettingsEditor(session: SessionViewModel, profile: UserProfile) {
    var fullName by remember { mutableStateOf(profile.fullName) }
    var affected by remember { mutableStateOf(profile.affectedNames) }
    var abuserName by remember { mutableStateOf(profile.abuserName) }
    var dob by remember { mutableStateOf(profile.dob) }
    var language by remember { mutableStateOf(profile.language) }
    var situation by remember { mutableStateOf(profile.situation) }
    var address by remember { mutableStateOf(profile.address) }
    var trigger by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var currentDevicePin by remember { mutableStateOf("") }
    var newDevicePin by remember { mutableStateOf("") }
    var confirmDevicePin by remember { mutableStateOf("") }
    FormColumn {
        SafeBack(session)
        Heading("Settings","Update your profile and language at any time.")
        OutlinedButton(onClick={session.navigate(SafePage.Contacts)},modifier=Modifier.fillMaxWidth().testTag("safe_contacts")) { LocalizedText("Safe contacts") }
        // Change the visible app and launcher icon here; add new covers to CoverType and CoverLauncher.
        CoverPicker(profile.coverType,!session.busy) { session.recordSettingsInteraction("Olive Branch","Selected cover app");session.changeCover(it) }
        LocalizedText("Olive and Daily Digest use search for safe entry. Simple Calculator uses your device PIN followed by two equals taps.",style=MaterialTheme.typography.bodySmall,color=Muted)
        Field("Full name (required)",fullName,"settings_full_name",onFocus={session.recordSettingsInteraction("Olive Branch","Focused full name")}) { fullName=it.take(120) }
        Field("Names of people experiencing abuse (optional)",affected,"settings_affected",onFocus={session.recordSettingsInteraction("Olive Branch","Focused affected names")}) { affected=it.take(500) }
        Field("Abuser's name (optional)",abuserName,"settings_abuser_name",onFocus={session.recordSettingsInteraction("Olive Branch","Focused abuser name")}) { abuserName=it.take(120) }
        BirthdayPicker(dob,"settings_dob",!session.busy) { dob=it }
        LanguagePicker(language,!session.busy) { session.recordSettingsInteraction("Olive Branch","Selected language");language=it }
        Field("Brief description of your situation (optional, recommended)",situation,"settings_situation",multiline=true,onFocus={session.recordSettingsInteraction("Olive Branch","Focused situation")}) { situation=it.take(2000) }
        LocalizedText("Location · city required",style=MaterialTheme.typography.titleLarge)
        AddressFields(address,cityOnlyRequired=true,onFocus={session.recordSettingsInteraction("Olive Branch","Focused address: $it")}) { address=it }
        Button(onClick={session.saveSettings(fullName,affected,abuserName,dob,language,situation,address)},enabled=!session.busy,modifier=Modifier.testTag("save_settings")) { LocalizedText("Save settings") }
        HorizontalDivider()
        LocalizedText("Change account password",style=MaterialTheme.typography.titleLarge)
        LocalizedText("Your username cannot be changed.")
        Field("Current password",currentPassword,"current_password",secret=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused current password")}) { currentPassword=it.take(24) }
        Field("New password",newPassword,"new_password",secret=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused new password")}) { newPassword=it.take(24) }
        Field("Confirm new password",confirmPassword,"confirm_new_password",secret=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused password confirmation")}) { confirmPassword=it.take(24) }
        Button(onClick={session.changePassword(currentPassword,newPassword,confirmPassword);currentPassword="";newPassword="";confirmPassword=""},enabled=!session.busy,modifier=Modifier.testTag("change_password")) { LocalizedText("Change password") }
        HorizontalDivider()
        LocalizedText("Change device PIN",style=MaterialTheme.typography.titleLarge)
        Field("Current device PIN",currentDevicePin,"current_device_pin",secret=true,numeric=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused current PIN")}) { currentDevicePin=it.filter(Char::isDigit).take(6) }
        Field("New device PIN",newDevicePin,"new_device_pin",secret=true,numeric=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused new PIN")}) { newDevicePin=it.filter(Char::isDigit).take(6) }
        Field("Confirm new device PIN",confirmDevicePin,"confirm_new_device_pin",secret=true,numeric=true,enabled=!session.busy,onFocus={session.recordSettingsInteraction("Olive Branch","Focused PIN confirmation")}) { confirmDevicePin=it.filter(Char::isDigit).take(6) }
        Button(onClick={session.changePin(currentDevicePin,newDevicePin,confirmDevicePin);currentDevicePin="";newDevicePin="";confirmDevicePin=""},enabled=!session.busy,modifier=Modifier.testTag("change_device_pin")) { LocalizedText("Change device PIN") }
        HorizontalDivider()
        LocalizedText("Change trigger word",style=MaterialTheme.typography.titleLarge)
        LocalizedText("Enter your current device PIN to change the trigger word. Incorrect attempts count toward the five-attempt limit.")
        Field("New trigger word or phrase",trigger,"settings_trigger",secret=true,onFocus={session.recordSettingsInteraction("Olive Branch","Focused trigger word")}) { trigger=it.take(64) }
        Field("Current PIN",pin,"settings_pin",secret=true,numeric=true,onFocus={session.recordSettingsInteraction("Olive Branch","Focused trigger-change PIN")}) { pin=it.filter { c -> c in '0'..'9' }.take(6) }
        Button(onClick={session.changeTrigger(trigger,pin);pin="";trigger=""},enabled=!session.busy,modifier=Modifier.testTag("change_trigger")) { LocalizedText("Change trigger word") }
        Status(session)
    }
}

// Tap a range edge to choose its local calendar date first and clock time second.
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ActivityDateTimePicker(label: String, value: Long?, tag: String, onChange: (Long?)->Unit) {
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var pendingDate by remember { mutableStateOf<LocalDate?>(null) }
    val zone=ZoneId.systemDefault()
    val locale=if(LocalOliveLanguage.current=="French") java.util.Locale.CANADA_FRENCH else java.util.Locale.CANADA
    val display=value?.let { DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm",locale).withZone(zone).format(Instant.ofEpochMilli(it)) }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick={showDate=true},modifier=Modifier.weight(1f).testTag(tag),contentPadding=PaddingValues(horizontal=14.dp,vertical=12.dp)) {
            Icon(Icons.Outlined.CalendarMonth,null);Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f),horizontalAlignment=Alignment.Start) {
                LocalizedText(label,style=MaterialTheme.typography.labelMedium)
                LocalizedText(display ?: "Not selected",color=if(value==null) Muted else MaterialTheme.colorScheme.onSurface)
            }
            Icon(Icons.Outlined.AccessTime,null)
        }
        if(value!=null) TextButton(onClick={onChange(null)}) { LocalizedText("Clear") }
    }
    if(showDate) key(value) {
        val initial=value ?: System.currentTimeMillis()
        val localDate=Instant.ofEpochMilli(initial).atZone(zone).toLocalDate()
        val utcDate=localDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val picker=rememberDatePickerState(initialSelectedDateMillis=utcDate)
        DatePickerDialog(onDismissRequest={showDate=false},confirmButton={
            TextButton(onClick={
                pendingDate=picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate() } ?: localDate
                showDate=false;showTime=true
            }) { LocalizedText("Next") }
        },dismissButton={TextButton(onClick={showDate=false}) { LocalizedText("Cancel") }}) {
            DatePicker(state=picker,modifier=Modifier.testTag("${tag}_calendar"))
        }
    }
    if(showTime) key(pendingDate,value) {
        val initial=value?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() } ?: java.time.LocalTime.now(zone)
        val picker=rememberTimePickerState(initialHour=initial.hour,initialMinute=initial.minute,is24Hour=true)
        AlertDialog(onDismissRequest={showTime=false},title={LocalizedText("Select time")},text={Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
            TimePicker(state=picker,modifier=Modifier.testTag("${tag}_clock"))
        }},
            confirmButton={TextButton(onClick={
                val date=pendingDate ?: LocalDate.now(zone)
                onChange(date.atTime(picker.hour,picker.minute).atZone(zone).toInstant().toEpochMilli());showTime=false
            }) { LocalizedText("Apply") }},dismissButton={TextButton(onClick={showTime=false}) { LocalizedText("Cancel") }})
    }
}

// Activity stays grouped on the overview, then See more opens one searchable category at a time.
@Composable private fun ActivityHistory(session: SessionViewModel) {
    val language=LocalOliveLanguage.current
    val formatter=DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm:ss z",
        if(language=="French") java.util.Locale.CANADA_FRENCH else java.util.Locale.CANADA).withZone(ZoneId.systemDefault())
    var from by remember { mutableStateOf<Long?>(null) }
    var to by remember { mutableStateOf<Long?>(null) }
    fun inRange(time: Long)=(from==null || time>=from!!) && (to==null || time<=to!!+59_999)
    val settings=session.settingsHistory.filter { inRange(it.timestamp) }
    val triggers=session.triggerHistory.filter { inRange(it.timestamp) }
    val attempts=session.attempts.filter { inRange(it.timestamp) }
    val checkouts=session.checkoutHistory.filter { inRange(it.timestamp) }
    val privacy=session.privacyHistory.filter { inRange(it.timestamp) }
    val selected=session.activityCategory
    fun <T> visible(category: ActivityCategory,rows: List<T>)=if(selected==category) rows else rows.take(5)
    LazyColumn(contentPadding=PaddingValues(start=24.dp,end=24.dp,top=24.dp,bottom=112.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { TextButton(onClick=session::activityBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,null); LocalizedText(if(selected==null) "Safe home" else "All activity") } }
        item { Heading(if(selected==null) "Account activity" else when(selected) {
            ActivityCategory.Settings -> "Settings history";ActivityCategory.Triggers -> "Trigger history"
            ActivityCategory.PinAttempts -> "PIN attempt history";ActivityCategory.Checkouts -> "Checkout attempts"
            ActivityCategory.Privacy -> "Privacy events"
        },"Search activity between optional local date and time values.") }
        item {
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                ActivityDateTimePicker("From",from,"activity_from") { from=it }
                ActivityDateTimePicker("To",to,"activity_to") { to=it }
            }
        }
        if(selected==null || selected==ActivityCategory.Settings) {
            item { LocalizedText("Settings history",style=MaterialTheme.typography.titleLarge) }
            if(settings.isEmpty()) item { LocalizedText("No settings activity yet.") }
            items(visible(ActivityCategory.Settings,settings),key={it.id}) { event ->
                Card(Modifier.fillMaxWidth().testTag("settings_history_row")) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Text("${translate(event.area,language)} · ${translate(event.action,language)}",fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold)
                    LocalizedText(formatter.format(Instant.ofEpochMilli(event.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted)
                    event.changes.forEach { change -> Text("${translate(change.field,language)}: ${translate(change.before.ifBlank { "Empty" },language)} → ${translate(change.after.ifBlank { "Empty" },language)}",style=MaterialTheme.typography.bodySmall) }
                } }
            }
            if(selected==null && settings.size>5) item { TextButton(onClick={session.showActivityCategory(ActivityCategory.Settings)}) { LocalizedText("See more") } }
        }
        if(selected==null || selected==ActivityCategory.Triggers) {
            item { HorizontalDivider();LocalizedText("Trigger history",style=MaterialTheme.typography.titleLarge) }
            if(triggers.isEmpty()) item { LocalizedText("No trigger activity yet.") }
            items(visible(ActivityCategory.Triggers,triggers),key={it.id}) { event ->
                Card(Modifier.fillMaxWidth().testTag("trigger_history_row")) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    LocalizedText(event.source,fontWeight=androidx.compose.ui.text.font.FontWeight.SemiBold)
                    LocalizedText(formatter.format(Instant.ofEpochMilli(event.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted)
                    if(event.pinAttempts>0) Text("${translate("PIN attempts",language)}: ${event.pinAttempts} · ${translate(event.lastResult,language)}")
                } }
            }
            if(selected==null && triggers.size>5) item { TextButton(onClick={session.showActivityCategory(ActivityCategory.Triggers)}) { LocalizedText("See more") } }
        }
        if(selected==null || selected==ActivityCategory.PinAttempts) {
            item { HorizontalDivider();LocalizedText("PIN attempt history",style=MaterialTheme.typography.titleLarge) }
            if(attempts.isEmpty()) item { LocalizedText("No failed attempts recorded.") }
            items(visible(ActivityCategory.PinAttempts,attempts),key={it.id}) { attempt ->
                Card(Modifier.fillMaxWidth().testTag("attempt_row")) { Column(Modifier.padding(16.dp)) {
                    LocalizedText("Incorrect PIN");LocalizedText(formatter.format(Instant.ofEpochMilli(attempt.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted)
                } }
            }
            if(selected==null && attempts.size>5) item { TextButton(onClick={session.showActivityCategory(ActivityCategory.PinAttempts)}) { LocalizedText("See more") } }
        }
        if(selected==null || selected==ActivityCategory.Checkouts) {
            item { HorizontalDivider();LocalizedText("Checkout attempts",style=MaterialTheme.typography.titleLarge) }
            if(checkouts.isEmpty()) item { LocalizedText("No checkout attempts yet.") }
            items(visible(ActivityCategory.Checkouts,checkouts),key={it.id}) { event ->
                Card(Modifier.fillMaxWidth().testTag("safe_checkout_history_row")) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    LocalizedText(formatter.format(Instant.ofEpochMilli(event.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted)
                    event.foods.forEach { LocalizedText("${it.quantity} × ${it.food}") };LocalizedText(event.error)
                } }
            }
            if(selected==null && checkouts.size>5) item { TextButton(onClick={session.showActivityCategory(ActivityCategory.Checkouts)}) { LocalizedText("See more") } }
        }
        if(selected==null || selected==ActivityCategory.Privacy) {
            item { HorizontalDivider();LocalizedText("Privacy events",style=MaterialTheme.typography.titleLarge) }
            if(privacy.isEmpty()) item { LocalizedText("No capture events reported by Android.") }
            items(visible(ActivityCategory.Privacy,privacy),key={it.id}) { event ->
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { LocalizedText(event.action);LocalizedText(formatter.format(Instant.ofEpochMilli(event.timestamp)),style=MaterialTheme.typography.bodySmall,color=Muted) } }
            }
            if(selected==null && privacy.size>5) item { TextButton(onClick={session.showActivityCategory(ActivityCategory.Privacy)}) { LocalizedText("See more") } }
        }
    }
}

@Composable private fun CoverPicker(selected: CoverType, enabled: Boolean, onSelect: (CoverType) -> Unit) {
    LocalizedText("Choose your cover app",style=MaterialTheme.typography.titleLarge)
    LocalizedText("The app stays open. Its icon and name update after you return to the Home screen.",style=MaterialTheme.typography.bodySmall,color=Muted)
    CoverType.entries.forEach { type ->
        val label=when(type) {
            CoverType.FOOD -> "Food Delivery App · Olive"
            CoverType.GAME -> "Game · In development"
            CoverType.NEWS -> "News App · Daily Digest"
            CoverType.CALCULATOR -> "Calculator · Simple Calculator"
        }
        OutlinedButton(onClick={onSelect(type)},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("cover_choice_${type.name.lowercase()}")) {
            LocalizedText(if(selected==type) "✓  $label" else label)
        }
    }
}
