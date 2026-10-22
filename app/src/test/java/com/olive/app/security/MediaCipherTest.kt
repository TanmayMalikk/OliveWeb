package com.olive.app.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaCipherTest {
    @Test fun streamingRoundTripUsesBoundedChunks() {
        val plain=ByteArray(200_000) { (it%251).toByte() }
        val key=EncryptionUtils.generateSymmetricKey()
        val encrypted=ByteArrayOutputStream()
        var encryptProgress=0L
        MediaCipher.encrypt(ByteArrayInputStream(plain),encrypted,key) { encryptProgress=it }
        assertEquals(plain.size.toLong(),encryptProgress)
        assertEquals(MediaCipher.encryptedSize(plain.size.toLong()),encrypted.size().toLong())

        val restored=ByteArrayOutputStream()
        MediaCipher.decrypt(ByteArrayInputStream(encrypted.toByteArray()),restored,key)
        assertArrayEquals(plain,restored.toByteArray())
    }

    @Test fun everyEncryptionUsesANewIv() {
        val key=EncryptionUtils.generateSymmetricKey();val plain="private document".toByteArray()
        val first=ByteArrayOutputStream();val second=ByteArrayOutputStream()
        MediaCipher.encrypt(ByteArrayInputStream(plain),first,key)
        MediaCipher.encrypt(ByteArrayInputStream(plain),second,key)
        assertFalse(first.toByteArray().contentEquals(second.toByteArray()))
    }

    @Test fun tamperingIsRejectedBeforeOutputIsTrusted() {
        val key=EncryptionUtils.generateSymmetricKey();val encrypted=ByteArrayOutputStream()
        MediaCipher.encrypt(ByteArrayInputStream("evidence".toByteArray()),encrypted,key)
        val damaged=encrypted.toByteArray().also { it[it.lastIndex]=(it.last().toInt() xor 1).toByte() }
        assertThrows(AEADBadTagException::class.java) {
            MediaCipher.decrypt(ByteArrayInputStream(damaged),ByteArrayOutputStream(),key)
        }
    }

    @Test fun invalidHeaderIsRejected() {
        val key=EncryptionUtils.generateSymmetricKey()
        assertThrows(IllegalArgumentException::class.java) {
            MediaCipher.decrypt(ByteArrayInputStream(ByteArray(32)),ByteArrayOutputStream(),key)
        }
    }
}
