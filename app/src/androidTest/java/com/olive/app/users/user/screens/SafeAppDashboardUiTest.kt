// Tests safe-contact status and dashboard cards for users.
package com.olive.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.data.CoverType
import com.olive.app.data.SafeContact
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import org.junit.Test
import java.io.File

class SafeAppDashboardUiTest : OliveUiTestBase() {
    @Test
    fun trustedPartnerCanBeMarkedAsSafeToStay() {
        register(username = "safe_stay_toggle")
        unlock()
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        compose.runOnIdle {
            session.saveContacts(
                listOf(
                    SafeContact(
                        "trusted_friend",
                        "Trusted Friend",
                        "555-0100"
                    )
                )
            )
        }
        compose.waitUntil(10_000) { !session.busy && session.profile?.contacts?.singleOrNull()?.id == "trusted_friend" }
        compose.runOnIdle { session.navigation.openSafePage(SafePage.Contacts) }
        waitFor("safe_to_stay_trusted_friend")
        compose.onNodeWithTag("safe_to_stay_trusted_friend").assertIsOff().performClick()
        compose.waitUntil(10_000) { !session.busy && session.profile?.contacts?.singleOrNull()?.safeToStay == true }
        compose.onNodeWithTag("safe_to_stay_trusted_friend").assertIsOn()
    }

    @Test
    fun featureCardsAndNewsLocationDialogAreUsable() {
        register(username = "safe_home_polish")
        unlock()
        listOf("safe_notes", "safe_messages", "safe_safety_plan", "safe_documents").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed().assertIsEnabled()
        }
        screenshot("safe-home-polish.png")

        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        compose.runOnIdle {
            session.changeCover(CoverType.NEWS)
            session.navigation.quickExit()
        }
        waitCover()
        waitFor("change_news_location")
        click("change_news_location")
        waitFor("news_location_input")
        compose.onNodeWithTag("news_location_device").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("news_location_cancel").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("news_location_save").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("news_location_input").performTextReplacement("")
        compose.onNodeWithTag("news_location_save").assertIsNotEnabled()
        compose.onNodeWithTag("news_location_input").performTextReplacement("Calgary, Alberta")
        compose.onNodeWithTag("news_location_save").assertIsEnabled()
        screenshot("news-location-polish.png")
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream().use {
            instrumentation.uiAutomation.takeScreenshot()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
