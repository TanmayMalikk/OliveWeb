package com.olive.app

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.security.EncryptedVault
import com.olive.app.security.child
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CoverAccountFlowTest : OliveUiTestBase() {
    @Test fun coverChoiceSwitchesNewsAndCalculatorAndBothReachSafeArea() {
        register(username="cover_switch_tester")
        unlock()
        click("safe_settings")
        click("cover_choice_news")
        compose.onNodeWithTag("settings_full_name").assertExists()
        compose.onNodeWithTag("quick_exit").performClick()
        waitFor("news_cover")
        fill("news_search","moon garden")
        waitFor("unlock_pin")
        enterPin("123456"); waitFor("safe_settings")
        click("safe_settings")
        click("cover_choice_calculator")
        compose.onNodeWithTag("settings_full_name").assertExists()
        compose.onNodeWithTag("quick_exit").performClick()
        waitFor("calculator_cover")
        "123456".forEach { compose.onNodeWithTag("calc_key_$it").performClick() }
        compose.onNodeWithTag("calc_key_equals").performClick()
        compose.onNodeWithTag("calc_key_equals").performClick()
        waitFor("safe_settings")
        click("sign_out")
        waitFor("returning_user")
    }

    @Test fun coverAccountShowsOnlyDeliveryProfileAndChangesLanguage() {
        register(username="cover_account_tester")
        compose.onNodeWithText("Account").performClick()
        compose.onNodeWithTag("cover_profile_name").assertTextEquals("Test Person")
        compose.onNodeWithTag("cover_profile_username").assertTextEquals("@cover_account_tester")
        compose.onNodeWithTag("cover_sign_out").assertDoesNotExist()
        compose.onNodeWithTag("cover_profile_address").assert(hasText("Edmonton",substring=true))
        compose.onNodeWithText("2000-01-02").assertDoesNotExist()
        compose.onNodeWithText("Test Person Causing Harm").assertDoesNotExist()
        compose.onNodeWithText("moon garden").assertDoesNotExist()
        fill("cover_full_name","Delivery Customer")
        click("cover_language"); compose.onNodeWithText("Français").performClick()
        click("cover_save_settings")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Paramètres du compte enregistrés.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Compte").assertExists()
        compose.onNodeWithText("Découvrir").performClick(); waitCover()
        compose.onNode(hasSetTextAction()).performTextInput("moon garden"); waitFor("unlock_pin")
        enterPin("123456"); waitFor("safe_profile")
        click("safe_profile")
        compose.onNodeWithText("Delivery Customer").assertExists()
        compose.onNodeWithText("Français").assertExists()
    }
}
