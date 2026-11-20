package com.olive.app.ui.session

import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.olive.app.data.account.*
import com.olive.app.support.SessionRuntimeFixture
import com.olive.app.ui.users.shared.navigation.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AccountSignInControllerTest {
    private val identity=AccountIdentity("new-owner","fixture_owner")
    private val profile=UserProfile("fixture_owner","Synthetic owner","","","2000-01-01","English","","Edmonton","",address=DeliveryAddress(city="Edmonton"))
    private fun fixture(role:AccountRole=AccountRole.USER)=SessionRuntimeFixture(role,dispatcher=Dispatchers.Default).also {
        it.runtime.enrollment=null
        it.runtime.state.screen=SessionScreen.ReturningCredentials
    }
    @Test
    fun missingCredentialsAndStaleFormsCannotCallAuthentication() {
        fixture().use { f ->
            f.runtime.accountSignIn.authenticate("","password",false)
            assertEquals(CredentialFieldError.InvalidUsername,f.runtime.state.credentialFieldError)
            f.runtime.accountSignIn.authenticate("fixture_owner","",false)
            assertEquals(CredentialFieldError.IncorrectPassword,f.runtime.state.credentialFieldError)
            f.runtime.state.screen=SessionScreen.Cover
            f.runtime.accountSignIn.authenticate("fixture_owner","Secret!1234",false)
            runBlocking { verify(f.accounts,never()).authenticate(anyString(),anyString(),any<AccountRole>() ?: AccountRole.USER) }
        }
    }
    @Test
    fun missingProfileReturnsToSetupAndCompleteUserProfileAsksForItsPrivateEntryMethod()=runBlocking<Unit> {
        fixture().use { f ->
            `when`(f.accounts.authenticate("fixture_owner","Secret!1234",AccountRole.USER)).thenAnswer { Thread.sleep(20);identity }
            `when`(f.accounts.profile(identity)).thenReturn(null)
            f.runtime.accountSignIn.authenticate("fixture_owner","Secret!1234",false)
            f.await { !f.runtime.state.busy }
            assertEquals(SessionScreen.ProfileSetup,f.runtime.state.screen)
            assertEquals(identity,f.runtime.pendingIdentity)
            f.runtime.state.screen=SessionScreen.ReturningCredentials
            `when`(f.accounts.profile(identity)).thenReturn(profile)
            f.runtime.accountSignIn.authenticate("fixture_owner","Secret!1234",false)
            f.await { !f.runtime.state.busy }
            assertEquals(SessionScreen.TriggerSetup,f.runtime.state.screen)
            f.runtime.state.screen=SessionScreen.ReturningCredentials
            `when`(f.accounts.profile(identity)).thenReturn(profile.copy(triggerHash="saved-trigger-hash"))
            f.runtime.accountSignIn.authenticate("fixture_owner","Secret!1234",false)
            f.await { !f.runtime.state.busy }
            assertEquals(SessionScreen.DevicePin,f.runtime.state.screen)
        }
    }
    @Test
    fun wrongPasswordAndTakenUsernameHaveFieldSpecificErrorsAndReleaseBusyState()=runBlocking<Unit> {
        fixture().use { f ->
            `when`(f.accounts.authenticate("fixture_owner","wrong",AccountRole.USER)).thenAnswer { Thread.sleep(20);throw FirebaseAuthInvalidCredentialsException("ERROR_INVALID_CREDENTIAL","Incorrect password") }
            f.runtime.accountSignIn.authenticate("fixture_owner","wrong",false)
            f.await { !f.runtime.state.busy }
            assertEquals(CredentialFieldError.IncorrectPassword,f.runtime.state.credentialFieldError)
            f.runtime.state.screen=SessionScreen.NewCredentials
            `when`(f.accounts.create("fixture_owner","Secret!1234",AccountRole.USER)).thenAnswer { Thread.sleep(20);error("Username unavailable") }
            f.runtime.accountSignIn.authenticate("fixture_owner","Secret!1234",true)
            f.await { !f.runtime.state.busy }
            assertEquals(CredentialFieldError.UsernameTaken,f.runtime.state.credentialFieldError)
        }
    }
    @Test
    fun unprovisionedOrganizationAccountsAndRoleMismatchesAreSignedOut()=runBlocking<Unit> {
        fixture(AccountRole.STAFF).use { f ->
            `when`(f.accounts.authenticate("fixture_owner","Secret!1234",AccountRole.STAFF)).thenAnswer { Thread.sleep(20);identity }
            `when`(f.accounts.profile(identity)).thenReturn(null)
            f.runtime.accountSignIn.authenticate("fixture_owner","Secret!1234",false)
            f.await { !f.runtime.state.busy }
            assertNull(f.runtime.pendingIdentity)
            verify(f.accounts).signOut()
            assertTrue(f.runtime.state.message.orEmpty().contains("not_been_provisioned"))
            `when`(f.accounts.profile(identity)).thenReturn(profile)
            f.runtime.accountSignIn.authenticate("fixture_owner","Secret!1234",false)
            f.await { !f.runtime.state.busy }
            verify(f.accounts,times(2)).signOut()
            assertTrue(f.runtime.state.message.orEmpty().contains("registered_as"))
        }
    }

    @Test
    fun completedSafeContactAndStaffProfilesReturnToTheirOwnWorkspaces() = runBlocking<Unit> {
        SessionRuntimeFixture(dispatcher = Dispatchers.Default, localMode = true).use { fixture ->
            for ((role, screen) in listOf(AccountRole.SAFE_CONTACT to SessionScreen.Safe, AccountRole.STAFF to SessionScreen.Portal)) {
                val session = fixture.runtime
                val password = "Synthetic!123"
                val restored = profile.copy(role = role, email = "fixture@example.test")
                val account = identity.copy(uid = "fixture-${role.name.lowercase()}")
                session.enrollment = null
                session.state.accountRole = role
                session.state.screen = SessionScreen.ReturningCredentials
                session.safePageHistory.add(SafePage.Messages)
                `when`(fixture.accounts.authenticate(account.username, password, role)).thenReturn(account)
                `when`(fixture.accounts.profile(account)).thenReturn(restored)

                session.accountSignIn.authenticate(account.username, password, false)
                fixture.await { !session.state.busy }
                assertEquals("Role $role returned ${session.state.message}", screen, session.state.screen)
                assertEquals(account.uid, session.enrollment?.uid)
                assertEquals(role, session.enrollment?.role)
                assertEquals(restored, session.state.profile)
                assertTrue(session.safePageHistory.isEmpty())
                if (role == AccountRole.SAFE_CONTACT) assertEquals(SafePage.Home, session.state.page)
            }
        }
    }
}
