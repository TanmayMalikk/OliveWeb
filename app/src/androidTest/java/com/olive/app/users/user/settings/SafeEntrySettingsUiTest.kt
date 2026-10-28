// Tests the safe entry settings UI test behavior.
package com.olive.app

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.data.*
import com.olive.app.security.*
import com.olive.app.ui.*
import org.junit.Assert.*
import org.junit.Test

// Groups the data and actions for safe entry settings UI test.
class SafeEntrySettingsUiTest : OliveUiTestBase() {
    private var activeAutomation: android.app.UiAutomation? = null
    private val driver
        get() = activeAutomation?.let { DeviceCredentialTestDriver(compose, it) }
            ?: DeviceCredentialTestDriver(compose)

    private fun activate(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    private fun accessiblePin(pin: String) {
        // The keypad is fixed on screen, outside the scrollable form used by settings.
        pin.forEach { digit ->
            compose.onNodeWithTag("pin_digit_$digit")
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
        }
        compose.onNodeWithTag("unlock_submit")
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    private fun session() = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
    // Sets up.
    private fun setup(deviceLock: Boolean = false): SessionViewModel {
        if (deviceLock) driver.ensurePhonePin()
        openOnboarding()
        val model = session()
        compose.runOnIdle {
            model.chooseNew()
            model.authenticate(
                "entry_tester",
                "test-password-123",
                true
            )
        }
        waitFor("full_name")
        compose.runOnIdle {
            model.saveSetupProfile(
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

    // Sets tings.
    private fun settings(model: SessionViewModel) {
        compose.runOnIdle { model.navigate(SafePage.Settings) }
        waitFor("entry_use_device")
    }

    private fun finished(model: SessionViewModel) {
        compose.waitUntil(20_000) { !model.busy }
    }

    private fun trigger(model: SessionViewModel) {
        compose.runOnIdle { model.quickExit() }
        waitCover()
        compose.runOnIdle { assertTrue(model.interceptSearch("moon garden")) }
    }

    private fun enableDevice(model: SessionViewModel, pin: String = "123456") {
        driver.ensurePhonePin()
        settings(model)
        fill("current_device_pin", pin)
        activate("entry_use_device")
        driver.confirmPhonePin()
        finished(model)
        assertEquals(SafeEntryMethod.DEVICE_LOCK, model.preferredEntryMethod)
    }

    // Saves d PIN matches.
    private fun savedPinMatches(model: SessionViewModel, pin: String): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val entry = EncryptedVault(context, "demo").read().getJSONObject("enrollments")
            .getJSONObject(model.coverAccountId!!)
        return SecretVerifier.matches(pin, entry.getString("pinVerifier"))
    }

    @Test
    fun calculatorCoverAddsBackupPinWithoutChangingPreferredDeviceLock() {
        val model = setup(deviceLock = true)
        assertFalse(model.hasAppPin)
        trigger(model)
        driver.confirmPhonePin()
        waitFor("safe_settings")
        settings(model)

        activate("cover_choice_calculator")
        waitFor("calculator_pin_required")
        assertNotEquals(CoverType.CALCULATOR, model.coverType)
        fill("calculator_new_pin", "123456")
        fill("calculator_confirm_pin", "123456")
        compose.onNodeWithTag("calculator_pin_continue")
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        driver.confirmPhonePin()

        compose.waitUntil(20_000) {
            !model.busy && model.hasAppPin && model.coverType == CoverType.CALCULATOR
        }
        assertTrue(model.hasAppPin)
        assertEquals(SafeEntryMethod.DEVICE_LOCK, model.preferredEntryMethod)
        compose.onNodeWithTag("calculator_pin_required").assertDoesNotExist()

        compose.runOnIdle { model.quickExit() }
        waitFor("calculator_cover")
        "1234".forEach { digit ->
            compose.onNodeWithTag("calc_key_$digit").performClick()
        }
        compose.onNodeWithTag("calc_key_equals").performClick()
        compose.onNodeWithTag("calc_key_equals").performClick()
        compose.waitUntil(20_000) { !model.busy }
        assertEquals(SessionScreen.Cover, model.screen)
        compose.onNodeWithTag("calculator_cover").assertExists()
        compose.onNodeWithTag("unlock_pin").assertDoesNotExist()
    }

    @Test
    fun switchingAndBackupSetupRequireProofAndNeverExposePinDuringDeviceLock() {
        val model = setup(deviceLock = true)
        assertFalse(model.hasAppPin)
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
        assertTrue(model.hasAppPin)
        assertEquals(SafeEntryMethod.DEVICE_LOCK, model.preferredEntryMethod)
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
            assertEquals(SafeEntryMethod.DEVICE_LOCK, model.preferredEntryMethod)
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
            assertEquals(SafeEntryMethod.DEVICE_LOCK, model.preferredEntryMethod)
            assertTrue(savedPinMatches(model, "654321"))
        }
        activeAutomation = null
    }

    @Test
    fun backupPinOpensAutomaticallyWhenPhoneScreenLockIsRemoved() {
        val model = setup()
        enableDevice(model)
        try {
            compose.runOnIdle { model.quickExit() }
            waitCover()
            driver.shell("locksettings clear --old 246810")
            compose.activityRule.scenario.recreate()
            waitCover()
            assertFalse(model.phoneHasLock)
            assertEquals(SafeEntryMethod.DEVICE_LOCK, model.preferredEntryMethod)
            assertTrue(model.canUseAppPin)
            compose.runOnIdle { model.interceptSearch("moon garden") }
            waitFor("unlock_pin")
            enterPin("123456")
            waitFor("safe_settings")
            assertNull(model.deviceLockRequest)
            assertEquals(SafeEntryMethod.DEVICE_LOCK, model.preferredEntryMethod)
        } finally {
            driver.shell("locksettings set-pin 246810")
            compose.runOnIdle { model.refreshDeviceSecurity() }
        }
    }

    @Test
    fun removingPhoneLockWithoutBackupNeverCreatesAnAppPinBypass() {
        val model = setup(deviceLock = true)
        assertFalse(model.hasAppPin)
        try {
            driver.shell("locksettings clear --old 246810")
            compose.activityRule.scenario.recreate()
            waitCover()
            compose.runOnIdle { model.interceptSearch("moon garden") }
            compose.waitUntil(10_000) { model.screen == SessionScreen.Pin }
            compose.onNodeWithTag("pin_digit_1").assertDoesNotExist()
            compose.onNodeWithTag("device_lock_unlock").assertDoesNotExist()
            compose.onNodeWithTag("missing_backup_pin").assertExists()
            assertFalse(model.canUseAppPin)
            compose.runOnIdle { model.submitPin("123456") }
            assertEquals(SessionScreen.Pin, model.screen)
            assertNull(model.profile)
        } finally {
            driver.shell("locksettings set-pin 246810")
        }
    }
}
