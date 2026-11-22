package com.olive.app.ui.users.shared.navigation

import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveTextButton as TextButton
import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.olive.app.R
import com.olive.app.data.update.AppUpdateClient
import com.olive.app.data.update.AppUpdateInstallerActivity
import com.olive.app.data.update.AppUpdateOffer
import com.olive.app.data.update.AppUpdateStore
import com.olive.app.data.update.installUpdatesPermissionIntent
import com.olive.app.ui.covers.fitness.FitnessHealthConnect
import com.olive.app.ui.shared.*
import com.olive.app.ui.users.user.account.SettingsSection
import com.olive.app.ui.users.user.dashboard.LocalBackgroundLocationPermissionRequest
import com.olive.app.ui.users.user.location.BackgroundLocationPermissionDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

private enum class PermissionFeature(val title: Int, val reason: Int, val runtime: List<String> = emptyList()) {
    Camera(R.string.app_permissions_camera, R.string.app_permissions_camera_why, listOf(Manifest.permission.CAMERA)),
    Microphone(R.string.app_permissions_microphone, R.string.app_permissions_microphone_why, listOf(Manifest.permission.RECORD_AUDIO)),
    Location(R.string.app_permissions_location, R.string.app_permissions_location_why,
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
    BackgroundLocation(R.string.app_permissions_background_location, R.string.app_permissions_background_location_why),
    Notifications(R.string.app_permissions_notifications, R.string.app_permissions_notifications_why,
        if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()),
    IncomingCalls(R.string.app_permissions_incoming_calls, R.string.app_permissions_incoming_calls_why),
    Activity(R.string.app_permissions_activity, R.string.app_permissions_activity_why, if (Build.VERSION.SDK_INT >= 29) listOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyList()),
    Health(R.string.app_permissions_health, R.string.app_permissions_health_why),
}

private data class PermissionAction(val label: Int, val description: Int, val run: () -> Unit)

private enum class PermissionStatus(val label: Int) {
    Allowed(R.string.app_permissions_allowed),
    Denied(R.string.app_permissions_disallowed),
    Limited(R.string.app_permissions_limited),
    Unavailable(R.string.app_permissions_unavailable),
    Checking(R.string.app_permissions_checking),
    Automatic(R.string.app_permissions_automatic_status),
}

/**
 * Computes permission status.
 *
 * @param allowed Whether to apply allowed.
 * @return The resulting permission status.
 */
private fun permissionStatus(allowed: Boolean) = if (allowed) PermissionStatus.Allowed else PermissionStatus.Denied

private data class HealthAccess(val requested: Set<String>, val missing: Set<String>) {
    val status get() = when {
        requested.isEmpty() -> PermissionStatus.Unavailable
        missing.isEmpty() -> PermissionStatus.Allowed
        missing.size == requested.size -> PermissionStatus.Denied
        else -> PermissionStatus.Limited
    }
}

private val automaticPermissions = listOf(
    R.string.app_permissions_internet to R.string.app_permissions_internet_why,
    R.string.app_permissions_services to R.string.app_permissions_services_why,
    R.string.app_permissions_capture to R.string.app_permissions_capture_why,
    R.string.app_permissions_system to R.string.app_permissions_system_why,
)

/**
 * Composes the safe permissions settings UI element.
 */
@Composable
internal fun SafePermissionsSettings() {
    val context = LocalContext.current
    val openSystem = LocalSafeSystemLauncher.current
    val actions = LocalSafePermissionActions.current
    val requestBackground = LocalBackgroundLocationPermissionRequest.current
    val scope = rememberCoroutineScope()
    val packageUri = "package:${context.packageName}".toUri()
    var resumed by remember { mutableIntStateOf(0) }
    var feedback by remember { mutableStateOf<Int?>(null) }
    var backgroundPrompt by remember { mutableStateOf(false) }
    LifecycleResumeEffect(context) {
        resumed++
        onPauseOrDispose {}
    }
    val canInstall = remember(resumed, actions.revision) { context.packageManager.canRequestPackageInstalls() }
    val health by produceState<HealthAccess?>(null, context, resumed, actions.revision) {
        value = try {
            if (!FitnessHealthConnect.available(context)) HealthAccess(emptySet(), emptySet()) else {
                val client = HealthConnectClient.getOrCreate(context)
                val requested = FitnessHealthConnect.permissionsFor(client)
                HealthAccess(requested, requested - client.permissionController.getGrantedPermissions())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            HealthAccess(emptySet(), emptySet())
        }
    }
    /**
     * Computes granted.
     *
     * @param permission Permission supplied to [granted].
     * @return The resulting granted.
     */
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    /**
     * Computes status.
     *
     * @param feature Feature supplied to [status].
     * @return The resulting [PermissionStatus] instance.
     */
    fun status(feature: PermissionFeature): PermissionStatus = when (feature) {
        PermissionFeature.Notifications -> permissionStatus(NotificationManagerCompat.from(context).areNotificationsEnabled())
        PermissionFeature.Location -> when {
            granted(Manifest.permission.ACCESS_FINE_LOCATION) -> PermissionStatus.Allowed
            granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> PermissionStatus.Limited
            else -> PermissionStatus.Denied
        }
        PermissionFeature.BackgroundLocation -> permissionStatus(
            if (Build.VERSION.SDK_INT >= 29) granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            else granted(Manifest.permission.ACCESS_COARSE_LOCATION),
        )
        PermissionFeature.Activity -> if (Build.VERSION.SDK_INT < 29) PermissionStatus.Automatic
            else permissionStatus(granted(Manifest.permission.ACTIVITY_RECOGNITION))
        PermissionFeature.IncomingCalls -> if (Build.VERSION.SDK_INT < 34) PermissionStatus.Automatic
            else permissionStatus(context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent())
        PermissionFeature.Health -> health?.status ?: PermissionStatus.Checking
        else -> permissionStatus(feature.runtime.all(::granted))
    }
    /**
     * Opens the target.
     *
     * @param intent Android intent containing the action and optional extras for this component.
     */
    fun open(intent: Intent) {
        feedback = if (openSystem(intent)) null else R.string.app_permissions_settings_unavailable
    }
    /**
     * Computes permission action.
     *
     * @param feature Feature supplied to [permissionAction].
     * @param status Status supplied to [permissionAction].
     * @return The resulting [PermissionAction], or `null` if unavailable.
     */
    fun permissionAction(feature: PermissionFeature, status: PermissionStatus): PermissionAction? {
        if (feature == PermissionFeature.Notifications && status == PermissionStatus.Denied &&
            (Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
        ) return PermissionAction(R.string.app_permissions_manage_short, R.string.app_permissions_manage) {
            open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }
        if (feature.runtime.isNotEmpty()) {
            if (feature == PermissionFeature.Notifications && Build.VERSION.SDK_INT < 33 ||
                feature == PermissionFeature.Activity && Build.VERSION.SDK_INT < 29) return null
            return if (status == PermissionStatus.Allowed) null
            else PermissionAction(R.string.app_permissions_allow_short, R.string.app_permissions_request) {
                feedback = if (actions.request(feature.runtime.toTypedArray())) null
                    else R.string.app_permissions_settings_unavailable
            }
        }
        return when (feature) {
            PermissionFeature.BackgroundLocation -> requestBackground?.let {
                PermissionAction(R.string.app_permissions_allow_short, R.string.app_permissions_request) { backgroundPrompt = true }
            }
            PermissionFeature.IncomingCalls -> if (Build.VERSION.SDK_INT >= 34 && status == PermissionStatus.Denied) {
                PermissionAction(R.string.app_permissions_manage_short, R.string.app_permissions_manage) {
                    open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, packageUri))
                }
            } else null
            PermissionFeature.Health -> health?.missing?.takeIf { it.isNotEmpty() }?.let { missing ->
                PermissionAction(R.string.app_permissions_manage_short, R.string.app_permissions_manage) {
                    feedback = if (actions.requestHealth(missing)) null else R.string.app_permissions_settings_unavailable
                }
            }
            else -> null
        }
    }
    SettingsSection(stringResource(R.string.app_permissions_install)) {
        Text(stringResource(R.string.app_permissions_install_why))
        Text(stringResource(permissionStatus(canInstall).label),
            style = MaterialTheme.typography.labelLarge)
        Button(
            onClick = { open(installUpdatesPermissionIntent(context)) },
            modifier = Modifier.fillMaxWidth().testTag("settings_install_permission"),
        ) { Text(stringResource(R.string.app_permissions_manage_install)) }
    }
    if (backgroundPrompt) BackgroundLocationPermissionDialog(
        onDismiss = { backgroundPrompt = false },
        onContinue = { backgroundPrompt = false; scope.launch { requestBackground?.invoke() } },
    )
    feedback?.let { AccessibleMessage(stringResource(it)) }
    SettingsSection(stringResource(R.string.app_permissions_features)) {
        PermissionFeature.entries.filter { entry ->
            when (entry) {
                PermissionFeature.BackgroundLocation, PermissionFeature.Activity -> Build.VERSION.SDK_INT >= 29
                PermissionFeature.IncomingCalls -> Build.VERSION.SDK_INT >= 34
                PermissionFeature.Health -> health?.status != PermissionStatus.Unavailable
                else -> true
            }
        }.forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider()
            val status = remember(entry, resumed, actions.revision, health) { status(entry) }
            PermissionExplanation(entry.title, entry.reason, status, permissionAction(entry, status), id = entry.name)
        }
        OutlinedButton(
            onClick = {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri))
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.app_permissions_open_android)) }
    }
    SettingsSection(stringResource(R.string.app_permissions_automatic)) {
        automaticPermissions.forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider()
            PermissionExplanation(entry.first, entry.second, PermissionStatus.Automatic)
        }
    }
}

/**
 * Composes the permission explanation UI element.
 *
 * @param title Primary display title text.
 * @param reason Reason supplied to [PermissionExplanation].
 * @param status Status supplied to [PermissionExplanation].
 * @param action Optional action supplied to [PermissionExplanation].
 * @param id Optional ID supplied to [PermissionExplanation].
 */
@Composable
private fun PermissionExplanation(
    title: Int, reason: Int, status: PermissionStatus, action: PermissionAction? = null, id: String? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(reason), style = MaterialTheme.typography.bodySmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(stringResource(status.label), style = MaterialTheme.typography.labelMedium,
                color = if (status == PermissionStatus.Denied) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (id == null) Modifier else Modifier.testTag("settings_permission_status_$id"))
            action?.let {
                val description = stringResource(it.description, stringResource(title))
                TextButton(onClick = it.run,
                    modifier = Modifier.testTag("settings_permission_$id").semantics { contentDescription = description }) {
                    Text(stringResource(it.label))
                }
            }
        }
    }
}

internal val LocalSafeSystemLauncher = staticCompositionLocalOf<(Intent) -> Boolean> { { false } }
internal val LocalSafePermissionActions = androidx.compose.runtime.compositionLocalOf {
    SafePermissionActions(0, { false }, { false })
}

internal data class SafePermissionActions(
    val revision: Int,
    val request: (Array<String>) -> Boolean,
    val requestHealth: (Set<String>) -> Boolean,
)

internal data class SafeSystemLaunchers(
    val open: (Intent) -> Boolean,
    val permissions: SafePermissionActions,
)

/**
 * Composes the remember safe system launchers UI element.
 *
 * @param session Shared session view model owning application state and actions.
 * @return The resulting [SafeSystemLaunchers] for remember safe system launchers.
 */
@Composable
internal fun rememberSafeSystemLaunchers(session: SessionViewModel): SafeSystemLaunchers {
    val view = LocalView.current
    var revision by remember { mutableIntStateOf(0) }
    /**
     * Computes returned.
     */
    fun returned() {
        revision++
        session.safeSystemActivityResult(view)
    }
    val system = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { returned() }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { returned() }
    val health = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { returned() }
    return SafeSystemLaunchers(
        open = { launchWithSafeSession(session) { system.launch(it) } },
        permissions = SafePermissionActions(
            revision,
            request = { launchWithSafeSession(session) { runtime.launch(it) } },
            requestHealth = { launchWithSafeSession(session) { health.launch(it) } },
        ),
    )
}

/**
 * Launches with safe session.
 *
 * @param session Shared session view model owning application state and actions.
 * @param launch Action or callback executed for launch.
 * @return `true` if launch with safe session, or `false` otherwise.
 */
private inline fun launchWithSafeSession(session: SessionViewModel, launch: () -> Unit): Boolean {
    val marker = session.beginSafeSystemActivity() ?: return false
    try {
        launch()
        return true
    } catch (_: ActivityNotFoundException) {
        session.finishSafeSystemActivity(marker)
    } catch (_: SecurityException) {
        session.finishSafeSystemActivity(marker)
    }
    return false
}

internal val LocalSafeUpdateReminder = compositionLocalOf<(() -> Unit)?> { null }

internal data class SafeAppUpdateUi(
    val offer: AppUpdateOffer?,
    val notice: Int?,
    val onUpdate: () -> Unit,
    val onLater: (() -> Unit)?,
    val onDownload: (() -> Unit)?,
    val onDismissNotice: () -> Unit,
)

/**
 * Composes the remember safe app update UI element.
 *
 * @param enabled Whether this feature or control is currently enabled.
 * @param cameraInHeader Whether to apply camera in header.
 * @param launch Function used to transform or provide launch.
 * @return The resulting [SafeAppUpdateUi] for remember safe app update.
 */
@Composable
internal fun rememberSafeAppUpdate(
    enabled: Boolean,
    cameraInHeader: Boolean,
    launch: (Intent) -> Boolean,
): SafeAppUpdateUi {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val store = remember(context) { AppUpdateStore(context) }
    var offer by remember(store, enabled) { mutableStateOf(if (enabled) store.offer() else null) }
    var notice by remember(enabled, offer?.release?.versionCode) { mutableStateOf<Int?>(null) }
    DisposableEffect(store, enabled) {
        val subscription = if (enabled) store.observe { offer = it } else null
        onDispose { subscription?.close() }
    }
    LaunchedEffect(enabled, store, lifecycle) {
        if (!enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val client = AppUpdateClient(context)
            while (true) {
                withContext(Dispatchers.IO) { runCatching { store.refresh(client) } }
                delay(60_000.milliseconds)
            }
        }
    }
    val current = offer
    val updateNow: () -> Unit = {
        current?.let {
            notice = if (launch(Intent(context, AppUpdateInstallerActivity::class.java)
                    .putExtra(AppUpdateInstallerActivity.EXTRA_RELEASE_JSON, it.release.toJson()))) {
                null
            } else R.string.app_update_failed
        }
    }
    return SafeAppUpdateUi(
        offer = current?.takeUnless { it.dismissed },
        notice = notice,
        onUpdate = updateNow,
        onLater = current?.let {
            {
                store.dismiss(it)
                notice = if (cameraInHeader) R.string.app_update_later_camera_hint else R.string.app_update_later_hint
            }
        },
        onDownload = updateNow.takeIf { current?.dismissed == true },
        onDismissNotice = { notice = null },
    )
}

/**
 * Displays the safe app update banner overlay.
 *
 * @param state Current state instance to read or render.
 * @param modifier Compose modifier applied to the root layout.
 */
@Composable
internal fun SafeAppUpdateBanner(state: SafeAppUpdateUi, modifier: Modifier = Modifier) {
    val dismissNotice by rememberUpdatedState(state.onDismissNotice)
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(3_000.milliseconds)
            dismissNotice()
        }
    }
    Box(modifier.windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(visible = state.notice != null && state.offer == null, enter = fadeIn(), exit = fadeOut()) {
            state.notice?.let { notice ->
                Surface(modifier = Modifier.testTag("safe_update_notice").swipeAway(dismissNotice),
                    shape = MaterialTheme.shapes.large, shadowElevation = 6.dp,
                    color = MaterialTheme.colorScheme.secondaryContainer) {
                    AccessibleMessage(stringResource(notice), Modifier.padding(12.dp))
                }
            }
        }
        UpdateOfferCard(state)
    }
}

/**
 * Renders the update offer card composable.
 *
 * @param state Current state instance to read or render.
 */
@Composable
private fun UpdateOfferCard(state: SafeAppUpdateUi) {
    var recentOffer by remember { mutableStateOf(state.offer) }
    SideEffect { state.offer?.let { recentOffer = it } }
    AnimatedVisibility(
        visible = state.offer != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        val offer = state.offer ?: recentOffer ?: return@AnimatedVisibility
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .testTag("safe_update_banner_surface")
                .swipeAway(state.onLater.takeIf { state.offer != null }),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AccessibleMessage(
                    stringResource(if (offer.required) R.string.app_update_required else R.string.ui_a_new_update_is_available),
                    modifier = Modifier.testTag("safe_update_banner"),
                )
                Text(stringResource(R.string.app_update_version, offer.release.versionName),
                    style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = state.onUpdate, enabled = state.offer != null) {
                        Text(stringResource(R.string.app_update_now))
                    }
                    state.onLater?.let { later ->
                        TextButton(onClick = later, enabled = state.offer != null) {
                            Text(stringResource(if (offer.required) R.string.app_update_minimize else R.string.app_update_later))
                        }
                    }
                }
                if (state.offer != null) state.notice?.let { AccessibleMessage(stringResource(it)) }
            }
        }
    }
}

/**
 * Composes the swipe away UI element.
 *
 * @param onDismiss Callback invoked when the dialog or sheet is dismissed, or null when not provided.
 * @return The resulting [Modifier] for swipe away.
 */
@Composable
private fun Modifier.swipeAway(onDismiss: (() -> Unit)?): Modifier {
    if (onDismiss == null) return this
    val dismiss by rememberUpdatedState(onDismiss)
    var drag by remember { mutableStateOf(Offset.Zero) }
    return offset {
        if (drag.isHorizontalSwipe()) IntOffset(drag.x.roundToInt(), 0)
        else IntOffset(0, drag.y.coerceAtMost(0f).roundToInt())
    }
        .pointerInput(Unit) {
            detectDragGestures(
                onDragEnd = {
                    val qualifies = if (drag.isHorizontalSwipe()) abs(drag.x) >= 72.dp.toPx()
                    else -drag.y >= minOf(48.dp.toPx(), size.height / 3f)
                    if (qualifies) dismiss()
                    drag = Offset.Zero
                },
                onDragCancel = { drag = Offset.Zero },
            ) { change, amount ->
                change.consume()
                drag += amount
            }
        }
}

/**
 * Checks whether horizontal swipe for offset.
 *
 * @return `true` if is horizontal swipe, or `false` otherwise.
 */
private fun Offset.isHorizontalSwipe() = abs(x) > abs(y)

/**
 * Composes the safe app update reminder UI element.
 */
@Composable
internal fun SafeAppUpdateReminder() {
    val download = LocalSafeUpdateReminder.current
    AnimatedVisibility(visible = download != null, enter = scaleIn(initialScale = .4f) + fadeIn(),
        exit = scaleOut(targetScale = .4f) + fadeOut()) {
        IconButton(onClick = { download?.invoke() }, modifier = Modifier.testTag("safe_update_reminder")) {
            Icon(Icons.Outlined.FileDownload, stringResource(R.string.app_update_now))
        }
    }
}

/**
 * Gives every long form the same spacing, scrolling, and room above Quick exit.
 *
 * @param scrollResetKey Optional scroll reset key supplied to [FormColumn].
 * @param content Composable content lambda rendered inside this container.
 */
@Composable
internal fun FormColumn(
    scrollResetKey: Any? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(scrollResetKey) {
        if (scrollResetKey != null) scrollState.scrollTo(0)
    }
    Column(
        Modifier.fillMaxSize().oliveScrollbar(scrollState).verticalScroll(scrollState)
            .padding(horizontal = 24.dp).padding(top = 24.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), content = content
    )
}

/**
 * Shows the app name, page title, and help text, then moves TalkBack focus to the new page title.
 *
 * @param title Primary display title text.
 * @param subtitle Secondary descriptive text.
 * @param appName App name supplied to [Heading].
 * @param focusKey Optional focus key supplied to [Heading].
 */
@Composable
internal fun Heading(
    title: String,
    subtitle: String,
    appName: String = stringResource(R.string.olive_branch),
    focusKey: Any? = title,
) {
    val focusRequester = remember { FocusRequester() }
    // Moves TalkBack focus to the new form heading after the account step changes.
    LaunchedEffect(focusKey) {
        if (focusKey != null) {
            withFrameNanos { }
            focusRequester.requestFocus()
        }
    }
    Text(appName, fontFamily = FontFamily.Serif, fontSize = 30.sp, color = OliveGreen)
    Text(
        title,
        modifier = Modifier.focusRequester(focusRequester).focusable().semantics { heading() },
        style = MaterialTheme.typography.headlineMedium
    )
    if (subtitle.isNotBlank()) Text(subtitle, color = Muted)
}

/**
 * Warns testers when data is only being saved on the current device.
 *
 * @param session Shared session view model owning application state and actions.
 */
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

/**
 * Announces status changes to TalkBack and shows when the app is busy.
 *
 * @param session Shared session view model owning application state and actions.
 * @param showMessage Whether to show message.
 */
@Composable
internal fun Status(session: SessionViewModel, showMessage: Boolean = true) {
    // Shows the account form message only when there is text to explain.
    if (showMessage) session.message?.let {
        AccessibleMessage(
            it,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)
                .testTag("session_message")
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center),
        )
    }
    AnimatedVisibility(
        session.busy,
        enter = fadeIn(tween(120)) + expandVertically(),
        exit = fadeOut(tween(100)) + shrinkVertically()
    ) {
        LoadingSkeleton(SkeletonLayout.Action, Modifier.fillMaxWidth().padding(horizontal = 18.dp))
    }
}

/**
 * Builds a text field with consistent validation, password controls, and keyboard behavior.
 *
 * @param label Accessible label or display caption.
 * @param value Input value to store, parse, or format.
 * @param tag Tag supplied to [Field].
 * @param secret Whether to apply secret.
 * @param numeric Whether to apply numeric.
 * @param showSecretToggle Whether to show secret toggle.
 * @param multiline Whether to apply multiline.
 * @param enabled Whether this feature or control is currently enabled.
 * @param error Throwable or error message describing a failure, or null when not provided.
 * @param onFocus Callback invoked when focus changes or occurs.
 * @param newPassword Whether to apply new password.
 * @param onChange Callback invoked when the value or observed state changes.
 */
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
    newPassword: Boolean = false,
    onChange: (String) -> Unit
) {
    val uiText = rememberResourceText()
    var secretVisible by rememberSaveable(tag) { mutableStateOf(false) }
    val secretDescription = when {
        numeric -> uiText(R.string.a11y_pin)
        tag.contains("trigger", ignoreCase = true) -> uiText(R.string.a11y_trigger_word)
        else -> uiText(R.string.a11y_password)
    }
    val autofillType = when (tag) {
        "username" -> ContentType.Username
        "password" -> if (newPassword) ContentType.NewPassword else ContentType.Password
        "confirm_password" -> ContentType.NewPassword
        else -> null
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics {
            error?.let { error(it) }
            autofillType?.let { contentType = it }
        }
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
        visualTransformation = if (secret && !secretVisible) PasswordVisualTransformation()
        else VisualTransformation.None,
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
