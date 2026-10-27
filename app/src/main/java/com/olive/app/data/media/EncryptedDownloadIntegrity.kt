package com.olive.app.data.media

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Streams ciphertext once while enforcing its immutable byte count and SHA-256 digest. */
internal suspend fun copyAndVerifyEncryptedDownload(
    input:InputStream,
    output:OutputStream,
    expectedSize:Long,
    expectedSha256:String,
):Long {
    require(expectedSize>0) { "Encrypted download size is invalid" }
    require(expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Encrypted download checksum is invalid" }
    val digest=MessageDigest.getInstance("SHA-256")
    val buffer=ByteArray(64*1024)
    var received=0L
    try {
        while(true) {
            currentCoroutineContext().ensureActive()
            val count=input.read(buffer)
            if(count<0) break
            if(count==0) continue
            received+=count
            require(received<=expectedSize) {
                "Encrypted download size does not match (expected $expectedSize bytes, received more than $expectedSize bytes)"
            }
            digest.update(buffer,0,count)
            output.write(buffer,0,count)
        }
    } finally {
        buffer.fill(0)
    }
    require(received==expectedSize) {
        "Encrypted download size does not match (expected $expectedSize bytes, received $received bytes)"
    }
    val actual=digest.digest().joinToString("") { "%02x".format(it) }
    require(actual.equals(expectedSha256,true)) { "Encrypted download checksum does not match" }
    return received
}
