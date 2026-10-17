package com.olive.app

import android.app.KeyguardManager
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.security.*
import com.olive.app.ui.SessionScreen
import com.olive.app.ui.SessionViewModel
import org.junit.Assert.*
import org.junit.Test
import android.view.accessibility.AccessibilityNodeInfo
import android.os.ParcelFileDescriptor
import com.olive.app.data.UserProfile
import com.olive.app.data.DeliveryAddress

class SafeEntryUiTest : OliveUiTestBase() {
    private fun entrySetup() {
        waitFor("new_user")
        val session=ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        // Exercise real account/profile persistence while keeping these tests focused on the entry-method screens.
        compose.runOnIdle { session.chooseNew();session.authenticate("entry_tester","test-password-123",true) }
        waitFor("full_name")
        compose.runOnIdle { session.saveSetupProfile(UserProfile("entry_tester","Entry Tester","","","2000-01-02",
            "English","","Edmonton",InputPolicy.triggerHash("moon garden"),address=DeliveryAddress(city="Edmonton"))) }
        waitFor("entry_knows_pin_yes")
    }

    @Test fun safetyAnswerChangesRecommendationAndSeparatePinStillUnlocks() {
        entrySetup()
        compose.onNodeWithTag("entry_choose_pin").assertDoesNotExist()
        click("entry_knows_pin_no")
        compose.onNodeWithTag("entry_choose_device").assertTextContains("Device lock · Recommended")
        compose.onNodeWithTag("new_pin").assertDoesNotExist()
        click("entry_knows_pin_yes")
        compose.onNodeWithTag("entry_choose_pin").assertTextContains("New PIN · Recommended")
        click("entry_choose_pin")
        fill("new_pin","123456");fill("confirm_pin","123456");click("pin_setup_submit");waitCover()
        unlock()
        compose.runOnIdle { assertFalse(ViewModelProvider(compose.activity)[SessionViewModel::class.java].usesDeviceLock) }
    }

    @Test fun deviceLockCancellationDoesNotEnrollOrUnlock() {
        entrySetup();click("entry_knows_pin_no")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        if(!context.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            compose.onNodeWithTag("entry_choose_device").assertIsNotEnabled()
            return
        }
        val session=ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        click("entry_choose_device")
        compose.waitUntil(15_000) { session.deviceLockRequest!=null || !session.busy }
        assertNotNull(session.message,session.deviceLockRequest)
        // Dismiss the real system dialog; no authentication callback is mocked.
        waitForPhonePin()
        shell("input keyevent 4")
        compose.waitUntil(15_000) { !session.busy }
        assertEquals(SessionScreen.DevicePin,session.screen)
        assertFalse(session.configured)
        compose.onNodeWithTag("new_pin").assertDoesNotExist()
    }

    @Test fun androidKeystoreRefusesUnauthenticatedOperation() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        if(!context.getSystemService(KeyguardManager::class.java).isDeviceSecure) return
        val challenge=DeviceLockKey("instrumented").prepare("unauthenticated-key-test",null)
        try {
            val cipher=challenge.promptCipher ?: challenge.legacyCipher()
            try {
                challenge.finish(cipher)
                fail("An authentication-required key must not encrypt without Android authentication")
            } catch(_:java.security.GeneralSecurityException) { /* Expected: Keystore denied the operation. */ }
        } finally { challenge.close() }
    }

    @Test fun deviceLockEnrollmentAndEveryReentryRequireAndroidAuthentication() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        org.junit.Assume.assumeTrue(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        entrySetup()
        val session=ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        click("entry_knows_pin_no");click("entry_choose_device")
        confirmPhonePin()
        waitCover()
        compose.activityRule.scenario.recreate();waitCover()
        assertTrue(session.usesDeviceLock)
        val saved=EncryptedVault(context,"demo").read().getJSONObject("enrollments").getJSONObject(session.coverAccountId!!)
        assertFalse(saved.has("pinVerifier"))
        compose.onNode(hasSetTextAction()).performTextInput("moon garden")
        confirmPhonePin();waitFor("safe_settings")
        compose.onNodeWithTag("quick_exit").performClick();waitCover()
        compose.onNode(hasSetTextAction()).performTextInput("moon garden")
        compose.waitUntil(15_000) { session.deviceLockRequest!=null }
        waitForPhonePin()
        shell("input keyevent 4")
        compose.waitUntil(15_000) { !session.busy }
        assertEquals(SessionScreen.Pin,session.screen)
        compose.runOnIdle { session.submitPin("123456") }
        assertEquals(SessionScreen.Pin,session.screen)
        compose.onNodeWithTag("pin_digit_1").assertDoesNotExist()
        click("device_lock_unlock");waitForPhonePin()
        val abandoned=session.deviceLockRequest!!
        shell("input keyevent 3")
        compose.waitUntil(15_000) { session.screen==SessionScreen.Cover }
        shell("am start -n com.olive.app/.MainActivity")
        waitCover()
        abandoned.complete(abandoned.challenge.promptCipher)
        assertEquals(SessionScreen.Cover,session.screen)
    }

    private fun confirmPhonePin() {
        waitForPhonePin()
        // This PIN belongs only to the disposable emulator used by this test run.
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        if(findNode(automation.rootInActiveWindow) { it.viewIdResourceName=="cred_pin_pad" }!=null) {
            "246810".forEach { digit ->
                val key=findNode(automation.rootInActiveWindow) { it.className?.toString()=="android.widget.Button" && it.text?.toString()==digit.toString() }
                assertTrue("Android PIN key $digit unavailable",key?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true)
                automation.waitForIdle(150,3_000)
            }
            compose.waitUntil(5_000) {
                findNode(automation.rootInActiveWindow) { it.viewIdResourceName=="com.android.systemui:id/key_enter" && it.isEnabled }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
            }
        } else { shell("input text 246810");shell("input keyevent 66") }
    }

    private fun waitForPhonePin() {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.waitUntil(20_000) {
            val root=automation.rootInActiveWindow
            // The cover's search box is also an EditText; only accept the system credential window.
            if(root==null || root.packageName?.toString()=="com.olive.app") return@waitUntil false
            val field=findNode(root) { it.className?.toString()=="android.widget.EditText" || it.viewIdResourceName=="cred_pin_pad" }
            if(field!=null) true else {
                findNode(root) { it.text?.toString()?.contains("Use PIN",ignoreCase=true)==true }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                false
            }
        }
        // SystemUI creates keypad nodes before its opening animation starts accepting PIN input.
        automation.waitForIdle(1_000,5_000)
    }


    private fun findNode(node:AccessibilityNodeInfo?,predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo? {
        if(node==null) return null
        if(predicate(node)) return node
        for(index in 0 until node.childCount) findNode(node.getChild(index),predicate)?.let { return it }
        return null
    }

    private fun shell(command:String) {
        val fd=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
    }
}
