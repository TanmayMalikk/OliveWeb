package com.olive.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.olive.app.R
import com.olive.app.data.account.SettingsEvent
import com.olive.app.security.DeviceEnrollment
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.users.user.dashboard.ActivityHistory
import org.junit.Rule
import org.junit.Test

class ActivityHistoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun privacyHistoryExpandsBeyondFiveRowsAndFiltersByLocalDateAndTime() {
        val activity = compose.activity
        lateinit var session: SessionViewModel
        compose.runOnUiThread { session = ViewModelProvider(activity)[SessionViewModel::class.java] }
        compose.waitUntil(15_000) { !session.busy }
        compose.runOnUiThread {
            val uid = "activity-history-fixture"
            session.runtime.enrollment = DeviceEnrollment(uid, "")
            session.runtime.pinAuthorizedUid = uid
            session.runtime.pinAuthorizedEpoch = session.runtime.epoch
            session.runtime.state.screen = SessionScreen.Safe
            session.runtime.state.page = SafePage.Activity
            val now = System.currentTimeMillis()
            session.runtime.state.privacyHistory = (1..6).map { index ->
                SettingsEvent(
                    id = "capture-$index",
                    area = "Privacy",
                    action = "Fixture capture $index",
                    timestamp = if (index == 1) now + 120_000 else now - 172_800_000 - index,
                )
            }
        }
        compose.setContent { OliveTheme { ActivityHistory(session) } }
        val history = compose.onNodeWithTag("activity_list")
        val more = hasText(activity.getString(R.string.see_more))
        history.performScrollToNode(more)
        compose.onNodeWithText("Fixture capture 6").assertDoesNotExist()
        compose.onNode(more).performClick()
        val sixth = hasText("Fixture capture 6")
        history.performScrollToNode(sixth)
        compose.onNode(sixth).assertIsDisplayed()

        val from = hasTestTag("activity_from")
        history.performScrollToNode(from)
        compose.onNode(from).performClick()
        compose.onNodeWithTag("activity_from_calendar").assertIsDisplayed()
        compose.onNodeWithText(activity.getString(R.string.next)).performClick()
        compose.onNodeWithTag("activity_from_clock").assertIsDisplayed()
        compose.onNodeWithText(activity.getString(R.string.apply)).performClick()
        val recent = hasText("Fixture capture 1")
        history.performScrollToNode(recent)
        compose.onNode(recent).assertIsDisplayed()
        compose.onNode(sixth).assertDoesNotExist()
        val clear = hasText(activity.getString(R.string.clear))
        history.performScrollToNode(clear)
        compose.onNode(clear).performClick()
        history.performScrollToNode(sixth)
        compose.onNode(sixth).assertIsDisplayed()
    }
}
