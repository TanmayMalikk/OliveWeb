// Tests the credential rules UI test behavior.
package com.olive.app

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import androidx.compose.ui.test.*
import org.junit.Test

// Groups the data and actions for credential rules UI test.
class CredentialRulesUiTest : OliveUiTestBase() {
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
}
