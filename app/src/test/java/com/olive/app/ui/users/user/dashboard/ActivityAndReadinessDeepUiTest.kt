package com.olive.app.ui.users.user.dashboard

import android.app.Application
import android.content.Context
import android.location.LocationManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import com.olive.app.ui.users.shared.navigation.LocalSafeSystemLauncher
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.FailedAttempt
import com.olive.app.data.account.FieldChange
import com.olive.app.data.account.SettingsEvent
import com.olive.app.data.account.TriggerEvent
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class ActivityAndReadinessDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun activityHistoryRendersAllCategoriesSeeMoreAndDatePickers() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Activity, localMode = true).use { handle ->
            val session = handle.session
            val now = System.currentTimeMillis()
            session.runtime.state.settingsHistory = (1..6).map { i ->
                SettingsEvent(
                    id = "s-$i",
                    timestamp = now - i * 10_000L,
                    area = "Profile",
                    action = "Updated field $i",
                    changes = listOf(FieldChange("City", "", "Edmonton")),
                )
            }
            session.runtime.state.triggerHistory = (1..6).map { i ->
                TriggerEvent(
                    id = "t-$i",
                    timestamp = now - i * 10_000L,
                    source = "Cover",
                    pinAttempts = i,
                    lastResult = when (i % 3) {
                        0 -> "Accepted"
                        1 -> "Incorrect PIN"
                        else -> "No PIN attempt"
                    },
                )
            }
            session.runtime.state.attempts = (1..6).map { i ->
                FailedAttempt(id = "a-$i", timestamp = now - i * 10_000L)
            }

            compose.setContent {
                OliveUiTestHost {
                    ActivityHistory(session)
                }
            }
            compose.waitForIdle()

            // Open date/time picker from ActivityDateTimePicker
            compose.onNodeWithTag("activity_from").performClick()
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.next)).performClick()
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.apply)).performClick()
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.clear)).performClick()
            compose.waitForIdle()

            // Click See more for Settings
            val seeMore = context.getString(R.string.see_more)
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(seeMore))
            compose.onAllNodesWithText(seeMore)[0].performClick()
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.all_activity)).performClick()
            compose.waitForIdle()
        }
    }

    @Test
    fun emergencyReadinessCardDisplaysIssuesHandlesSystemFailuresAndRemindLater() {
        compose.mainClock.autoAdvance = false
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF)
        context.getSharedPreferences("olive_emergency_readiness", Context.MODE_PRIVATE).edit().clear().commit()

        compose.setContent {
            OliveUiTestHost(locationGranted = false) {
                CompositionLocalProvider(LocalSafeSystemLauncher provides { false }) {
                    Column {
                        EmergencyReadinessCard(accountId = "u-ready-1", localMode = false)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(300)

        // Click device location action which invokes openSystem -> false -> shows settingsError
        val deviceLocAction = context.getString(R.string.emergency_readiness_enable_location)
        compose.onNodeWithText(deviceLocAction).performClick()
        compose.mainClock.advanceTimeBy(150)
        compose.onNodeWithText(context.getString(R.string.app_permissions_settings_unavailable)).assertExists()

        // Snooze with remind later
        compose.onNodeWithText(context.getString(R.string.emergency_readiness_remind_later)).performClick()
        compose.mainClock.advanceTimeBy(150)
    }
}
