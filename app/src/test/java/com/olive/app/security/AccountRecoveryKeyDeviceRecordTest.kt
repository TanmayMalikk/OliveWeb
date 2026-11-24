package com.olive.app.security

import org.junit.Assert.assertArrayEquals
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
        val protectionId = UUID.randomUUID().toString()
        val record = AccountRecoveryKeyDeviceRecord(
            accountId = "account-a",
            protectionId = protectionId,
            ivBase64 = Base64.getEncoder().encodeToString(ByteArray(12) { 1 }),
            ciphertextBase64 = Base64.getEncoder().encodeToString(ByteArray(48) { 2 }),
        )

        val json = record.toJson()
        val serialized = json.toString()

        assertEquals(AccountRecoveryKeyDeviceRecord.VERSION_APP_MANAGED, record.version)
        assertEquals(AccountRecoveryKeyDeviceRecord.VERSION_APP_MANAGED, json.getInt("version"))
        assertArrayEquals("2|account-a|$protectionId".toByteArray(Charsets.UTF_8), record.aad())
        assertFalse(serialized.contains(Base64.getEncoder().encodeToString(rawArk)))
        assertEquals(record, AccountRecoveryKeyDeviceRecord.fromJson(json))
    }

    @Test
    fun `device record preserves legacy version 1 records and aad`() {
        val protectionId = UUID.randomUUID().toString()
        val record = AccountRecoveryKeyDeviceRecord(
            accountId = "account-a",
            protectionId = protectionId,
            ivBase64 = Base64.getEncoder().encodeToString(ByteArray(12) { 3 }),
            ciphertextBase64 = Base64.getEncoder().encodeToString(ByteArray(48) { 4 }),
            version = AccountRecoveryKeyDeviceRecord.VERSION_DEVICE_AUTH,
        )

        val parsed = AccountRecoveryKeyDeviceRecord.fromJson(record.toJson())

        assertEquals(AccountRecoveryKeyDeviceRecord.VERSION_DEVICE_AUTH, parsed.version)
        assertArrayEquals("1|account-a|$protectionId".toByteArray(Charsets.UTF_8), parsed.aad())
        assertEquals(record, parsed)
    }

    @Test
    fun `device record rejects malformed identity iv ciphertext and unsupported version`() {
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
        for (badVersion in listOf(0, 3)) {
            assertThrows(IllegalArgumentException::class.java) {
                AccountRecoveryKeyDeviceRecord(
                    accountId = "account-a",
                    protectionId = UUID.randomUUID().toString(),
                    ivBase64 = Base64.getEncoder().encodeToString(ByteArray(12)),
                    ciphertextBase64 = Base64.getEncoder().encodeToString(ByteArray(48)),
                    version = badVersion,
                )
            }
            val invalidJson = AccountRecoveryKeyDeviceRecord(
                accountId = "account-a",
                protectionId = UUID.randomUUID().toString(),
                ivBase64 = Base64.getEncoder().encodeToString(ByteArray(12)),
                ciphertextBase64 = Base64.getEncoder().encodeToString(ByteArray(48)),
            ).toJson().put("version", badVersion)
            assertThrows(IllegalArgumentException::class.java) {
                AccountRecoveryKeyDeviceRecord.fromJson(invalidJson)
            }
        }
    }
}
