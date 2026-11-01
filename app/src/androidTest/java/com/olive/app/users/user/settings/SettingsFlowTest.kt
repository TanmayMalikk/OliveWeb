// Tests user language, trigger, and address settings.
package com.olive.app

import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.security.EncryptedVault

import org.junit.Assert.*
import org.junit.Test

class SettingsFlowTest : OliveUiTestBase() {
    // returns the settings preserve language without apicker and keep trigger and address working for user settings flow test. The test gives the code a clear input and checks the expected result.
    @Test
    fun settingsPreserveLanguageWithoutAPickerAndKeepTriggerAndAddressWorking() {
        register("1234", username = "settings_tester")
        unlock("1234")
        click("safe_settings")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session =
            androidx.lifecycle.ViewModelProvider(compose.activity)[com.olive.app.ui.users.shared.navigation.SessionViewModel::class.java]
        val originalLanguage = session.profile!!.language
        compose.onNodeWithTag("language").assertDoesNotExist()
        fill("settings_situation", "Updated test situation")
        fill("address_line1", "123 Test Street")
        fill("address_province", "Alberta")
        fill("address_postal", "T5J 0N3")
        fill("address_country", "Canada")
        click("save_settings")
        compose.waitUntil(30_000) { !session.busy && session.profile?.situation == "Updated test situation" }
        fill("settings_trigger", "new secret")
        fill(
            "settings_pin",
            "9999"
        )
        click("change_trigger")
        compose.waitUntil(30_000) { !session.busy && session.message?.contains(context.getString(R.string.incorrect_pin)) == true }
        fill("settings_trigger", "new secret")
        fill(
            "settings_pin",
            "1234"
        )
        click("change_trigger")
        compose.waitUntil(30_000) { !session.busy && session.message == context.getString(R.string.trigger_word_changed) }
        compose.onNodeWithTag("quick_exit").performClick()
        waitCover()
        compose.activityRule.scenario.recreate()
        waitCover()
        compose.onNode(hasSetTextAction()).performTextInput("new secret")
        waitFor("unlock_pin")
        enterPin("1234")
        waitFor("safe_settings")
        val vault = EncryptedVault(context, "demo")
        val root = vault.read()
        val uid = root.getString("activeUid")
        val profile = root.getJSONObject("profiles").getJSONObject(uid)
        assertEquals(originalLanguage, profile.getString("language"))
        assertEquals("Updated test situation", profile.getString("situation"))
        assertEquals("123 Test Street", profile.getJSONObject("address").getString("line1"))
        assertEquals(1, root.getJSONObject("attempts").getJSONArray(uid).length())
        click("safe_activity")
        compose.onAllNodesWithTag("settings_history_row").onFirst().assertExists()
        compose.onNodeWithTag("activity_list")
            .performScrollToNode(hasTestTag("trigger_history_row"))
        compose.onAllNodesWithTag("trigger_history_row").onFirst().assertExists()
    }
}
