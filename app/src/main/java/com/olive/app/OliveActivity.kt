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
import android.content.Intent
import androidx.core.content.ContextCompat
import com.olive.app.location.LiveLocationController
import com.olive.app.location.LiveLocationService
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.fragment.app.FragmentActivity
import com.olive.app.data.CoverType
import com.olive.app.data.AccountRole
import com.olive.app.notification.OliveNotificationManager
import com.olive.app.ui.covers.CoverLauncher
import com.olive.app.ui.users.shared.navigation.OliveRoot
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.shared.navigation.SafePage
import java.util.function.Consumer

// Prevents Android from showing private content in the recent-apps preview.
internal fun shouldBlankRecentPreview(protectionEnabled: Boolean, sensitive: Boolean) =
    protectionEnabled && sensitive

// Gives the task a discreet label and colour in Android recents.
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

    // The resource toggle avoids hard-coding the privacy mode into this activity's control flow.
    private val screenCaptureProtectionEnabled by lazy {
        resources.getBoolean(R.bool.screen_capture_protection_enabled)
    }
    private val session: SessionViewModel by viewModels()
    private var screenshotCallback: ScreenCaptureCallback? = null
    private var recordingCallback: Consumer<Int>? = null
    private var recentPrivacyCover: View? = null
    private var privacyCoverEpoch = 0
    private var appliedTaskIdentity: Pair<CoverType, AccountRole>? = null
    private lateinit var accessibilityContent: ViewGroup
    // Counts changes to location permission.
    private var locationPermissionVersion by mutableIntStateOf(0)
    private val permissions =
        AppPermissions(this, { !session.localMode }, { session.accountRole == AccountRole.USER }) {
            locationPermissionVersion++
            session.syncLocalNotificationHandler()
        }

    // Starts the root Compose UI and restores the correct cover or account screen.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OliveNotificationManager.createNotificationChannels(this)
        CoverLauncher.ensureStudioLauncher(this)
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
            LaunchedEffect(
                selectedCover,
                selectedRole,
                session.screen,
                session.page
            ) { applySystemBarContrast() }
            // Keep the current activity available to privacy and permission callbacks.
            LaunchedEffect(notificationAccountId, selectedCover, selectedRole) {
                // Update the open task now; the Home-screen alias changes after the user leaves the app.
                updateRecentAppIdentity(selectedCover, selectedRole)
                if (notificationAccountId != null) permissions.afterOnboarding()
                session.syncLocalNotificationHandler()
            }
            CompositionLocalProvider(com.olive.app.ui.users.user.dashboard.LocalLocationPermissionRequest provides permissions::requestLocation) {
                OliveTheme { OliveRoot(session, locationPermissionVersion) }
            }
        }
    }

    // Leaving the foreground locks the User safe area; the resource flag controls the extra native privacy cover.
    override fun onPause() {
        showRecentPrivacyCover()
        applyScreenCaptureProtection()
        // Authentication and runtime permission dialogs temporarily own another window.
        // Home/onStop still lock the safe area; a permission result never restores a private screen.
        if (!deviceLock.awaitingCredential && !permissions.awaitingResult && !session.externalFilePickerOpen) session.navigation.background()
        updateRecentAppIdentity(session.coverType, session.accountRole)
        super.onPause()
    }

    // Android calls this as the user heads for Home or the recent-apps screen, so swap to cover before they get there.
    public override fun onUserLeaveHint() {
        showRecentPrivacyCover()
        applyScreenCaptureProtection()
        if (!deviceLock.awaitingCredential && !permissions.awaitingResult && !session.externalFilePickerOpen) session.navigation.background()
        updateRecentAppIdentity(session.coverType, session.accountRole)
        super.onUserLeaveHint()
    }

    // Coming back just refreshes screen protection; it never auto-reopens private pages.
    override fun onResume() {
        super.onResume()
        session.account.refreshDeviceSecurity()
        deviceLock.resume()
        applyScreenCaptureProtection()
        updateRecentAppIdentity(session.coverType, session.accountRole)
        removeRecentPrivacyCoverAfterRedraw()
        if (LiveLocationController.active(this)) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, LiveLocationService::class.java).setAction(LiveLocationService.PING)
            )
        }
    }

    // Resets the privacy timer whenever the user touches or types in the app.
    override fun onUserInteraction() {
        super.onUserInteraction()
        session.noteInteraction()
    }

    // FLAG_SECURE blacks out captures; these callbacks log only attempts Android actually reports.
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

    // Applies the privacy policy when the app is no longer visible.
    override fun onStop() {
        if (!deviceLock.awaitingCredential && !permissions.awaitingResult && !session.externalFilePickerOpen && (session.deviceLockRequest != null ||
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
        runCatching { CoverLauncher.apply(this, session.coverType, session.accountRole) }
    }

    // Apply the resource flag to FLAG_SECURE and recent-app screenshots; lifecycle methods register capture callbacks separately.
    private fun applyScreenCaptureProtection() {
        if (screenCaptureProtectionEnabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!screenCaptureProtectionEnabled)
    }

    // When capture protection is enabled, cover sensitive content and remove its native accessibility descendants together.
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

    // After two resumed frames, remove the native cover and restore accessibility only if no newer blackout replaced it.
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

    // Applies system bar contrast.
    private fun applySystemBarContrast() {
        val transparent = Color.TRANSPARENT
        val camera = session.screen == SessionScreen.Safe && session.page == SafePage.Camera
        enableEdgeToEdge(
            statusBarStyle = if (camera) SystemBarStyle.dark(transparent) else SystemBarStyle.light(
                transparent,
                transparent
            ),
            navigationBarStyle = if (camera) SystemBarStyle.dark(transparent) else SystemBarStyle.light(
                transparent,
                transparent
            ),
        )
    }

    // Keep the recent-apps title and icon aligned with the chosen launcher cover.
    private fun updateRecentAppIdentity(type: CoverType, role: AccountRole) {
        val identity = type to role
        if (!shouldApplyTaskIdentity(appliedTaskIdentity, type, role)) return
        val iconId = when {
            role == AccountRole.STAFF || role == AccountRole.ADMIN -> R.mipmap.ic_launcher_tree
            role == AccountRole.SAFE_CONTACT -> R.mipmap.ic_launcher_branch
            type == CoverType.NEWS -> R.mipmap.ic_launcher_news
            type == CoverType.FITNESS -> R.mipmap.ic_launcher_fitness
            else -> R.mipmap.ic_launcher_food
        }
        val appTitle = when (role) {
            AccountRole.SAFE_CONTACT -> getString(R.string.olive_branch)
            AccountRole.STAFF, AccountRole.ADMIN -> getString(R.string.olive_tree)
            AccountRole.USER -> getString(
                // Chooses the recent-app title and icon that match the active cover.
                when (type) {
                    CoverType.FOOD -> R.string.ui_olive_8f62f4
                    CoverType.NEWS -> R.string.ui_daily_digest
                    CoverType.FITNESS -> R.string.fitness_tracker
                    CoverType.GAME -> R.string.ui_game
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
