// Tests encrypted document size and checksum checks for approved viewers.
package com.olive.app.security

import com.olive.app.data.media.copyAndVerifyEncryptedDownload
import com.olive.app.data.media.DocumentDownloadGate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EncryptedDownloadIntegrityTest {
    @Test
    fun `accepts ciphertext with exact size and digest`() = runBlocking {
        val ciphertext="authenticated ciphertext".toByteArray()
        val output=ByteArrayOutputStream()

        copyAndVerifyEncryptedDownload(ByteArrayInputStream(ciphertext),output,
            ciphertext.size.toLong(),sha256(ciphertext))

        assertArrayEquals(ciphertext,output.toByteArray())
    }

    @Test
    fun `rejects truncated ciphertext`() {
        val ciphertext="short".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            copyAndVerifyEncryptedDownload(ByteArrayInputStream(ciphertext),ByteArrayOutputStream(),
                ciphertext.size+1L,sha256(ciphertext))
        } }
    }

    @Test
    fun `rejects oversized ciphertext before publishing it`() {
        val ciphertext="too long".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            copyAndVerifyEncryptedDownload(ByteArrayInputStream(ciphertext),ByteArrayOutputStream(),
                ciphertext.size-1L,sha256(ciphertext))
        } }
    }

    @Test
    fun `rejects same-size corrupted ciphertext`() {
        val expected="expected".toByteArray()
        val corrupted="corrupt!".toByteArray()
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            copyAndVerifyEncryptedDownload(ByteArrayInputStream(corrupted),ByteArrayOutputStream(),
                expected.size.toLong(),sha256(expected))
        } }
    }

    @Test
    fun `serializes concurrent opens of the same document`() = runBlocking {
        val gate=DocumentDownloadGate()
        val firstEntered=CompletableDeferred<Unit>()
        val releaseFirst=CompletableDeferred<Unit>()
        val active=AtomicInteger()
        val maximum=AtomicInteger()
        val first=async(Dispatchers.Default) { gate.withDocumentLock("document") {
            maximum.accumulateAndGet(active.incrementAndGet()) { left,right -> maxOf(left,right) }
            firstEntered.complete(Unit)
            releaseFirst.await()
            active.decrementAndGet()
        } }
        firstEntered.await()
        val second=async(Dispatchers.Default) { gate.withDocumentLock("document") {
            maximum.accumulateAndGet(active.incrementAndGet()) { left,right -> maxOf(left,right) }
            active.decrementAndGet()
        } }

        delay(50)
        assertEquals(1,maximum.get())
        releaseFirst.complete(Unit)
        first.await();second.await()
        assertEquals(1,maximum.get())
    }

    private fun sha256(value:ByteArray)=MessageDigest.getInstance("SHA-256").digest(value)
        .joinToString("") { "%02x".format(it) }
}
