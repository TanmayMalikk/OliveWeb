// Tests PIN and device-lock entry into the user safe app.
package com.olive.app

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import android.app.KeyguardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.*
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.security.*
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import org.junit.Assert.*
import org.junit.Test
import com.olive.app.data.UserProfile
import com.olive.app.data.DeliveryAddress

class SafeEntryUiTest : OliveUiTestBase() {
    private fun entrySetup() {
        openOnboarding()
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        // Exercise real account/profile persistence while keeping these tests focused on the entry-method screens.
        compose.runOnIdle {
            session.account.chooseNew()
            session.account.authenticate(
                "entry_tester",
                "test-password-123",
                true
            )
        }
        waitFor("full_name")
        compose.runOnIdle {
            session.account.saveSetupProfile(
                UserProfile(
                    "entry_tester",
                    "Entry Tester",
                    "",
                    "",
                    "2000-01-02",
                    "English",
                    "",
                    "Edmonton",
                    InputPolicy.triggerHash("moon garden"),
                    address = DeliveryAddress(city = "Edmonton")
                )
            )
        }
        waitFor("entry_knows_pin_yes")
    }

    @Test
    fun correctPinRunsOliveAnimationBeforeSafeAreaBecomesInteractive() {
        entrySetup()
        chooseAppPin()
        fill("new_pin", "123456")
        fill("confirm_pin", "123456")
        click("pin_setup_submit")
        waitCover()
        trigger()
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        val unlockVersion = session.unlockAnimationVersion
        compose.mainClock.autoAdvance = false
        try {
            enterPin("123456")
            compose.waitUntil(30_000) { session.unlockAnimationVersion > unlockVersion }
            compose.runOnIdle {
                assertEquals(SessionScreen.Safe, session.screen)
                assertTrue(session.unlockAnimationVersion > unlockVersion)
            }
            // Devices with animations disabled may complete the overlay before Compose exposes it.
            val overlay = compose.onAllNodesWithTag("pin_safe_animation", useUnmergedTree = true)
            compose.mainClock.advanceTimeBy(3_000)
            compose.waitForIdle()
            overlay.assertCountEquals(0)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.onNodeWithTag("safe_settings").assertIsDisplayed()
    }

    @Test
    fun safetyAnswerChangesRecommendationAndSeparatePinStillUnlocks() {
        entrySetup()
        compose.onNodeWithTag("entry_choose_pin").assertDoesNotExist()
        compose.onNodeWithTag("entry_knows_pin_no")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsNotSelected()
        click("entry_knows_pin_no")
        compose.onNodeWithTag("entry_knows_pin_no").assertIsSelected()
        compose.onNodeWithTag("entry_knows_pin_yes").assertIsNotSelected()
        compose.onNodeWithTag("entry_recommendation")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite
                )
            )
        compose.onNodeWithTag("entry_choose_device").assertTextContains("Device lock · Recommended")
        compose.onNodeWithTag("new_pin").assertDoesNotExist()
        click("entry_knows_pin_yes")
        compose.onNodeWithTag("entry_choose_pin").assertTextContains("New PIN · Recommended")
        click("entry_choose_pin")
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "New PIN"))
            .assertExists()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.PaneTitle,
                "Protect your safe area"
            )
        ).assertExists()
        click("entry_choose_pin")
        fill("new_pin", "123456")
        fill(
            "confirm_pin",
            "123456"
        )
        click("pin_setup_submit")
        waitCover()
        unlock()
        compose.runOnIdle {
            assertFalse(
                ViewModelProvider(compose.activity)[SessionViewModel::class.java].account.usesDeviceLock
            )
        }
    }

    @Test
    fun deviceLockCancellationDoesNotEnrollOrUnlock() {
        entrySetup()
        click("entry_knows_pin_no")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (!context.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            compose.onNodeWithTag("entry_choose_device").assertIsNotEnabled()
            return
        }
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        click("entry_choose_device")
        compose.waitUntil(15_000) { session.deviceLockRequest != null || !session.busy }
        assertNotNull(session.message, session.deviceLockRequest)
        // Dismiss the real system dialog; no authentication callback is mocked.
        waitForPhonePin()
        shell("input keyevent 4")
        compose.waitUntil(15_000) { !session.busy }
        assertEquals(SessionScreen.DevicePin, session.screen)
        assertFalse(session.account.configured)
        compose.onNodeWithTag("new_pin").assertDoesNotExist()
    }

    @Test
    fun deviceLockCancellationReturnsActualTalkBackFocusAndAllowsRetry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DeviceCredentialTestDriver(compose).ensurePhonePin()
        assertTrue(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        entrySetup()
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        TalkBackTestSession(compose.activity).use { talkBack ->
            compose.waitUntil(10_000) { context.getSystemService(android.view.accessibility.AccessibilityManager::class.java).isTouchExplorationEnabled }
            fun activate(tag: String) {
                compose.onNodeWithTag(tag).performScrollTo()
                    .performSemanticsAction(SemanticsActions.OnClick) { it() }
            }
            activate("entry_knows_pin_no")
            compose.onNodeWithTag("entry_knows_pin_no").assertIsSelected()
            repeat(2) {
                talkBack.focused.clear()
                activate("entry_choose_device")
                compose.waitUntil(20_000) {
                    val root = talkBack.automation.rootInActiveWindow
                    root != null && root.packageName?.toString() != "com.olive.app"
                }
                talkBack.automation.waitForIdle(1_000, 5_000)
                talkBack.shell("input keyevent 4")
                compose.waitUntil(15_000) { !session.busy }
                val cancellation = context.getString(R.string.entry_auth_cancelled)
                compose.onNodeWithTag("session_message").assertTextContains(cancellation)
                    .assert(
                        SemanticsMatcher.expectValue(
                            SemanticsProperties.LiveRegion,
                            LiveRegionMode.Polite
                        )
                    )
                assertTrue(
                    "Cancellation must be announced without moving TalkBack focus",
                    talkBack.focused.none { it == cancellation })
                assertEquals(SessionScreen.DevicePin, session.screen)
                assertFalse(session.account.configured)
            }
            // A cancelled OS prompt must leave both onboarding choices accessible.
            compose.onNodeWithTag("entry_choose_pin").assertIsEnabled()
            compose.onNodeWithTag("entry_choose_device").assertIsEnabled()
        }
    }

    @Test
    fun androidKeystoreRefusesUnauthenticatedOperation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        if (!context.getSystemService(KeyguardManager::class.java).isDeviceSecure) return
        val challenge = DeviceLockKey("instrumented").prepare("unauthenticated-key-test", null)
        try {
            val cipher = challenge.promptCipher ?: challenge.legacyCipher()
            try {
                challenge.finish(cipher)
                fail("An authentication-required key must not encrypt without Android authentication")
            } catch (_: java.security.GeneralSecurityException) { /* Expected: Keystore denied the operation. */
            }
        } finally {
            challenge.close()
        }
    }

    @Test
    fun deviceLockEnrollmentAndEveryReentryRequireAndroidAuthentication() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DeviceCredentialTestDriver(compose).ensurePhonePin()
        assertTrue(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        entrySetup()
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        click("entry_knows_pin_no")
        click("entry_choose_device")
        confirmPhonePin()
        waitCover()
        compose.activityRule.scenario.recreate()
        waitCover()
        assertTrue(session.account.usesDeviceLock)
        val saved = EncryptedVault(context, "demo").read().getJSONObject("enrollments")
            .getJSONObject(session.coverAccountId!!)
        assertFalse(saved.has("pinVerifier"))
        compose.onNode(hasSetTextAction()).performTextInput("moon garden")
        confirmPhonePin()
        waitFor("safe_settings")
        compose.onNodeWithTag("quick_exit").performClick()
        waitCover()
        compose.onNode(hasSetTextAction()).performTextInput("moon garden")
        compose.waitUntil(15_000) { session.deviceLockRequest != null }
        waitForPhonePin()
        shell("input keyevent 4")
        compose.waitUntil(15_000) { !session.busy }
        assertEquals(SessionScreen.Pin, session.screen)
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, "Device lock"))
            .assertExists()
        compose.runOnIdle { session.account.submitPin("123456") }
        assertEquals(SessionScreen.Pin, session.screen)
        compose.onNodeWithTag("pin_digit_1").assertDoesNotExist()
        click("device_lock_unlock")
        waitForPhonePin()
        val abandoned = session.deviceLockRequest!!
        shell("input keyevent 3")
        compose.waitUntil(15_000) { session.screen == SessionScreen.Cover }
        shell("am start -n com.olive.app/.OliveActivity")
        waitCover()
        abandoned.complete(abandoned.challenge.promptCipher)
        assertEquals(SessionScreen.Cover, session.screen)
    }

    private fun confirmPhonePin() = DeviceCredentialTestDriver(compose).confirmPhonePin()
    private fun waitForPhonePin() = DeviceCredentialTestDriver(compose).waitForPhonePin()
    private fun shell(command: String) = DeviceCredentialTestDriver(compose).shell(command)
}
