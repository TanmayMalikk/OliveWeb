package com.olive.app

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
import com.olive.app.ui.*
import com.olive.app.data.ChatConversation
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import android.app.UiAutomation
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CopyOnWriteArrayList
import android.provider.Settings
import android.content.ComponentName
import android.content.Intent
import org.junit.Assume.assumeTrue

class AccessibilityUiTest:OliveUiTestBase() {
    @Test fun gameCoverGroupsItsTitleAndStatus() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent {
                OliveTheme { com.olive.app.ui.covers.game.GameCover() }
            }
        }
        compose.onNodeWithTag("cover_brand_game",useUnmergedTree=true).assertIsDisplayed()
        compose.onNodeWithTag("game_cover").assertTextContains("Game").assertTextContains("In development")
        compose.onNodeWithTag("game_cover").onChildren().assertCountEquals(0)
    }

    @Test fun pinErrorsAndQuickExitMoveActualTalkBackFocus() {
        register()
        val session=ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val resolver=compose.activity.contentResolver
        val service="com.google.android.marvin.talkback/.TalkBackService"
        val installed=Intent().setComponent(ComponentName.unflattenFromString(service))
        assumeTrue("TalkBack must be installed for this service-level regression",compose.activity.packageManager.resolveService(installed,0)!=null)
        // TalkBack's own first-use permission dialog backgrounds and locks Olive.
        // Resolve that test-environment prompt before enabling the real service.
        val notificationPermission=android.Manifest.permission.POST_NOTIFICATIONS
        val hadNotifications=android.os.Build.VERSION.SDK_INT<33 || compose.activity.packageManager.checkPermission(notificationPermission,"com.google.android.marvin.talkback")==android.content.pm.PackageManager.PERMISSION_GRANTED
        if(!hadNotifications) instrumentation.uiAutomation.grantRuntimePermission("com.google.android.marvin.talkback",notificationPermission)
        val automation=instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val previousServices=Settings.Secure.getString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val previousEnabled=Settings.Secure.getString(resolver,Settings.Secure.ACCESSIBILITY_ENABLED)
        val previousTouch=Settings.Secure.getString(resolver,Settings.Secure.TOUCH_EXPLORATION_ENABLED)
        val focused=CopyOnWriteArrayList<String>()
        automation.setOnAccessibilityEventListener { event ->
            if(event.eventType==AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                focused+=event.contentDescription?.toString().orEmpty()
        }
        try {
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,(previousServices.orEmpty().split(':').filter(String::isNotBlank)+service).distinct().joinToString(":"))
            Settings.Secure.putInt(resolver,Settings.Secure.ACCESSIBILITY_ENABLED,1)
            automation.dropShellPermissionIdentity()
            val manager=compose.activity.getSystemService(AccessibilityManager::class.java)
            // This regression needs the emulator's installed TalkBack service, not just a semantics assertion.
            compose.waitUntil(10_000) { manager.isTouchExplorationEnabled }
            // With touch exploration active, inject semantic accessibility clicks
            // rather than ordinary touch taps that TalkBack interprets as exploration.
            fun find(node:AccessibilityNodeInfo?,label:String):AccessibilityNodeInfo? {
                if(node==null) return null
                if(node.text?.toString()==label || node.contentDescription?.toString()==label) {
                    var actionNode:AccessibilityNodeInfo?=node
                    while(actionNode!=null) {
                        if(actionNode.isClickable) return actionNode
                        actionNode=actionNode.parent
                    }
                }
                for(index in 0 until node.childCount) find(node.getChild(index),label)?.let { return it }
                return null
            }
            fun click(label:String) {
                compose.waitUntil(10_000) { find(automation.rootInActiveWindow,label)!=null }
                assertTrue(find(automation.rootInActiveWindow,label)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }
            instrumentation.runOnMainSync {
                session.interceptSearch("moon garden")
            }
            // Exercise the native actions TalkBack invokes, including repeated/stale clicks.
            repeat(12) { click("9");instrumentation.waitForIdleSync() }
            compose.onNodeWithTag("unlock_pin").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Digits entered: 6"))
            compose.waitUntil(5_000) { find(automation.rootInActiveWindow,"Unlock")?.isEnabled==true }
            val unlock=find(automation.rootInActiveWindow,"Unlock")!!
            repeat(40) { unlock.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            try { compose.waitUntil(10_000) { focused.any { it.contains("Incorrect PIN") } } }
            catch(error:Throwable) {
                throw AssertionError("PIN stress focus: screen=${session.screen}, busy=${session.busy}, blackout=${session.privacyBlackout}, message=${session.message}, events=$focused",error)
            }
            assertEquals(SessionScreen.Pin,session.screen)
            assertNull(session.profile)
            assertTrue(automation.rootInActiveWindow.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                ?.contentDescription?.contains("Incorrect PIN")==true)
            instrumentation.runOnMainSync { session.submitPin("123456") }
            try { compose.waitUntil(10_000) { find(automation.rootInActiveWindow,"Quick exit")!=null } }
            catch(error:Throwable) {
                fun labels(node:AccessibilityNodeInfo?):List<String> = if(node==null) emptyList() else
                    listOf("${node.className}: text=${node.text}, description=${node.contentDescription}, clickable=${node.isClickable}")+ (0 until node.childCount).flatMap { labels(node.getChild(it)) }
                throw AssertionError("screen=${session.screen}, busy=${session.busy}; ${labels(automation.rootInActiveWindow)}",error)
            }
            focused.clear()
            click("Quick exit")
            compose.waitUntil(10_000) { focused.any { it=="Returned to Olive" } }
            assertEquals("Returned to Olive",automation.rootInActiveWindow.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.contentDescription?.toString())
            compose.onNodeWithText("Returned to Olive").assertDoesNotExist()
            assertNull(find(automation.rootInActiveWindow,"Quick exit"))
        } finally {
            automation.setOnAccessibilityEventListener(null)
            automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            Settings.Secure.putString(resolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,previousServices)
            Settings.Secure.putString(resolver,Settings.Secure.ACCESSIBILITY_ENABLED,previousEnabled)
            Settings.Secure.putString(resolver,Settings.Secure.TOUCH_EXPLORATION_ENABLED,previousTouch)
            automation.dropShellPermissionIdentity()
            val restoredAutomation=instrumentation.getUiAutomation(0)
            if(!hadNotifications) restoredAutomation.revokeRuntimePermission("com.google.android.marvin.talkback",notificationPermission)
        }
    }
    @Test fun foodCardDetailsAreMergedWhileFavouriteAndQuantityActionsStayAvailable() {
        register()
        compose.onNodeWithTag("dish_card_bowl").performScrollTo()
            .assertTextContains("Garden harvest bowl",substring=true)
            .assertTextContains("$14.95",substring=true)
            .assertHasClickAction()
        compose.onNodeWithContentDescription("Save Garden harvest bowl").assertHasClickAction()
        compose.onNodeWithTag("dish_card_bowl").performClick()
        compose.onNodeWithText("Add to cart").performScrollTo().performClick()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("Cart").performClick()
        compose.onNodeWithTag("cart_row_bowl").assertTextContains("Garden harvest bowl",substring=true)
        compose.onNodeWithContentDescription("Remove one Garden harvest bowl").assertHasClickAction()
        compose.onNodeWithContentDescription("Add one Garden harvest bowl").assertHasClickAction()
    }
    @Test fun nativeAccessibilityEventsReachTheInteractionObserver() {
        waitFor("new_user")
        val observed=AtomicInteger(0)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val content=compose.activity.findViewById<ViewGroup>(android.R.id.content)
            observeAccessibilityInteractions(content) { observed.incrementAndGet() }
            // Exercise native child-to-parent event delivery without replacing Compose's delegate.
            val host=content.getChildAt(0) as ComposeView
            host.getChildAt(0).sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
        }
        compose.waitUntil(5_000) { observed.get()>0 }
    }
    @Test fun chatTipIsModalAndQuickExitRemainsAvailableInsideIt() {
        register();unlock()
        val session=ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val uid=requireNotNull(session.coverAccountId)
            session.navigate(SafePage.Messages)
            session.openConversation(ChatConversation("a11y-chat","fixture",listOf(uid,"peer"),mapOf(uid to "olive_tester","peer" to "Test Friend")))
        }
        waitFor("chat_quick_exit_hint")
        compose.onNode(isDialog()).assertExists()
        compose.onNodeWithTag("chat_hint_quick_exit").performClick()
        waitCover()
        compose.onNodeWithTag("chat_quick_exit_hint").assertDoesNotExist()
    }
    @Test fun pinActionsAndDigitCountAreSpokenWithoutExposingThePin() {
        register();trigger()
        compose.onNodeWithTag("pin_backspace").assertContentDescriptionEquals("Delete digit")
        compose.onNodeWithTag("unlock_submit").assertContentDescriptionEquals("Unlock").assertIsNotEnabled()
        compose.onNodeWithTag("unlock_pin").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Digits entered: 0"))
        compose.onNodeWithTag("pin_digit_1").performClick()
        compose.onNodeWithTag("pin_digit_2").performClick()
        compose.onNodeWithTag("unlock_pin").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Digits entered: 2"))
        compose.onNodeWithTag("pin_backspace").performClick()
        compose.onNodeWithTag("unlock_pin").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Digits entered: 1"))
        compose.onNodeWithTag("pin_backspace").performClick()
        enterPin("9999")
        waitFor("session_message")
        compose.onNodeWithTag("session_message").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion,LiveRegionMode.Polite))
        enterPin("123456");waitFor("safe_settings")
        compose.onNodeWithTag("quick_exit").performClick();waitCover()
        unlock()
    }

    @Test fun blackoutHidesTheNativeAccessibilityTreeAndRestoresTheCoverAfterwards() {
        register();unlock()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val session=ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        instrumentation.runOnMainSync { session.captureDetected("Screen recording") }
        waitFor("privacy_blackout")
        compose.waitForIdle()
        fun findCompose(view:View):ComposeView? {
            if(view is ComposeView) return view
            if(view is ViewGroup) for(index in 0 until view.childCount) findCompose(view.getChildAt(index))?.let { return it }
            return null
        }
        instrumentation.runOnMainSync {
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS,findCompose(compose.activity.window.decorView)!!.importantForAccessibility)
            session.recordingStopped()
        }
        waitCover();compose.waitForIdle()
        instrumentation.runOnMainSync {
            assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO,findCompose(compose.activity.window.decorView)!!.importantForAccessibility)
        }
    }

    @Test fun iconLabelsUseTheSelectedAppLanguage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent {
                val configuration=android.content.res.Configuration(compose.activity.resources.configuration).apply {
                    setLocale(java.util.Locale.FRENCH)
                }
                val context=compose.activity.createConfigurationContext(configuration)
                OliveTheme { CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides configuration) {
                    LocalizedIcon(Icons.Outlined.Visibility,rememberResourceText()(R.string.ui_show_value,rememberResourceText()(R.string.a11y_password)))
                } }
            }
        }
        val french=android.content.res.Configuration(compose.activity.resources.configuration).apply { setLocale(java.util.Locale.FRENCH) }
        val resources=compose.activity.createConfigurationContext(french).resources
        compose.onNodeWithContentDescription(resources.getString(R.string.ui_show_value,resources.getString(R.string.a11y_password))).assertExists()
    }

    @Test fun onlyUserAccessibilityEventsCountAsActivity() {
        assertTrue(isAccessibilityUserInteraction(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED))
        assertTrue(isAccessibilityUserInteraction(AccessibilityEvent.TYPE_VIEW_CLICKED))
        assertTrue(isAccessibilityUserInteraction(AccessibilityEvent.TYPE_VIEW_TEXT_TRAVERSED_AT_MOVEMENT_GRANULARITY))
        // Incoming messages, animations and automatic scrolling must not keep a private session unlocked.
        assertFalse(isAccessibilityUserInteraction(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED))
        assertFalse(isAccessibilityUserInteraction(AccessibilityEvent.TYPE_VIEW_SCROLLED))
    }

    @Test fun accessibilityLabelsFollowAllSupportedAndroidLocales() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        mapOf("English" to "en","French" to "fr","Spanish" to "es","Arabic" to "ar","Chinese" to "zh-CN").forEach { (name,tag) ->
            val configuration=android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag(tag))
            }
            val expected=context.createConfigurationContext(configuration).getString(R.string.a11y_unlock)
            assertEquals(expected,ResourceText(context.createConfigurationContext(configuration).resources)(R.string.a11y_unlock))
        }
    }
}
