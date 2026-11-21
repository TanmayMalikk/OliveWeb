package com.olive.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64
import java.util.UUID

class AccountRecoveryKeyDeviceRecordTest {
    @Test
    fun `device record serializes encrypted ARK metadata without ARK bytes`() {
        val rawArk = ByteArray(32) { it.toByte() }
        val record = AccountRecoveryKeyDeviceRecord(
            accountId = "account-a",
            protectionId = UUID.randomUUID().toString(),
            ivBase64 = Base64.getEncoder().encodeToString(ByteArray(12) { 1 }),
            ciphertextBase64 = Base64.getEncoder().encodeToString(ByteArray(48) { 2 }),
        )

        val serialized = record.toJson().toString()

        assertFalse(serialized.contains(Base64.getEncoder().encodeToString(rawArk)))
        assertEquals(record, AccountRecoveryKeyDeviceRecord.fromJson(record.toJson()))
    }

    @Test
    fun `device record rejects malformed identity iv and ciphertext`() {
        assertThrows(IllegalArgumentException::class.java) {
            AccountRecoveryKeyDeviceRecord(
                accountId = "account/a",
                protectionId = UUID.randomUUID().toString(),
                ivBase64 = Base64.getEncoder().encodeToString(ByteArray(12)),
                ciphertextBase64 = Base64.getEncoder().encodeToString(ByteArray(48)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccountRecoveryKeyDeviceRecord(
                accountId = "account-a",
                protectionId = UUID.randomUUID().toString(),
                ivBase64 = Base64.getEncoder().encodeToString(ByteArray(11)),
                ciphertextBase64 = Base64.getEncoder().encodeToString(ByteArray(48)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AccountRecoveryKeyDeviceRecord(
                accountId = "account-a",
                protectionId = UUID.randomUUID().toString(),
                ivBase64 = Base64.getEncoder().encodeToString(ByteArray(12)),
                ciphertextBase64 = Base64.getEncoder().encodeToString(ByteArray(16)),
            )
        }
    }
}
