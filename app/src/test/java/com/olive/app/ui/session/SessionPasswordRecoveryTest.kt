package com.olive.app.ui.session

import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.functions.FirebaseFunctionsException
import com.olive.app.R
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountKeysetRepository
import com.olive.app.data.account.AccountRecoveryAuthenticator
import com.olive.app.data.account.AccountRecoveryCallable
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.OpaqueClientProtocol
import com.olive.app.data.account.OpaqueRecoveryRepository
import com.olive.app.data.account.UserProfile
import com.olive.app.security.AccountKeysetEnvelope
import com.olive.app.security.AccountKeysetLocalStore
import com.olive.app.security.AccountRecoveryKey
import com.olive.app.security.AccountRecoveryKeyDeviceRecord
import com.olive.app.security.InputPolicy
import com.olive.app.support.SessionRuntimeFixture
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.KeyGenerator

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
        authenticator = AccountRecoveryAuthenticator { _, _ -> },
        opaqueClient = fakeOpaqueClient,
    )

    @Test
    fun unlockThrottledMapsResourceExhaustedToThrottledMessage() = runBlocking<Unit> {
        val throttledClient = object : OpaqueClientProtocol {
            override suspend fun startRegistration(password: String) = OpaqueClientProtocol.RegistrationStart("state", "req")
            override suspend fun finishRegistration(state: String, response: String, password: String) =
                OpaqueClientProtocol.RegistrationFinish("record", ByteArray(32))
            override suspend fun startLogin(password: String) = OpaqueClientProtocol.LoginStart("loginState", "startReq")
            override suspend fun finishLogin(state: String, response: String, password: String) =
                OpaqueClientProtocol.LoginFinish("finishReq", ByteArray(32))
        }
        val throttledRepo = OpaqueRecoveryRepository(
            callable = AccountRecoveryCallable { _, _ ->
                kotlinx.coroutines.delay(1)
                throw functionsFailure(
                    "Too many failed recovery attempts.",
                    FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED,
                )
            },
            authenticator = AccountRecoveryAuthenticator { _, _ -> },
            opaqueClient = throttledClient,
        )
        val keysetRepo = mock(com.olive.app.data.account.AccountKeysetRepository::class.java)
        `when`(keysetRepo.fetch(uid)).thenReturn(AccountKeysetEnvelope.create(uid, AccountRecoveryKey.generate()))

        SessionRuntimeFixture(
            accountKeysetsOverride = keysetRepo,
            opaqueRecoveryOverride = throttledRepo,
        ).use { fixture ->
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

            runtime.passwordRecovery.unlockWithPasswordRecovery(password)
            fixture.await { !runtime.state.busy }

            assertEquals(
                runtime.application.getString(R.string.password_recovery_error_throttled),
                runtime.state.message,
            )
        }
    }

    @Test
    fun recoverAccountRecoveryKeyPromptsForPasswordUnlocksAndProtectsDeviceCopy() = runBlocking<Unit> {
        val ark = AccountRecoveryKey.generate()
        val keyset = AccountKeysetEnvelope.create(uid, ark)
        val exportKey = ByteArray(32) { 5 }
        val envelope = com.olive.app.security.OpaquePasswordRecoveryEnvelope.create(
            accountId = uid,
            opaqueRecordId = "rec_1234567890abcdef",
            exportKey = exportKey.copyOf(),
            recoveryKey = ark,
        )
        val unlockClient = object : OpaqueClientProtocol {
            override suspend fun startRegistration(password: String) = error("Unused")
            override suspend fun finishRegistration(state: String, response: String, password: String) = error("Unused")
            override suspend fun startLogin(password: String) = OpaqueClientProtocol.LoginStart("loginState", "startReq")
            override suspend fun finishLogin(state: String, response: String, password: String) =
                OpaqueClientProtocol.LoginFinish("finishReq", exportKey.copyOf())
        }
        val unlockRepo = OpaqueRecoveryRepository(
            callable = AccountRecoveryCallable { name, _ ->
                when (name) {
                    "opaqueRecoveryBeginUnlock" -> mapOf("sessionId" to "sess_1", "loginResponse" to "srvLogin")
                    "opaqueRecoveryFinishUnlock" -> mapOf("envelope" to envelope.toMap())
                    else -> error("Unexpected call: $name")
                }
            },
            authenticator = AccountRecoveryAuthenticator { _, _ -> },
            opaqueClient = unlockClient,
        )

        SessionRuntimeFixture(opaqueRecoveryOverride = unlockRepo).use { fixture ->
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

            var recovered: AccountRecoveryKey? = null
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                recovered = runtime.passwordRecovery.recoverAccountRecoveryKey(uid, keyset, runtime.epoch)
            }
            fixture.await { runtime.state.passwordRecoveryUnlockRequest != null || job.isCompleted }
            assertNull(runtime.state.deviceLockRequest)
            runtime.state.passwordRecoveryUnlockRequest?.complete(password)
            job.join()

            assertNull(runtime.state.deviceLockRequest)
            assertNotNull(recovered)
            assertArrayEquals(ark.encodedCopy(), recovered!!.encodedCopy())
            assertNull(runtime.state.passwordRecoveryUnlockRequest)
            val saved = runtime.accountKeysets.read(uid)
            assertNotNull(saved)
            assertEquals(AccountRecoveryKeyDeviceRecord.VERSION_APP_MANAGED, saved?.deviceRecord?.version)

            // Cancellation must fail cleanly without leaving an active request
            var cancelThrown: Throwable? = null
            val cancelJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                cancelThrown = runCatching {
                    runtime.passwordRecovery.recoverAccountRecoveryKey(uid, keyset, runtime.epoch)
                }.exceptionOrNull()
            }
            fixture.await { runtime.state.passwordRecoveryUnlockRequest != null || cancelJob.isCompleted }
            assertNull(runtime.state.deviceLockRequest)
            runtime.state.passwordRecoveryUnlockRequest?.complete(null)
            cancelJob.join()
            assertTrue(cancelThrown != null)
            assertNull(runtime.state.deviceLockRequest)
            assertNull(runtime.state.passwordRecoveryUnlockRequest)
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
            authenticator = AccountRecoveryAuthenticator { _, _ ->
                throw FirebaseAuthInvalidCredentialsException("ERROR_WRONG_PASSWORD", "The password is invalid")
            },
            opaqueClient = fakeOpaqueClient,
        )

        SessionRuntimeFixture(accountKeysetsOverride = unenrolledKeysets(), opaqueRecoveryOverride = failingAuthRepo).use { fixture ->
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

            enrollDeviceArk(runtime)

            startEnrollment(runtime, "WrongPassword123!")
            fixture.await { !runtime.state.busy }

            assertNull(runtime.state.deviceLockRequest)
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
            authenticator = AccountRecoveryAuthenticator { _, _ ->
                throw FirebaseAuthInvalidUserException("ERROR_USER_NOT_FOUND", "User not found")
            },
            opaqueClient = fakeOpaqueClient,
        )

        SessionRuntimeFixture(accountKeysetsOverride = unenrolledKeysets(), opaqueRecoveryOverride = expiredAuthRepo).use { fixture ->
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

            enrollDeviceArk(runtime)

            startEnrollment(runtime, "Password123!")
            fixture.await { !runtime.state.busy }

            assertNull(runtime.state.deviceLockRequest)
            assertEquals(
                runtime.application.getString(R.string.password_recovery_error_session_expired),
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
            authenticator = AccountRecoveryAuthenticator { _, _ -> },
            opaqueClient = failingClient,
        )

        SessionRuntimeFixture(accountKeysetsOverride = unenrolledKeysets(), opaqueRecoveryOverride = runtimeFailingRepo).use { fixture ->
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

            enrollDeviceArk(runtime)

            startEnrollment(runtime, "Password123!")
            fixture.await { !runtime.state.busy }

            assertNull(runtime.state.deviceLockRequest)
            assertEquals(
                runtime.application.getString(R.string.password_recovery_error_runtime_unavailable),
                runtime.state.message
            )
        }
    }

    /**
     * Verifies that an OPAQUE client bridge operation failure maps to the runtime-unavailable message
     * rather than generic OPK-01.
     */
    @Test
    fun enablePasswordRecoveryWithOpaqueClientOperationFailureShowsRuntimeUnavailableMessage() = runBlocking<Unit> {
        val clientFailingClient = object : OpaqueClientProtocol {
            override suspend fun startRegistration(password: String): OpaqueClientProtocol.RegistrationStart {
                throw IllegalStateException("OPAQUE client operation failed")
            }
            override suspend fun finishRegistration(state: String, response: String, password: String) =
                OpaqueClientProtocol.RegistrationFinish("record", ByteArray(64))
            override suspend fun startLogin(password: String) = OpaqueClientProtocol.LoginStart("loginState", "startReq")
            override suspend fun finishLogin(state: String, response: String, password: String) = null
        }

        val clientFailingRepo = OpaqueRecoveryRepository(
            callable = AccountRecoveryCallable { _, _ -> emptyMap<String, Any?>() },
            authenticator = AccountRecoveryAuthenticator { _, _ -> },
            opaqueClient = clientFailingClient,
        )

        SessionRuntimeFixture(accountKeysetsOverride = unenrolledKeysets(), opaqueRecoveryOverride = clientFailingRepo).use { fixture ->
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

            enrollDeviceArk(runtime)

            startEnrollment(runtime, "Password123!")
            fixture.await { !runtime.state.busy }

            assertNull(runtime.state.deviceLockRequest)
            assertEquals(
                runtime.application.getString(R.string.password_recovery_error_runtime_unavailable),
                runtime.state.message
            )
        }
    }

    /**
     * Verifies that cancelling OPAQUE password recovery unlock during recovery displays
     * the unlock cancelled message without triggering device lock prompts.
     */
    @Test
    fun accountRecoveryKeyUnlockWithCancelledPasswordRecoveryShowsCancelledMessage() = runBlocking<Unit> {
        val ark = AccountRecoveryKey.generate()
        val keyset = AccountKeysetEnvelope.create(uid, ark)
        val keysetRepo = mock(AccountKeysetRepository::class.java)
        `when`(keysetRepo.fetch(uid)).thenReturn(keyset)

        SessionRuntimeFixture(
            accountKeysetsOverride = keysetRepo,
            opaqueRecoveryOverride = fakeRecoveryRepo,
        ).use { fixture ->
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

            runtime.operations.runOperation(
                "Recovery unlock failed",
                failureFor = runtime.passwordRecovery::passwordRecoveryFailure,
            ) { token ->
                runtime.settings.accountRecoveryKey(uid, token)
            }
            fixture.await { runtime.state.passwordRecoveryUnlockRequest != null || !runtime.state.busy }
            assertNull(runtime.state.deviceLockRequest)
            runtime.state.passwordRecoveryUnlockRequest?.complete(null)
            fixture.await { !runtime.state.busy }

            assertNull(runtime.state.deviceLockRequest)
            assertEquals(
                runtime.application.getString(R.string.password_recovery_unlock_cancelled),
                runtime.state.message
            )
        }
    }

    /**
     * Verifies that a legacy v1 device-auth record in local storage does not trigger biometrics,
     * recovers via OPAQUE password prompt, migrates local storage to v2, and forgets the v1 alias.
     */
    @Test
    fun accountRecoveryKeyMigratesLegacyV1RecordViaOpaqueWithoutBiometrics() = runBlocking<Unit> {
        val ark = AccountRecoveryKey.generate()
        val keyset = AccountKeysetEnvelope.create(uid, ark)
        val exportKey = ByteArray(32) { 9 }
        val envelope = com.olive.app.security.OpaquePasswordRecoveryEnvelope.create(
            accountId = uid,
            opaqueRecordId = "rec_legacy_v1_migration",
            exportKey = exportKey.copyOf(),
            recoveryKey = ark,
        )
        val unlockClient = object : OpaqueClientProtocol {
            override suspend fun startRegistration(password: String) = error("Unused")
            override suspend fun finishRegistration(state: String, response: String, password: String) = error("Unused")
            override suspend fun startLogin(password: String) = OpaqueClientProtocol.LoginStart("loginState", "startReq")
            override suspend fun finishLogin(state: String, response: String, password: String) =
                OpaqueClientProtocol.LoginFinish("finishReq", exportKey.copyOf())
        }
        val unlockRepo = OpaqueRecoveryRepository(
            callable = AccountRecoveryCallable { name, _ ->
                when (name) {
                    "opaqueRecoveryBeginUnlock" -> mapOf("sessionId" to "sess_v1", "loginResponse" to "srvLogin")
                    "opaqueRecoveryFinishUnlock" -> mapOf("envelope" to envelope.toMap())
                    else -> error("Unexpected call: $name")
                }
            },
            authenticator = AccountRecoveryAuthenticator { _, _ -> },
            opaqueClient = unlockClient,
        )
        val keysetRepo = mock(com.olive.app.data.account.AccountKeysetRepository::class.java)
        `when`(keysetRepo.fetch(uid)).thenReturn(keyset)

        SessionRuntimeFixture(
            accountKeysetsOverride = keysetRepo,
            opaqueRecoveryOverride = unlockRepo,
        ).use { fixture ->
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

            val legacyProtectionId = UUID.randomUUID().toString()
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(uid.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val legacyAlias = "olive.account.ark.v1.$hash.$legacyProtectionId"
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.setKeyEntry(legacyAlias, KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(), null, null)
            val legacyRecord = AccountRecoveryKeyDeviceRecord(
                accountId = uid,
                protectionId = legacyProtectionId,
                ivBase64 = Base64.getEncoder().encodeToString(ByteArray(12) { 3 }),
                ciphertextBase64 = Base64.getEncoder().encodeToString(ByteArray(48) { 4 }),
                version = AccountRecoveryKeyDeviceRecord.VERSION_DEVICE_AUTH,
            )
            runtime.accountKeysets.save(AccountKeysetLocalStore.Saved(keyset, legacyRecord))

            var recovered: AccountRecoveryKey? = null
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
                recovered = runtime.settings.accountRecoveryKey(uid, runtime.epoch)
            }
            fixture.await { runtime.state.passwordRecoveryUnlockRequest != null || job.isCompleted }
            assertNull(runtime.state.deviceLockRequest)
            runtime.state.passwordRecoveryUnlockRequest?.complete(password)
            job.join()

            assertNull(runtime.state.deviceLockRequest)
            assertNotNull(recovered)
            assertArrayEquals(ark.encodedCopy(), recovered!!.encodedCopy())
            val migrated = runtime.accountKeysets.read(uid)
            assertNotNull(migrated)
            assertEquals(AccountRecoveryKeyDeviceRecord.VERSION_APP_MANAGED, migrated?.deviceRecord?.version)
            assertNotEquals(legacyProtectionId, migrated?.deviceRecord?.protectionId)
            assertFalse(store.containsAlias(legacyAlias))
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
            authenticator = AccountRecoveryAuthenticator { _, _ -> },
            opaqueClient = fakeOpaqueClient,
        )

        SessionRuntimeFixture(accountKeysetsOverride = unenrolledKeysets(), opaqueRecoveryOverride = serverUnconfiguredRepo).use { fixture ->
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

            enrollDeviceArk(runtime)

            startEnrollment(runtime, "Password123!")
            fixture.await { !runtime.state.busy }

            assertNull(runtime.state.deviceLockRequest)
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

            val failure = runCatching {
                runtime.passwordRecovery.enrollAfterAuthentication(uid, password, runtime.epoch, true)
            }.exceptionOrNull()
            assertEquals("Recovery session changed", failure?.message)
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
            authenticator = AccountRecoveryAuthenticator { _, _ -> },
            opaqueClient = rotatingClient,
        )

        val keysetRepo = mock(com.olive.app.data.account.AccountKeysetRepository::class.java)

        SessionRuntimeFixture(
            accountKeysetsOverride = keysetRepo,
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
            enrollDeviceArk(runtime)

            val newPassword = "NewPassword456!"
            runtime.settings.changePassword(password, newPassword, newPassword)
            fixture.await { !runtime.state.busy }

            assertNull(runtime.state.deviceLockRequest)
            assertEquals("password_changed", runtime.state.message)
            assertEquals("rotated", rotatedOld)
        }
    }

    @Test
    fun signupRetryEnrollsRecoveryWithAccountPasswordWithoutCreatingAnotherAccount() = runBlocking<Unit> {
        val identity = AccountIdentity(uid, "fixture_owner")
        val keysets = unenrolledKeysets()
        var authenticatedPassword: String? = null
        var finished = false
        var registrationAttempts = 0
        val recovery = OpaqueRecoveryRepository(
            callable = AccountRecoveryCallable { name, _ ->
                when (name) {
                    "opaqueRecoveryBeginRegistration" -> {
                        registrationAttempts++
                        if (registrationAttempts == 1) {
                            throw functionsFailure("Temporary registration failure", FirebaseFunctionsException.Code.UNAVAILABLE)
                        }
                        mapOf("registrationResponse" to "response")
                    }
                    "opaqueRecoveryFinishRegistration" -> {
                        finished = true
                        mapOf("success" to true)
                    }
                    else -> error("Unexpected callable")
                }
            },
            authenticator = AccountRecoveryAuthenticator { _, enteredPassword -> authenticatedPassword = enteredPassword },
            opaqueClient = fakeOpaqueClient,
        )
        SessionRuntimeFixture(accountKeysetsOverride = keysets, opaqueRecoveryOverride = recovery).use { fixture ->
            val runtime = fixture.runtime
            runtime.enrollment = null
            runtime.state.screen = SessionScreen.NewCredentials
            `when`(fixture.accounts.authenticatedUid()).thenReturn(uid)
            `when`(fixture.accounts.create(identity.username, password, AccountRole.USER)).thenReturn(identity)
            `when`(fixture.accounts.authenticate(identity.username, password, AccountRole.USER)).thenReturn(identity)

            runtime.accountSignIn.authenticate(identity.username, password, create = true)
            fixture.await { !runtime.state.busy }
            assertNull(runtime.state.deviceLockRequest)
            assertEquals(SessionScreen.NewCredentials, runtime.state.screen)
            assertFalse(finished)

            runtime.accountSignIn.authenticate(identity.username, password, create = true)
            fixture.await { !runtime.state.busy }
            assertNull(runtime.state.deviceLockRequest)
            assertEquals(SessionScreen.ProfileSetup, runtime.state.screen)
            assertEquals(password, authenticatedPassword)
            assertTrue(finished)
            assertTrue(runtime.state.passwordRecoveryEnrolled == true)
            assertNotNull(runtime.accountKeysets.read(uid))
            verify(fixture.accounts).create(identity.username, password, AccountRole.USER)
            verify(fixture.accounts).authenticate(identity.username, password, AccountRole.USER)
        }
    }

    @Test
    fun existingRecoveryEnrollmentDoesNotPromptOrReplaceItsKeyset() = runBlocking<Unit> {
        val keysets = mock(AccountKeysetRepository::class.java)
        `when`(keysets.isPasswordRecoveryEnrolled(uid)).thenReturn(true)
        fixture(keysets).use { fixture ->
            val runtime = fixture.runtime
            runtime.passwordRecovery.enrollAfterAuthentication(uid, password, runtime.epoch, setupRequired = false)
            assertTrue(runtime.state.passwordRecoveryEnrolled == true)
            assertNull(runtime.state.deviceLockRequest)
            assertNull(runtime.accountKeysets.read(uid))
        }
    }

    @Test
    fun encryptedAccessRestoreFailureMapsNotEnrolledAndFallsBackForUnexpectedErrors() = runBlocking<Unit> {
        fixture().use { fixture ->
            val runtime = fixture.runtime
            val notEnrolled = functionsFailure(
                "No password recovery enrolled for this account.",
                FirebaseFunctionsException.Code.NOT_FOUND,
            )

            assertEquals(
                runtime.application.getString(R.string.password_recovery_error_not_enrolled),
                runtime.passwordRecovery.passwordRecoveryFailure(notEnrolled),
            )
            assertEquals(
                runtime.application.getString(R.string.password_recovery_error_not_enrolled),
                runtime.passwordRecovery.encryptedAccessRestoreFailure(notEnrolled),
            )
            assertEquals(
                runtime.application.getString(R.string.encrypted_access_restore_failed),
                runtime.passwordRecovery.encryptedAccessRestoreFailure(RuntimeException("unexpected")),
            )
        }
    }

    @Test
    fun canRecoverAccountKeysetRequiresLocalAppManagedArkOrEnrolledPasswordRecovery() = runBlocking<Unit> {
        val keysets = unenrolledKeysets()
        fixture(keysets).use { fixture ->
            val runtime = fixture.runtime

            assertFalse(runtime.settings.canRecoverAccountKeyset(uid))

            `when`(keysets.isPasswordRecoveryEnrolled(uid)).thenReturn(true)
            assertTrue(runtime.settings.canRecoverAccountKeyset(uid))

            `when`(keysets.isPasswordRecoveryEnrolled(uid)).thenReturn(false)
            enrollDeviceArk(runtime)
            assertTrue(runtime.settings.canRecoverAccountKeyset(uid))
        }
    }

    private suspend fun unenrolledKeysets() = mock(AccountKeysetRepository::class.java).also {
        `when`(it.isPasswordRecoveryEnrolled(uid)).thenReturn(false)
    }

    private fun startEnrollment(runtime: SessionRuntime, password: String) {
        runtime.operations.runOperation("Recovery enrollment failed", failureFor = runtime.passwordRecovery::passwordRecoveryFailure) { token ->
            runtime.passwordRecovery.enrollAfterAuthentication(uid, password, token, setupRequired = true)
        }
    }

    private fun fixture(keysets: AccountKeysetRepository? = null) = SessionRuntimeFixture(
        accountKeysetsOverride = keysets,
        opaqueRecoveryOverride = fakeRecoveryRepo,
    ).also { fixture ->
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
     * @param runtime the active session runtime.
     */
    private suspend fun enrollDeviceArk(runtime: SessionRuntime) {
        val ark = com.olive.app.security.AccountRecoveryKey.generate()
        val envelope = com.olive.app.security.AccountKeysetEnvelope.create(uid, ark)
        runtime.settings.protectDeviceRecoveryKey(uid, ark, envelope, runtime.epoch)
        assertNull(runtime.state.deviceLockRequest)
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
