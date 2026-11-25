package com.olive.app.ui.users.shared.safetyplan

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.UserProfile
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class SafetyPlanDeepInteractionUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun safetyPlanScreenSwitchesPrimaryAndBackupTabsAndHandlesBackAndConsultJump() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.SafetyPlan, localMode = true).use { handle ->
            handle.session.runtime.state.profile = UserProfile(
                username = "test_user",
                fullName = "Alex",
                role = AccountRole.USER,
            )
            var backed = false

            compose.setContent {
                OliveUiTestHost {
                    SafetyPlanScreen(session = handle.session, onBack = { backed = true })
                }
            }
            compose.mainClock.advanceTimeBy(200)
            Thread.sleep(100)
            compose.mainClock.advanceTimeBy(300)

            compose.onNodeWithTag("safety_plan_tabs").assertExists()
            compose.onNodeWithTag("safety_backup").performClick()
            compose.mainClock.advanceTimeBy(250)
            compose.onNodeWithTag("safety_primary").performClick()
            compose.mainClock.advanceTimeBy(250)
            compose.onNodeWithTag("safety_jump_consultation").performClick()
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithTag("safety_back").performClick()
            compose.mainClock.advanceTimeBy(150)
            assertTrue(backed)
        }
    }
}
