package com.olive.app.security

import com.olive.app.data.account.AccountRecoveryCallable
import com.olive.app.data.account.OpaqueClientProtocol
import com.olive.app.data.account.OpaqueRecoveryRepository
import com.olive.app.data.account.PasskeyRecoveryAuthenticator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.UUID

class OpaqueRecoveryRepositoryTest {

    private val accountId = "test-uid-123"
    private val password = "test-password-456"
    private val exportKey = ByteArray(32) { 7 }
    private val recoveryKey = AccountRecoveryKey.generate()

    @Test
    fun `enrollment performs complete OPAQUE registration exchange and publishes envelope`() = runBlocking {
        val calls = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fakeCallable = AccountRecoveryCallable { name, data ->
            calls += name to data
            when (name) {
                "opaqueRecoveryBeginRegistration" -> mapOf("registrationResponse" to "server-reg-resp")
                "opaqueRecoveryFinishRegistration" -> mapOf("success" to true, "opaqueRecordId" to data["opaqueRecordId"])
                else -> error("Unexpected call: $name")
            }
        }

        var reauthenticated = false
        val fakeAuthenticator = PasskeyRecoveryAuthenticator { _, _ -> reauthenticated = true }

        val fakeOpaqueClient = object : OpaqueClientProtocol {
            override suspend fun startRegistration(password: String) =
                OpaqueClientProtocol.RegistrationStart("state-1", "client-reg-req")

            override suspend fun finishRegistration(state: String, response: String, password: String) =
                OpaqueClientProtocol.RegistrationFinish("reg-record-string", exportKey)

            override suspend fun startLogin(password: String) = error("Not called")
            override suspend fun finishLogin(state: String, response: String, password: String) = error("Not called")
        }

        val repository = OpaqueRecoveryRepository(
            callable = fakeCallable,
            authenticator = fakeAuthenticator,
            opaqueClient = fakeOpaqueClient,
        )

        val envelope = repository.enroll(accountId, password, recoveryKey)

        assertTrue(reauthenticated)
        assertEquals(accountId, envelope.accountId)
        assertEquals("OPAQUE-3DH-HKDF-SHA256-AES-256-GCM", envelope.algorithm)
        assertEquals(listOf("opaqueRecoveryBeginRegistration", "opaqueRecoveryFinishRegistration"), calls.map { it.first })
        assertEquals("client-reg-req", calls[0].second["registrationRequest"])
        assertEquals("reg-record-string", calls[1].second["registrationRecord"])
    }

    @Test
    fun `unlock performs OPAQUE login exchange and recovers original ARK`() = runBlocking {
        val recordId = "rec_" + UUID.randomUUID().toString().replace("-", "")
        val envelope = OpaquePasswordRecoveryEnvelope.create(
            accountId = accountId,
            opaqueRecordId = recordId,
            exportKey = exportKey,
            recoveryKey = recoveryKey,
        )

        val calls = mutableListOf<String>()
        val fakeCallable = AccountRecoveryCallable { name, _ ->
            calls += name
            when (name) {
                "opaqueRecoveryBeginUnlock" -> mapOf("sessionId" to "sess-123", "loginResponse" to "server-login-resp")
                "opaqueRecoveryFinishUnlock" -> mapOf("envelope" to envelope.toMap())
                else -> error("Unexpected call: $name")
            }
        }

        val fakeOpaqueClient = object : OpaqueClientProtocol {
            override suspend fun startRegistration(password: String) = error("Not called")
            override suspend fun finishRegistration(state: String, response: String, password: String) = error("Not called")

            override suspend fun startLogin(password: String) =
                OpaqueClientProtocol.LoginStart("login-state-1", "start-login-req")

            override suspend fun finishLogin(state: String, response: String, password: String) =
                if (password == "test-password-456") {
                    OpaqueClientProtocol.LoginFinish("finish-login-req", exportKey)
                } else null
        }

        val repository = OpaqueRecoveryRepository(
            callable = fakeCallable,
            opaqueClient = fakeOpaqueClient,
        )

        val recoveredArk = repository.unlock(accountId, password)

        assertEquals(
            Base64.getEncoder().encodeToString(recoveryKey.encodedCopy()),
            Base64.getEncoder().encodeToString(recoveredArk.encodedCopy()),
        )
        assertEquals(listOf("opaqueRecoveryBeginUnlock", "opaqueRecoveryFinishUnlock"), calls)
    }

    @Test
    fun `wrong password fails unlock without unwrap`() {
        val fakeCallable = AccountRecoveryCallable { name, _ ->
            when (name) {
                "opaqueRecoveryBeginUnlock" -> mapOf("sessionId" to "sess-123", "loginResponse" to "server-login-resp")
                else -> error("Unexpected call: $name")
            }
        }

        val fakeOpaqueClient = object : OpaqueClientProtocol {
            override suspend fun startRegistration(password: String) = error("Not called")
            override suspend fun finishRegistration(state: String, response: String, password: String) = error("Not called")
            override suspend fun startLogin(password: String) =
                OpaqueClientProtocol.LoginStart("login-state-1", "start-login-req")
            override suspend fun finishLogin(state: String, response: String, password: String) = null
        }

        val repository = OpaqueRecoveryRepository(
            callable = fakeCallable,
            opaqueClient = fakeOpaqueClient,
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                repository.unlock(accountId, "wrong-password")
            }
        }
    }

    @Test
    fun `password change rotates OPAQUE record and recovery envelope`() = runBlocking {
        val calls = mutableListOf<Pair<String, Map<String, Any?>>>()
        val fakeCallable = AccountRecoveryCallable { name, data ->
            calls += name to data
            when (name) {
                "opaqueRecoveryBeginRegistration" -> mapOf("registrationResponse" to "new-reg-resp")
                "opaqueRecoveryRotate" -> mapOf("success" to true, "opaqueRecordId" to data["opaqueRecordId"])
                else -> error("Unexpected call: $name")
            }
        }

        var reauthenticated = false
        val fakeAuthenticator = PasskeyRecoveryAuthenticator { _, _ -> reauthenticated = true }

        val newExportKey = ByteArray(32) { 9 }
        val fakeOpaqueClient = object : OpaqueClientProtocol {
            override suspend fun startRegistration(password: String) =
                OpaqueClientProtocol.RegistrationStart("new-state-1", "new-reg-req")

            override suspend fun finishRegistration(state: String, response: String, password: String) =
                OpaqueClientProtocol.RegistrationFinish("new-reg-record", newExportKey)

            override suspend fun startLogin(password: String) = error("Not called")
            override suspend fun finishLogin(state: String, response: String, password: String) = error("Not called")
        }

        val repository = OpaqueRecoveryRepository(
            callable = fakeCallable,
            authenticator = fakeAuthenticator,
            opaqueClient = fakeOpaqueClient,
        )

        val newEnvelope = repository.rotate(accountId, "old-password", "new-password", recoveryKey)

        assertTrue(reauthenticated)
        assertEquals(accountId, newEnvelope.accountId)
        assertEquals(listOf("opaqueRecoveryBeginRegistration", "opaqueRecoveryRotate"), calls.map { it.first })
        assertEquals("new-reg-req", calls[0].second["registrationRequest"])
        assertEquals("new-reg-record", calls[1].second["registrationRecord"])

        // Verify that the repository cleared the mutable exportKey buffer
        assertTrue(newExportKey.all { it == 0.toByte() })

        val recoveredArk = newEnvelope.open(accountId, newEnvelope.opaqueRecordId, ByteArray(32) { 9 })
        assertEquals(
            Base64.getEncoder().encodeToString(recoveryKey.encodedCopy()),
            Base64.getEncoder().encodeToString(recoveredArk.encodedCopy()),
        )
    }

    @Test
    fun `removal sends remove callable after reauthenticating`() = runBlocking {
        var reauthenticated = false
        val calls = mutableListOf<String>()
        val fakeCallable = AccountRecoveryCallable { name, _ ->
            calls += name
            mapOf("success" to true)
        }
        val fakeAuthenticator = PasskeyRecoveryAuthenticator { _, _ -> reauthenticated = true }
        val fakeOpaqueClient = object : OpaqueClientProtocol {
            override suspend fun startRegistration(password: String) = error("Not called")
            override suspend fun finishRegistration(state: String, response: String, password: String) = error("Not called")
            override suspend fun startLogin(password: String) = error("Not called")
            override suspend fun finishLogin(state: String, response: String, password: String) = error("Not called")
        }

        val repository = OpaqueRecoveryRepository(
            callable = fakeCallable,
            authenticator = fakeAuthenticator,
            opaqueClient = fakeOpaqueClient,
        )

        repository.remove(accountId, password)

        assertTrue(reauthenticated)
        assertEquals(listOf("opaqueRecoveryRemove"), calls)
    }
}
