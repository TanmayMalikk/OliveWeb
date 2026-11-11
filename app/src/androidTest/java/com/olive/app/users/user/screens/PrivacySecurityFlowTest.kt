// Tests stale actions and PIN retries cannot unlock the safe app.
package com.olive.app

import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.data.account.AccountRole
import com.olive.app.security.EncryptedVault
import com.olive.app.security.child
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PrivacySecurityFlowTest : OliveUiTestBase() {
    // Checks the stale account actions and rapid pin attempts cannot unlock step with the test's sample data.
    @Test
    fun staleAccountActionsAndRapidPinAttemptsCannotUnlock() {
        register(pin = "739182", username = "strict_pin_tester")
        val session =
            androidx.lifecycle.ViewModelProvider(compose.activity)[com.olive.app.ui.users.shared.navigation.SessionViewModel::class.java]

        // Checks the assert locked step with the test's sample data.
        fun assertLocked() {
            assertNotEquals(com.olive.app.ui.users.shared.navigation.SessionScreen.Safe, session.screen)
            assertEquals(AccountRole.USER, session.accountRole)
            assertNull(session.profile)
        }

        // These calls represent accessibility callbacks from old onboarding nodes.
        fun staleActions() {
            AccountRole.entries.forEach(session.account::selectRole)
            session.account.openAccountSetup()
            session.account.chooseNew()
            session.account.chooseReturning()
            session.account.createDevicePin("999999", "999999")
            session.navigation.openSafePage(com.olive.app.ui.users.shared.navigation.SafePage.Notes)
        }
        compose.runOnIdle {
            staleActions()
            assertLocked()
            session.account.interceptSearch("moon garden")
        }
        val obsoleteChallenge = session.pinChallengeVersion
        repeat(5) { index ->
            compose.runOnIdle {
                staleActions()
                // Many queued callbacks must start only one verification operation.
                repeat(100) { session.account.submitPin("999999") }
            }
            compose.waitUntil(30_000) { !session.busy }
            compose.runOnIdle {
                assertLocked()
                if (index < 4) {
                    val remaining = 4 - index
                    val attemptLabel = if (remaining == 1) "attempt" else "attempts"
                    assertEquals(
                        "Incorrect PIN. $remaining $attemptLabel remaining.",
                        session.message
                    )
                } else assertEquals(com.olive.app.ui.users.shared.navigation.SessionScreen.Cover, session.screen)
            }
        }
        compose.runOnIdle {
            session.account.interceptSearch("moon garden")
            session.account.submitPin("739182", obsoleteChallenge)
            assertFalse(session.busy)
            assertLocked()
            // Even a correct verification cannot complete after exit/re-entry.
            session.account.submitPin("739182")
            session.navigation.quickExit()
            session.account.interceptSearch("moon garden")
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertLocked()
            session.account.submitPin("739182")
        }
        waitFor("safe_settings")
        compose.runOnIdle { assertNotNull(session.profile) }
        compose.onNodeWithTag("quick_exit").performClick()
        waitCover()
        compose.runOnIdle {
            staleActions()
            session.navigation.quickExit()
            assertLocked()
        }
    }

    // Checks the fifth failure allows immediate retry and legacy cooldown is ignored step with the test's sample data.
    @Test
    fun fifthFailureAllowsImmediateRetryAndLegacyCooldownIsIgnored() {
        register(username = "pin_attempt_tester")
        val session =
            androidx.lifecycle.ViewModelProvider(compose.activity)[com.olive.app.ui.users.shared.navigation.SessionViewModel::class.java]
        repeat(5) { index ->
            if (index == 0) trigger()
            enterPin("999999")
            // Run this part only when index < 4.
            if (index < 4) {
                compose.waitUntil(30_000) { !session.busy }
                val remaining = 4 - index
                val attemptLabel = if (remaining == 1) "attempt" else "attempts"
                assertEquals("Incorrect PIN. $remaining $attemptLabel remaining.", session.message)
            }
        }
        waitCover()
        compose.onNodeWithTag("safe_attempts").assertDoesNotExist()
        trigger()
        enterPin("999999")
        compose.waitUntil(30_000) { !session.busy }
        assertEquals("Incorrect PIN. 4 attempts remaining.", session.message)
        compose.onNodeWithTag("quick_exit").performClick()
        waitCover()
        compose.activityRule.scenario.recreate()
        waitCover()
        // Simulate an upgrade from a version with an active cooldown.
        val vault =
            EncryptedVault(InstrumentationRegistry.getInstrumentation().targetContext, "demo")
        vault.update { root ->
            root.child("enrollments").getJSONObject(root.getString("activeUid"))
                .put("failures", 5).put("lockedUntil", System.currentTimeMillis() + 60_000L)
        }
        unlock()
        click("safe_activity")
        compose.onNodeWithTag("activity_list").performScrollToNode(hasTestTag("attempt_row"))
        compose.onAllNodesWithTag("attempt_row").onFirst().assertExists()
        val root = vault.read()
        assertFalse(
            root.getJSONObject("enrollments").getJSONObject(root.getString("activeUid"))
                .has("lockedUntil")
        )
        assertEquals(
            6,
            root.getJSONObject("attempts").getJSONArray(root.getString("activeUid")).length()
        )
        val bytes = File(
            InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
            "olive-demo.vault"
        ).readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("Test Person"))
        assertFalse(bytes.contains("moon garden"))
        assertFalse(
            bytes.contains("test-password-123")
        )

        compose.onNodeWithTag("activity_list").performScrollToNode(hasTestTag("activity_from"))
        click("activity_from")
        compose.onNodeWithTag("activity_from_calendar").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithTag("activity_from_clock").assertExists()
        compose.onNodeWithText("Cancel").performClick()
    }

    // Checks the background and in flight unlock never reopen safe area step with the test's sample data.
    @Test
    fun backgroundAndInFlightUnlockNeverReopenSafeArea() {
        register(username = "privacy_tester")
        unlock()
        click("safe_profile")
        compose.onNodeWithText("Test Person Causing Harm").assertExists()
        compose.activityRule.scenario.onActivity { it.onUserLeaveHint() }
        waitCover()
        compose.onNodeWithText("Test Person Causing Harm").assertDoesNotExist()
        unlock()
        click("safe_profile")
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        waitCover()
        compose.onNodeWithText("Test Person").assertDoesNotExist()
        trigger()
        enterPin("123456")
        compose.onNodeWithTag("quick_exit").performClick()
        waitCover()
        compose.waitUntil(30_000) { !androidx.lifecycle.ViewModelProvider(compose.activity)[com.olive.app.ui.users.shared.navigation.SessionViewModel::class.java].busy }
        compose.waitForIdle()
        compose.onNodeWithTag("safe_contacts").assertDoesNotExist()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Your safe space").assertDoesNotExist()
    }

    // Checks the password change requires the current password step with the test's sample data.
    @Test
    fun passwordChangeRequiresTheCurrentPassword() {
        register(username = "password_tester")
        unlock()
        click("safe_settings")
        fill("current_password", "wrong-password")
        fill(
            "new_password",
            "new-password-456"
        )
        fill("confirm_new_password", "new-password-456")
        click("change_password")
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText(
                "Unable to change the password.",
                substring = true
            ).fetchSemanticsNodes().isNotEmpty()
        }
        fill("current_password", "test-password-123")
        fill(
            "new_password",
            "new-password-456"
        )
        fill("confirm_new_password", "new-password-456")
        click("change_password")
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Password changed.").fetchSemanticsNodes().isNotEmpty()
        }
        fill("current_device_pin", "123456")
        fill(
            "new_device_pin",
            "654321"
        )
        fill("confirm_new_device_pin", "654321")
        click("change_device_pin")
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Safe entry settings saved.").fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("quick_exit").performClick()
        waitCover()
        unlock("654321")
    }
}
