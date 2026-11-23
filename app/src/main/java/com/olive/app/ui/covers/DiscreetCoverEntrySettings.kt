// Cover users: shows cover settings and handles discreet entry into the safe app.
package com.olive.app.ui.covers

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.*
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.location.DeviceLocation
import com.olive.app.location.AdminLocationService
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.user.documents.EmergencyReportLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.CoverType
import com.olive.app.R
import com.olive.app.security.InputPolicy
import com.olive.app.ui.shared.AddressFields
import com.olive.app.ui.shared.Cream
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.users.shared.navigation.SessionViewModel


/**
 * A tiny olive gives every cover the same private-entry shortcut without looking like a normal button. Sighted users double-tap it. TalkBack users activate it with TalkBack's normal double-tap gesture.
 *
 * @param enabled Whether this feature or control is currently enabled.
 * @param onOpen Callback invoked when open changes or occurs.
 * @param modifier Compose modifier applied to the root layout.
 * @param onEmergency Callback invoked when emergency changes or occurs.
 * @param onEmergencyFeedback Callback invoked when emergency feedback changes or occurs.
 */
@Composable
internal fun DiscreetCoverEntry(
    enabled: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    onEmergency: (() -> Unit)? = null,
    onEmergencyFeedback: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val platformConfiguration = LocalViewConfiguration.current
    val holdConfiguration = remember(platformConfiguration) {
        object : ViewConfiguration by platformConfiguration { override val longPressTimeoutMillis = 2_000L }
    }
    val emergencyAction = onEmergency?.let { action -> {
        if (onEmergencyFeedback != null) onEmergencyFeedback()
        else if (!performEmergencyVibration(context)) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        action()
    } }
    val oliveLabel = stringResource(R.string.olive_icon)
    CompositionLocalProvider(LocalViewConfiguration provides holdConfiguration) {
        Canvas(
            modifier.size(48.dp)
                .testTag("discreet_cover_olive")
                .semantics {
                    contentDescription = oliveLabel
                    if (enabled && emergencyAction != null) onLongClick { emergencyAction(); true }
                    if (enabled) onClick {
                        onOpen()
                        true
                    }
                }
                .pointerInput(enabled, onOpen, emergencyAction) {
                    if (enabled) detectTapGestures(onDoubleTap = { onOpen() },
                        onLongPress = emergencyAction?.let { action -> { _: Offset -> action() } })
                }
        ) {
            val oliveWidth = 16.dp.toPx()
            val oliveHeight = 21.dp.toPx()
            val center = Offset(size.width * .58f, size.height * .58f)
            rotate(-18f, center) {
                drawOval(
                    color = OliveGreen,
                    topLeft = Offset(center.x - oliveWidth / 2f, center.y - oliveHeight / 2f),
                    size = Size(oliveWidth, oliveHeight),
                )
            }
            rotate(-35f, Offset(center.x - 3.dp.toPx(), center.y - 8.dp.toPx())) {
                drawOval(
                    color = SageGreen,
                    topLeft = Offset(center.x - 6.dp.toPx(), center.y - 11.dp.toPx()),
                    size = Size(8.dp.toPx(), 4.dp.toPx()),
                )
            }
        }
    }
}

/**
 * Fitness and Game use this page for cover-account settings and the discreet entry olive.
 *
 * @param session Shared session view model owning application state and actions.
 * @param onBack Callback invoked when the user navigates back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CoverSettingsPage(session: SessionViewModel, onBack: () -> Unit) {
    val scrollState = rememberScrollState()
    val back = rememberOliveBack(onBack = onBack)
    Scaffold(
        containerColor = Cream,
        modifier = Modifier.oliveBackPreview(back),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Cream),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).oliveScrollbar(scrollState)
                .verticalScroll(scrollState).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CoverAccountPanel(session)
            AppVersionFooter()
        }
    }
}

/**
 * Shows cover-account settings and the hidden olive used for private entry.
 *
 * @param session Shared session view model owning application state and actions.
 */
@Composable
internal fun CoverAccountPanel(session: SessionViewModel) {
    var fullName by remember(session.coverFullName) { mutableStateOf(session.coverFullName) }
    var birthday by rememberBirthdayInput(session.coverDob)
    var showCoverErrors by remember(session.coverAccountId, session.coverDob) { mutableStateOf(false) }
    var coverShake by remember(session.coverAccountId, session.coverDob) { mutableIntStateOf(0) }
    var address by remember(session.deliveryAddress) { mutableStateOf(session.deliveryAddress) }
    val nameMissing = showCoverErrors && fullName.isBlank()
    val nameShake = rememberFieldErrorShakeTrigger(
        if (nameMissing) stringResource(R.string.ui_name_required) else null,
        coverShake,
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("@${session.coverUsername}")
        OutlinedTextField(
            fullName,
            {
                fullName = it.take(120)
                showCoverErrors = false
            },
            label = { Text(stringResource(R.string.full_name)) },
            singleLine = true,
            enabled = !session.busy,
            isError = nameMissing,
            supportingText = if (nameMissing) ({ AccessibleMessage(stringResource(R.string.ui_name_required)) }) else null,
            modifier = Modifier.fillMaxWidth().testTag("cover_full_name")
                .oliveErrorShake(nameShake)
                .onFocusChanged {
                    if (it.isFocused) session.recordSettingsInteraction(
                        session.coverType.title,
                        "Focused full name"
                    )
                }.keepVisibleWhileTyping()
        )
        BirthdayPicker(birthday, "cover_dob", !session.busy, showCoverErrors, shakeTrigger = coverShake) {
            birthday = it
            showCoverErrors = false
        }
        Text(stringResource(R.string.location), style = MaterialTheme.typography.titleMedium)
        AddressFields(
            address,
            cityOnlyRequired = true,
            cityError = if (showCoverErrors && !address.locationValid) stringResource(R.string.ui_city_required) else null,
            shakeTrigger = coverShake,
        ) {
            address = it
            showCoverErrors = false
        }
        Button(
            onClick = {
                val invalidDob = !InputPolicy.validDob(birthday)
                val invalid = fullName.isBlank() || invalidDob || !address.locationValid
                showCoverErrors = invalid
                if (invalid) {
                    coverShake += 1
                    if (!invalidDob) session.saveCoverSettings(fullName, birthday, address)
                } else {
                    session.saveCoverSettings(fullName, birthday, address)
                }
            },
            enabled = !session.busy,
            modifier = Modifier.fillMaxWidth().testTag("cover_save_settings")
        ) {
            Text(stringResource(R.string.save_account_settings))
        }
        session.message?.let { AccessibleMessage(it) }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        DiscreetCoverEntry(
            enabled = !session.busy,
            onOpen = session.account::beginDiscreetEntry,
            onEmergency = if (session.coverAccountId != null && session.accountRole == AccountRole.USER)
                session::sendCoverEmergency else null,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp),
        )
    }
}

// Android reads launcher names/icons from aliases; switch them here when the saved cover changes.
internal object CoverLauncher {
    /**
     * Keep Android Studio's Run target alive without touching the user's visible cover icon.
     *
     * @param context Android context used to access system services, resources, or storage.
     */
    fun ensureStudioLauncher(context: Context) {
        val manager = context.packageManager
        val component = ComponentName(context.packageName, "${context.packageName}.OliveLauncher")
        if (manager.getComponentEnabledSetting(component) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            manager.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
    }

    /**
     * Switches the visible launcher icon to the selected cover app.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param selected Selected supplied to [apply].
     * @param role Account role determining workspace permissions.
     */
    fun apply(context: Context, selected: CoverType, role: AccountRole) {
        val manager = context.packageManager
        val aliases = listOf(
            "NewsLauncher",
            "FitnessLauncher",
            "BranchLauncher",
            "TreeLauncher"
        )
        val target = when (role) {
            AccountRole.SAFE_CONTACT -> "BranchLauncher"
            AccountRole.STAFF, AccountRole.ADMIN -> "TreeLauncher"
            AccountRole.USER -> when (selected) {
                CoverType.NEWS -> "NewsLauncher"
                CoverType.FITNESS -> "FitnessLauncher"
            }
        }
        val changes = aliases.map { name ->
            val component = ComponentName(context.packageName, "${context.packageName}.$name")
            val state =
                if (name == target) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            component to state
        }.filter { (component, state) -> manager.getComponentEnabledSetting(component) != state }
        if (changes.isEmpty()) return
        // On Pixel's Android versions, change the aliases together so a launcher always stays enabled.
        if (Build.VERSION.SDK_INT >= 33) {
            manager.setComponentEnabledSettings(changes.map { (component, state) ->
                PackageManager.ComponentEnabledSetting(
                    component,
                    state,
                    PackageManager.DONT_KILL_APP
                )
            })
        } else {
            changes.sortedByDescending { it.second == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
                .forEach { (component, state) ->
                    manager.setComponentEnabledSetting(
                        component,
                        state,
                        PackageManager.DONT_KILL_APP
                    )
                }
        }
    }
}

/**
 * Send an explicitly requested report without unlocking or exposing the Safe App on the cover.
 */
internal fun SessionViewModel.sendCoverEmergency() {
    val uid = coverAccountId ?: return
    if (accountRole != AccountRole.USER || screen != SessionScreen.Cover || busy || coverEmergencyInFlight) return
    coverEmergencyInFlight = true
    val token = runtime.epoch
    /**
     * Finishes the target.
     *
     * @param result Result payload delivered to the callback.
     */
    fun finish(result: Result<Unit>) {
        coverEmergencyInFlight = false
        if (coverAccountId == uid && runtime.epoch == token && result.isFailure) {
            Toast.makeText(runtime.application, R.string.emergency_report_failed, Toast.LENGTH_LONG).show()
        }
    }
    runtime.scope.launch {
        try {
            val (savedProfile, entries) = withContext(Dispatchers.IO) {
                runtime.store.profile(uid) to runtime.store.privateEntries(uid)
            }
            if (coverAccountId != uid || runtime.epoch != token) {
                coverEmergencyInFlight = false
                return@launch
            }
            if (!localMode) runCatching { AdminLocationService.start(runtime.application, uid, savedProfile.fullName) }
            documents.createEmergencyReport(AccountIdentity(uid, savedProfile.username), savedProfile, entries,
                location = {
                    DeviceLocation.currentCoordinates(runtime.application)?.let {
                        EmergencyReportLocation(it.latitude, it.longitude, it.accuracy, it.timestamp)
                    }
                },
                onAlertSent = {
                    if (coverAccountId == uid && runtime.epoch == token) {
                        val confirmation = uid to android.os.SystemClock.elapsedRealtime()
                        coverEmergencyConfirmation = confirmation
                        runtime.scope.launch {
                            kotlinx.coroutines.delay(3_500)
                            if (coverEmergencyConfirmation == confirmation) coverEmergencyConfirmation = null
                        }
                    }
                },
                onFinished = ::finish,
            )
        } catch (cancelled: CancellationException) {
            coverEmergencyInFlight = false
            throw cancelled
        } catch (failure: Exception) {
            finish(Result.failure(failure))
        }
    }
}

/**
 * Amplitude is a hardware request; devices without amplitude control use their default strength.
 *
 * @param context Android context used to access system services, resources, or storage.
 * @return `true` if perform emergency vibration, or `false` otherwise.
 */
@Suppress("DEPRECATION")
internal fun performEmergencyVibration(context: Context): Boolean {
    val vibrator = if (Build.VERSION.SDK_INT >= 31)
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else context.getSystemService(Vibrator::class.java)
    if (vibrator == null || !vibrator.hasVibrator()) return false
    val amplitude = if (vibrator.hasAmplitudeControl()) 179 else VibrationEffect.DEFAULT_AMPLITUDE
    val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    vibrator.vibrate(VibrationEffect.createOneShot(120, amplitude), attributes)
    return true
}
