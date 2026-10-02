package com.olive.app

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.security.EncryptedVault
import com.olive.app.security.child
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SettingsFlowTest : OliveUiTestBase() {
    @Test fun settingsLanguageTriggerAndAddressPersist() {
        register("1234",username="settings_tester"); unlock("1234"); click("safe_settings")
        fill("settings_situation","Updated test situation")
        click("language"); compose.onNodeWithText("Français").performClick(); click("save_settings")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Paramètres").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Enregistrer les paramètres").assertExists()
        fill("settings_trigger","new secret"); fill("settings_pin","9999"); click("change_trigger")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("NIP incorrect.",substring=true).fetchSemanticsNodes().isNotEmpty() }
        fill("settings_trigger","new secret"); fill("settings_pin","1234"); click("change_trigger")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Mot déclencheur modifié.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("quick_exit").performClick(); waitCover()
        compose.onNodeWithText("Découvrir").assertExists()
        compose.activityRule.scenario.recreate(); waitCover()
        compose.onNodeWithText("Découvrir").assertExists()
        compose.onNode(hasSetTextAction()).performTextInput("new secret"); waitFor("unlock_pin")
        enterPin("1234"); waitFor("safe_settings")
        val vault=EncryptedVault(InstrumentationRegistry.getInstrumentation().targetContext,"demo")
        val root=vault.read();val uid=root.getString("activeUid")
        val profile=root.getJSONObject("profiles").getJSONObject(uid)
        assertEquals("French",profile.getString("language"))
        assertEquals("Updated test situation",profile.getString("situation"))
        assertEquals("123 Test Street",profile.getJSONObject("address").getString("line1"))
        assertEquals(1,root.getJSONObject("attempts").getJSONArray(uid).length())
        click("safe_activity")
        compose.onAllNodesWithTag("settings_history_row").onFirst().assertExists()
        compose.onNodeWithTag("activity_list").performScrollToNode(hasTestTag("trigger_history_row"))
        compose.onAllNodesWithTag("trigger_history_row").onFirst().assertExists()
    }
}
