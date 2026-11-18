// Tests cover account setup without opening safe-app onboarding.
package com.olive.app

import androidx.compose.ui.test.*

import org.junit.Assert.*
import org.junit.Test

class CoverAccountFlowTest : OliveUiTestBase() {
    // Checks the guest delivery area opens address editor without starting onboarding step with the test's sample data.
    @Test
    fun guestDeliveryAreaOpensAddressEditorWithoutStartingOnboarding() {
        waitCover()
        val session = androidx.lifecycle.ViewModelProvider(compose.activity)[
            com.olive.app.ui.users.shared.navigation.SessionViewModel::class.java
        ]
        compose.runOnIdle { session.runtime.state.coverType = com.olive.app.data.account.CoverType.NEWS }
        waitFor("news_cover")
        click("change_news_location")
        fill("news_location_input", "Edmonton")
        click("news_location_save")
        val preferences = compose.activity.getSharedPreferences("daily_digest_preferences", android.content.Context.MODE_PRIVATE)
        compose.waitUntil(10_000) { preferences.getString("custom_location_guest", null) == "Edmonton" }
        compose.onNodeWithTag("news_location_input").assertDoesNotExist()
        compose.onNodeWithTag("new_user").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(session.account.configured)
            assertEquals(com.olive.app.ui.users.shared.navigation.SessionScreen.Cover, session.screen)
        }
    }

    // Checks the cover choice switches news and fitness and both reach safe area step with the test's sample data.
    @Test
    fun coverChoiceSwitchesNewsAndFitnessAndBothReachSafeArea() {
        register(username = "cover_switch_tester")
        unlock()
        click("safe_settings")
        listOf("news", "fitness", "game").forEach {
            compose.onNodeWithTag("cover_choice_icon_$it", useUnmergedTree = true).assertExists()
        }
        click("cover_choice_news")
        click("settings_tab_profile")
        compose.onNodeWithTag("settings_full_name").assertExists()
        compose.onNodeWithTag("quick_exit").performClick()
        waitFor("news_cover")
        compose.onNodeWithTag("cover_brand_news").assertIsDisplayed()
        compose.onNodeWithText("Account").performClick()
        compose.onNodeWithTag("cover_dob").assertExists()
        compose.onNodeWithTag("address_city").assertTextContains("Edmonton")
        compose.onNodeWithText("Today").performClick()
        fill("news_search", "moon garden")
        waitFor("unlock_pin")
        enterPin("123456")
        waitFor("safe_settings")
        click("safe_settings")
        // This flow checks switching covers; the sensor permission flow is tested separately.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .uiAutomation.grantRuntimePermission(
                    compose.activity.packageName,
                    android.Manifest.permission.ACTIVITY_RECOGNITION,
                )
        }
        click("cover_choice_fitness")
        click("settings_tab_profile")
        compose.onNodeWithTag("settings_full_name").assertExists()
        compose.onNodeWithTag("quick_exit").performClick()
        waitFor("fitness_home")
        // The fitness cover moves TalkBack to its page heading after the cover opens.
        compose.onNodeWithTag("fitness_page_title").assertIsFocused()
        click("fitness_account_settings")
        // Re-enter through the same session action used by the olive; the olive gesture is covered by onboarding UI tests.
        compose.runOnIdle {
            androidx.lifecycle.ViewModelProvider(compose.activity)[com.olive.app.ui.users.shared.navigation.SessionViewModel::class.java]
                .account.beginDiscreetEntry()
        }
        waitFor("unlock_pin")
        enterPin("123456")
        waitFor("safe_settings")
        click("sign_out")
        openOnboarding()
        waitFor("returning_user")
    }

    // Checks the cover account saves birthday and location without exposing private details step with the test's sample data.
    @Test
    fun coverAccountSavesBirthdayAndLocationWithoutExposingPrivateDetails() {
        register(username = "cover_account_tester")
        click("fitness_account_settings")
        compose.onNodeWithTag("cover_full_name").assertTextContains("Test Person")
        compose.onNodeWithText("@cover_account_tester").assertExists()
        compose.onNodeWithTag("cover_sign_out").assertDoesNotExist()
        compose.onNodeWithTag("address_city").assertTextContains("Edmonton")
        compose.onNodeWithText("2000-01-02").assertDoesNotExist()
        compose.onNodeWithText("Test Person Causing Harm").assertDoesNotExist()
        compose.onNodeWithText("moon garden").assertDoesNotExist()
        fill("cover_full_name", "Delivery Customer")
        hideKeyboard()
        click("cover_dob_open")
        click("cover_dob_range_2006")
        click("cover_dob_year_1999")
        click("cover_dob_month_1")
        click("cover_dob_day_2")
        fill("address_city", "Calgary")
        hideKeyboard()
        compose.onNodeWithTag("cover_language").assertDoesNotExist()
        compose.onNodeWithTag("language").assertDoesNotExist()
        val session =
            androidx.lifecycle.ViewModelProvider(compose.activity)[com.olive.app.ui.users.shared.navigation.SessionViewModel::class.java]
        val originalLanguage = session.language
        compose.onNodeWithTag("cover_save_settings").performScrollTo().assertIsEnabled()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        // Use the fallback when cover account saves birthday and location without exposing private details is unavailable.
        try {
            compose.waitUntil(30_000) { !session.busy && session.coverFullName == "Delivery Customer" }
        } catch (error: Throwable) {
            throw AssertionError(
                "Cover save: screen=${session.screen}, busy=${session.busy}, name=${session.coverFullName}, message=${session.message}\n${
                    compose.onRoot().printToString()
                }", error
            )
        }
        unlock()
        waitFor("safe_profile")
        click("safe_profile")
        compose.onNodeWithText("Delivery Customer").assertExists()
        org.junit.Assert.assertEquals(originalLanguage, session.profile?.language)
        assertEquals("1999-01-02", session.profile?.dob)
        assertEquals("Calgary", session.profile?.address?.city)
        assertEquals("", session.profile?.address?.line1)
    }
}
