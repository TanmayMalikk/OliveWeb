package com.olive.app.ui.session

import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.functions.FirebaseFunctionsException
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

    /**
     * Verifies that entering an invalid account password during failsafe recovery enrollment
     * presents an explicit incorrect password message instead of a generic protocol error code.
     */
    @Test
    fun enablePasswordRecoveryWithIncorrectAccountPasswordShowsIncorrectPasswordMessage() = runBlocking<Unit> {
        val failingAuthRepo = OpaqueRecoveryRepository(
            callable = AccountRecoveryCallable { _, _ -> emptyMap<String, Any?>() },
            authenticator = PasskeyRecoveryAuthenticator { _, _ ->
                throw FirebaseAuthInvalidCredentialsException("ERROR_WRONG_PASSWORD", "The password is invalid")
            },
            opaqueClient = fakeOpaqueClient,
        )

        SessionRuntimeFixture(opaqueRecoveryOverride = failingAuthRepo).use { fixture ->
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

            enrollDeviceArk(fixture, runtime)

            runtime.passwordRecovery.enablePasswordRecovery("WrongPassword123!")
            answerDeviceLock(fixture, runtime)

            assertEquals(
                runtime.application.getString(R.string.password_recovery_error_incorrect),
                runtime.state.message
            )
        }
    }

    /**
     * Verifies that attempting to enroll password recovery with an expired authentication session
     * notifies the user to sign in again.
     */
    @Test
    fun enablePasswordRecoveryWithExpiredSessionShowsSessionExpiredMessage() = runBlocking<Unit> {
        val expiredAuthRepo = OpaqueRecoveryRepository(
            callable = AccountRecoveryCallable { _, _ -> emptyMap<String, Any?>() },
            authenticator = PasskeyRecoveryAuthenticator { _, _ ->
                throw FirebaseAuthInvalidUserException("ERROR_USER_NOT_FOUND", "User not found")
            },
            opaqueClient = fakeOpaqueClient,
        )

        SessionRuntimeFixture(opaqueRecoveryOverride = expiredAuthRepo).use { fixture ->
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

            enrollDeviceArk(fixture, runtime)

            runtime.passwordRecovery.enablePasswordRecovery("Password123!")
            answerDeviceLock(fixture, runtime)

            assertEquals(
                runtime.application.getString(R.string.passkey_recovery_reauth_session_expired),
                runtime.state.message
            )
        }
    }

    /**
     * Verifies that failure to initialize the local cryptographic runtime surfaces a descriptive
     * message rather than an ambiguous protocol error code.
     */
    @Test
    fun enablePasswordRecoveryWithBridgeInitializationFailureShowsDescriptiveRuntimeMessage() = runBlocking<Unit> {
        val failingClient = object : OpaqueClientProtocol {
            override suspend fun startRegistration(password: String): OpaqueClientProtocol.RegistrationStart {
                throw IllegalStateException("Failed to initialize OPAQUE WebAssembly runtime")
            }
            override suspend fun finishRegistration(state: String, response: String, password: String) =
                OpaqueClientProtocol.RegistrationFinish("record", ByteArray(32))
            override suspend fun startLogin(password: String) = OpaqueClientProtocol.LoginStart("loginState", "startReq")
            override suspend fun finishLogin(state: String, response: String, password: String) = null
        }

        val runtimeFailingRepo = OpaqueRecoveryRepository(
            callable = AccountRecoveryCallable { _, _ -> emptyMap<String, Any?>() },
            authenticator = PasskeyRecoveryAuthenticator { _, _ -> },
            opaqueClient = failingClient,
        )

        SessionRuntimeFixture(opaqueRecoveryOverride = runtimeFailingRepo).use { fixture ->
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

            enrollDeviceArk(fixture, runtime)

            runtime.passwordRecovery.enablePasswordRecovery("Password123!")
            answerDeviceLock(fixture, runtime)

            assertEquals(
                runtime.application.getString(R.string.password_recovery_error_runtime_unavailable),
                runtime.state.message
            )
        }
    }

    /**
     * Verifies that cancelling device lock authentication displays the setup cancelled message.
     */
    @Test
    fun enablePasswordRecoveryWithCancelledDeviceLockShowsCancelledMessage() = runBlocking<Unit> {
        fixture().use { fixture ->
            val runtime = fixture.runtime
            enrollDeviceArk(fixture, runtime)

            runtime.passwordRecovery.enablePasswordRecovery("Password123!")
            fixture.await { runtime.state.deviceLockRequest != null }
            runtime.state.deviceLockRequest?.complete(null)
            fixture.await { !runtime.state.busy }

            assertEquals(
                runtime.application.getString(R.string.passkey_recovery_setup_cancelled),
                runtime.state.message
            )
        }
    }

    /**
     * Verifies that server unconfigured state displays a runtime unavailable message rather than generic OPK-01.
     */
    @Test
    fun enablePasswordRecoveryWithServerNotConfiguredShowsRuntimeUnavailableMessage() = runBlocking<Unit> {
        val serverUnconfiguredRepo = OpaqueRecoveryRepository(
            callable = AccountRecoveryCallable { _, _ ->
                throw functionsFailure("OPAQUE recovery is not configured.", FirebaseFunctionsException.Code.FAILED_PRECONDITION)
            },
            authenticator = PasskeyRecoveryAuthenticator { _, _ -> },
            opaqueClient = fakeOpaqueClient,
        )

        SessionRuntimeFixture(opaqueRecoveryOverride = serverUnconfiguredRepo).use { fixture ->
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

            enrollDeviceArk(fixture, runtime)

            runtime.passwordRecovery.enablePasswordRecovery("Password123!")
            answerDeviceLock(fixture, runtime)

            assertEquals(
                runtime.application.getString(R.string.password_recovery_error_runtime_unavailable),
                runtime.state.message
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

    /**
     * Enrolls an in-memory device account recovery key (ARK) so recovery operations have an active key.
     *
     * @param fixture the active test session runtime fixture.
     * @param runtime the active session runtime.
     */
    private suspend fun enrollDeviceArk(fixture: SessionRuntimeFixture, runtime: SessionRuntime) {
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
    }

    /**
     * Answers pending device lock requests during recovery execution and waits until runtime is no longer busy.
     *
     * @param fixture the active test session runtime fixture.
     * @param runtime the active session runtime.
     */
    private suspend fun answerDeviceLock(fixture: SessionRuntimeFixture, runtime: SessionRuntime) {
        fixture.await { runtime.state.deviceLockRequest != null || !runtime.state.busy }
        runtime.state.deviceLockRequest?.let { req ->
            val cipher = req.challenge.promptCipher ?: req.challenge.legacyCipher()
            req.complete(cipher)
        }
        fixture.await { !runtime.state.busy }
    }

    /**
     * Constructs a synthetic FirebaseFunctionsException instance for testing.
     *
     * @param message failure description.
     * @param code Firebase Functions status code.
     * @return constructed FirebaseFunctionsException.
     */
    private fun functionsFailure(message: String, code: FirebaseFunctionsException.Code): FirebaseFunctionsException =
        FirebaseFunctionsException::class.java.getDeclaredConstructor(
            String::class.java, FirebaseFunctionsException.Code::class.java, Any::class.java
        ).apply { isAccessible = true }.newInstance(message, code, null)
}
