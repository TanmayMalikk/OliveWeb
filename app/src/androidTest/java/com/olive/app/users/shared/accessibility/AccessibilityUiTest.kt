// Tests TalkBack labels, focus, and live updates across cover and safe-app screens.
package com.olive.app

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.data.ChatConversation
import com.olive.app.data.AccountCounts
import com.olive.app.ui.users.admin.AccountCountGrid
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AccessibilityUiTest : OliveUiTestBase() {
    // returns the admin counts read as one label per card for shared safe app accessibility ui test. The test gives the code a clear input and checks the expected result.
    @Test
    fun adminCountsReadAsOneLabelPerCard() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent {
                AccountCountGrid(AccountCounts(users = 5, safeContacts = 2, staff = 1, admins = 1))
            }
        }
        val label = compose.activity.getString(R.string.ui_users)
        compose.onNodeWithContentDescription("5 $label").assertExists()
        compose.onNodeWithText("5").assertDoesNotExist()
    }

    // Shared safe app accessibility ui test: returns the game cover keeps its controls individually accessible. The test gives the code a clear input and checks the expected result.
    @Test
    fun gameCoverKeepsItsControlsIndividuallyAccessible() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent {
                OliveTheme { com.olive.app.ui.covers.GameCover() }
            }
        }
        compose.onNodeWithTag("cover_brand_game", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Olive Tap").assertExists()
        compose.onNodeWithTag("game_score").assertTextContains("Score: 0")
        compose.onNodeWithTag("game_entry").assertHasClickAction()
        compose.onNodeWithTag("game_olive").assertHasClickAction().performClick()
        compose.onNodeWithTag("game_score").assertTextContains("Score: 1")
    }

    // Shared safe app accessibility ui test: returns the pin errors and quick exit use live regions without stealing talk back focus. The test gives the code a clear input and checks the expected result.
    @Test
    fun pinErrorsAndQuickExitUseLiveRegionsWithoutStealingTalkBackFocus() {
        register()
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            session.account.interceptSearch("moon garden")
        }
        repeat(6) { compose.onNodeWithTag("pin_digit_9").performClick() }
        compose.onNodeWithTag("unlock_pin").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Digits entered: 6")
        )
        compose.onNodeWithTag("unlock_submit").performClick()
        waitFor("session_message")
        compose.onNodeWithTag("session_message")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite
                )
            )
            .assertTextContains("Incorrect PIN", substring = true)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            session.account.submitPin("123456")
        }
        waitFor("safe_settings")
        compose.onNodeWithTag("quick_exit").performClick()
        waitCover()
        compose.onNodeWithContentDescription("Returned to Olive")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite
                )
            )
        compose.onNodeWithTag("quick_exit").assertDoesNotExist()
    }

    // Reports whether food card details are merged while favourite and quantity actions stay available for shared safe app accessibility ui test. The test gives the code a clear input and checks the expected result.
    @Test
    fun foodCardDetailsAreMergedWhileFavouriteAndQuantityActionsStayAvailable() {
        register()
        compose.onNodeWithTag("dish_card_bowl").performScrollTo()
            .assertTextContains("Garden harvest bowl", substring = true)
            .assertTextContains("$14.95", substring = true)
            .assertHasClickAction()
        compose.onNodeWithContentDescription("Save Garden harvest bowl").assertHasClickAction()
        compose.onNodeWithTag("dish_card_bowl").performClick()
        compose.onNodeWithText("Add to cart").performScrollTo().performClick()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Cart").performClick()
        compose.onNodeWithTag("cart_row_bowl")
            .assertTextContains("Garden harvest bowl", substring = true)
        compose.onNodeWithContentDescription("Remove one Garden harvest bowl")
            .assertHasClickAction()
        compose.onNodeWithContentDescription("Add one Garden harvest bowl").assertHasClickAction()
    }

    // returns the native accessibility events reach the interaction observer for shared safe app accessibility ui test. The test gives the code a clear input and checks the expected result.
    @Test
    fun nativeAccessibilityEventsReachTheInteractionObserver() {
        openOnboarding()
        val observed = AtomicInteger(0)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val content = compose.activity.findViewById<ViewGroup>(android.R.id.content)
            observeAccessibilityInteractions(content) { observed.incrementAndGet() }
            // Exercise native child-to-parent event delivery without replacing Compose's delegate.
            val host = content.getChildAt(0) as ComposeView
            host.getChildAt(0)
                .sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
        }
        compose.waitUntil(5_000) { observed.get() > 0 }
    }

    // returns the chat tip appears once points to exit and hides the floating menu for shared safe app accessibility ui test. The test gives the code a clear input and checks the expected result.
    @Test
    fun chatTipAppearsOncePointsToExitAndHidesTheFloatingMenu() {
        register()
        unlock()
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val uid = requireNotNull(session.coverAccountId)
            session.navigation.openSafePage(SafePage.Messages)
            session.messages.openConversation(
                ChatConversation(
                    "a11y-chat",
                    "fixture",
                    listOf(uid, "peer"),
                    mapOf(uid to "olive_tester", "peer" to "Test Friend")
                )
            )
        }
        waitFor("chat_quick_exit_hint")
        compose.onNodeWithTag("support_menu_toggle").assertDoesNotExist()
        compose.onNodeWithTag("chat_hint_quick_exit").assertDoesNotExist()
        compose.onNodeWithTag("dismiss_chat_quick_exit_hint").performClick()
        compose.onNodeWithTag("chat_quick_exit_hint").assertDoesNotExist()
        compose.runOnIdle {
            session.messages.closeConversation()
            val uid = requireNotNull(session.coverAccountId)
            session.messages.openConversation(
                ChatConversation(
                    "a11y-chat",
                    "fixture",
                    listOf(uid, "peer"),
                    mapOf(uid to "olive_tester", "peer" to "Test Friend")
                )
            )
        }
        compose.onNodeWithTag("chat_quick_exit_hint").assertDoesNotExist()
        compose.onNodeWithTag("quick_exit").performClick()
        waitCover()
    }

    // returns the attachment picker moves focus to each new category title for shared safe app accessibility ui test. The test gives the code a clear input and checks the expected result.
    @Test
    fun attachmentPickerMovesFocusToEachNewCategoryTitle() {
        register()
        unlock()
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val uid = requireNotNull(session.coverAccountId)
            session.navigation.openSafePage(SafePage.Messages)
            session.messages.openConversation(
                ChatConversation(
                    "attachment-focus-chat",
                    "fixture",
                    listOf(uid, "peer"),
                    mapOf(uid to "olive_tester", "peer" to "Test Friend")
                )
            )
        }
        waitFor("chat_quick_exit_hint")
        compose.onNodeWithTag("dismiss_chat_quick_exit_hint").performClick()
        compose.onNodeWithTag("attach_private_entry").performClick()
        compose.onNodeWithTag("attachment_picker_title").assertIsFocused()
        compose.onNodeWithTag("attachment_category_notes").performClick()
        val notesTitle = compose.activity.getString(
            R.string.select_attachment_category,
            compose.activity.getString(R.string.private_notes)
        )
        compose.onNodeWithTag("attachment_picker_title")
            .assertTextEquals(notesTitle)
            .assertIsFocused()
    }

    // returns the pin actions and digit count are spoken without exposing the pin for shared safe app accessibility ui test. The test gives the code a clear input and checks the expected result.
    @Test
    fun pinActionsAndDigitCountAreSpokenWithoutExposingThePin() {
        register()
        trigger()
        compose.onNodeWithTag("pin_backspace").assertContentDescriptionEquals("Delete digit")
        compose.onNodeWithTag("unlock_submit").assertContentDescriptionEquals("Unlock")
            .assertIsNotEnabled()
        compose.onNodeWithTag("unlock_pin").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Digits entered: 0"
            )
        )
        compose.onNodeWithTag("pin_digit_1").performClick()
        compose.onNodeWithTag("pin_digit_2").performClick()
        compose.onNodeWithTag("unlock_pin").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Digits entered: 2"
            )
        )
        compose.onNodeWithTag("pin_backspace").performClick()
        compose.onNodeWithTag("unlock_pin").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Digits entered: 1"
            )
        )
        compose.onNodeWithTag("pin_backspace").performClick()
        enterPin("9999")
        waitFor("session_message")
        compose.onNodeWithTag("session_message").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Polite
            )
        )
        enterPin("123456")
        waitFor("safe_settings")
        compose.onNodeWithTag("quick_exit").performClick()
        waitCover()
        unlock()
    }

    // Shared safe app accessibility ui test: returns the blackout hides the native accessibility tree and restores the cover afterwards. The test gives the code a clear input and checks the expected result.
    @Test
    fun blackoutHidesTheNativeAccessibilityTreeAndRestoresTheCoverAfterwards() {
        register()
        unlock()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        instrumentation.runOnMainSync { session.captureDetected("Screen recording") }
        waitFor("privacy_blackout")
        compose.waitForIdle()
        // Finds compose for shared safe app accessibility ui test. The test gives the code a clear input and checks the expected result.
        fun findCompose(view: View): ComposeView? {
            if (view is ComposeView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) findCompose(
                view.getChildAt(
                    index
                )
            )?.let { return it }
            return null
        }
        instrumentation.runOnMainSync {
            assertEquals(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,
                findCompose(compose.activity.window.decorView)!!.importantForAccessibility
            )
            session.recordingStopped()
        }
        waitCover()
        compose.waitForIdle()
        instrumentation.runOnMainSync {
            assertEquals(
                View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,
                findCompose(compose.activity.window.decorView)!!.importantForAccessibility
            )
        }
    }

    // Shared safe app accessibility ui test: returns the icon labels use the selected app language. The test gives the code a clear input and checks the expected result.
    @Test
    fun iconLabelsUseTheSelectedAppLanguage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent {
                val configuration =
                    android.content.res.Configuration(compose.activity.resources.configuration)
                        .apply {
                            setLocale(java.util.Locale.FRENCH)
                        }
                val context = compose.activity.createConfigurationContext(configuration)
                OliveTheme {
                    CompositionLocalProvider(
                        LocalContext provides context,
                        LocalConfiguration provides configuration
                    ) {
                        LocalizedIcon(
                            Icons.Outlined.Visibility,
                            rememberResourceText()(
                                R.string.ui_show_value,
                                rememberResourceText()(R.string.a11y_password)
                            )
                        )
                    }
                }
            }
        }
        val french = android.content.res.Configuration(compose.activity.resources.configuration)
            .apply { setLocale(java.util.Locale.FRENCH) }
        val resources = compose.activity.createConfigurationContext(french).resources
        compose.onNodeWithContentDescription(
            resources.getString(
                R.string.ui_show_value,
                resources.getString(R.string.a11y_password)
            )
        ).assertExists()
    }

    // returns the only user accessibility events count as activity for shared safe app accessibility ui test. The test gives the code a clear input and checks the expected result.
    @Test
    fun onlyUserAccessibilityEventsCountAsActivity() {
        assertTrue(isAccessibilityUserInteraction(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED))
        assertTrue(isAccessibilityUserInteraction(AccessibilityEvent.TYPE_VIEW_CLICKED))
        assertTrue(isAccessibilityUserInteraction(AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY))
        // Incoming messages, animations and automatic scrolling must not keep a private session unlocked.
        assertFalse(isAccessibilityUserInteraction(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertFalse(isAccessibilityUserInteraction(AccessibilityEvent.TYPE_VIEW_SCROLLED))
    }

    // returns the accessibility labels follow all supported android locales for shared safe app accessibility ui test. The test gives the code a clear input and checks the expected result.
    @Test
    fun accessibilityLabelsFollowAllSupportedAndroidLocales() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        mapOf(
            "English" to "en",
            "French" to "fr",
            "Spanish" to "es",
            "Arabic" to "ar",
            "Chinese" to "zh-CN"
        ).forEach { (name, tag) ->
            val configuration =
                android.content.res.Configuration(context.resources.configuration).apply {
                    setLocale(java.util.Locale.forLanguageTag(tag))
                }
            val expected =
                context.createConfigurationContext(configuration).getString(R.string.a11y_unlock)
            assertEquals(
                expected,
                ResourceText(context.createConfigurationContext(configuration).resources)(R.string.a11y_unlock)
            )
        }
    }
}
