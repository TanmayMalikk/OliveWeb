package com.olive.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class AccountKeysetEnvelopeTest {
    @Test
    fun `new account envelope unlocks only with its account recovery key`() {
        val recoveryKey = AccountRecoveryKey.generate()
        val envelope = AccountKeysetEnvelope.create("account-a", recoveryKey)

        val unlocked = envelope.unlock("account-a", recoveryKey)

        assertEquals("account-a", unlocked.accountId)
        assertEquals(envelope.keyId, unlocked.keyId)
        assertEquals(envelope.epoch, unlocked.epoch)
        assertEquals(
            Base64.getDecoder().decode(envelope.publicKeyBase64).toList(),
            unlocked.publicKey.encoded.toList(),
        )
    }

    @Test
    fun `account identity recovery key and ciphertext are authenticated`() {
        val recoveryKey = AccountRecoveryKey.generate()
        val envelope = AccountKeysetEnvelope.create("account-a", recoveryKey)

        assertThrows(IllegalArgumentException::class.java) {
            envelope.unlock("account-b", recoveryKey)
        }
        assertThrows(Exception::class.java) {
            envelope.unlock("account-a", AccountRecoveryKey.generate())
        }
        assertThrows(Exception::class.java) {
            envelope.copy(ciphertextBase64 = Base64.getEncoder().encodeToString(
                envelope.ciphertext.reversedArray(),
            ))
                .unlock("account-a", recoveryKey)
        }
    }

    @Test
    fun `serialized envelope never includes account private key material`() {
        val recoveryKey = AccountRecoveryKey.generate()
        val envelope = AccountKeysetEnvelope.create("account-a", recoveryKey)
        val privateKeyBase64 = Base64.getEncoder().encodeToString(
            envelope.unlock("account-a", recoveryKey).privateKey.encoded,
        )

        val serialized = envelope.toJson().toString()

        assertFalse(serialized.contains(privateKeyBase64))
        assertEquals(envelope, AccountKeysetEnvelope.fromJson(envelope.toJson()))
    }

    @Test
    fun `fresh account envelopes use distinct recovery keys and key identities`() {
        val firstRecoveryKey = AccountRecoveryKey.generate()
        val secondRecoveryKey = AccountRecoveryKey.generate()
        val first = AccountKeysetEnvelope.create("account-a", firstRecoveryKey)
        val second = AccountKeysetEnvelope.create("account-a", secondRecoveryKey)

        assertNotEquals(first.keyId, second.keyId)
        assertNotEquals(first.ciphertext.toList(), second.ciphertext.toList())
        assertEquals("account-a", first.unlock("account-a", firstRecoveryKey).accountId)
        assertEquals("account-a", second.unlock("account-a", secondRecoveryKey).accountId)
    }

    @Test
    fun `unlocked account keyset opens an OAEP wrapped file key`() {
        val recoveryKey = AccountRecoveryKey.generate()
        val accountKeyset = AccountKeysetEnvelope.create("account-a", recoveryKey)
            .unlock("account-a", recoveryKey)
        val fileKey = EncryptionUtils.generateSymmetricKey()

        val wrapped = KeyManager.wrapForAccountKeyset(fileKey, accountKeyset.publicKey)
        val unwrapped = KeyManager.unwrapKey(wrapped, accountKeyset.privateKey)

        assertEquals(fileKey.encoded.toList(), unwrapped.encoded.toList())
    }

    @Test
    fun `media account wrapper requires its exact recovered keyset`() {
        val recoveryKey = AccountRecoveryKey.generate()
        val envelope = AccountKeysetEnvelope.create("account-a", recoveryKey)
        val unlocked = envelope.unlock("account-a", recoveryKey)
        val fileKey = EncryptionUtils.generateSymmetricKey()
        val wrapper = AccountKeysetWrappedKey(
            envelope.keyId,envelope.epoch,"RSA-OAEP-256",
            KeyManager.wrapForAccountKeyset(fileKey,unlocked.publicKey),
        )

        assertEquals(fileKey.encoded.toList(),wrapper.unwrap(unlocked).encoded.toList())
        assertThrows(IllegalArgumentException::class.java) {
            wrapper.copy(keyId="223e4567-e89b-42d3-a456-426614174000").unwrap(unlocked)
        }
    }
}
