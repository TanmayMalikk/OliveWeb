// Tests cover account setup without opening safe-app onboarding.
package com.olive.app

import androidx.compose.ui.test.*

import org.junit.Assert.*
import org.junit.Test

class CoverAccountFlowTest : OliveUiTestBase() {
    @Test
    fun guestDeliveryAreaOpensAddressEditorWithoutStartingOnboarding() {
        waitCover()
        compose.onNodeWithText("DELIVER TO").performClick()
        waitFor("save_address")
        compose.onNodeWithTag("new_user").assertDoesNotExist()
        fill("address_line1", "123 Test Street")
        fill("address_city", "Edmonton")
        fill("address_province", "Alberta")
        fill("address_postal", "T5J 0N3")
        fill("address_country", "Canada")
        click("save_address")
        compose.onNodeWithTag("save_address").assertDoesNotExist()
        compose.onNodeWithText("Edmonton", substring = true).assertExists()
    }

    @Test
    fun coverChoiceSwitchesNewsAndFitnessAndBothReachSafeArea() {
        register(username = "cover_switch_tester")
        unlock()
        click("safe_settings")
        listOf("food", "news", "fitness", "game").forEach {
            compose.onNodeWithTag("cover_choice_icon_$it", useUnmergedTree = true).assertExists()
        }
        click("cover_choice_news")
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
        click("cover_choice_fitness")
        compose.onNodeWithTag("settings_full_name").assertExists()
        compose.onNodeWithTag("quick_exit").performClick()
        waitFor("fitness_home")
        click("fitness_account_settings")
        compose.onNodeWithTag("discreet_cover_olive").performClick()
        waitFor("safe_settings")
        click("sign_out")
        openOnboarding()
        waitFor("returning_user")
    }

    @Test
    fun coverAccountSavesBirthdayAndLocationWithoutExposingPrivateDetails() {
        register(username = "cover_account_tester")
        compose.onNodeWithText("Account").performClick()
        compose.onNodeWithTag("cover_profile_summary").assertTextContains("Test Person")
            .assertTextContains("@cover_account_tester")
        compose.onNodeWithTag("cover_sign_out").assertDoesNotExist()
        compose.onNodeWithTag("cover_profile_summary")
            .assertTextContains("Edmonton", substring = true)
        compose.onNodeWithText("2000-01-02").assertDoesNotExist()
        compose.onNodeWithText("Test Person Causing Harm").assertDoesNotExist()
        compose.onNodeWithText("moon garden").assertDoesNotExist()
        fill("cover_full_name", "Delivery Customer")
        compose.onNodeWithTag("cover_dob_year").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        waitFor("cover_dob_year_1999")
        click("cover_dob_year_1999")
        fill("address_city", "Calgary")
        compose.onNodeWithTag("cover_language").assertDoesNotExist()
        compose.onNodeWithTag("language").assertDoesNotExist()
        val session =
            androidx.lifecycle.ViewModelProvider(compose.activity)[com.olive.app.ui.users.shared.navigation.SessionViewModel::class.java]
        val originalLanguage = session.language
        compose.onNodeWithTag("cover_save_settings").performScrollTo().assertIsEnabled()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        try {
            compose.waitUntil(30_000) { !session.busy && session.coverFullName == "Delivery Customer" }
        } catch (error: Throwable) {
            throw AssertionError(
                "Cover save: screen=${session.screen}, busy=${session.busy}, name=${session.coverFullName}, message=${session.message}\n${
                    compose.onRoot().printToString()
                }", error
            )
        }
        compose.onNodeWithText(compose.activity.getString(R.string.discover))
            .performClick()
        waitCover()
        compose.onNode(hasSetTextAction()).performTextInput("moon garden")
        waitFor("unlock_pin")
        enterPin("123456")
        waitFor("safe_profile")
        click("safe_profile")
        compose.onNodeWithText("Delivery Customer").assertExists()
        org.junit.Assert.assertEquals(originalLanguage, session.profile?.language)
        assertEquals("1999-01-02", session.profile?.dob)
        assertEquals("Calgary", session.profile?.address?.city)
        assertEquals("", session.profile?.address?.line1)
    }
}
