package com.olive.app.security

import com.olive.app.data.account.AccountRecoveryCallable
import com.olive.app.data.account.PasskeyRecoveryRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64
import java.util.UUID

class AccountKeysetLocalStoreTest {
    @Test
    fun `keysets remain isolated by account and purge only the selected account`() {
        val store = AccountKeysetLocalStore(FakeVault())
        val first = saved("account-a")
        val second = saved("account-b")

        store.save(first)
        store.save(second)

        assertEquals(first.envelope.keyId, store.read("account-a")?.envelope?.keyId)
        assertEquals(second.envelope.keyId, store.read("account-b")?.envelope?.keyId)
        assertEquals(first.deviceRecord, store.purge("account-a"))
        assertNull(store.read("account-a"))
        assertEquals(second.envelope.keyId, store.read("account-b")?.envelope?.keyId)
    }

    @Test
    fun `mismatched stored account records fail closed`() {
        val store = AccountKeysetLocalStore(FakeVault())
        val envelope = AccountKeysetEnvelope.create("account-a", AccountRecoveryKey.generate())
        val record = deviceRecord("account-b")

        assertThrows(IllegalArgumentException::class.java) {
            store.save(AccountKeysetLocalStore.Saved(envelope, record))
        }
    }

    private fun saved(accountId: String) = AccountKeysetLocalStore.Saved(
        AccountKeysetEnvelope.create(accountId, AccountRecoveryKey.generate()),
        deviceRecord(accountId),
    )

    private fun deviceRecord(accountId: String) = AccountRecoveryKeyDeviceRecord(
        accountId,
        UUID.randomUUID().toString(),
        Base64.getEncoder().encodeToString(ByteArray(12)),
        Base64.getEncoder().encodeToString(ByteArray(17)),
    )

    private class FakeVault : JsonVault {
        private var root = JSONObject()
        override fun read() = JSONObject(root.toString())
        override fun update(change: (JSONObject) -> Unit) = change(root)
        override fun clear() { root = JSONObject() }
    }
}

class PasskeyRecoveryRepositoryTest {
    @Test
    fun `enrollment sends only sanitized verifier responses and opaque envelope`() = runBlocking {
        val calls = mutableListOf<Pair<String, Map<String, Any?>>>()
        val responses = ArrayDeque<Any?>(listOf(
            mapOf(
                "options" to mapOf("challenge" to "create", "rp" to mapOf("id" to "yourolivebranch.ca")),
                "challengeId" to "register-challenge-abcdefghijkl",
            ),
            mapOf(
                "options" to mapOf("challenge" to "assert", "rpId" to "yourolivebranch.ca"),
                "challengeId" to "proof-challenge-abcdefghijklmn",
                "prfInputs" to mapOf("credential_abcdefghijklmnop" to Base64.getEncoder()
                    .encodeToString(ByteArray(32) { 5 })),
            ),
            mapOf("registered" to true, "credentialId" to "credential_abcdefghijklmnop"),
        ))
        val repository = PasskeyRecoveryRepository(AccountRecoveryCallable { name, data ->
            calls += name to data
            responses.removeFirst()
        })
        val registration = repository.beginEnrollment()
        val proof = repository.continueEnrollment(
            registration.challengeId,
            """{"id":"credential_abcdefghijklmnop","clientExtensionResults":{"prf":{"enabled":true}}}""",
        )
        val envelope = PasskeyRecoveryEnvelope.create(
            "account-a", "credential_abcdefghijklmnop", ByteArray(32) { 3 },
            AccountRecoveryKey.fromEncoded(ByteArray(32) { 9 }),
        )

        repository.completeEnrollment(
            proof.challengeId,
            """{"id":"credential_abcdefghijklmnop","clientExtensionResults":{"prf":{"enabled":true}}}""",
            envelope,
        )

        assertEquals(listOf(
            "passkeyRecoveryBeginEnrollment",
            "passkeyRecoveryContinueEnrollment",
            "passkeyRecoveryCompleteEnrollment",
        ), calls.map { it.first })
        assertFalse(calls.toString().contains("results"))
        assertEquals(envelope.toMap(), calls.last().second["envelope"])
    }

    @Test
    fun `unlock and factor management validate account scoped response shapes`() = runBlocking {
        val envelope = PasskeyRecoveryEnvelope.create(
            "account-a", "credential_abcdefghijklmnop", ByteArray(32) { 3 },
            AccountRecoveryKey.fromEncoded(ByteArray(32) { 9 }),
        )
        val responses = ArrayDeque<Any?>(listOf(
            mapOf(
                "options" to mapOf("challenge" to "assert", "rpId" to "yourolivebranch.ca"),
                "challengeId" to "unlock-challenge-abcdefghijkl",
                "prfInputs" to mapOf("credential_abcdefghijklmnop" to Base64.getEncoder()
                    .encodeToString(ByteArray(32) { 5 })),
            ),
            mapOf("envelope" to envelope.toMap(), "prfInput" to Base64.getEncoder()
                .encodeToString(ByteArray(32) { 5 })),
            mapOf("credentials" to listOf(mapOf(
                "credentialId" to "credential_abcdefghijklmnop",
                "deviceType" to "multiDevice",
                "backedUp" to true,
            ))),
            mapOf("removed" to true, "credentialId" to "credential_abcdefghijklmnop"),
        ))
        val repository = PasskeyRecoveryRepository(AccountRecoveryCallable { _, _ -> responses.removeFirst() })

        val start = repository.beginUnlock()
        assertEquals(envelope, repository.completeUnlock(
            start.challengeId,
            """{"id":"credential_abcdefghijklmnop","clientExtensionResults":{"prf":{"enabled":true}}}""",
        ).envelope)
        assertEquals(true, repository.listCredentials().single().backedUp)
        repository.removeCredential("credential_abcdefghijklmnop")
    }

    @Test
    fun `repository rejects accidental PRF output before invoking Firebase`() = runBlocking {
        var invoked = false
        val repository = PasskeyRecoveryRepository(AccountRecoveryCallable { _, _ ->
            invoked = true
            emptyMap<String, Any?>()
        })
        val response = """{"id":"credential_abcdefghijklmnop","clientExtensionResults":{"prf":{"results":{"first":"secret"}}}}"""

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repository.continueEnrollment("register-challenge-abcdefghijkl", response)
            }
        }
        assertFalse(invoked)
    }
}
