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
import com.olive.app.data.*
import com.olive.app.security.InputPolicy
import com.olive.app.ui.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SafeSupportUiTest : OliveUiTestBase() {
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

    private fun toggle() = compose.onNodeWithTag("support_menu_toggle").performClick()
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), name)
        file.outputStream().use {
            instrumentation.uiAutomation.takeScreenshot()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

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

    @Test
    fun talkBackCanOpenSupportAndQuickExitWithoutReachingTheUnderlyingScreen() {
        val session = setup()
        TalkBackTestSession(compose.activity).use { talkBack ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            compose.waitUntil(10_000) { context.getSystemService(android.view.accessibility.AccessibilityManager::class.java).isTouchExplorationEnabled }
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
