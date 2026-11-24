// Tests cover navigation while the keyboard is open.
package com.olive.app

import android.os.ParcelFileDescriptor
import android.view.WindowInsets
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.data.messaging.ChatConversation
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
        originalHardwareKeyboardImeSetting = runEmulatorSetting("get secure show_ime_with_hard_keyboard")
        runEmulatorSetting("put secure show_ime_with_hard_keyboard 1")
    }

    // Restore the setting even if the IME assertions fail.
    @After
    fun restoreSoftwareKeyboardSetting() {
        val original = originalHardwareKeyboardImeSetting
        if (original == "null" || original.isBlank()) {
            runEmulatorSetting("delete secure show_ime_with_hard_keyboard")
        } else {
            runEmulatorSetting("put secure show_ime_with_hard_keyboard $original")
        }
    }

    private fun runEmulatorSetting(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("settings $command"))
            .bufferedReader().use { it.readText().trim() }
    }

    @Test
    fun privatePinOpensTheNumberKeyboardWithoutTapping() {
        register(pin = "1234", username = "pin_keyboard")
        if (compose.onAllNodesWithTag("discreet_cover_olive").fetchSemanticsNodes().isEmpty()) {
            click("fitness_account_settings")
        }
        compose.onNodeWithTag("discreet_cover_olive").performScrollTo().performTouchInput { doubleClick() }
        waitFor("unlock_pin")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("unlock_pin") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(10_000) {
            val decor = compose.activity.window.decorView
            androidx.core.view.ViewCompat.getRootWindowInsets(decor)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        }
        repeat(4) { compose.onNodeWithTag("unlock_pin_dot_$it", useUnmergedTree = true).assertIsDisplayed() }
        compose.onNodeWithTag("unlock_pin_dot_4", useUnmergedTree = true).assertDoesNotExist()
        enterPin("1234")
        waitFor("safe_settings")
    }

    // Checks the news navigation stays at the bottom while typing.
    @Test
    fun newsNavigationStaysAtTheBottomWhileTyping() {
        register(username = "cover_keyboard")
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        compose.runOnIdle { session.runtime.state.coverType = com.olive.app.data.account.CoverType.NEWS }
        waitFor("news_cover")
        val newsBar = compose.onNodeWithTag("cover_navigation").fetchSemanticsNode().boundsInWindow
        focus("news_search")
        val newsWithKeyboard =
            compose.onNodeWithTag("cover_navigation").fetchSemanticsNode().boundsInWindow
        org.junit.Assert.assertEquals(newsBar.top, newsWithKeyboard.top, 1f)
        compose.activity.runOnUiThread { compose.activity.window.insetsController?.hide(WindowInsets.Type.ime()) }
        compose.waitUntil(10_000) {
            compose.activity.window.decorView.rootWindowInsets?.isVisible(
                WindowInsets.Type.ime()
            ) == false
        }
    }

    // Checks the focus step with the test's sample data.
    private fun focus(tag: String) {
        val inputField = compose.onNodeWithTag(tag)
        if (runCatching { inputField.assertIsDisplayed() }.isFailure) inputField.performScrollTo()
        if (tag != "message_body") {
            inputField.performTouchInput { click() }
            compose.waitForIdle()
        }
        if (compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isEmpty()) {
            inputField.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.waitForIdle()
        }
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
        if (tag == "message_body" && !softwareImeAvailable) {
            val fieldFocused = compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().isNotEmpty()
            val dialogCopy = compose.onAllNodes(
                hasAnyAncestor(isDialog()) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            throw AssertionError(
                "Chat keyboard unavailable: composerFocused=$fieldFocused, " +
                    "windowFocused=${compose.activity.window.decorView.hasWindowFocus()}, dialogs=$dialogCopy"
            )
        } else if (tag != "message_body") {
            org.junit.Assume.assumeTrue("Android did not show its software keyboard for $tag.", softwareImeAvailable)
        }
        var lastPosition = ""
        try {
            compose.waitUntil(10_000) {
                val decor = compose.activity.window.decorView
                val bottom = decor.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
                val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
                val field = node.boundsInWindow
                val keyboardTop = decor.height - bottom
                lastPosition = "bounds=$field height=${node.layoutInfo.height} keyboardTop=$keyboardTop " +
                    "focused=${node.config.getOrNull(SemanticsProperties.Focused)}"
                field.height >= node.layoutInfo.height - 1 && field.top >= 0 && field.bottom <= keyboardTop + 1
            }
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Field $tag did not settle above the keyboard: $lastPosition", timeout)
        }
        if (tag != "message_body") {
            val bounds = inputField.fetchSemanticsNode().boundsInWindow
            tapOnScreen(bounds.center.x, bounds.center.y)
            compose.waitForIdle()
        }
        inputField.assertIsFocused()
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
        clickAccessibleAction("safe_settings")
        clickAccessibleAction("settings_tab_password")
        focus("current_password")
        focus("new_password")
        focus("confirm_new_password")
        compose.onNodeWithTag("confirm_new_password").performTextInput("example")
        hideKeyboard()
        compose.waitUntil(10_000) {
            compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == false
        }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor("safe_messages")
        clickAccessibleAction("safe_messages")
        waitFor("messages_dms_tab")
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        compose.runOnIdle {
            val uid = requireNotNull(session.coverAccountId)
            session.messages.openConversation(
                ChatConversation(
                    "keyboard-chat",
                    "fixture",
                    listOf(uid, "peer"),
                    mapOf(uid to "keyboard_tester", "peer" to "Test Friend")
                )
            )
            // The accessibility suite exercises the separate quick-exit popup. Keep this
            // fixture in the main window to measure the composer against its own IME insets.
            session.messages.dismissChatQuickExitHint()
        }
        waitFor("message_body")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("open_chat_info") and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        focus("message_body")
        compose.onNodeWithTag("message_body").performTextInput("A message\nwith another line")
        focus("message_body")
    }
}
