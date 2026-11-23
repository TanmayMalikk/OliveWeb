package com.olive.app.ui.session

import com.olive.app.R
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountRecoveryCallable
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.OpaqueClientProtocol
import com.olive.app.data.account.OpaqueRecoveryRepository
import com.olive.app.data.account.PasskeyRecoveryAuthenticator
import com.olive.app.data.account.UserProfile
import com.olive.app.security.InputPolicy
import com.olive.app.support.SessionRuntimeFixture
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionPasswordRecoveryTest {

    private val uid = "test-user-recovery"
    private val password = "CorrectPassword123!"

    private val fakeOpaqueClient = object : OpaqueClientProtocol {
        override suspend fun startRegistration(password: String) = OpaqueClientProtocol.RegistrationStart("state", "req")
        override suspend fun finishRegistration(state: String, response: String, password: String) =
            OpaqueClientProtocol.RegistrationFinish("record", ByteArray(32))
        override suspend fun startLogin(password: String) = OpaqueClientProtocol.LoginStart("loginState", "startReq")
        override suspend fun finishLogin(state: String, response: String, password: String) = null
    }

    private val fakeRecoveryRepo = OpaqueRecoveryRepository(
        callable = AccountRecoveryCallable { _, _ -> emptyMap<String, Any?>() },
        authenticator = PasskeyRecoveryAuthenticator { _, _ -> },
        opaqueClient = fakeOpaqueClient,
    )

    @Test
    fun removePasswordRecoveryIsBlockedWhenNoPasskeysAreEnrolled() = runBlocking<Unit> {
        fixture().use { fixture ->
            val runtime = fixture.runtime
            val state = runtime.state
            state.screen = SessionScreen.Safe
            state.accountRole = AccountRole.USER
            state.passkeyRecoveryCredentials = emptyList()

            runtime.passwordRecovery.removePasswordRecovery(password)

            assertEquals(
                runtime.application.getString(R.string.password_recovery_remove_blocked_last),
                state.message
            )
        }
    }

    @Test
    fun enablePasswordRecoveryIgnoredWhenScreenIsNotSafe() = runBlocking<Unit> {
        fixture().use { fixture ->
            val runtime = fixture.runtime
            val state = runtime.state
            state.screen = SessionScreen.Cover
            state.accountRole = AccountRole.USER

            runtime.passwordRecovery.enablePasswordRecovery(password)

            assertFalse(state.passwordRecoveryEnrolled == true)
        }
    }

    @Test
    fun changePasswordTriggersRotationWhenPasswordRecoveryIsEnrolled() = runBlocking<Unit> {
        var rotatedOld: String? = null
        val rotatingClient = object : OpaqueClientProtocol {
            override suspend fun startRegistration(password: String) = OpaqueClientProtocol.RegistrationStart("state", "req")
            override suspend fun finishRegistration(state: String, response: String, password: String) =
                OpaqueClientProtocol.RegistrationFinish("record", ByteArray(32))
            override suspend fun startLogin(password: String) = OpaqueClientProtocol.LoginStart("loginState", "startReq")
            override suspend fun finishLogin(state: String, response: String, password: String) =
                OpaqueClientProtocol.LoginFinish("finishLoginReq", ByteArray(32))
        }
        val rotatingRepo = OpaqueRecoveryRepository(
            callable = AccountRecoveryCallable { name, data ->
                when (name) {
                    "opaqueRecoveryBeginUnlock" -> mapOf("sessionId" to "sess_1", "loginResponse" to "srvLogin")
                    "opaqueRecoveryBeginRegistration" -> mapOf("registrationResponse" to "srvReg")
                    "opaqueRecoveryRotate" -> {
                        rotatedOld = "rotated"
                        mapOf("success" to true)
                    }
                    else -> emptyMap<String, Any?>()
                }
            },
            authenticator = PasskeyRecoveryAuthenticator { _, _ -> },
            opaqueClient = rotatingClient,
        )

        val keysetRepo = mock(com.olive.app.data.account.AccountKeysetRepository::class.java)
        val passkeyRecoveryRepo = mock(com.olive.app.data.account.PasskeyRecoveryRepository::class.java)

        SessionRuntimeFixture(
            accountKeysetsOverride = keysetRepo,
            passkeyRecoveryOverride = passkeyRecoveryRepo,
            opaqueRecoveryOverride = rotatingRepo
        ).use { fixture ->
            val runtime = fixture.runtime
            val identity = AccountIdentity(uid, "fixture_owner")
            val profile = UserProfile(
                "fixture_owner", "Synthetic enrolled user", "", "", "2000-01-01",
                "English", "", "", InputPolicy.triggerHash("moon garden")
            )
            runtime.enrollment = runtime.store.enroll(identity, profile, "123456")
            runtime.state.profile = profile
            runtime.pinAuthorizedUid = uid
            runtime.pinAuthorizedEpoch = runtime.epoch
            runtime.state.screen = SessionScreen.Safe
            runtime.state.accountRole = AccountRole.USER
            runtime.state.passwordRecoveryEnrolled = true

            // Enroll a local keyset and device record properly via protectDeviceRecoveryKey
            val ark = com.olive.app.security.AccountRecoveryKey.generate()
            val envelope = com.olive.app.security.AccountKeysetEnvelope.create(uid, ark)
            val enrollJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                runtime.settings.protectDeviceRecoveryKey(uid, ark, envelope, runtime.epoch)
            }
            fixture.await { runtime.state.deviceLockRequest != null }
            runtime.state.deviceLockRequest?.let { req ->
                val cipher = req.challenge.promptCipher ?: req.challenge.legacyCipher()
                req.complete(cipher)
            }
            enrollJob.join()

            val newPassword = "NewPassword456!"
            runtime.settings.changePassword(password, newPassword, newPassword)
            fixture.await { runtime.state.deviceLockRequest != null || !runtime.state.busy }
            runtime.state.deviceLockRequest?.let { req ->
                val cipher = req.challenge.promptCipher ?: req.challenge.legacyCipher()
                req.complete(cipher)
            }
            fixture.await { !runtime.state.busy }

            assertEquals("password_changed", runtime.state.message)
            assertEquals("rotated", rotatedOld)
        }
    }

    private fun fixture() = SessionRuntimeFixture(opaqueRecoveryOverride = fakeRecoveryRepo).also { fixture ->
        val runtime = fixture.runtime
        val identity = AccountIdentity(uid, "fixture_owner")
        val profile = UserProfile(
            "fixture_owner", "Synthetic enrolled user", "", "", "2000-01-01",
            "English", "", "", InputPolicy.triggerHash("moon garden")
        )
        runtime.enrollment = runtime.store.enroll(identity, profile, "123456")
        runtime.pinAuthorizedUid = uid
        runtime.pinAuthorizedEpoch = runtime.epoch
        runtime.state.screen = SessionScreen.Safe
        runtime.state.accountRole = AccountRole.USER
    }
}
