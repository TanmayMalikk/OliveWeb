package com.olive.app.ui.users.user.dashboard

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.FailedAttempt
import com.olive.app.data.account.SettingsEvent
import com.olive.app.data.account.TriggerEvent
import com.olive.app.data.account.UserProfile
import com.olive.app.data.safety.HotlineResource
import com.olive.app.data.safety.HotlineResourceKind
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
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
class UserDashboardAndResourcesUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    private val sampleProfile = UserProfile(
        username = "alex_user",
        fullName = "Alex Rivera",
        phone = "555-0100",
        dob = "1995-04-12",
        language = "English",
        situation = "Safety planning",
        affectedNames = "Child One",
        address = DeliveryAddress(city = "Edmonton", province = "AB", country = "Canada"),
        role = AccountRole.USER,
        coverType = CoverType.FITNESS,
    )

    @Test
    fun safeAreaNavigatesCoreUserPagesWithManualClock() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, localMode = true).use { handle ->
            handle.session.runtime.state.profile = sampleProfile
            compose.setContent {
                OliveUiTestHost {
                    SafeArea(handle.session, exit = {})
                }
            }
            compose.mainClock.advanceTimeBy(50)
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            compose.onNodeWithTag("safe_home_content").assertExists()

            // Navigate across user SafePages that don't invoke camera/webview native hardware.
            for (page in listOf(
                SafePage.Profile,
                SafePage.Notes,
                SafePage.Messages,
                SafePage.Settings,
                SafePage.Contacts,
                SafePage.Activity,
                SafePage.Hotlines,
                SafePage.SafetyPlan,
                SafePage.Documents,
                SafePage.Walkthrough,
                SafePage.Home,
            )) {
                compose.runOnIdle { handle.session.navigation.openSafePage(page) }
                compose.mainClock.advanceTimeBy(250)
                compose.waitForIdle()
            }
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            compose.onNodeWithTag("safe_home_content").assertExists()
        }
    }

    @Test
    fun activityHistoryHotlinesAndCardsRenderAndInteract() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Activity, localMode = true).use { handle ->
            handle.session.runtime.state.profile = sampleProfile
            handle.session.runtime.state.settingsHistory = listOf(SettingsEvent(timestamp = 1700000000000L, area = "Cover", action = "Updated cover"))
            handle.session.runtime.state.triggerHistory = listOf(TriggerEvent(timestamp = 1700000100000L, source = "Cover trigger"))
            handle.session.runtime.state.attempts = listOf(FailedAttempt(id = "fa-1", timestamp = 1700000200000L))
            handle.session.runtime.state.privacyHistory = listOf(SettingsEvent(timestamp = 1700000300000L, area = "Privacy", action = "Enabled screenshot block"))

            var screenStage by mutableIntStateOf(0)
            var kindLabel = ""
            var regionLabel = ""
            val sampleHotline = HotlineResource(
                id = "h1",
                region = "AB",
                city = "Edmonton",
                name = "24/7 Hope Helpline",
                description = "Confidential crisis support.",
                kind = HotlineResourceKind.Hotline,
                phone = "1-800-555-0123",
                website = "https://example.org",
                source = "Olive Directory",
                phoneUri = "tel:18005550123",
                hours = "24/7",
                textNumber = "686868",
            )
            compose.setContent {
                OliveUiTestHost {
                    kindLabel = hotlineKindLabel(HotlineResourceKind.Hotline)
                    regionLabel = hotlineRegionName("AB")
                    when (screenStage) {
                        0 -> ActivityHistory(handle.session)
                        1 -> SafeResourcesScreen(handle.session, sampleProfile)
                        else -> HotlineResourceCard(
                            resource = sampleHotline,
                            saved = true,
                            onToggleSave = {},
                            onOpen = { _, _ -> },
                        )
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("activity_list").assertIsDisplayed()

            screenStage = 1
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("hotline_region").fetchSemanticsNodes().isNotEmpty() }

            screenStage = 2
            compose.waitForIdle()
            compose.onNodeWithText("24/7 Hope Helpline").assertIsDisplayed()
            assertTrue(kindLabel.isNotBlank())
            assertTrue(regionLabel.isNotBlank())
        }
    }
}
