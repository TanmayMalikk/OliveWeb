package com.olive.app.ui.users.shared.auth

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.olive.app.data.account.AccountRole
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.shared.onboarding.OnboardingNavigation
import com.olive.app.ui.users.shared.onboarding.OnboardingProgress
import com.olive.app.ui.users.shared.onboarding.RequirementBullet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class OnboardingAndAuthScreensUiDeepTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun welcomeRoleDropdownAndOnboardingProgressNavigationAndBulletsWork() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Cover, localMode = true).use { handle ->
            var backed = 0
            var nexted = 0
            var stage by mutableIntStateOf(0)

            compose.setContent {
                OliveUiTestHost {
                    Column {
                        RequirementBullet("Length", met = false, active = false)
                        RequirementBullet("Uppercase", met = true, active = true)
                        RequirementBullet("Special char", met = false, active = true)
                        OnboardingProgress(completed = 1, total = 3, page = stage, pages = 3, pageComplete = listOf(true, false, false))
                        OnboardingNavigation(back = { backed++ }, next = { nexted++ }, last = stage == 2)
                        Welcome(handle.session)
                    }
                }
            }
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithTag("onboarding_progress").assertExists()
            compose.onNodeWithTag("onboarding_back").performClick()
            compose.onNodeWithTag("onboarding_next").performClick()
            assertEquals(1, backed)
            assertEquals(1, nexted)

            stage = 2
            compose.mainClock.advanceTimeBy(150)

            // Open role dropdown and select Staff then Safe Contact
            compose.onNodeWithTag("account_role").performClick()
            compose.mainClock.advanceTimeBy(150)
            compose.onNodeWithTag("role_staff").performClick()
            compose.mainClock.advanceTimeBy(150)
        }
    }
}
