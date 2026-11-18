// Tests TalkBack labels, focus, and live updates across cover and safe-app screens.
package com.olive.app

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.account.AccountCounts
import com.olive.app.ui.users.admin.AccountCountGrid
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AccessibilityUiTest : OliveUiTestBase() {
    @Test
    fun followingUserCoordinatesRefreshWithTheMapFeed() {
        val point = com.olive.app.data.location.MapLocationPoint(
            "map-fixture", "Test map user", 53.5, -113.5, 1000L, true, 6f
        )
        val points = androidx.compose.runtime.mutableStateOf(listOf(point))
        compose.runOnUiThread {
            compose.activity.setContent {
                OliveTheme {
                    com.olive.app.ui.users.user.location.InteractiveLocationMap(
                        points = points.value,
                        ownPointUid = null,
                        detailsOnlyForSelectedPoint = true,
                        userSearchEnabled = true,
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    )
                }
            }
        }
        waitFor("search_user_location")
        click("search_user_location")
        click("location_user_map-fixture")
        compose.onNodeWithTag("followed_user_latitude").assertTextContains("53.5", substring = true)
        compose.runOnIdle {
            points.value = listOf(point.copy(latitude = 53.5123456, longitude = -113.5345678, updatedAt = 2000L))
        }
        compose.onNodeWithTag("followed_user_latitude").assertTextContains("53.5123456", substring = true)
        compose.onNodeWithTag("followed_user_longitude").assertTextContains("-113.5345678", substring = true)
        compose.runOnIdle { points.value = emptyList() }
        compose.onNodeWithTag("followed_user_coordinates").assertDoesNotExist()
    }

    // Checks the admin counts read as one label per card step with the test's sample data.
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

    // Checks the game cover keeps its controls individually accessible step with the test's sample data.
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

    // Checks the pin errors and quick exit use live regions without stealing talk back focus step with the test's sample data.
    @Test
    fun pinErrorsAndQuickExitUseLiveRegionsWithoutStealingTalkBackFocus() {
        register()
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            session.account.interceptSearch("moon garden")
        }
        compose.onNodeWithTag("unlock_pin").performTextReplacement("999999")
        compose.onNodeWithTag("unlock_pin").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        hideKeyboard()
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
        enterPin("123456")
        waitFor("safe_settings")
        compose.onNodeWithTag("quick_exit").performClick()
        waitCover()
        compose.onNodeWithContentDescription(
            compose.activity.getString(
                R.string.a11y_returned_to_cover,
                session.coverType.localizedTitle(ResourceText(compose.activity.resources)),
            ),
        ).assertDoesNotExist()
        compose.onNodeWithTag("quick_exit").assertDoesNotExist()
    }

    // Checks the native accessibility events reach the interaction observer step with the test's sample data.
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

    // Checks the chat tip appears once points to exit and hides the floating menu step with the test's sample data.
    @Test
    fun chatTipAppearsOncePointsToExitAndHidesTheFloatingMenu() {
        register()
        unlock()
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        compose.runOnIdle { session.navigation.openSafePage(SafePage.Messages) }
        waitFor("messages_dms_tab")
        compose.onNodeWithTag("messages_dms_tab").assertIsSelected()
        compose.onNodeWithTag("friend_invite").assertExists()
        click("messages_tickets_tab")
        compose.onNodeWithTag("messages_tickets_tab").assertIsSelected()
        compose.onNodeWithTag("friend_invite").assertDoesNotExist()
        click("messages_dms_tab")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
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

    // Checks the attachment picker moves focus to each new category title step with the test's sample data.
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

    // Checks the pin actions and digit count are spoken without exposing the pin step with the test's sample data.
    @Test
    fun pinUsesNativePasswordInputAndKeepsAttemptFeedbackAccessible() {
        register()
        trigger()
        val field = compose.onNodeWithTag("unlock_pin")
        field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
        compose.onNodeWithTag("unlock_submit").assertIsNotEnabled()
        compose.onNodeWithTag("pin_digit_1").assertDoesNotExist()
        field.performTextReplacement("12")
        compose.onNodeWithTag("unlock_submit").assertIsNotEnabled()
        field.performTextClearance()
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

    // Checks the blackout hides the native accessibility tree and restores the cover afterwards step with the test's sample data.
    @Test
    fun blackoutHidesTheNativeAccessibilityTreeAndRestoresTheCoverAfterwards() {
        register()
        unlock()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        instrumentation.runOnMainSync { session.captureDetected("Screen recording") }
        waitFor("privacy_blackout")
        compose.waitForIdle()
        // Finds the compose needed by this test.
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

    // Checks the icon labels use the selected app language step with the test's sample data.
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

    // Checks the only user accessibility events count as activity step with the test's sample data.
    @Test
    fun onlyUserAccessibilityEventsCountAsActivity() {
        assertTrue(isAccessibilityUserInteraction(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED))
        assertTrue(isAccessibilityUserInteraction(AccessibilityEvent.TYPE_VIEW_CLICKED))
        assertTrue(isAccessibilityUserInteraction(AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY))
        // Incoming messages, animations and automatic scrolling must not keep a private session unlocked.
        assertFalse(isAccessibilityUserInteraction(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertFalse(isAccessibilityUserInteraction(AccessibilityEvent.TYPE_VIEW_SCROLLED))
    }

    // Checks the accessibility labels follow all supported android locales step with the test's sample data.
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
