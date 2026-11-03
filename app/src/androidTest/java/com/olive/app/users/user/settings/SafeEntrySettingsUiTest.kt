// Tests changing PIN and device-lock settings for users.
package com.olive.app

import com.olive.app.ui.users.shared.navigation.*

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.data.*
import com.olive.app.security.*
import org.junit.Assert.*
import org.junit.Test

class SafeEntrySettingsUiTest : OliveUiTestBase() {
    private var activeAutomation: android.app.UiAutomation? = null
    private val driver
        get() = activeAutomation?.let { DeviceCredentialTestDriver(compose, it) }
            ?: DeviceCredentialTestDriver(compose)

    // User safe entry settings ui test: returns the activate. The test gives the code a clear input and checks the expected result.
    private fun activate(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    // returns the accessible pin for user safe entry settings ui test. The test gives the code a clear input and checks the expected result.
    private fun accessiblePin(pin: String) {
        // The keypad is fixed on screen, outside the scrollable form used by settings.
        pin.forEach { digit ->
            compose.onNodeWithTag("pin_digit_$digit")
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
        }
        compose.onNodeWithTag("unlock_submit")
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    // User safe entry settings ui test: returns the session. The test gives the code a clear input and checks the expected result.
    private fun session() = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
    // returns the setup for user safe entry settings ui test. The test gives the code a clear input and checks the expected result.
    private fun setup(deviceLock: Boolean = false): SessionViewModel {
        if (deviceLock) driver.ensurePhonePin()
        openOnboarding()
        val model = session()
        compose.runOnIdle {
            model.account.chooseNew()
            model.account.authenticate(
                "entry_tester",
                "test-password-123",
                true
            )
        }
        waitFor("full_name")
        compose.runOnIdle {
            model.account.saveSetupProfile(
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
        // Only continue with setup when device lock.
        if (deviceLock) {
            activate("entry_knows_pin_no")
            activate("entry_choose_device")
            driver.confirmPhonePin()
            waitCover()
        } else {
            chooseAppPin()
            fill("new_pin", "123456")
            fill(
                "confirm_pin",
                "123456"
            )
            activate("pin_setup_submit")
            waitCover()
            unlock()
        }
        return model
    }

    // returns the settings for user safe entry settings ui test. The test gives the code a clear input and checks the expected result.
    private fun settings(model: SessionViewModel) {
        compose.runOnIdle { model.navigation.openSafePage(SafePage.Settings) }
        waitFor("entry_use_device")
    }

    // User safe entry settings ui test: returns the finished. The test gives the code a clear input and checks the expected result.
    private fun finished(model: SessionViewModel) {
        compose.waitUntil(20_000) { !model.busy }
    }

    // User safe entry settings ui test: returns the trigger. The test gives the code a clear input and checks the expected result.
    private fun trigger(model: SessionViewModel) {
        compose.runOnIdle { model.navigation.quickExit() }
        waitCover()
        compose.runOnIdle { assertTrue(model.account.interceptSearch("moon garden")) }
    }

    // User safe entry settings ui test: returns the enable device. The test gives the code a clear input and checks the expected result.
    private fun enableDevice(model: SessionViewModel, pin: String = "123456") {
        driver.ensurePhonePin()
        settings(model)
        fill("current_device_pin", pin)
        activate("entry_use_device")
        driver.confirmPhonePin()
        finished(model)
        assertEquals(SafeEntryMethod.DEVICE_LOCK, model.account.preferredEntryMethod)
    }

    // returns the saved pin matches for user safe entry settings ui test. The test gives the code a clear input and checks the expected result.
    private fun savedPinMatches(model: SessionViewModel, pin: String): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EncryptedVault(context, "demo").read().getJSONObject("enrollments")
            .getJSONObject(model.coverAccountId!!)
        return SecretVerifier.matches(pin, entry.getString("pinVerifier"))
    }

    // returns the switching and backup setup require proof and never expose pin during device lock for user safe entry settings ui test. The test gives the code a clear input and checks the expected result.
    @Test
    fun switchingAndBackupSetupRequireProofAndNeverExposePinDuringDeviceLock() {
        val model = setup(deviceLock = true)
        assertFalse(model.account.hasAppPin)
        trigger(model)
        driver.confirmPhonePin()
        waitFor("safe_settings")
        settings(model)
        compose.onNodeWithTag("entry_use_pin").assertDoesNotExist()
        fill("new_device_pin", "123456")
        fill(
            "confirm_new_device_pin",
            "123456"
        )
        activate("entry_save_backup")
        driver.confirmPhonePin()
        finished(model)
        assertTrue(model.account.hasAppPin)
        assertEquals(SafeEntryMethod.DEVICE_LOCK, model.account.preferredEntryMethod)
        TalkBackTestSession(compose.activity).use { talkBack ->
            activeAutomation = talkBack.automation
            val manager =
                InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            compose.waitUntil(10_000) { manager.isTouchExplorationEnabled }
            // Cancelling fresh authentication leaves the old backup PIN and preference intact.
            fill("new_device_pin", "654321")
            fill(
                "confirm_new_device_pin",
                "654321"
            )
            activate("entry_save_backup")
            driver.waitForPhonePin()
            driver.shell("input keyevent 4")
            finished(model)
            val cancelled =
                InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.entry_settings_cancelled)
            // Android closes its credential window before Compose receives and draws the cancellation result.
            // Wait for that result instead of racing the system-window transition.
            waitFor("session_message")
            compose.onNodeWithTag("session_message").assertTextContains(cancelled)
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.LiveRegion,
                        LiveRegionMode.Polite
                    )
                )
            assertTrue(
                "Status announcements must preserve the current TalkBack focus",
                talkBack.focused.none { it.contains(cancelled) })
            assertTrue(savedPinMatches(model, "123456"))
            assertEquals(SafeEntryMethod.DEVICE_LOCK, model.account.preferredEntryMethod)
            fill("new_device_pin", "654321")
            fill(
                "confirm_new_device_pin",
                "654321"
            )
            activate("entry_save_backup")
            driver.confirmPhonePin()
            finished(model)
            assertTrue(savedPinMatches(model, "654321"))
            trigger(model)
            driver.waitForPhonePin()
            driver.shell("input keyevent 4")
            finished(
                model
            )
            compose.onNodeWithTag("pin_digit_1").assertDoesNotExist()
            activate("use_backup_pin")
            waitFor("unlock_pin")
            accessiblePin("654321")
            waitFor("safe_settings")
            assertEquals(SafeEntryMethod.DEVICE_LOCK, model.account.preferredEntryMethod)
            assertTrue(savedPinMatches(model, "654321"))
        }
        activeAutomation = null
    }

    // Inputs: device lock plus a backup app PIN, followed by removing the emulator lock. Expected: the backup PIN still opens the safe app without changing the preferred method.
    @Test
    fun backupPinOpensAutomaticallyWhenPhoneScreenLockIsRemoved() {
        driver.ensurePhonePin()
        val model = setup()
        enableDevice(model)
        // A backup pin opens automatically when phone screen lock is removed error is handled here instead of closing the app.
        try {
            compose.runOnIdle { model.navigation.quickExit() }
            waitCover()
            driver.shell("locksettings clear --old 246810")
            compose.activityRule.scenario.recreate()
            waitCover()
            assertFalse(model.account.phoneHasLock)
            assertEquals(SafeEntryMethod.DEVICE_LOCK, model.account.preferredEntryMethod)
            assertTrue(model.account.canUseAppPin)
            compose.runOnIdle { model.account.interceptSearch("moon garden") }
            waitFor("unlock_pin")
            enterPin("123456")
            waitFor("safe_settings")
            assertNull(model.deviceLockRequest)
            assertEquals(SafeEntryMethod.DEVICE_LOCK, model.account.preferredEntryMethod)
        } finally {
            driver.shell("locksettings set-pin 246810")
            compose.runOnIdle { model.account.refreshDeviceSecurity() }
        }
    }

    // Inputs: device lock without a backup app PIN, followed by removing the emulator lock. Expected: no number can become an automatic app-PIN bypass.
    @Test
    fun removingPhoneLockWithoutBackupNeverCreatesAnAppPinBypass() {
        driver.ensurePhonePin()
        val model = setup(deviceLock = true)
        assertFalse(model.account.hasAppPin)
        // A removing phone lock without backup never creates an app pin bypass error is handled here instead of closing the app.
        try {
            driver.shell("locksettings clear --old 246810")
            compose.activityRule.scenario.recreate()
            waitCover()
            compose.runOnIdle { model.account.interceptSearch("moon garden") }
            compose.waitUntil(10_000) { model.screen == SessionScreen.Pin }
            compose.onNodeWithTag("pin_digit_1").assertDoesNotExist()
            compose.onNodeWithTag("device_lock_unlock").assertDoesNotExist()
            compose.onNodeWithTag("missing_backup_pin").assertExists()
            assertFalse(model.account.canUseAppPin)
            compose.runOnIdle { model.account.submitPin("123456") }
            assertEquals(SessionScreen.Pin, model.screen)
            assertNull(model.profile)
        } finally {
            driver.shell("locksettings set-pin 246810")
        }
    }

}
