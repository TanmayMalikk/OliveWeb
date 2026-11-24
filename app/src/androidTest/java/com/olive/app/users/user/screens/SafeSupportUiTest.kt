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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.account.UserProfile
import com.olive.app.security.InputPolicy
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SafeSupportUiTest : OliveUiTestBase() {
    @Test
    fun notesBoardSortsAndFiltersEntries() {
        val session = setup()
        val note = "Earlier sample note"
        val incident = "Later sample incident"
        click("safe_notes")
        compose.runOnIdle {
            session.savePrivateEntry(null, PrivateEntryKind.Note, note, "Synthetic note", "", "", "")
        }
        compose.waitUntil(10_000) { !session.busy && session.privateEntries.any { it.title == note } }
        compose.runOnIdle {
            session.savePrivateEntry(
                null, PrivateEntryKind.Incident, incident, "Synthetic incident", "2026-11-01", "", "Edmonton"
            )
        }
        compose.waitUntil(10_000) { !session.busy && session.privateEntries.any { it.title == incident } }
        fun precedes(first: String, second: String): Boolean {
            val a = compose.onNodeWithText(first).getUnclippedBoundsInRoot()
            val b = compose.onNodeWithText(second).getUnclippedBoundsInRoot()
            return a.top < b.top || (a.top == b.top && a.left < b.left)
        }
        assertTrue(precedes(incident, note))
        click("private_sort")
        click("private_sort_oldest")
        compose.waitUntil(4_000) { precedes(note, incident) }
        click("private_filter_note")
        compose.onNodeWithText(note).assertExists()
        compose.onNodeWithText(incident).assertDoesNotExist()
        click("private_filter_incident")
        compose.onNodeWithText(incident).assertExists()
        compose.onNodeWithText(note).assertDoesNotExist()
        fill("private_notes_search", "missing fixture")
        compose.onNodeWithText("No notes or incidents match.").assertExists()
    }

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
        compose.runOnIdle { assertEquals(420, session.idleTimeoutSeconds()) }
        click("settings_tab_profile")
        compose.onNodeWithTag("settings_full_name").assertTextContains("Unsaved test name")
        compose.runOnIdle { session.navigation.back() }
        waitFor("safe_settings")
        click("safe_settings")
        click("settings_tab_privacy")
        compose.onNodeWithTag("idle_timeout").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "7 min")
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
    fun incidentCanClearOptionalDateAndTimeAndSaveTypedCoordinatesWithoutATitle() {
        val session = setup()
        click("safe_notes")
        click("new_private_entry")
        click("private_kind_incident")
        fill("private_entry_body", "Synthetic coordinate fixture")
        hideKeyboard()
        click("incident_date")
        click("incident_date_apply")
        val dateClear = compose.onNodeWithContentDescription(compose.activity.getString(R.string.incident_clear_date))
        dateClear.performScrollTo().performClick()
        dateClear.assertDoesNotExist()
        click("incident_time")
        click("incident_time_apply")
        val timeClear = compose.onNodeWithContentDescription(compose.activity.getString(R.string.incident_clear_time))
        timeClear.performScrollTo().performClick()
        timeClear.assertDoesNotExist()
        fill("incident_location", "53.5, -113.5")
        hideKeyboard()
        click("save_private_entry")
        compose.waitUntil(15_000) { !session.busy && session.privateEntries.any { it.body == "Synthetic coordinate fixture" } }
        compose.runOnIdle {
            val entry = session.privateEntries.single { it.body == "Synthetic coordinate fixture" }
            assertTrue(entry.title.isBlank() && entry.incidentDate.isBlank() && entry.incidentTime.isBlank())
            assertEquals(53.5, entry.incidentLatitude ?: Double.NaN, 0.0)
            assertEquals(-113.5, entry.incidentLongitude ?: Double.NaN, 0.0)
        }
    }

    @Test
    fun staffHelpShowsTheOfflineErrorAndCanReturnToTheSafeDashboard() {
        val session = setup()
        click("support_menu_toggle")
        click("support_staff_help")
        waitFor("staff_help_back")
        compose.onNodeWithText(compose.activity.getString(R.string.safety_account_required)).assertExists()
        compose.onNodeWithTag("cancel_staff_help").assertDoesNotExist()
        compose.runOnIdle { assertEquals(SafePage.StaffHelp, session.page) }
        click("staff_help_back")
        waitFor("safe_home_content")
        compose.runOnIdle { assertEquals(SafePage.Home, session.page) }
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
        compose.onNodeWithTag("support_walkthrough").assertIsEnabled()
        compose.onNodeWithTag("support_emergency").assertIsEnabled()
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
    fun walkthroughOpensFromSupportMenuWithAutoExpandedSupportMenuAndAllSettingsTabs() {
        val session = setup()
        toggle()
        compose.onNodeWithTag("support_walkthrough").performClick()
        waitFor("walkthrough_screen")
        assertEquals(SafePage.Walkthrough, session.page)
        compose.onNodeWithTag("walkthrough_toc").assertExists()

        compose.onNodeWithTag("walkthrough_toc_staff_help").performScrollTo().performClick()
        waitFor("walkthrough_active_feature")
        assertEquals(SafePage.Home, session.page)
        compose.onNodeWithTag("support_action_list").assertIsDisplayed()
        compose.onNodeWithTag("walkthrough_tour_arrow").assertExists()
        compose.onNodeWithTag("quick_exit").assertIsDisplayed().assertIsEnabled()

        compose.onNodeWithTag("walkthrough_tour_toc").performClick()
        waitFor("walkthrough_toc")
        compose.onNodeWithTag("walkthrough_toc_cover_entry").performScrollTo().performClick()
        waitFor("walkthrough_active_feature")
        assertEquals(SafePage.Settings, session.page)
        compose.onNodeWithTag("settings_tab_profile").assertIsSelected()

        compose.onNodeWithTag("walkthrough_tour_next").performClick()
        compose.onNodeWithTag("settings_tab_password").assertIsSelected()
        compose.onNodeWithTag("walkthrough_tour_next").performClick()
        compose.onNodeWithTag("settings_tab_privacy").assertIsSelected()
        compose.onNodeWithTag("walkthrough_tour_next").performClick()
        compose.onNodeWithTag("settings_tab_permissions").assertIsSelected()

        compose.onNodeWithTag("walkthrough_tour_skip").performClick()
        compose.onNodeWithTag("walkthrough_active_feature").assertDoesNotExist()
    }

    @Test
    fun firstLoginAutoOpensWalkthroughWithoutBlockingQuickExitAndNeverReopens() {
        val session = setup()
        waitFor("walkthrough_active_feature")
        compose.onNodeWithTag("quick_exit").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("walkthrough_tour_skip").assertIsDisplayed().performClick()
        compose.onNodeWithTag("walkthrough_active_feature").assertDoesNotExist()

        click("safe_notes")
        waitFor("new_private_entry")
        compose.onNodeWithTag("walkthrough_active_feature").assertDoesNotExist()

        compose.runOnIdle { session.navigation.openSafePage(SafePage.Documents) }
        compose.waitForIdle()
        compose.onNodeWithTag("walkthrough_active_feature").assertDoesNotExist()

        compose.onNodeWithTag("quick_exit").performClick()
        waitCover()
        unlock()
        compose.onNodeWithTag("walkthrough_active_feature").assertDoesNotExist()
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
        compose.onNodeWithTag("hotline_region")
            .assertTextContains(compose.activity.getString(R.string.support_region_ab))
        compose.onNodeWithTag("hotline_page")
            .performScrollToNode(hasText("Family Violence Info Line"))
        compose.onNodeWithText("Family Violence Info Line").assertIsDisplayed()
        capture("hotline-page.png")
        compose.onNodeWithTag("hotline_page").performScrollToNode(hasTestTag("hotline_region"))
        compose.onNodeWithTag("hotline_region").performClick()
        compose.onNodeWithTag("hotline_region_ON").performScrollTo().performClick()
        compose.onNodeWithTag("hotline_page").performScrollToNode(hasText("1-866-863-0511"))
        compose.onNodeWithText("1-866-863-0511").assertExists()
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
        var locationPermissionGranted = false
        // Supply a deterministic location result; the separate permission-flow test exercises the real Android dialog.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent {
                CompositionLocalProvider(
                    LocalLocationPermissionRequest provides { locationPermissionGranted },
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
        waitFor("hotline_location_error")
        compose.onNodeWithTag("hotline_location_permissions").assertExists()
        compose.runOnIdle { locationPermissionGranted = true }
        compose.onNodeWithTag("hotline_use_device_location").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            runCatching {
                compose.onNodeWithTag("hotline_region")
                    .assertTextContains(compose.activity.getString(R.string.support_region_on))
            }.isSuccess
        }
        compose.onNodeWithTag("hotline_page").performScrollToNode(hasText("1-866-863-0511"))
        compose.onNodeWithText("1-866-863-0511").assertExists()
        compose.runOnIdle {
            assertEquals(
                "Edmonton",
                session.profile!!.address.city
            )
            assertEquals("", session.profile!!.address.province)
        }
        compose.onNodeWithTag("hotline_city").assertDoesNotExist()
        val hotlineList = compose.onNodeWithTag("hotline_page")
        val region = hasTestTag("hotline_region")
        hotlineList.performScrollToNode(region)
        compose.onNode(region).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.support_saved_location)).performClick()
        compose.onNode(region).assertTextContains(compose.activity.getString(R.string.support_region_ab))
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
            compose.onNodeWithTag("support_emergency").assertIsEnabled()
            activate("support_hotlines")
            waitFor("hotline_page")
            activate("support_menu_toggle")
            activate("quick_exit")
            waitCover()
            val returnedToCover = compose.activity.getString(
                R.string.a11y_returned_to_cover,
                session.coverType.localizedTitle(ResourceText(compose.activity.resources)),
            )
            compose.waitUntil(10_000) { returnedToCover in talkBack.announcements }
            compose.onNodeWithContentDescription(returnedToCover).assertDoesNotExist()
            assertTrue(
                "Quick-exit status must not move TalkBack focus",
                talkBack.focused.none { it.contains(returnedToCover) })
            assertNull(session.profile)
            compose.onNodeWithTag("hotline_page").assertDoesNotExist()
        }
    }
}
