package com.olive.app

import android.os.Bundle
import android.os.Build
import android.app.Activity
import android.app.ActivityManager
import android.graphics.Canvas
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import com.olive.app.data.CoverType
import com.olive.app.ui.CoverLauncher
import com.olive.app.ui.OliveRoot
import com.olive.app.ui.OliveTheme
import com.olive.app.ui.SessionViewModel
import java.util.function.Consumer

// The Android shell is intentionally thin: show Olive and lock it whenever it leaves the foreground.
class MainActivity : ComponentActivity() {
    companion object {
        const val SCREEN_CAPTURE_PROTECTION_ENABLED = false
    }
    private val session: SessionViewModel by viewModels()
    private var screenshotCallback: Activity.ScreenCaptureCallback?=null
    private var recordingCallback: Consumer<Int>?=null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CoverLauncher.ensureStudioLauncher(this)
        applyScreenCaptureProtection()
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        enableEdgeToEdge()
        setContent {
            val selectedCover=session.coverType
            LaunchedEffect(selectedCover) {
                // Update the open task now; the Home-screen alias changes after the user leaves the app.
                updateRecentAppIdentity(selectedCover)
            }
            OliveTheme { OliveRoot(session) }
        }
    }
    // The second Olive slips into the background, the safe area gets covered and locked.
    override fun onPause() {
        applyScreenCaptureProtection()
        session.background()
        updateRecentAppIdentity(session.coverType)
        super.onPause()
    }
    // Android calls this as the user heads for Home or the recent-apps screen, so swap to cover before they get there.
    public override fun onUserLeaveHint() {
        applyScreenCaptureProtection()
        session.background()
        updateRecentAppIdentity(session.coverType)
        super.onUserLeaveHint()
    }
    // Coming back just refreshes screen protection; it never auto-reopens private pages.
    override fun onResume() {
        super.onResume()
        applyScreenCaptureProtection()
        updateRecentAppIdentity(session.coverType)
    }
    override fun onUserInteraction() {
        super.onUserInteraction()
        session.noteInteraction()
    }
    // FLAG_SECURE blacks out captures; these callbacks log only attempts Android actually reports.
    override fun onStart() {
        super.onStart()
        if(!SCREEN_CAPTURE_PROTECTION_ENABLED) return
        if(Build.VERSION.SDK_INT>=34) runCatching {
            Activity.ScreenCaptureCallback { session.captureDetected("Screenshot") }.also {
                registerScreenCaptureCallback(mainExecutor,it)
                screenshotCallback=it
            }
        }
        if(Build.VERSION.SDK_INT>=35) runCatching {
            Consumer<Int> { state ->
                if(state==WindowManager.SCREEN_RECORDING_STATE_VISIBLE) session.captureDetected("Screen recording")
                else session.recordingStopped()
            }.also {
                recordingCallback=it
                if(windowManager.addScreenRecordingCallback(mainExecutor,it)==WindowManager.SCREEN_RECORDING_STATE_VISIBLE)
                    session.captureDetected("Screen recording")
            }
        }
    }
    override fun onStop() {
        if(Build.VERSION.SDK_INT>=34) screenshotCallback?.let { runCatching { unregisterScreenCaptureCallback(it) } }
        if(Build.VERSION.SDK_INT>=35) recordingCallback?.let { runCatching { windowManager.removeScreenRecordingCallback(it) } }
        screenshotCallback=null;recordingCallback=null
        session.recordingStopped()
        super.onStop()
        runCatching { CoverLauncher.apply(this,session.coverType) }
    }

    // This single switch controls screenshots, recordings, recent-app previews, and capture callbacks.
    private fun applyScreenCaptureProtection() {
        if(SCREEN_CAPTURE_PROTECTION_ENABLED) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if(Build.VERSION.SDK_INT>=33) setRecentsScreenshotEnabled(!SCREEN_CAPTURE_PROTECTION_ENABLED)
    }

    // Keep the recent-apps title and icon aligned with the chosen launcher cover.
    @Suppress("DEPRECATION")
    private fun updateRecentAppIdentity(type: CoverType) {
        val iconId=when(type) {
            CoverType.NEWS -> R.drawable.ic_daily_digest
            CoverType.CALCULATOR -> R.drawable.ic_calculator
            else -> R.drawable.ic_olive
        }
        // Android uses this title for the active task and screen-capture attribution.
        title=type.title
        val icon=ContextCompat.getDrawable(this,iconId) ?: return
        val bitmap=createBitmap(108,108)
        icon.setBounds(0,0,108,108)
        icon.draw(Canvas(bitmap))
        if(Build.VERSION.SDK_INT>=33) setTaskDescription(ActivityManager.TaskDescription.Builder()
            .setLabel(type.title).setIcon(iconId).build())
        else setTaskDescription(ActivityManager.TaskDescription(type.title,bitmap))
    }
}
