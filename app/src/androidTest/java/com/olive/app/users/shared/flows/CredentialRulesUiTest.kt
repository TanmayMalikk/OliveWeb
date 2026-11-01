// Tests username and password validation during account setup.
package com.olive.app

import androidx.compose.ui.test.*
import org.junit.Test

class CredentialRulesUiTest : OliveUiTestBase() {
    // Shared safe app credential rules ui test: returns the new accounts reject numeric usernames and passwords containing username. The test gives the code a clear input and checks the expected result.
    @Test
    fun newAccountsRejectNumericUsernamesAndPasswordsContainingUsername() {
        openOnboarding()
        click("new_user")
        fill("username", "12345678")
        fill("password", "Unrelated123!")
        fill("confirm_password", "Unrelated123!")
        click("credentials_submit")
        compose.onNodeWithTag("username_error", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("full_name").assertDoesNotExist()
        fill("username", "lettersonly")
        fill("password", "LettersOnly123!")
        fill("confirm_password", "LettersOnly123!")
        click("credentials_submit")
        compose.onNodeWithTag("password_error", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("full_name").assertDoesNotExist()
        fill("password", "Unrelated123!")
        fill("confirm_password", "Unrelated123!")
        click("credentials_submit")
        waitFor("full_name")
    }

    // Inputs: a 7-character username and a 25-character username. Expected: short input is rejected and overflow is capped at 24.
    @Test
    fun usernameLengthBoundariesAreEnforcedByTheVisibleForm() {
        openOnboarding()
        click("new_user")
        fill("password", "Unrelated123!")
        fill("confirm_password", "Unrelated123!")
        fill("username", "a123456")
        click("credentials_submit")
        compose.onNodeWithTag("username_error", useUnmergedTree = true).assertExists()
        fill("username", "a" + "1".repeat(24))
        compose.onNodeWithTag("username").assertTextContains("a" + "1".repeat(23))
        click("credentials_submit")
        waitFor("full_name")
    }

    // Inputs: valid credentials with a mismatched confirmation, then a correction. Expected: inline error clears and setup advances.
    @Test
    fun correctedPasswordConfirmationCanAdvanceWithoutRestarting() {
        openOnboarding()
        click("new_user")
        fill("username", "confirm_test")
        fill("password", "Unrelated123!")
        fill("confirm_password", "Different123!")
        click("credentials_submit")
        compose.onNodeWithText("Passwords differ").assertExists()
        fill("confirm_password", "Unrelated123!")
        click("credentials_submit")
        waitFor("full_name")
        compose.onNodeWithText("Passwords differ").assertDoesNotExist()
    }
}
