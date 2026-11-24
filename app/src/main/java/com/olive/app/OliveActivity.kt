// All app roles: starts the Android UI and handles private-screen behavior.
package com.olive.app

import com.olive.app.ui.shared.observeAccessibilityInteractions
import android.app.ActivityManager
import android.os.Bundle
import android.os.Build
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.olive.app.location.LiveLocationController
import com.olive.app.location.LiveLocationService
import com.olive.app.location.AdminLocationService
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.fragment.app.FragmentActivity
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.AccountRole
import com.olive.app.notification.OliveNotificationManager
import com.olive.app.notification.LocalNotificationEventHandler
import com.olive.app.security.trustedSystemHandoffActive
import com.olive.app.ui.covers.CoverLauncher
import com.olive.app.ui.users.shared.navigation.OliveRoot
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.users.user.account.PasswordRecoveryUnlockDialog
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.user.dashboard.LocalBackgroundLocationPermissionRequest
import com.olive.app.ui.users.user.dashboard.LocalLocationPermissionRequest
import java.util.function.Consumer
import android.app.Application
import com.google.firebase.auth.FirebaseAuth
import android.app.Activity
import com.olive.app.data.update.AppUpdateScheduler
import com.olive.app.data.messaging.VoipCallForegroundService
import kotlinx.coroutines.launch
import android.Manifest
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.lifecycle.withResumed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Prevents Android from showing private content in the recent-apps preview.
 *
 * @param protectionEnabled Whether to apply protection enabled.
 * @param sensitive Whether to apply sensitive.
 * @return `true` if should blank recent preview, or `false` otherwise.
 */
internal fun shouldBlankRecentPreview(protectionEnabled: Boolean, sensitive: Boolean) =
    protectionEnabled && sensitive

/**
 * Gives the task a discreet label and colour in Android recents.
 *
 * @param applied Optional applied supplied to [shouldApplyTaskIdentity].
 * @param type Type supplied to [shouldApplyTaskIdentity].
 * @param role Account role determining workspace permissions.
 * @return `true` if should apply task identity, or `false` otherwise.
 */
internal fun shouldApplyTaskIdentity(
    applied: Pair<CoverType, AccountRole>?,
    type: CoverType,
    role: AccountRole
) =
    applied != (type to role)

// Android launches this activity first; it hosts the Compose UI and protects private content.
class OliveActivity : FragmentActivity() {
    // Create device authentication after Android has attached this activity to its app context.
    private val deviceLock by lazy(LazyThreadSafetyMode.NONE) {
        com.olive.app.security.AndroidDeviceLock(this) { session.navigation.background() }
    }

    private val screenCaptureProtectionEnabled get() =
        com.olive.app.security.ScreenCapturePreferences.enabled(this, session.coverAccountId)
    private val capturePreferencesListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        applyScreenCaptureProtection()
    }
    private val session: SessionViewModel by viewModels()
    private val callActionReceiver = object : BroadcastReceiver() {
        /**
         * Handles the receive event.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @param intent Android intent containing the action and optional extras for this component.
         */
        override fun onReceive(context: Context, intent: Intent) {
            val callId = intent.data?.lastPathSegment ?: return
            if (!session.calls.canHandleNotification(callId)) return
            when (intent.action) {
                VoipCallForegroundService.ACTION_END_CALL ->
                    session.calls.finish(session.coverAccountId.orEmpty(), callId)
                VoipCallForegroundService.ACTION_QUICK_EXIT -> {
                    val hangup = session.quickExit(callId)
                    lifecycleScope.launch {
                        // Removing the task clears the ViewModel, which owns the hangup job.
                        hangup.join()
                        finishAndRemoveTask()
                    }
                }
            }
        }
    }
    private var closingForQuickExit = false
    private var screenshotCallback: ScreenCaptureCallback? = null
    private var recordingCallback: Consumer<Int>? = null
    private var recentPrivacyCover: View? = null
    private var privacyCoverEpoch = 0
    private var appliedTaskIdentity: Pair<CoverType, AccountRole>? = null
    private lateinit var accessibilityContent: ViewGroup
    // Counts changes to location permission.
    private var locationPermissionVersion by mutableIntStateOf(0)
    private val permissions =
        AppPermissions(this, { !session.localMode }) {
            locationPermissionVersion++
            session.syncLocalNotificationHandler()
        }

    /**
     * Starts the root Compose UI and restores the correct cover or account screen.
     *
     * @param savedInstanceState Previously saved instance state bundle, or null on a fresh creation.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OliveNotificationManager.createNotificationChannels(this)
        ContextCompat.registerReceiver(
            this,
            callActionReceiver,
            IntentFilter().apply {
                addAction(VoipCallForegroundService.ACTION_END_CALL)
                addAction(VoipCallForegroundService.ACTION_QUICK_EXIT)
                addDataScheme(VoipCallForegroundService.CALL_ACTION_SCHEME)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiveIncomingCallIntent(intent)
        CoverLauncher.ensureStudioLauncher(this)
        com.olive.app.security.ScreenCapturePreferences.store(this)
            .registerOnSharedPreferenceChangeListener(capturePreferencesListener)
        applyScreenCaptureProtection()
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        applySystemBarContrast()
        accessibilityContent = findViewById(android.R.id.content)
        observeAccessibilityInteractions(accessibilityContent, session::noteInteraction)
        setContent {
            val authentication = session.deviceLockRequest
            DisposableEffect(authentication) {
                authentication?.let(deviceLock::show)
                onDispose { deviceLock.cancel() }
            }

            val selectedCover = session.coverType
            val selectedRole = session.accountRole
            val notificationAccountId = session.coverAccountId
            val locationAccountName = session.coverFullName.ifBlank { session.coverUsername }
            LaunchedEffect(
                selectedCover,
                selectedRole,
                session.screen,
                session.page,
            ) { applySystemBarContrast() }
            // Keep the current activity available to privacy and permission callbacks.
            LaunchedEffect(notificationAccountId, selectedCover, selectedRole) {
                // Update the open task now; the Home-screen alias changes after the user leaves the app.
                applyScreenCaptureProtection()
                updateRecentAppIdentity(selectedCover, selectedRole)
                if (notificationAccountId != null) permissions.afterOnboarding()
                session.syncLocalNotificationHandler()
            }
            // User accounts keep the admin-only location feed running after Android grants location access.
            LaunchedEffect(
                notificationAccountId,
                locationAccountName,
                selectedRole,
                locationPermissionVersion,
            ) {
                if (!session.localMode && notificationAccountId != null && selectedRole == AccountRole.USER &&
                    permissions.hasLocation() && permissions.hasBackgroundLocation()
                ) {
                    AdminLocationService.start(this@OliveActivity, notificationAccountId, locationAccountName)
                } else if (AdminLocationService.active(this@OliveActivity)) {
                    AdminLocationService.stop(this@OliveActivity)
                }
            }
            val backgroundLocationRequest: (suspend () -> Boolean)? =
                androidx.compose.runtime.remember(locationPermissionVersion) {
                    if (permissions.hasBackgroundLocation()) null else permissions::requestBackgroundLocation
                }
            CompositionLocalProvider(
                LocalLocationPermissionRequest provides permissions::requestLocation,
                LocalBackgroundLocationPermissionRequest provides backgroundLocationRequest,
            ) {
                OliveTheme {
                    OliveRoot(session, locationPermissionVersion, closeApp = ::closeAfterQuickExit)
                    PasswordRecoveryUnlockDialog(session)
                }
            }
        }
    }

    /**
     * Cleans up listeners and allocated resources before the component is destroyed.
     */
    override fun onDestroy() {
        com.olive.app.security.ScreenCapturePreferences.store(this)
            .unregisterOnSharedPreferenceChangeListener(capturePreferencesListener)
        unregisterReceiver(callActionReceiver)
        super.onDestroy()
    }

    /**
     * Updates the activity's intent and forwards incoming call details to the session.
     *
     * @param intent Android intent containing the action and optional extras for this component.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveIncomingCallIntent(intent)
    }

    /**
     * Computes receive incoming call intent.
     *
     * @param intent Android intent containing the action and optional extras for this component.
     */
    private fun receiveIncomingCallIntent(intent: Intent?) {
        val callId = intent?.getStringExtra(OliveNotificationManager.EXTRA_INCOMING_CALL_ID).orEmpty()
        val callerId = intent?.getStringExtra(OliveNotificationManager.EXTRA_INCOMING_CALL_CALLER_ID).orEmpty()
        if (callId.isBlank() || callerId.isBlank()) return
        // The authenticated call poll is the source of truth. A notification may be tapped after
        // its 20-second invitation expired, so never reconstruct a ringing screen from old extras.
        intent?.removeExtra(OliveNotificationManager.EXTRA_INCOMING_CALL_ID)
        intent?.removeExtra(OliveNotificationManager.EXTRA_INCOMING_CALL_CALLER_ID)
        intent?.removeExtra(OliveNotificationManager.EXTRA_INCOMING_CALL_NAME)
    }

    /**
     * Leaving the foreground locks the User safe area; the account preference controls the extra privacy cover.
     */
    override fun onPause() {
        showRecentPrivacyCover()
        applyScreenCaptureProtection()
        // Authentication and runtime permission dialogs temporarily own another window.
        // The Safe App stays on its current page until the system permission result arrives.
        if (!systemHandoffActive()) session.navigation.background()
        updateRecentAppIdentity(session.coverType, session.accountRole)
        super.onPause()
    }

    /**
     * Android calls this as the user heads for Home or the recent-apps screen, so swap to cover before they get there.
     */
    public override fun onUserLeaveHint() {
        showRecentPrivacyCover()
        applyScreenCaptureProtection()
        if (!systemHandoffActive()) session.navigation.background()
        updateRecentAppIdentity(session.coverType, session.accountRole)
        super.onUserLeaveHint()
    }

    /**
     * Coming back just refreshes screen protection; it never auto-reopens private pages.
     */
    override fun onResume() {
        super.onResume()
        locationPermissionVersion++
        closingForQuickExit = false
        session.reconcilePriorityData()
        session.account.refreshDeviceSecurity()
        deviceLock.resume()
        applyScreenCaptureProtection()
        updateRecentAppIdentity(session.coverType, session.accountRole)
        removeRecentPrivacyCoverAfterRedraw()
        if (permissions.hasLocation() && LiveLocationController.active(this)) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, LiveLocationService::class.java).setAction(LiveLocationService.PING)
            )
        }
        if (!session.localMode && permissions.hasLocation() && permissions.hasBackgroundLocation() &&
            AdminLocationService.active(this)
        ) {
            ContextCompat.startForegroundService(this, Intent(this, AdminLocationService::class.java))
        }
    }

    /**
     * Resets the privacy timer whenever the user touches or types in the app.
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        session.noteInteraction()
    }

    /**
     * FLAG_SECURE blacks out captures; these callbacks log only attempts Android actually reports.
     */
    override fun onStart() {
        super.onStart()
        if (!screenCaptureProtectionEnabled) return
        // Skip the next action unless the Android version is at least 34.
        if (Build.VERSION.SDK_INT >= 34) runCatching {
            ScreenCaptureCallback { session.captureDetected("Screenshot") }.also {
                registerScreenCaptureCallback(mainExecutor, it)
                screenshotCallback = it
            }
        }
        // Android 15 and newer need this extra screen-capture protection.
        if (Build.VERSION.SDK_INT >= 35) runCatching {
            Consumer<Int> { state ->
                if (state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE) session.captureDetected("Screen recording")
                else session.recordingStopped()
            }.also {
                recordingCallback = it
                if (windowManager.addScreenRecordingCallback(
                        mainExecutor,
                        it
                    ) == WindowManager.SCREEN_RECORDING_STATE_VISIBLE
                )
                    session.captureDetected("Screen recording")
            }
        }
    }

    /**
     * Applies the privacy policy when the app is no longer visible.
     */
    override fun onStop() {
        if (!systemHandoffActive() && (session.deviceLockRequest != null ||
                    session.screen in setOf(
                SessionScreen.Pin,
                SessionScreen.DevicePin,
                SessionScreen.Safe
            ))
        ) {
            deviceLock.cancel()
            session.navigation.background()
        }
        // Skip the next action unless the Android version is at least 34.
        if (Build.VERSION.SDK_INT >= 34) screenshotCallback?.let {
            runCatching {
                unregisterScreenCaptureCallback(
                    it
                )
            }
        }
        // Android 15 and newer require an explicit screen-capture callback registration.
        if (Build.VERSION.SDK_INT >= 35) recordingCallback?.let {
            runCatching {
                windowManager.removeScreenRecordingCallback(
                    it
                )
            }
        }
        screenshotCallback = null
        recordingCallback = null
        session.recordingStopped()
        super.onStop()
        if (!permissions.awaitingResult && !session.externalFilePickerOpen && !session.callPermissionRequestOpen &&
            !session.safeSystemActivityOpen) {
            runCatching { CoverLauncher.apply(this, session.coverType, session.accountRole) }
        }
    }

    /**
     * Checks whether an expected Android system handoff (biometric, permission, or picker) is active.
     *
     * @return True when leaving the foreground is caused by a trusted system prompt or picker.
     */
    private fun systemHandoffActive(): Boolean = trustedSystemHandoffActive(
        deviceCredential = deviceLock.awaitingCredential,
        runtimePermission = permissions.awaitingResult,
        externalFilePicker = session.externalFilePickerOpen,
        callPermission = session.callPermissionRequestOpen,
        safeSystemActivity = session.safeSystemActivityOpen,
    )

    /**
     * Closes after quick exit.
     */
    private fun closeAfterQuickExit() {
        // The task can be snapshotted before Compose redraws the cover or dashboard.
        closingForQuickExit = true
        applyScreenCaptureProtection()
        if (!moveTaskToBack(true)) {
            closingForQuickExit = false
            applyScreenCaptureProtection()
        }
    }

    /**
     * Lifecycle methods register the capture callbacks separately.
     */
    private fun applyScreenCaptureProtection() {
        val protect = screenCaptureProtectionEnabled || closingForQuickExit
        if (protect) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!protect)
    }

    /**
     * When capture protection is enabled, cover sensitive content and remove its native accessibility descendants together.
     */
    private fun showRecentPrivacyCover() {
        if (!shouldBlankRecentPreview(screenCaptureProtectionEnabled, session.sensitive)) return
        accessibilityContent.importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        privacyCoverEpoch++
        if (recentPrivacyCover != null) return
        val root = window.decorView as? ViewGroup ?: return
        recentPrivacyCover = View(this).apply {
            setBackgroundColor(Color.BLACK)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            elevation = Float.MAX_VALUE
        }.also { cover ->
            root.addView(
                cover,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            cover.bringToFront()
            root.invalidate()
        }
    }

    /**
     * After two resumed frames, remove the native cover and restore accessibility only if no newer blackout replaced it.
     */
    private fun removeRecentPrivacyCoverAfterRedraw() {
        val expectedEpoch = privacyCoverEpoch
        window.decorView.postOnAnimation {
            window.decorView.postOnAnimation {
                // Restore the cover only when this is still the active privacy event and the app is visible.
                if (expectedEpoch == privacyCoverEpoch && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    val cover = recentPrivacyCover ?: return@postOnAnimation
                    (cover.parent as? ViewGroup)?.removeView(cover)
                    recentPrivacyCover = null
                    accessibilityContent.importantForAccessibility =
                        View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                }
            }
        }
    }

    /**
     * Applies system bar contrast.
     */
    private fun applySystemBarContrast() {
        val transparent = Color.TRANSPARENT
        val camera = session.screen == SessionScreen.Safe && session.page == SafePage.Camera
        val barStyle = if (camera) SystemBarStyle.dark(transparent)
            else SystemBarStyle.light(transparent, transparent)
        enableEdgeToEdge(
            statusBarStyle = barStyle,
            navigationBarStyle = barStyle,
        )
    }

    /**
     * Keep the recent-apps title and icon aligned with the chosen launcher cover.
     *
     * @param type Type supplied to [updateRecentAppIdentity].
     * @param role Account role determining workspace permissions.
     */
    private fun updateRecentAppIdentity(type: CoverType, role: AccountRole) {
        val identity = type to role
        if (!shouldApplyTaskIdentity(appliedTaskIdentity, type, role)) return
        val iconId = when {
            role == AccountRole.STAFF || role == AccountRole.ADMIN -> R.mipmap.ic_launcher_tree
            role == AccountRole.SAFE_CONTACT -> R.mipmap.ic_launcher_branch
            type == CoverType.NEWS -> R.mipmap.ic_launcher_news
            else -> R.mipmap.ic_launcher_fitness
        }
        val appTitle = when (role) {
            AccountRole.SAFE_CONTACT -> getString(R.string.olive_branch)
            AccountRole.STAFF, AccountRole.ADMIN -> getString(R.string.olive_tree)
            AccountRole.USER -> getString(
                // Chooses the recent-app title and icon that match the active cover.
                when (type) {
                    CoverType.NEWS -> R.string.ui_daily_digest
                    CoverType.FITNESS -> R.string.fitness_tracker
                }
            )
        }
        // Android uses this title for the active task and screen-capture attribution.
        title = appTitle
        if (Build.VERSION.SDK_INT >= 33) setTaskDescription(
            ActivityManager.TaskDescription.Builder()
                .setLabel(appTitle).setIcon(iconId).build()
        )
        appliedTaskIdentity = identity
    }
}

// All app roles: sets up app-wide services before any screen opens.

class OliveApplication : Application() {
    /**
     * Creates app-wide notification channels and background services at startup.
     */
    override fun onCreate() {
        super.onCreate()
        cacheDir.listFiles { file ->
            file.name.startsWith(com.olive.app.ui.users.shared.messaging.VOICE_DRAFT_PREFIX)
        }?.forEach(java.io.File::delete)
        if (BuildConfig.FIREBASE_CONFIGURED) {
            com.google.firebase.firestore.FirebaseFirestore.getInstance().firestoreSettings =
                com.google.firebase.firestore.FirebaseFirestoreSettings.Builder()
                    .setLocalCacheSettings(com.google.firebase.firestore.MemoryCacheSettings.newBuilder().build())
                    .build()
        }
        // Release updates are checked in the background. Android always keeps the final install
        // confirmation under the device owner's control.
        AppUpdateScheduler.schedule(this)
        if (BuildConfig.FIREBASE_CONFIGURED) {
            AppCheckProviderInstaller.install()
            FirebaseAuth.getInstance().addAuthStateListener { auth ->
                // The watch follows the account currently authenticated on this phone.
                com.olive.app.ui.covers.fitness.FitnessAccountSync.bind(this@OliveApplication, auth.currentUser?.uid)
                if (auth.currentUser != null) LocalNotificationEventHandler.start(this@OliveApplication)
                else LocalNotificationEventHandler.cancel(this@OliveApplication)
            }
        }
    }
}

// All app roles: starts the cover app when the Android launcher opens.

// Android Studio launches through here so changing cover icons never breaks the Run button.
class OliveLauncher : Activity() {
    /**
     * Sends launcher taps into OliveActivity and closes this small forwarding activity.
     *
     * @param savedInstanceState Previously saved instance state bundle, or null on a fresh creation.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, OliveActivity::class.java))
        finish()
    }
}

/**
 * Determines whether the app should request notifications.
 *
 * @param enabled Whether this feature or control is currently enabled.
 * @param asked Whether to apply asked.
 * @param granted Whether to apply granted.
 * @return `true` if should request notifications, or `false` otherwise.
 */
internal fun shouldRequestNotifications(enabled: Boolean, asked: Boolean, granted: Boolean) =
    enabled && !asked && !granted

/**
 * Determines whether the app should open location settings.
 *
 * @param asked Whether to apply asked.
 * @param fineRationale Whether to apply fine rationale.
 * @param coarseRationale Whether to apply coarse rationale.
 * @return `true` if should open location settings, or `false` otherwise.
 */
internal fun shouldOpenLocationSettings(asked: Boolean, fineRationale: Boolean, coarseRationale: Boolean) =
    asked && !fineRationale && !coarseRationale

// One owner serializes every post-onboarding prompt and explicit permission retries.
internal class AppPermissions(
    private val activity: ComponentActivity,
    private val notificationsEnabled: () -> Boolean,
    private val onChanged: () -> Unit
) {
    private val preferences by lazy {
        activity.getSharedPreferences(
            "olive_permissions",
            Context.MODE_PRIVATE
        )
    }
    private var notificationPrompt = false
    private val locationRequests = Mutex()
    private var locationResult: CompletableDeferred<Boolean>? = null
    val awaitingResult get() = notificationPrompt || locationResult != null
    private val notifications =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationPrompt = false
            onChanged()
        }
    private val location =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            finishLocationRequest(hasLocation())
        }
    private val backgroundLocation =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            finishLocationRequest(hasBackgroundLocation())
        }
    private val locationSettings = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        finishLocationRequest(hasLocation())
    }
    private val backgroundLocationSettings =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            finishLocationRequest(hasBackgroundLocation())
        }

    /**
     * Returns the app location settings.
     *
     * @return The resulting app location settings.
     */
    private fun appLocationSettings() =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${activity.packageName}".toUri())

    /**
     * Checks whether Android granted approximate or precise location access.
     *
     * @return `true` if has location, or `false` otherwise.
     */
    fun hasLocation() = listOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    ).any {
        ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Checks background location on Android 10 and newer; older versions do not need a separate grant.
     *
     * @return `true` if has background location, or `false` otherwise.
     */
    fun hasBackgroundLocation() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(
        activity,
        Manifest.permission.ACCESS_BACKGROUND_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Notifications support tickets, calls, and updates after account setup.
     */
    fun afterOnboarding() {
        activity.lifecycleScope.launch {
            activity.lifecycle.withResumed {
                if (awaitingResult || Build.VERSION.SDK_INT < 33 || !shouldRequestNotifications(
                        enabled = notificationsEnabled(),
                        asked = preferences.getBoolean("notifications_asked", false),
                        granted = ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED,
                    )
                ) return@withResumed
                preferences.edit { putBoolean("notifications_asked", true) }
                notificationPrompt = true
                try {
                    notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (_: IllegalStateException) {
                    notificationPrompt = false
                    preferences.edit { remove("notifications_asked") }
                }
            }
        }
    }

    /**
     * Requests location.
     *
     * @return `true` if request location, or `false` otherwise.
     */
    suspend fun requestLocation(): Boolean = locationRequests.withLock {
        locationResult?.await()
        hasLocation() || launchLocationRequest {
            val deniedPermanently = shouldOpenLocationSettings(
                preferences.getBoolean("location_asked", false),
                ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION),
                ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
            if (deniedPermanently) locationSettings.launch(appLocationSettings())
            else {
                preferences.edit { putBoolean("location_asked", true) }
                location.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            }
        }
    }

    /**
     * Requests background location.
     *
     * @return `true` if request background location, or `false` otherwise.
     */
    suspend fun requestBackgroundLocation(): Boolean {
        if (!requestLocation()) return false
        return locationRequests.withLock {
            locationResult?.await()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || hasBackgroundLocation()) return@withLock true
            launchLocationRequest {
                val permission = Manifest.permission.ACCESS_BACKGROUND_LOCATION
                val canRequest = !preferences.getBoolean("background_location_asked", false) ||
                    ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
                if (canRequest) {
                    // Android owns the link to the location permission page.
                    backgroundLocation.launch(permission)
                    preferences.edit { putBoolean("background_location_asked", true) }
                } else {
                    backgroundLocationSettings.launch(appLocationSettings())
                }
            }
        }
    }

    /**
     * Launches location request.
     *
     * @param launch Action or callback executed for launch.
     * @return `true` if launch location request, or `false` otherwise.
     */
    private suspend fun launchLocationRequest(launch: () -> Unit): Boolean {
        val pending = CompletableDeferred<Boolean>()
        activity.lifecycle.withResumed {
            locationResult = pending
            try {
                launch()
            } catch (_: android.content.ActivityNotFoundException) {
                finishLocationRequest(false)
            } catch (_: IllegalStateException) {
                finishLocationRequest(false)
            }
        }
        val granted = pending.await()
        return activity.lifecycle.withResumed { granted }
    }

    /**
     * Finishes location request.
     *
     * @param granted Whether to apply granted.
     */
    private fun finishLocationRequest(granted: Boolean) {
        val pending = locationResult
        locationResult = null
        onChanged()
        pending?.complete(granted)
    }
}
