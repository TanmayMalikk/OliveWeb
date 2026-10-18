package com.olive.app

import android.view.WindowInsets
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.data.ChatConversation
import com.olive.app.ui.SafePage
import com.olive.app.ui.SessionViewModel
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardVisibilityUiTest: OliveUiTestBase() {
    @Test fun foodAndNewsNavigationStayAtTheBottomWhileTyping() {
        register(username="cover_keyboard")
        val foodBar=compose.onNodeWithTag("cover_navigation").fetchSemanticsNode().boundsInWindow
        focus("food_search")
        val foodWithKeyboard=compose.onNodeWithTag("cover_navigation").fetchSemanticsNode().boundsInWindow
        org.junit.Assert.assertEquals(foodBar.top,foodWithKeyboard.top,1f)
        compose.activity.runOnUiThread { compose.activity.window.insetsController?.hide(WindowInsets.Type.ime()) }
        compose.waitUntil(10_000) { compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==false }
        unlock();click("safe_settings");click("cover_choice_news")
        compose.onNodeWithTag("quick_exit").performClick();waitFor("news_search")
        val newsBar=compose.onNodeWithTag("cover_navigation").fetchSemanticsNode().boundsInWindow
        focus("news_search")
        val newsWithKeyboard=compose.onNodeWithTag("cover_navigation").fetchSemanticsNode().boundsInWindow
        org.junit.Assert.assertEquals(newsBar.top,newsWithKeyboard.top,1f)
    }
    private fun focus(tag:String) {
        // Requesting focus directly also exercises keyboard-next/TalkBack navigation to an offscreen field.
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.window.insetsController?.show(WindowInsets.Type.ime())
        }
        compose.waitUntil(10_000) {
            compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==true
        }
        compose.waitUntil(10_000) {
            val decor=compose.activity.window.decorView
            val bottom=decor.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
            val node=compose.onNodeWithTag(tag).fetchSemanticsNode()
            val field=node.boundsInWindow
            field.height>=node.layoutInfo.height-1 && field.top>=0 && field.bottom<=decor.height-bottom+1
        }
        compose.onNodeWithTag(tag).assertIsFocused()
    }

    @Test fun credentialsStayAboveKeyboardWhenSwitchingFieldsAndReopeningIt() {
        waitFor("new_user");click("new_user")
        focus("password")
        compose.onNodeWithTag("password").performTextInput("example")
        focus("confirm_password")
        compose.onNodeWithTag("confirm_password").performTextInput("example")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.window.insetsController?.hide(WindowInsets.Type.ime())
        }
        compose.waitUntil(10_000) {
            compose.activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime())==false
        }
        focus("confirm_password")
        focus("username")
        assertTrue(compose.onNodeWithTag("username").fetchSemanticsNode().boundsInWindow.top>=0)
    }

    @Test fun settingsAndChatFieldsStayAboveTheKeyboard() {
        register(username="keyboard_tester");unlock();click("safe_settings")
        focus("current_password")
        focus("new_password")
        focus("confirm_new_password")
        compose.onNodeWithTag("confirm_new_password").performTextInput("example")
        val session=ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        compose.runOnIdle {
            val uid=requireNotNull(session.coverAccountId)
            session.navigate(SafePage.Messages)
            session.openConversation(ChatConversation("keyboard-chat","fixture",listOf(uid,"peer"),mapOf(uid to "keyboard_tester","peer" to "Test Friend")))
        }
        waitFor("dismiss_chat_quick_exit_hint")
        compose.onNodeWithTag("dismiss_chat_quick_exit_hint").performClick()
        focus("message_body")
        compose.onNodeWithTag("message_body").performTextInput("A message\nwith another line")
        focus("message_body")
    }
}
