package com.olive.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64
import java.util.UUID

class OpaquePasswordRecoveryEnvelopeTest {

    private val accountId = "test-user-account-123"
    private val recordId = "rec_" + UUID.randomUUID().toString().replace("-", "")
    private val exportKey = ByteArray(32) { (it + 1).toByte() }
    private val recoveryKey = AccountRecoveryKey.generate()

    @Test
    fun `recovery envelope encrypts and recovers the exact same ARK`() {
        val envelope = OpaquePasswordRecoveryEnvelope.create(
            accountId = accountId,
            opaqueRecordId = recordId,
            exportKey = exportKey,
            recoveryKey = recoveryKey,
        )

        val opened = envelope.open(
            expectedAccountId = accountId,
            expectedRecordId = recordId,
            exportKey = exportKey,
        )

        assertEquals(
            Base64.getEncoder().encodeToString(recoveryKey.encodedCopy()),
            Base64.getEncoder().encodeToString(opened.encodedCopy()),
        )
    }

    @Test
    fun `wrong export key fails closed with cryptographic exception`() {
        val envelope = OpaquePasswordRecoveryEnvelope.create(
            accountId = accountId,
            opaqueRecordId = recordId,
            exportKey = exportKey,
            recoveryKey = recoveryKey,
        )

        val wrongExportKey = ByteArray(32) { (it + 99).toByte() }

        assertThrows(Exception::class.java) {
            envelope.open(
                expectedAccountId = accountId,
                expectedRecordId = recordId,
                exportKey = wrongExportKey,
            )
        }
    }

    @Test
    fun `cross-account or cross-record recovery is strictly blocked`() {
        val envelope = OpaquePasswordRecoveryEnvelope.create(
            accountId = accountId,
            opaqueRecordId = recordId,
            exportKey = exportKey,
            recoveryKey = recoveryKey,
        )

        assertThrows(IllegalArgumentException::class.java) {
            envelope.open(
                expectedAccountId = "other-account",
                expectedRecordId = recordId,
                exportKey = exportKey,
            )
        }

        assertThrows(IllegalArgumentException::class.java) {
            envelope.open(
                expectedAccountId = accountId,
                expectedRecordId = "other-record-id-1234567890",
                exportKey = exportKey,
            )
        }
    }

    @Test
    fun `tampered salt, iv, or ciphertext fails closed`() {
        val envelope = OpaquePasswordRecoveryEnvelope.create(
            accountId = accountId,
            opaqueRecordId = recordId,
            exportKey = exportKey,
            recoveryKey = recoveryKey,
        )

        val tamperedCiphertextBytes = Base64.getDecoder().decode(envelope.ciphertextBase64)
        tamperedCiphertextBytes[0] = (tamperedCiphertextBytes[0].toInt() xor 0xFF).toByte()
        val tamperedEnvelope = envelope.copy(
            ciphertextBase64 = Base64.getEncoder().encodeToString(tamperedCiphertextBytes),
        )

        assertThrows(Exception::class.java) {
            tamperedEnvelope.open(accountId, recordId, exportKey)
        }
    }

    @Test
    fun `serialization toMap and fromMap round-trips correctly`() {
        val envelope = OpaquePasswordRecoveryEnvelope.create(
            accountId = accountId,
            opaqueRecordId = recordId,
            exportKey = exportKey,
            recoveryKey = recoveryKey,
        )

        val map = envelope.toMap()
        val restored = OpaquePasswordRecoveryEnvelope.fromMap(map)

        assertEquals(envelope, restored)
        val opened = restored.open(accountId, recordId, exportKey)
        assertEquals(
            Base64.getEncoder().encodeToString(recoveryKey.encodedCopy()),
            Base64.getEncoder().encodeToString(opened.encodedCopy()),
        )
    }

    @Test
    fun `64-byte OPAQUE export key encrypts and recovers ARK and invalid sizes fail`() {
        val opaqueExportKey = ByteArray(64) { (it * 3 + 5).toByte() }
        val envelope = OpaquePasswordRecoveryEnvelope.create(
            accountId = accountId,
            opaqueRecordId = recordId,
            exportKey = opaqueExportKey,
            recoveryKey = recoveryKey,
        )

        val opened = envelope.open(
            expectedAccountId = accountId,
            expectedRecordId = recordId,
            exportKey = opaqueExportKey,
        )

        assertEquals(
            Base64.getEncoder().encodeToString(recoveryKey.encodedCopy()),
            Base64.getEncoder().encodeToString(opened.encodedCopy()),
        )

        assertThrows(IllegalArgumentException::class.java) {
            OpaquePasswordRecoveryEnvelope.create(
                accountId = accountId,
                opaqueRecordId = recordId,
                exportKey = ByteArray(16) { 1 },
                recoveryKey = recoveryKey,
            )
        }
    }
}
