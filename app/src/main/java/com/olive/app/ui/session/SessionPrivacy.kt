// Signed-in users: clears private state when the safe app closes.
package com.olive.app.ui.session

import com.olive.app.ui.users.shared.navigation.*

import android.os.SystemClock
import android.os.Build
import android.view.accessibility.AccessibilityManager
import com.olive.app.data.*
import com.olive.app.security.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

// Handles reported captures and User inactivity; Android's accessibility timeout can extend the idle warning.
internal class SessionPrivacy(private val runtime: SessionRuntime) {
    // Session privacy: Returns the note interaction. It reads the Android monotonic clock so clock changes cannot break the timing.
    fun noteInteraction() {
        with(runtime) {
            with(state) {
                if (screen != SessionScreen.Safe || accountRole != AccountRole.USER) return
                lastTouchAt = SystemClock.elapsedRealtime()
                idleCountdownSeconds = null
            }
        }
    }

    // returns the capture detected for session privacy. It waits for the set time and cancels the timer when the feature closes.
    fun captureDetected(kind: String) {
        with(runtime) {
            with(state) {
                if (privacyBlackout) return
                val uid = enrollment?.uid
                if (accountRole == AccountRole.USER) navigation.quickExit()
                privacyBlackout = true
                if (kind == "Screenshot") scope.launch {
                    delay(1_500.milliseconds)
                    privacyBlackout = false
                }
                if (uid == null) return
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        store.recordSettings(
                            uid,
                            SettingsEvent(area = "Privacy", action = "$kind detected")
                        )
                        history.queueHistorySync()
                    }
                }
            }
        }
    }

    // Session privacy: returns the recording stopped.
    fun recordingStopped() {
        with(runtime) {
            with(state) {
                privacyBlackout = false
            }
        }
    }

    // This warning applies only while the User safe area is open; background locking remains immediate.
    fun startIdleTimer() {
        with(runtime) {
            with(state) {
                idleJob?.cancel()
                if (accountRole != AccountRole.USER) {
                    idleCountdownSeconds = null
                    return
                }
                noteInteraction()
                idleJob = scope.launch {
                    // Session privacy: Keeps start idle timer active while screen equals session screen safe.
                    while (screen == SessionScreen.Safe) {
                        delay(250.milliseconds)
                        val accessibility =
                            application.getSystemService(AccessibilityManager::class.java)
                        val warningDuration =
                            if (Build.VERSION.SDK_INT >= 29) accessibility?.getRecommendedTimeoutMillis(
                                15_000,
                                AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS
                            )?.toLong() ?: 15_000L else 15_000L
                        val countdown = IdlePolicy.countdown(
                            SystemClock.elapsedRealtime() - lastTouchAt,
                            warningDuration
                        )
                        if (countdown == 0) {
                            navigation.quickExit()
                            break
                        }
                        idleCountdownSeconds = countdown
                    }
                }
            }
        }
    }
}
