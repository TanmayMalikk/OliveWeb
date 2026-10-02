package com.olive.app

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.security.EncryptedVault
import com.olive.app.security.child
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PrivacySecurityFlowTest : OliveUiTestBase() {
    @Test fun fifthFailureAllowsImmediateRetryAndLegacyCooldownIsIgnored() {
        register(username="pin_attempt_tester")
        repeat(5) { index ->
            if(index==0) trigger()
            enterPin("999999")
            if(index<4) compose.waitUntil(30_000) { compose.onAllNodesWithText("Incorrect PIN. ${4-index} attempts remaining.").fetchSemanticsNodes().isNotEmpty() }
        }
        waitCover()
        compose.onNodeWithTag("safe_attempts").assertDoesNotExist()
        trigger(); enterPin("999999")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Incorrect PIN. 4 attempts remaining.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("quick_exit").performClick(); waitCover()
        compose.activityRule.scenario.recreate(); waitCover()
        // Simulate an upgrade from a version with an active cooldown.
        val vault=EncryptedVault(InstrumentationRegistry.getInstrumentation().targetContext,"demo")
        vault.update { root -> root.child("enrollments").getJSONObject(root.getString("activeUid"))
            .put("failures",5).put("lockedUntil",System.currentTimeMillis()+60_000L) }
        unlock();click("safe_activity")
        compose.onNodeWithTag("activity_list").performScrollToNode(hasTestTag("attempt_row"))
        compose.onAllNodesWithTag("attempt_row").onFirst().assertExists()
        val root=vault.read()
        assertFalse(root.getJSONObject("enrollments").getJSONObject(root.getString("activeUid")).has("lockedUntil"))
        assertEquals(6,root.getJSONObject("attempts").getJSONArray(root.getString("activeUid")).length())
        val bytes=File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,"olive-demo.vault").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("Test Person")); assertFalse(bytes.contains("moon garden")); assertFalse(bytes.contains("test-password-123"))

        compose.onNodeWithTag("activity_list").performScrollToNode(hasTestTag("activity_from"))
        click("activity_from")
        compose.onNodeWithTag("activity_from_calendar").assertExists()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithTag("activity_from_clock").assertExists()
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun backgroundAndInFlightUnlockNeverReopenSafeArea() {
        register(username="privacy_tester"); unlock(); click("safe_profile")
        compose.onNodeWithText("Test Person Causing Harm").assertExists()
        compose.activityRule.scenario.onActivity { it.onUserLeaveHint() }
        waitCover(); compose.onNodeWithText("Test Person Causing Harm").assertDoesNotExist()
        unlock(); click("safe_profile")
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        waitCover(); compose.onNodeWithText("Test Person").assertDoesNotExist()
        trigger(); enterPin("123456")
        compose.onNodeWithTag("quick_exit").performClick()
        waitCover()
        compose.waitUntil(30_000) { !androidx.lifecycle.ViewModelProvider(compose.activity)[com.olive.app.ui.SessionViewModel::class.java].busy }
        compose.waitForIdle()
        compose.onNodeWithTag("safe_contacts").assertDoesNotExist()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Your safe space").assertDoesNotExist()
    }

    @Test fun passwordChangeRequiresTheCurrentPassword() {
        register(username="password_tester");unlock();click("safe_settings")
        fill("current_password","wrong-password");fill("new_password","new-password-456");fill("confirm_new_password","new-password-456");click("change_password")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Unable to change the password.",substring=true).fetchSemanticsNodes().isNotEmpty() }
        fill("current_password","test-password-123");fill("new_password","new-password-456");fill("confirm_new_password","new-password-456");click("change_password")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Password changed.").fetchSemanticsNodes().isNotEmpty() }
        fill("current_device_pin","123456");fill("new_device_pin","654321");fill("confirm_new_device_pin","654321");click("change_device_pin")
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Device PIN changed.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("quick_exit").performClick();waitCover();unlock("654321")
    }
}
