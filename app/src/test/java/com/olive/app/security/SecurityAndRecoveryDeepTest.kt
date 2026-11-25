package com.olive.app.security

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.functions.FirebaseFunctions
import com.olive.app.R
import com.olive.app.data.account.AccountRecoveryDeviceVerificationException
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.FirebaseAccountRecoveryAuthenticator
import com.olive.app.data.account.FirebaseAccountRecoveryCallable
import com.olive.app.data.account.UserProfile
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SecurityAndRecoveryDeepTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun ensureFirebase() {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("1:123456789012:android:abcdef123456")
                    .setApiKey("test-api-key")
                    .setProjectId("olive-test")
                    .build(),
            )
        }
    }

    @Test
    fun deviceLockChallengeEncryptsAndDecryptsAndKeyValidatesInputs() {
        val aesKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val keyId = "12345678-1234-1234-1234-1234567890ab"
        val enrollChallenge = DeviceLockChallenge("u-1", aesKey, null, keyId, "alias-1")
        assertTrue(enrollChallenge.enrolling)
        val cipher = enrollChallenge.promptCipher ?: enrollChallenge.legacyCipher()
        val wrappedJson = enrollChallenge.finish(cipher)
        assertNotNull(wrappedJson)
        enrollChallenge.markEnrolled()
        enrollChallenge.close()

        val unlockChallenge = DeviceLockChallenge("u-1", aesKey, wrappedJson, keyId, "alias-1")
        assertFalse(unlockChallenge.enrolling)
        val decCipher = unlockChallenge.promptCipher ?: unlockChallenge.legacyCipher()
        unlockChallenge.finish(decCipher)

        val badKeyIdFailed = runCatching {
            DeviceLockKey("ns").prepare("u-1", JSONObject().put("keyId", "not-uuid"))
        }.isFailure
        assertTrue(badKeyIdFailed)

        val missingKeyFailed = runCatching {
            DeviceLockKey("ns").prepare("u-1", JSONObject().put("keyId", keyId))
        }.isFailure
        assertTrue(missingKeyFailed)
    }

    @Test
    fun opaqueRecoveryAuthAndCallableValidateErrorPaths() = runBlocking {
        ensureFirebase()
        val mockAuth = Mockito.mock(FirebaseAuth::class.java)
        val mockUser = Mockito.mock(FirebaseUser::class.java)
        val auth = FirebaseAccountRecoveryAuthenticator { mockAuth }

        assertTrue(runCatching { auth.reauthenticate("u-1", "") }.isFailure)
        Mockito.`when`(mockAuth.currentUser).thenReturn(null)
        assertTrue(runCatching { auth.reauthenticate("u-1", "Pass123!") }.isFailure)

        Mockito.`when`(mockAuth.currentUser).thenReturn(mockUser)
        Mockito.`when`(mockUser.uid).thenReturn("other-user")
        assertTrue(runCatching { auth.reauthenticate("u-1", "Pass123!") }.isFailure)

        Mockito.`when`(mockUser.uid).thenReturn("u-1")
        Mockito.`when`(mockUser.email).thenReturn(null)
        assertTrue(runCatching { auth.reauthenticate("u-1", "Pass123!") }.isFailure)

        val mockFunctions = Mockito.mock(FirebaseFunctions::class.java)
        val callable = FirebaseAccountRecoveryCallable(
            functions = mockFunctions,
            verifyAppCheck = { throw IllegalStateException("app check failed") },
        )
        val appCheckFailed = runCatching { callable.call("recoverAccount", emptyMap()) }.exceptionOrNull()
        assertTrue(appCheckFailed is AccountRecoveryDeviceVerificationException)
    }

    @Test
    fun accountSessionControllerWelcomeAndSetupProfileValidationCoverBranches() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Welcome, page = SafePage.Home, localMode = true).use { handle ->
            val ctrl = handle.session.account
            handle.session.runtime.enrollment = null

            ctrl.selectRole(AccountRole.STAFF)
            ctrl.chooseNew()
            assertNotNull(handle.session.message)

            ctrl.selectRole(AccountRole.ADMIN)
            ctrl.chooseNew()
            assertNotNull(handle.session.message)

            ctrl.selectRole(AccountRole.USER)
            ctrl.chooseNew()
            assertEquals(SessionScreen.NewCredentials, handle.session.screen)

            handle.session.runtime.state.screen = SessionScreen.Welcome
            ctrl.chooseReturning()
            assertEquals(SessionScreen.ReturningCredentials, handle.session.screen)

            ctrl.clearCredentialError()
            handle.session.runtime.state.screen = SessionScreen.Cover
            ctrl.openAccountSetup()
            assertEquals(SessionScreen.Welcome, handle.session.screen)

            handle.session.runtime.state.screen = SessionScreen.ProfileSetup
            handle.session.runtime.pendingIdentity = com.olive.app.data.account.AccountIdentity("u-1", "alex")
            handle.session.runtime.state.accountRole = AccountRole.USER

            ctrl.saveSetupProfile(UserProfile(role = AccountRole.SAFE_CONTACT))
            assertTrue(handle.session.message == context.getString(R.string.ui_account_type_mismatch))

            ctrl.saveSetupProfile(UserProfile(role = AccountRole.USER, fullName = "", dob = "2000-01-01", language = "en", address = DeliveryAddress(city = "Edmonton")))
            assertNotNull(handle.session.message)

            ctrl.saveSetupProfile(
                UserProfile(
                    role = AccountRole.USER,
                    fullName = "Alex",
                    dob = "1995-05-05",
                    language = "en",
                    address = DeliveryAddress(city = "Edmonton"),
                    email = "not-an-email",
                    emailCommunicationConsent = false,
                ),
            )
            assertTrue(handle.session.message == context.getString(R.string.email_invalid_message))
        }
    }
}
