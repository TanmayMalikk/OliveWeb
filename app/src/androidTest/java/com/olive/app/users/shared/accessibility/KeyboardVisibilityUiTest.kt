// Tests cover navigation while the keyboard is open.
package com.olive.app

import android.view.WindowInsets
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.data.ChatConversation
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test

class KeyboardVisibilityUiTest : OliveUiTestBase() {
    private var originalHardwareKeyboardImeSetting = "0"

    // Checks the show software keyboard with hardware input step with the test's sample data.
    @Before
    fun showSoftwareKeyboardWithHardwareInput() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        originalHardwareKeyboardImeSetting = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("settings get secure show_ime_with_hard_keyboard")
        ).bufferedReader().use { it.readText().trim() }.ifBlank { "0" }
        automation.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1").close()
    }

    // Checks the restore software keyboard setting step with the test's sample data.
    @After
    fun restoreSoftwareKeyboardSetting() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("settings put secure show_ime_with_hard_keyboard $originalHardwareKeyboardImeSetting")
            .close()
    }

    // Checks the food and news navigation stay at the bottom while typing step with the test's sample data.
    @Test
    fun foodAndNewsNavigationStayAtTheBottomWhileTyping() {
        register(username = "cover_keyboard")
        val foodBar = compose.onNodeWithTag("cover_navigation").fetchSemanticsNode().boundsInWindow
        focus("food_search")
        val foodWithKeyboard =
            compose.onNodeWithTag("cover_navigation").fetchSemanticsNode().boundsInWindow
        org.junit.Assert.assertEquals(foodBar.top, foodWithKeyboard.top, 1f)
        compose.activity.runOnUiThread { compose.activity.window.insetsController?.hide(WindowInsets.Type.ime()) }
        compose.waitUntil(10_000) {
            compose.activity.window.decorView.rootWindowInsets?.isVisible(
                WindowInsets.Type.ime()
            ) == false
        }
        unlock()
        click("safe_settings")
        click("cover_choice_news")
        compose.onNodeWithTag("quick_exit").performClick()
        waitFor("news_search")
        val newsBar = compose.onNodeWithTag("cover_navigation").fetchSemanticsNode().boundsInWindow
        focus("news_search")
        val newsWithKeyboard =
            compose.onNodeWithTag("cover_navigation").fetchSemanticsNode().boundsInWindow
        org.junit.Assert.assertEquals(newsBar.top, newsWithKeyboard.top, 1f)
    }

    // Checks the focus step with the test's sample data.
    private fun focus(tag: String) {
        // Request focus through semantics, then click the field so the platform creates an input connection.
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        val bounds = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow
        tapOnScreen(bounds.center.x, bounds.center.y)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val focused = compose.activity.currentFocus
            val input =
                compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            if (focused != null) input.showSoftInput(focused, 0)
            compose.activity.window.insetsController?.show(WindowInsets.Type.ime())
        }
        val softwareImeAvailable = runCatching {
            compose.waitUntil(5_000) {
                compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
            }
        }.isSuccess
        org.junit.Assume.assumeTrue(
            "This device does not expose a software IME while a hardware keyboard is connected.",
            softwareImeAvailable,
        )
        compose.waitUntil(10_000) {
            val decor = compose.activity.window.decorView
            val bottom = decor.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
            val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
            val field = node.boundsInWindow
            field.height >= node.layoutInfo.height - 1 && field.top >= 0 && field.bottom <= decor.height - bottom + 1
        }
        compose.onNodeWithTag(tag).assertIsFocused()
    }

    // Checks the credentials stay above keyboard when switching fields and reopening it step with the test's sample data.
    @Test
    fun credentialsStayAboveKeyboardWhenSwitchingFieldsAndReopeningIt() {
        openOnboarding()
        click("new_user")
        focus("password")
        compose.onNodeWithTag("password").performTextInput("example")
        focus("confirm_password")
        compose.onNodeWithTag("confirm_password").performTextInput("example")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.window.insetsController?.hide(WindowInsets.Type.ime())
        }
        compose.waitUntil(10_000) {
            compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == false
        }
        focus("confirm_password")
        focus("username")
        assertTrue(compose.onNodeWithTag("username").fetchSemanticsNode().boundsInWindow.top >= 0)
    }

    // Checks the settings and chat fields stay above the keyboard step with the test's sample data.
    @Test
    fun settingsAndChatFieldsStayAboveTheKeyboard() {
        register(username = "keyboard_tester")
        unlock()
        click("safe_settings")
        focus("current_password")
        focus("new_password")
        focus("confirm_new_password")
        compose.onNodeWithTag("confirm_new_password").performTextInput("example")
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        compose.runOnIdle {
            val uid = requireNotNull(session.coverAccountId)
            session.navigation.openSafePage(SafePage.Messages)
            session.messages.openConversation(
                ChatConversation(
                    "keyboard-chat",
                    "fixture",
                    listOf(uid, "peer"),
                    mapOf(uid to "keyboard_tester", "peer" to "Test Friend")
                )
            )
        }
        waitFor("dismiss_chat_quick_exit_hint")
        compose.onNodeWithTag("dismiss_chat_quick_exit_hint").performClick()
        focus("message_body")
        compose.onNodeWithTag("message_body").performTextInput("A message\nwith another line")
        focus("message_body")
    }
}
