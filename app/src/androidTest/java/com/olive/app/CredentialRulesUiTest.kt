package com.olive.app

import androidx.compose.ui.test.*
import org.junit.Test

class CredentialRulesUiTest: OliveUiTestBase() {
    @Test fun newAccountsRejectNumericUsernamesAndPasswordsContainingUsername() {
        waitFor("new_user");click("new_user")
        fill("username","12345678")
        fill("password","Unrelated123!");fill("confirm_password","Unrelated123!")
        click("credentials_submit")
        compose.onNodeWithTag("username_error",useUnmergedTree=true).assertExists()
        compose.onNodeWithTag("full_name").assertDoesNotExist()
        fill("username","lettersonly")
        fill("password","LettersOnly123!");fill("confirm_password","LettersOnly123!")
        click("credentials_submit")
        compose.onNodeWithTag("password_error",useUnmergedTree=true).assertExists()
        compose.onNodeWithTag("full_name").assertDoesNotExist()
        fill("password","Unrelated123!");fill("confirm_password","Unrelated123!")
        click("credentials_submit");waitFor("full_name")
    }
}
