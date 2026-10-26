// Tests the onboarding flow test behavior.
package com.olive.app

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry

import org.junit.Assert.*
import org.junit.Test
import java.io.File

// Groups the data and actions for onboarding flow test.
class OnboardingFlowTest : OliveUiTestBase() {
    @Test
    fun cleanInstallOpensOnboardingOnlyAfterTheOliveIsDoubleTapped() {
        waitCover()
        compose.onNodeWithTag("new_user").assertDoesNotExist()
        click("cover_tab_account")
        waitFor("discreet_cover_olive")
        compose.onNodeWithTag("new_user").assertDoesNotExist()
        compose.onNodeWithText("Set up account").assertDoesNotExist()

        compose.onNodeWithTag("discreet_cover_olive").performTouchInput { doubleClick() }

        waitFor("new_user")
        compose.onNodeWithTag("new_user").assertIsDisplayed()
    }

    @Test
    fun credentialsUseLiveRequirementsAndInlineErrors() {
        openOnboarding()
        click("new_user")
        fill("username", "abc")
        fill("password", "short")
        fill("confirm_password", "different")
        click("credentials_submit")
        compose.onNodeWithText("Invalid username").assertExists()
        compose.onNodeWithText("Invalid password").assertExists()
        compose.onNodeWithText("Passwords differ").assertExists()
        compose.onNodeWithText("0 of 9 required", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("onboarding_progress").assertContentDescriptionEquals("Step 1 / 4")
    }

    @Test
    fun safeContactGetsShorterOnboardingAndNoCover() {
        openOnboarding()
        click("account_role")
        click("role_safe_contact")
        click("new_user")
        fill("username", "safe_contact_test")
        fill(
            "password",
            "test-password-123"
        )
        fill("confirm_password", "test-password-123")
        click("credentials_submit")
        waitFor("full_name")
        compose.onNodeWithTag("language").assertDoesNotExist()
        compose.onNodeWithText("Step 2 of 3", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("abuser_name").assertDoesNotExist()
        compose.onNodeWithTag("situation").assertDoesNotExist()
        compose.onNodeWithTag("trigger").assertDoesNotExist()
        compose.onNodeWithTag("cover_choice_food").assertDoesNotExist()
        fill(
            "full_name",
            "Trusted Contact"
        )
        chooseBirthday()
        nextOnboarding()
        fill("address_city", "Edmonton")
        waitFor("profile_submit")
        click("profile_submit")
        waitFor("safe_contact_settings")
        compose.onNodeWithTag("cover_root").assertDoesNotExist()
        compose.onNodeWithText("Safe Contact").assertExists()
        compose.onNodeWithTag("safe_contact_messages").assertExists()
        compose.onNodeWithTag("safe_contact_notes").assertExists()
        compose.onNodeWithTag("safe_contact_safety_plan").assertExists()
        compose.onNodeWithTag("safe_contact_documents").assertExists()
        compose.onNodeWithTag("safe_contact_manage_connections").assertDoesNotExist()
        compose.onNodeWithTag("sign_out").assertDoesNotExist()
        compose.onNodeWithTag("safe_contact_activity").assertDoesNotExist()
        compose.onNodeWithTag("change_device_pin").assertDoesNotExist()
        compose.onNodeWithTag("quick_exit").assertDoesNotExist()
        click("safe_contact_settings")
        waitFor("save_settings")
        compose.onNodeWithTag("sign_out").assertExists()
    }

    @Test
    // Runs when boarding needs city but not afull delivery address happens.
    fun onboardingNeedsCityButNotAFullDeliveryAddress() {
        openOnboarding()
        click("new_user")
        fill("username", "city_only")
        fill(
            "password",
            "test-password-123"
        )
        fill("confirm_password", "test-password-123")
        click("credentials_submit")
        waitFor("full_name")
        compose.onNodeWithTag("language").assertDoesNotExist()
        compose.onNodeWithText("Step 2 of 4", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("onboarding_step_4", useUnmergedTree = true).assertExists()
        fill("full_name", "City Test")
        chooseBirthday()
        nextOnboarding()
        fill("address_city", "Edmonton")
        nextOnboarding()
        fill("trigger", "moon garden")
        compose.onNodeWithTag("cover_choice_food").assertIsSelected()
        compose.onNodeWithTag("cover_choice_news").assertIsNotSelected().performClick()
            .assertIsSelected()
        compose.onNodeWithTag("cover_choice_food").assertIsNotSelected()
        waitFor(
            "profile_submit"
        )
        click("profile_submit")
        chooseAppPin()
        fill("new_pin", "1234")
        fill("confirm_pin", "1234")
        click("pin_setup_submit")
        waitCover()
        val session =
            androidx.lifecycle.ViewModelProvider(compose.activity)[com.olive.app.ui.users.shared.navigation.SessionViewModel::class.java]
        assertEquals("Edmonton", session.deliveryAddress.city)
        assertFalse(session.deliveryAddress.valid)
    }

    @Test
    // Runs when boarding contacts quick exit and returning user happens.
    fun onboardingContactsQuickExitAndReturningUser() {
        register(username = "returning_user_tester")
        compose.runOnIdle { assertFalse(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
        // Cover cart stays alive through the secure overlay.
        compose.onNodeWithText("Garden harvest bowl").performClick()
        compose.onNodeWithText("Add to cart").performScrollTo().performClick()
        compose.onNodeWithText("Discover").performClick()
        unlock()
        compose.onNodeWithTag("safe_notes").assertExists()
        compose.onNodeWithTag("safe_messages").assertExists()
        compose.onNodeWithTag("safe_safety_plan").assertExists()
        compose.onNodeWithTag("safe_documents").assertExists()
        click("safe_messages")
        waitFor("message_username")
        compose.onAllNodesWithText("Messaging needs a Firebase-connected build.")
            .assertAny(hasText("Messaging needs a Firebase-connected build."))
        click("safe_home")
        click("safe_notes")
        click("new_private_entry")
        click("private_kind_incident")
        fill("private_entry_title", "Private incident")
        fill("private_entry_body", "Details that must stay inside Olive Branch")
        click("save_private_entry")
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Details that must stay inside Olive Branch")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("quick_exit").performClick()
        waitCover()
        compose.onNodeWithText("Details that must stay inside Olive Branch").assertDoesNotExist()
        unlock()
        click("safe_notes")
        compose.onNodeWithText("Details that must stay inside Olive Branch").assertExists()
        fill("private_notes_search", "Private incident")
        compose.onNodeWithTag("private_entry_row").assertExists()
        fill("private_notes_search", "")
        compose.onNodeWithTag("private_pin").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Pinned").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("private_entry_row").performClick()
        fill("private_entry_body", "Updated private incident details")
        click("save_private_entry")
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Updated private incident details").fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Edited", substring = true).assertExists()
        compose.onNodeWithTag("private_edit_log").performClick()
        compose.onNodeWithText("Edit log").assertExists()
        compose.onNodeWithText("Details that must stay inside Olive Branch", substring = true)
            .assertExists()
        compose.onAllNodesWithText("Updated private incident details", substring = true)
            .assertAny(hasText("Updated private incident details", substring = true))
        compose.onAllNodesWithText("Pinned").assertCountEquals(0)
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("private_entry_row").performClick()
        click("delete_private_entry")
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Moved to Trash for 10 seconds.").fetchSemanticsNodes()
                .isNotEmpty()
        }
        click("open_scratchpad")
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("scratchpad_entry").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Updated private incident details").assertExists()
        click("restore_private_entry")
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Restored from Trash.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Updated private incident details").assertExists()
        click("new_private_entry")
        compose.onNodeWithText("Restored from Trash.").assertDoesNotExist()
        click("private_notes_back")
        val privateBytes = File(
            InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
            "olive-demo-activity.vault"
        ).readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(privateBytes.contains("Details that must stay inside Olive Branch"))
        assertFalse(privateBytes.contains("Updated private incident details"))
        click("safe_home")
        compose.runOnIdle { assertFalse(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
        click("support_menu_toggle")
        click("support_contacts")
        fill("contact_name", "Trusted Friend")
        fill(
            "contact_details",
            "friend@example.invalid"
        )
        click("save_contact")
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Trusted Friend").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Edit").performScrollTo().performClick()
        fill("contact_name", "Updated Friend")
        click("save_contact")
        // The draft field already contains this text before saving. Wait for the saved profile,
        // rather than letting Quick exit cancel an in-flight write after matching the draft.
        val savedSession =
            androidx.lifecycle.ViewModelProvider(compose.activity)[com.olive.app.ui.users.shared.navigation.SessionViewModel::class.java]
        compose.waitUntil(30_000) {
            !savedSession.busy && savedSession.profile?.contacts?.any { it.name == "Updated Friend" } == true
        }
        compose.onNodeWithTag("quick_exit").performClick()
        waitCover()
        compose.onNodeWithText("Updated Friend").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        waitCover()
        unlock()
        click("support_menu_toggle")
        click("support_contacts")
        compose.onNodeWithText("Updated Friend")
            .assertExists()
        compose.onNodeWithTag("quick_exit").performClick()
        waitCover()
        compose.onNodeWithText("Cart").performClick()
        compose.onNodeWithText("Garden harvest bowl").assertExists()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitCover()
        unlock()
        click("sign_out")
        waitFor("returning_user")
        click("returning_user")
        fill("username", "returning_user_tester")
        fill(
            "password",
            "wrong-password"
        )
        click("credentials_submit")
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("Incorrect password").fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitUntil(30_000) { !androidx.lifecycle.ViewModelProvider(compose.activity)[com.olive.app.ui.users.shared.navigation.SessionViewModel::class.java].busy }
        fill("password", "test-password-123")
        click("credentials_submit")
        chooseAppPin()
        fill("new_pin", "654321")
        fill("confirm_pin", "654321")
        click("pin_setup_submit")
        waitCover()
        compose.onNodeWithText("Discover").performClick()
        waitCover()
        unlock("654321")
        click("support_menu_toggle")
        click("support_contacts")
        compose.onNodeWithText(
            "Updated Friend"
        ).assertExists()
    }
}
