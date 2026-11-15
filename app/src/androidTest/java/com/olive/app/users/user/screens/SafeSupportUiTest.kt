// Tests support actions and quick exit while the menu is open.
package com.olive.app

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.user.dashboard.*

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.UserProfile
import com.olive.app.security.InputPolicy
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SafeSupportUiTest : OliveUiTestBase() {
    @Test
    fun idleLockSliderSavesRecommendedAndMaximumDelays() {
        val session = setup()
        click("safe_settings")
        fill("settings_full_name", "Unsaved test name")
        click("settings_tab_privacy")
        compose.onNodeWithTag("settings_tab_privacy").assertIsSelected()
        compose.onNodeWithTag("settings_full_name").assertDoesNotExist()
        val slider = compose.onNodeWithTag("idle_timeout").performScrollTo()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.runOnIdle { assertEquals(120, session.idleTimeoutSeconds()) }
        slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "2 min (Recommended)"))
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag("idle_timeout")))
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 300f) }
        slider.performTouchInput { swipe(centerLeft.copy(x = width / 7f), centerRight) }
        compose.runOnIdle { assertEquals(450, session.idleTimeoutSeconds()) }
        click("settings_tab_profile")
        compose.onNodeWithTag("settings_full_name").assertTextContains("Unsaved test name")
        compose.runOnIdle { session.navigation.back() }
        waitFor("safe_settings")
        click("safe_settings")
        click("settings_tab_privacy")
        compose.onNodeWithTag("idle_timeout").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "7 min 30 sec")
        )
    }

    @Test
    fun incidentCalendarAndClockSaveTheSelectedValues() {
        val session = setup()
        click("safe_notes")
        click("new_private_entry")
        click("private_kind_incident")
        fill("private_entry_title", "Test incident")
        fill("private_entry_body", "Synthetic test details")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        click("incident_date")
        compose.onNodeWithTag("incident_calendar").assertIsDisplayed()
        click("incident_date_apply")
        click("incident_time")
        compose.onNodeWithTag("incident_clock").assertIsDisplayed()
        click("incident_time_apply")
        fill("incident_location", "Edmonton")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        click("save_private_entry")
        compose.waitUntil(15_000) { session.privateEntries.any { it.title == "Test incident" } }
        compose.runOnIdle {
            val entry = session.privateEntries.single { it.title == "Test incident" }
            assertEquals(java.time.LocalDate.now(), java.time.LocalDate.parse(entry.incidentDate))
            assertTrue(entry.incidentTime.matches(Regex("[0-9]{2}:[0-9]{2}")))
        }
    }

    @Test
    fun safeHomeKeepsNavigationAndQuickExitAvailableWhenScrolled() {
        val session = setup()
        val home = compose.onNodeWithTag("safe_home_content")
        home.performScrollToNode(hasTestTag("safe_notes"))
        compose.onNodeWithTag("safe_notes").assertIsDisplayed()
        home.performScrollToNode(hasTestTag("safe_documents"))
        compose.onNodeWithTag("safe_documents").assertIsDisplayed()
        compose.onNodeWithTag("safe_settings").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(SafePage.Settings, session.page) }
        compose.runOnIdle { session.navigation.back() }
        waitFor("safe_settings")
        home.performScrollToNode(hasTestTag("sign_out"))
        compose.onNodeWithTag("sign_out").assertIsDisplayed()
        compose.onNodeWithTag("quick_exit").assertIsDisplayed().performClick()
        waitCover()
        home.assertDoesNotExist()
    }

    // Checks the support overlay uses the shared talk back traversal order step with the test's sample data.
    @Test
    fun supportOverlayUsesTheSharedTalkBackTraversalOrder() {
        setup()
        compose.onNodeWithTag("session_screen_content").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.TraversalIndex,
                AccessibilityTraversalOrder.ScreenContent.index
            )
        )
        compose.onNodeWithTag("support_menu_toggle").performClick()
        compose.onNodeWithTag("support_action_list").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.TraversalIndex,
                AccessibilityTraversalOrder.OverlayContent.index
            )
        )
        compose.onNodeWithTag("support_persistent_actions").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.TraversalIndex,
                AccessibilityTraversalOrder.PersistentActions.index
            )
        )
    }

    // Checks the setup step with the test's sample data.
    private fun setup(): SessionViewModel {
        openOnboarding()
        compose.onNodeWithTag("support_menu_toggle").assertDoesNotExist()
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        compose.runOnIdle {
            session.account.chooseNew()
            session.account.authenticate(
                "support_tester",
                "test-password-123",
                true
            )
        }
        waitFor("full_name")
        compose.runOnIdle {
            session.account.saveSetupProfile(
                UserProfile(
                    "support_tester",
                    "Support Tester",
                    "",
                    "",
                    "2000-01-02",
                    "English",
                    "",
                    "Edmonton",
                    InputPolicy.triggerHash("moon garden"),
                    address = DeliveryAddress(city = "Edmonton")
                )
            )
        }
        chooseAppPin()
        fill("new_pin", "123456")
        fill(
            "confirm_pin",
            "123456"
        )
        click("pin_setup_submit")
        waitCover()
        compose.onNodeWithTag("support_menu_toggle").assertDoesNotExist()
        unlock()
        return session
    }

    // Checks the toggle step with the test's sample data.
    private fun toggle() = compose.onNodeWithTag("support_menu_toggle").performClick()
    // Checks the capture step with the test's sample data.
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), name)
        file.outputStream().use {
            instrumentation.uiAutomation.takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    // Checks the available support actions and quick exit work with the menu open step with the test's sample data.
    @Test
    fun availableSupportActionsAndQuickExitWorkWithTheMenuOpen() {
        setup()
        toggle()
        listOf("support_walkthrough", "support_emergency")
            .forEach { compose.onNodeWithTag(it).assertIsNotEnabled() }
        compose.onNodeWithTag("support_staff_help").assertIsEnabled()
        compose.onNodeWithTag("support_hotlines").assertIsEnabled()
        compose.onNodeWithTag("support_contacts").assertIsEnabled()
        compose.onNodeWithTag("safe_settings").assertDoesNotExist()
        capture("support-menu.png")
        compose.onNodeWithTag("quick_exit").performClick()
        waitCover()
        compose.onNodeWithTag("support_hotlines").assertDoesNotExist()
        compose.onNodeWithTag("support_menu_toggle").assertDoesNotExist()
    }

    // Checks the trusted contacts open from the support menu instead of settings step with the test's sample data.
    @Test
    fun trustedContactsOpenFromTheSupportMenuInsteadOfSettings() {
        val session = setup()
        toggle()
        compose.onNodeWithTag("support_contacts").performClick()
        waitFor("contact_name")
        assertEquals(SafePage.Contacts, session.page)
        compose.onNodeWithTag("safe_contacts").assertDoesNotExist()
        compose.onNodeWithTag("trusted_contacts_back").performClick()
        assertEquals(SafePage.Home, session.page)
        click("safe_settings")
        compose.onNodeWithTag("safe_contacts").assertDoesNotExist()
    }

    // Checks the hotlines match the saved city and can browse another province step with the test's sample data.
    @Test
    fun hotlinesMatchTheSavedCityAndCanBrowseAnotherProvince() {
        val session = setup()
        toggle()
        compose.onNodeWithTag("support_hotlines")
            .performClick()
        waitFor("hotline_page")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("hotline_use_device_location").fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("hotline_city").assertDoesNotExist()
        compose.onNodeWithTag("hotline_location_summary")
            .assertTextContains("Edmonton", substring = true)
        compose.onNodeWithTag("hotline_page")
            .performScrollToNode(hasText("Family Violence Info Line"))
        compose.onNodeWithText("Family Violence Info Line").assertIsDisplayed()
        capture("hotline-page.png")
        compose.onNodeWithTag("hotline_page").performScrollToNode(hasTestTag("hotline_region"))
        compose.onNodeWithTag("hotline_region").performClick()
        compose.onNodeWithTag("hotline_region_ON").performScrollTo().performClick()
        compose.onNodeWithTag("hotline_page").performScrollToNode(hasText("1-866-863-7867"))
        compose.onNodeWithText("1-866-863-7867").assertExists()
        compose.onNodeWithTag("hotline_page").performScrollToNode(hasTestTag("hotline_search"))
        fill("hotline_search", "no-such-provider")
        compose.onNodeWithTag("hotline_page")
            .performScrollToNode(hasText("No matching resources. Try another search or region."))
        compose.onNodeWithText("No matching resources. Try another search or region.")
            .assertExists()
        compose.runOnIdle {
            assertEquals(
                "Edmonton",
                session.profile!!.address.city
            )
            assertEquals("", session.profile!!.address.province)
        }
    }

    // Checks the device location changes resources without changing the saved address step with the test's sample data.
    @Test
    fun deviceLocationChangesResourcesWithoutChangingTheSavedAddress() {
        val session = setup()
        // Supply a deterministic location result; the separate permission-flow test exercises the real Android dialog.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent {
                CompositionLocalProvider(
                    LocalLocationPermissionRequest provides { true },
                    LocalHotlineLocationLookup provides {
                        DeliveryAddress(
                            city = "Toronto",
                            province = "ON",
                            country = "CA"
                        )
                    }) {
                    OliveTheme { OliveRoot(session) }
                }
            }
        }
        compose.runOnIdle { session.navigation.openSafePage(SafePage.Hotlines) }
        waitFor("hotline_use_device_location")
        compose.onNodeWithTag("hotline_use_device_location").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("hotline_location_summary").fetchSemanticsNodes()
                .any {
                    it.config.getOrElse(SemanticsProperties.Text) { emptyList() }
                        .any { text -> text.text.contains("Toronto") }
                }
        }
        compose.onNodeWithTag("hotline_page").performScrollToNode(hasText("1-866-863-7867"))
        compose.onNodeWithText("1-866-863-7867").assertExists()
        compose.runOnIdle {
            assertEquals(
                "Edmonton",
                session.profile!!.address.city
            )
            assertEquals("", session.profile!!.address.province)
        }
        compose.onNodeWithTag("hotline_city").assertDoesNotExist()
    }

    // Checks the back dismisses support before leaving the current safe page step with the test's sample data.
    @Test
    fun backDismissesSupportBeforeLeavingTheCurrentSafePage() {
        val session = setup()
        toggle()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("support_hotlines").assertDoesNotExist()
        assertEquals(SessionScreen.Safe, session.screen)
        assertEquals(SafePage.Home, session.page)
        toggle()
        compose.onNodeWithTag("support_hotlines").performClick()
        waitFor("hotline_page")
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("safe_settings").assertExists()
        assertEquals(SafePage.Home, session.page)
    }

    // Checks the support menu fits compact screens with large french text step with the test's sample data.
    @Test
    fun supportMenuFitsCompactScreensWithLargeFrenchText() {
        val session = setup()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val config = android.content.res.Configuration(compose.activity.resources.configuration)
                .apply { setLocale(java.util.Locale.FRENCH) }
            val context = compose.activity.createConfigurationContext(config)
            compose.activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(
                    LocalContext provides context, LocalConfiguration provides config,
                    LocalDensity provides Density(density, 2f)
                ) {
                    OliveTheme {
                        Box(Modifier.size(320.dp, 480.dp).testTag("support_compact")) {
                            SafeSupportControls(session, {}, true, true, true, {})
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("support_compact").fetchSemanticsNode().boundsInRoot
        for (tag in listOf("support_menu_toggle", "quick_exit")) {
            val action = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "$tag extends outside compact screen",
                action.left >= bounds.left && action.right <= bounds.right && action.bottom <= bounds.bottom
            )
        }
        compose.onNodeWithTag("support_hotlines").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("support_contacts").performScrollTo().assertIsDisplayed()
        capture("support-menu-large-text.png")
    }

    // Checks the talk back can open support and quick exit without reaching the underlying screen step with the test's sample data.
    @Test
    fun talkBackCanOpenSupportAndQuickExitWithoutReachingTheUnderlyingScreen() {
        val session = setup()
        TalkBackTestSession(compose.activity).use { talkBack ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            compose.waitUntil(10_000) { context.getSystemService(android.view.accessibility.AccessibilityManager::class.java).isTouchExplorationEnabled }
            // Checks the activate step with the test's sample data.
            fun activate(tag: String) =
                compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { it() }
            activate("support_menu_toggle")
            compose.onNodeWithTag("support_menu_toggle").assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Expanded"
                )
            )
            compose.onNodeWithTag("safe_settings").assertDoesNotExist()
            compose.onNodeWithTag("support_emergency").assertIsNotEnabled()
            activate("support_hotlines")
            waitFor("hotline_page")
            activate("support_menu_toggle")
            activate("quick_exit")
            waitCover()
            compose.onNodeWithContentDescription("Returned to Olive")
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.LiveRegion,
                        LiveRegionMode.Polite
                    )
                )
            assertTrue(
                "Quick-exit status must not move TalkBack focus",
                talkBack.focused.none { it.contains("Returned to Olive") })
            assertNull(session.profile)
            compose.onNodeWithTag("hotline_page").assertDoesNotExist()
        }
    }
}
