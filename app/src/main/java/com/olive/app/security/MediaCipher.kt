// Users and approved viewers: encrypts media files before upload and decrypts them after download.
package com.olive.app.security

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Versioned streaming AES-256-GCM container for media stored outside Firestore.
 *
 * Binary layout: `magic(4) | version(1) | ivLength(1) | random IV(12) | ciphertext | GCM tag(16)`.
 * The IV is generated independently for every encryption operation and is intentionally stored in
 * the clear; confidentiality comes from the secret key, while IV uniqueness protects GCM security.
 * Authentication failure is reported by [Cipher.doFinal] during [decrypt].
 *
 * This object owns byte-format cryptography only. Key generation/wrapping belongs to
 * [EncryptionUtils]/[KeyManager], preprocessing belongs to `MediaPreprocessor`, and network transfer
 * belongs to `MediaUploadWorker`. That separation keeps cryptographic code independent of Android UI,
 * Firebase, Cloudflare, and file-selection concerns.
 */
object MediaCipher {
    private val magic=byteArrayOf('O'.code.toByte(),'L'.code.toByte(),'V'.code.toByte(),'M'.code.toByte())
    private const val version=1
    private const val ivLength=12
    private const val tagBits=128
    private const val bufferSize=64*1024
    const val headerBytes=6+ivLength
    const val authenticationTagBytes=tagBits/Byte.SIZE_BITS

    /** Returns the exact container size without reading or allocating the plaintext. */
    fun encryptedSize(plainSize:Long):Long {
        require(plainSize>=0)
        return headerBytes+plainSize+authenticationTagBytes
    }

    /**
     * Streams plaintext from [input] and writes one complete authenticated container to [output].
     * Neither the whole plaintext nor ciphertext is retained in memory.
     */
    fun encrypt(input:InputStream,output:OutputStream,key:SecretKey,onProgress:(Long)->Unit={}) {
        // GCM requires a unique unpredictable IV for every encryption under a key.
        val iv=ByteArray(ivLength).apply { SecureRandom().nextBytes(this) }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key,GCMParameterSpec(tagBits,iv))
        val target=DataOutputStream(output)
        target.write(magic);target.writeByte(version);target.writeByte(iv.size);target.write(iv)
        transform(input,target,cipher,onProgress)
    }

    /**
     * Validates the versioned header and streams authenticated plaintext to [output].
     * Callers should write to a temporary file and publish it only after this method returns, because
     * GCM authenticates the final tag at the end of the stream.
     */
    fun decrypt(input:InputStream,output:OutputStream,key:SecretKey,onProgress:(Long)->Unit={}) {
        val source=DataInputStream(input)
        val foundMagic=ByteArray(magic.size).also(source::readFully)
        require(foundMagic.contentEquals(magic)) { "Unsupported encrypted media" }
        require(source.readUnsignedByte()==version) { "Unsupported encrypted media version" }
        val foundIvLength=source.readUnsignedByte()
        require(foundIvLength==ivLength) { "Unsupported encrypted media IV" }
        val iv=ByteArray(foundIvLength).also(source::readFully)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(tagBits,iv))
        transform(source,output,cipher,onProgress)
    }

    /** Shared bounded-memory transform used in both encryption and decryption directions. */
    private fun transform(input:InputStream,output:OutputStream,cipher:Cipher,onProgress:(Long)->Unit) {
        val buffer=ByteArray(bufferSize)
        var processed=0L
        // If transform fails, catch the error so the app can keep running and explain the problem.
        try {
            // Media cipher: Keeps transform active while true.
            while(true) {
                val count=input.read(buffer)
                if(count<0) break
                if(count==0) continue
                cipher.update(buffer,0,count)?.let(output::write)
                processed+=count;onProgress(processed)
            }
            cipher.doFinal()?.let(output::write)
            output.flush()
        } finally { buffer.fill(0) }
    }
}
