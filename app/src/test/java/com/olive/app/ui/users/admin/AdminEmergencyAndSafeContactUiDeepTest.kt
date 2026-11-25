package com.olive.app.ui.users.admin

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.session.EmergencyAlert
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class AdminEmergencyAndSafeContactUiDeepTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun emergencyResponseScreenRendersActiveResolvedErrorAndLoadingStates() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.ADMIN, screen = SessionScreen.Portal, localMode = true).use { handle ->
            val now = System.currentTimeMillis()
            val activeAlert = EmergencyAlert("e-1", "u-1", "Alex", now - 2000L, "m-1", resolvedAt = 0L)
            val resolvedAlert = EmergencyAlert("e-2", "u-2", "Sam", now - 5000L, "m-2", resolvedAt = now - 1000L)
            var stage by mutableIntStateOf(0)
            var backed = false

            compose.setContent {
                OliveUiTestHost {
                    when (stage) {
                        0 -> EmergencyResponseScreen(
                            session = handle.session,
                            alerts = listOf(activeAlert, resolvedAlert),
                            error = null,
                            onBack = { backed = true },
                            onTrack = {},
                        )
                        1 -> EmergencyResponseScreen(
                            session = handle.session,
                            alerts = emptyList(),
                            error = "Network timeout",
                            onBack = {},
                            onTrack = {},
                        )
                        else -> EmergencyResponseScreen(
                            session = handle.session,
                            alerts = null,
                            error = null,
                            onBack = {},
                            onTrack = {},
                        )
                    }
                }
            }
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithText(context.getString(R.string.emergency_response_resolved)).performClick()
            compose.mainClock.advanceTimeBy(150)
            compose.onNodeWithText(context.getString(R.string.emergency_response_active)).performClick()
            compose.mainClock.advanceTimeBy(150)
            compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            assertTrue(backed)

            stage = 1
            compose.mainClock.advanceTimeBy(150)
            compose.onNodeWithText("Network timeout").assertExists()

            stage = 2
            compose.mainClock.advanceTimeBy(150)
        }
    }
}
