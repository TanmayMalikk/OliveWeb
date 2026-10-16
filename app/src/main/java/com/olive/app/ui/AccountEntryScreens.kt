package com.olive.app.ui


import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.olive.app.data.*
import com.olive.app.security.InputPolicy

// ALL ACCOUNT TYPES: role selection routes each person into the correct onboarding path.
// USER and SAFE CONTACT can create accounts here; STAFF and ADMIN use administrator-issued logins.
// UI sizing/text: the role menu inherits FormColumn spacing; descriptions intentionally use bodySmall and Muted so long translations can wrap.
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun Welcome(session: SessionViewModel) {
    var roleOpen by remember { mutableStateOf(false) }
    FormColumn {
        Heading("Welcome", "First, choose what kind of account you use.",if(session.accountRole in setOf(AccountRole.STAFF,AccountRole.ADMIN)) "Olive Tree" else "Olive Branch")
        ModeNotice(session)
        ExposedDropdownMenuBox(expanded=roleOpen,onExpandedChange={roleOpen=!roleOpen}) {
            OutlinedTextField(value=session.accountRole.title,onValueChange={},readOnly=true,label={LocalizedText("Account type")},
                trailingIcon={ExposedDropdownMenuDefaults.TrailingIcon(roleOpen)},modifier=Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).testTag("account_role"))
            ExposedDropdownMenu(expanded=roleOpen,onDismissRequest={roleOpen=false}) {
                AccountRole.entries.forEach { role -> DropdownMenuItem(
                    text={Column { LocalizedText(role.title);LocalizedText(role.description,style=MaterialTheme.typography.bodySmall,color=Muted) }},
                    onClick={session.selectRole(role);roleOpen=false},modifier=Modifier.testTag("role_${role.name.lowercase()}")) }
            }
        }
        if(session.accountRole==AccountRole.USER || session.accountRole==AccountRole.SAFE_CONTACT) {
            Button(onClick=session::chooseNew,enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("new_user")) { LocalizedText("Create a new account") }
            OutlinedButton(onClick=session::chooseReturning,enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("returning_user")) { LocalizedText("Sign in to an existing account") }
        } else {
            LocalizedText("${session.accountRole.title} accounts are created by an administrator. Sign in with your username or the real email saved to your account.",color=Muted)
            Button(onClick=session::chooseReturning,enabled=!session.busy,modifier=Modifier.fillMaxWidth().testTag("returning_user")) { LocalizedText("Sign in") }
        }
        Status(session)
    }
}

// ALL ACCOUNT TYPES: username/password sign-in. New-account creation is available only to USER and SAFE CONTACT.
// UI sizing/text: credential fields use the shared full-width field size; password guidance uses bodySmall and Muted.
@Composable internal fun Credentials(session: SessionViewModel, create: Boolean) {
    // Deliberately not rememberSaveable: passwords never enter Android saved-state bundles.
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    val organizationLogin=!create && session.accountRole in setOf(AccountRole.STAFF,AccountRole.ADMIN)
    val usernameValid=if(organizationLogin && '@' in username) android.util.Patterns.EMAIL_ADDRESS.matcher(username.trim()).matches()
        else if(create) InputPolicy.validUsername(username) else InputPolicy.validExistingUsername(username)
    val passwordValid=if(create) InputPolicy.validPassword(password) else password.isNotEmpty()
    val confirmationValid=!create || (confirm.isNotEmpty() && confirm==password)
    val completed=listOf(usernameValid,passwordValid,confirmationValid).take(if(create) 3 else 2).count { it }
    val stages=when {
        create && session.accountRole==AccountRole.USER -> 4
        create && session.accountRole==AccountRole.SAFE_CONTACT -> 3
        !create && session.accountRole==AccountRole.USER -> 2
        else -> 1
    }
    val totalRequired=when {
        create && session.accountRole==AccountRole.USER -> 10
        create && session.accountRole==AccountRole.SAFE_CONTACT -> 7
        !create && session.accountRole==AccountRole.USER -> 4
        else -> 2
    }
    val usernameError=when {
        session.credentialFieldError==CredentialFieldError.UsernameTaken -> "Username taken"
        submitted && !usernameValid -> "Invalid username"
        else -> null
    }
    val passwordError=when {
        session.credentialFieldError==CredentialFieldError.IncorrectPassword -> "Incorrect password"
        submitted && !passwordValid -> if(create) "Invalid password" else "Password required"
        else -> null
    }
    val confirmError=if(create && submitted && !confirmationValid) "Passwords differ" else null
    FormColumn {
        Heading(if(create) "Create your ${session.accountRole.title} account" else "${session.accountRole.title} sign in",
            if(create) "Use these credentials when setting up another device."
            else when(session.accountRole) {
                AccountRole.USER -> "Sign in with your existing username and password. Next, create a PIN for this device."
                AccountRole.SAFE_CONTACT -> "Sign in with your existing username and password."
                else -> "Use the credentials issued by an administrator."
            },if(session.accountRole in setOf(AccountRole.STAFF,AccountRole.ADMIN)) "Olive Tree" else "Olive Branch")
        ModeNotice(session)
        if(stages>1) OnboardingProgress(completed,totalRequired,0,stages,
            List(stages) { index -> index==0 && usernameValid && passwordValid && confirmationValid })
        Field(if(organizationLogin) "Username or email" else "Username",username,"username",enabled=!session.busy,error=usernameError) {
            username=it.take(if(create) 24 else 254);submitted=false;session.clearCredentialError()
        }
        if(create) {
            // UI spacing: keep related validation bullets close together below their input field.
            Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                RequirementBullet("8–24 characters",username.length in 8..24,username.isNotEmpty())
                RequirementBullet("Letters, numbers or _",username.matches(Regex("[A-Za-z0-9_]+")),username.isNotEmpty())
            }
        }
        Field("Password",password,"password",secret=true,showSecretToggle=true,enabled=!session.busy,error=passwordError) {
            password=it.take(if(create) 24 else 128);submitted=false;session.clearCredentialError()
        }
        if(create) {
            // UI spacing: password requirements read as one compact list.
            Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                RequirementBullet("8–24 characters",InputPolicy.passwordHasValidLength(password),password.isNotEmpty())
                RequirementBullet("At least 1 letter",InputPolicy.passwordHasLetter(password),password.isNotEmpty())
                RequirementBullet("At least 1 number",InputPolicy.passwordHasNumber(password),password.isNotEmpty())
                RequirementBullet("At least 1 special character",InputPolicy.passwordHasSpecialCharacter(password),password.isNotEmpty())
            }
            Field("Confirm password",confirm,"confirm_password",secret=true,showSecretToggle=true,enabled=!session.busy,error=confirmError) {
                confirm=it.take(24);submitted=false;session.clearCredentialError()
            }
            RequirementBullet("Passwords match",confirmationValid,password.isNotEmpty() || confirm.isNotEmpty())
        }
        Status(session,showMessage=session.credentialFieldError==null)
        OnboardingNavigation(session::back,{
            submitted=true
            if(usernameValid && passwordValid && confirmationValid) session.authenticate(username,password,create)
        },nextTag="credentials_submit",enabled=!session.busy,
            nextLabel=if(create) "Create account" else "Sign in")
    }
}
