// All signed-in roles: stores the key tied to the device lock.
package com.olive.app.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64 as AndroidBase64
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.SecureRandom
import java.util.Base64 as JavaBase64
import javax.crypto.spec.SecretKeySpec
import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

// A separate authentication-bound key guards device-lock entry. Cover storage stays usable while locked.
internal class DeviceLockKey(private val namespace: String) {
    /**
     * Device lock key: Prepares the feature data.
     *
     * @param uid Firebase UID of the target user account.
     * @param saved Optional saved supplied to [prepare].
     * @return The resulting [DeviceLockChallenge] instance.
     */
    fun prepare(uid: String, saved: JSONObject?): DeviceLockChallenge {
        val keyId = saved?.getString("keyId") ?: UUID.randomUUID().toString()
        require(keyId.matches(Regex("[a-f0-9-]{36}")))
        val alias = "olive.safe-entry.$namespace." + MessageDigest.getInstance("SHA-256")
            .digest(uid.toByteArray()).joinToString("") { "%02x".format(it) } + ".$keyId"
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (keys.getKey(alias, null) as? SecretKey) ?: run {
            // Missing keys for an existing enrollment must never silently create a replacement.
            check(saved == null) { "Device lock key unavailable; sign in again to enroll this device." }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                val spec = KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).setUserAuthenticationRequired(true)
                // Android 11 and newer can require authentication every time this key is used.
                if (Build.VERSION.SDK_INT >= 30) {
                    spec.setUserAuthenticationParameters(
                        0,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                    )
                } else {
                    configureLegacyAuthentication(spec)
                }
                init(spec.build())
                generateKey()
            }
        }
        return try {
            DeviceLockChallenge(uid, key, saved?.let { JSONObject(it.toString()) }, keyId, alias)
        } catch (error: Exception) {
            if (saved == null) keys.deleteEntry(alias)
            throw error
        }
    }

    /**
     * Builds the Android Keystore settings used on older Android versions.
     *
     * @param spec Spec supplied to [configureLegacyAuthentication].
     */
    @Suppress("DEPRECATION")
    private fun configureLegacyAuthentication(spec: KeyGenParameterSpec.Builder) {
        // Android 9/10 require this API; setUserAuthenticationParameters starts at Android 11.
        spec.setUserAuthenticationValidityDurationSeconds(10)
    }
}

/** Minimum cipher contract needed by the shared Android device-authentication prompt. */
internal interface DeviceAuthenticationChallenge {
    val enrolling: Boolean
    val promptCipher: Cipher?
    /**
     * Creates an unlocked cipher used for legacy pre-API-30 authentication prompts.
     *
     * @return The initialized cipher for the legacy Android authentication prompt.
     */
    fun legacyCipher(): Cipher
}

class DeviceLockChallenge internal constructor(
    private val uid: String, private val key: SecretKey, private val saved: JSONObject?,
    private val keyId: String, private val alias: String,
) : DeviceAuthenticationChallenge {
    private var committed = false
    /**
     * Reports whether this phone finished creating its device-lock key.
     */
    internal fun markEnrolled() {
        committed = true
    }

    /**
     * Clears the short-lived device-lock key after private authentication ends.
     */
    internal fun close() {
        // Remove an unfinished key when enrollment is cancelled.
        if (enrolling && !committed) runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(alias)
            }
        }
    }

    override val enrolling get() = saved == null
    private val expected get() = "olive-safe-entry-v1:$uid".toByteArray(Charsets.UTF_8)

    // API 30+ binds this exact operation to BiometricPrompt. Legacy devices initialize only after credentials succeed.
    override val promptCipher: Cipher? = if (Build.VERSION.SDK_INT >= 30) newCipher() else null
    private var credentialCipher: Cipher? = null
    /**
     * Creates the legacy cipher that protects app security.
     *
     * @return The resulting [Cipher] for legacy cipher.
     */
    override fun legacyCipher(): Cipher = newCipher().also { credentialCipher = it }
    /**
     * Creates the cipher that wraps or unwraps the account key after device authentication.
     *
     * @return The resulting [Cipher] for new cipher.
     */
    private fun newCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        if (saved == null) init(Cipher.ENCRYPT_MODE, key)
        else init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(128, AndroidBase64.decode(saved.getString("iv"), AndroidBase64.NO_WRAP))
        )
    }

    /**
     * Ends the authentication request once and sends its final result.
     *
     * @param cipher Cipher supplied to [finish].
     * @return JSON object representing finish.
     */
    internal fun finish(cipher: Cipher): JSONObject? {
        check(cipher === (promptCipher ?: credentialCipher))
        if (saved == null) {
            val encrypted = cipher.doFinal(expected)
            return JSONObject().put("keyId", keyId)
                .put("iv", AndroidBase64.encodeToString(cipher.iv, AndroidBase64.NO_WRAP))
                .put("data", AndroidBase64.encodeToString(encrypted, AndroidBase64.NO_WRAP))
        }
        val clear = cipher.doFinal(AndroidBase64.decode(saved.getString("data"), AndroidBase64.NO_WRAP))
        try {
            check(MessageDigest.isEqual(expected, clear))
        } finally {
            clear.fill(0)
        }
        return null
    }
}

// All signed-in roles: encrypts and decrypts private app values.

object EncryptionUtils {
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_SIZE = 256
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH = 128

    /**
     * Generates a secure symmetric key.
     * Used for encrypting notes or generating the Group Chat Room Key.
     *
     * @return The resulting [SecretKey] for generate symmetric key.
     */
    fun generateSymmetricKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(ALGORITHM)
        keyGenerator.init(KEY_SIZE, SecureRandom())
        return keyGenerator.generateKey()
    }

    /**
     * Encrypts a plaintext string and returns a Base64 encoded string.
     * Safe to store directly in Firestore NoSQL documents.
     *
     * @param plaintext Plaintext supplied to [encryptText].
     * @param key Lookup key or cryptographic key.
     * @return The resulting encrypt text string.
     */
    fun encryptText(plaintext: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)

        // GCM requires a unique Initialization Vector (IV) for every single encryption
        val iv = ByteArray(IV_LENGTH).apply { SecureRandom().nextBytes(this) }
        val parameterSpec = GCMParameterSpec(TAG_LENGTH, iv)

        cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec)
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Prepend the IV to the ciphertext so we know how to decrypt it later
        val combined = iv + ciphertext
        return JavaBase64.getEncoder().encodeToString(combined)
    }

    /**
     * Decrypts a Base64 encoded string back into readable plaintext.
     *
     * @param encryptedBase64 Encrypted base 64 supplied to [decryptText].
     * @param key Lookup key or cryptographic key.
     * @return The resulting decrypt text string.
     */
    fun decryptText(encryptedBase64: String, key: SecretKey): String {
        val combined = JavaBase64.getDecoder().decode(encryptedBase64)

        // Extract the IV from the first 12 bytes
        val iv = combined.copyOfRange(0, IV_LENGTH)
        val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val parameterSpec = GCMParameterSpec(TAG_LENGTH, iv)

        cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec)
        val plaintextBytes = cipher.doFinal(ciphertext)

        return String(plaintextBytes, Charsets.UTF_8)
    }

    /**
     * Encrypts binary data with AES-GCM.
     *
     * The returned payload contains the random IV followed by the authenticated ciphertext:
     * [12-byte IV][ciphertext and 16-byte authentication tag].
     *
     * @param plaintext Raw plaintext byte array.
     * @param secretKey Secret key supplied to [encryptBytes].
     * @return The resulting [ByteArray] for encrypt bytes.
     */
    fun encryptBytes(plaintext: ByteArray, secretKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(IV_LENGTH).apply { SecureRandom().nextBytes(this) }
        val parameterSpec = GCMParameterSpec(TAG_LENGTH, iv)

        cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec)
        val ciphertext = cipher.doFinal(plaintext)

        return iv + ciphertext
    }

    /**
     * Decrypts and authenticates a payload produced by [encryptBytes].
     *
     * @param encryptedPayload Raw encrypted payload byte array.
     * @param secretKey Secret key supplied to [decryptBytes].
     * @return The resulting [ByteArray] for decrypt bytes.
     */
    fun decryptBytes(encryptedPayload: ByteArray, secretKey: SecretKey): ByteArray {
        val minimumPayloadLength = IV_LENGTH + TAG_LENGTH / Byte.SIZE_BITS
        require(encryptedPayload.size >= minimumPayloadLength) { "Encrypted payload is too short" }

        val iv = encryptedPayload.copyOfRange(0, IV_LENGTH)
        val ciphertext = encryptedPayload.copyOfRange(IV_LENGTH, encryptedPayload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val parameterSpec = GCMParameterSpec(TAG_LENGTH, iv)

        cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec)
        return cipher.doFinal(ciphertext)
    }

    /**
     * Helper functions to convert the raw AES key into a string
     * so it can be securely wrapped/stored later.
     *
     * @param key Lookup key or cryptographic key.
     * @return The resulting encode key to string string.
     */
    fun encodeKeyToString(key: SecretKey): String {
        return JavaBase64.getEncoder().encodeToString(key.encoded)
    }

    /**
     * Encryption utils: Converts string to key.
     *
     * @param base64Key Base 64 key supplied to [decodeStringToKey].
     * @return The resulting [SecretKey] for decode string to key.
     */
    fun decodeStringToKey(base64Key: String): SecretKey {
        val decoded = JavaBase64.getDecoder().decode(base64Key)
        return SecretKeySpec(decoded, ALGORITHM)
    }
}

// All signed-in roles: keeps private app data encrypted on the device.

/** Small JSON persistence contract that keeps cache/index tests independent from Android Keystore. */
interface JsonVault {
    /**
     * Reads the JSON vault.
     *
     * @return JSON object representing read.
     */
    fun read(): JSONObject
    /**
     * Updates the JSON vault.
     *
     * @param change Action or callback executed for change.
     */
    fun update(change: (JSONObject) -> Unit)
    /**
     * Clears the JSON vault.
     */
    fun clear()
}

// The phone's private stash: one atomic AES-encrypted file backed by Android Keystore.
class EncryptedVault(context: Context, mode: String) : JsonVault {
    private val file = AtomicFile(File(context.noBackupFilesDir, "olive-$mode.vault"))
    private val stagingFile = File(file.baseFile.path + ".new")

    // Foreground editors and background workers use separate instances of the same file.
    private val fileLock = locks.computeIfAbsent(file.baseFile.absolutePath) { Any() }
    private val alias = "olive.vault.$mode.v1"

    // Decrypted memory cache for instantaneous reads within an active authenticated session.
    @Volatile private var cachedJson: String? = null
    @Volatile private var cachedLastModified = -1L

    init {
        registerInstance(this)
    }

    /**
     * Purges decrypted memory cache. Called on quick exit or app backgrounding.
     */
    fun purge() {
        synchronized(fileLock) {
            cachedJson = null
            cachedLastModified = -1L
        }
    }

    /**
     * Gets the phone-only key that locks and unlocks this private file.
     *
     * @return The resulting [SecretKey] instance.
     */
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build()
            )
            generateKey()
        }
    }

    /**
     * Decrypts and reads the complete private file.
     *
     * @return JSON object representing read.
     */
    override fun read(): JSONObject = synchronized(fileLock) { readUnlocked() }
    /**
     * Reads and parses the encrypted vault file while the caller holds the shared file lock.
     * Reuses in-memory decrypted JSON if the underlying file on disk has not changed.
     *
     * @return JSON object representing read unlocked.
     */
    private fun readUnlocked(): JSONObject {
        val currentMod = if (file.baseFile.exists()) file.baseFile.lastModified() else -1L
        val cached = cachedJson
        if (cached != null && cachedLastModified == currentMod && currentMod != -1L) {
            return JSONObject(cached)
        }
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) {
            cachedJson = null
            cachedLastModified = -1L
            return JSONObject()
        }
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size > 29 && bytes[0] == 1.toByte()) { "Unsupported encrypted store" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        val plain = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
        return try {
            val jsonStr = String(plain, Charsets.UTF_8)
            cachedJson = jsonStr
            cachedLastModified = file.baseFile.lastModified()
            JSONObject(jsonStr)
        } finally {
            plain.fill(0)
        }
    }

    /**
     * Changes the private data, encrypts it again, and replaces the file safely.
     *
     * @param change Action or callback executed for change.
     */
    override fun update(change: (JSONObject) -> Unit) = synchronized(fileLock) {
        val root = readUnlocked()
        change(root)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val jsonStr = root.toString()
        val plain = jsonStr.toByteArray(Charsets.UTF_8)
        val bytes = try {
            byteArrayOf(1) + cipher.iv + cipher.doFinal(plain)
        } finally {
            plain.fill(0)
        }
        try {
            FileOutputStream(stagingFile).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            // AtomicFile.finishWrite can silently keep the old file when renameTo cannot replace it.
            Files.move(stagingFile.toPath(), file.baseFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            cachedJson = jsonStr
            cachedLastModified = file.baseFile.lastModified()
        } finally {
            stagingFile.delete()
        }
        Unit
    }

    /**
     * Deletes a disposable encrypted store without removing its Android Keystore key.
     */
    override fun clear() = synchronized(fileLock) {
        cachedJson = null
        cachedLastModified = -1L
        file.delete()
    }

    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private val activeInstances =
            java.util.concurrent.CopyOnWriteArrayList<java.lang.ref.WeakReference<EncryptedVault>>()

        /**
         * Tracks an [EncryptedVault] instance so [purgeAll] can clear its cache later.
         *
         * @param vault The encrypted vault instance to track for cache invalidation.
         */
        private fun registerInstance(vault: EncryptedVault) {
            activeInstances.add(java.lang.ref.WeakReference(vault))
        }

        /**
         * Purges all in-memory decrypted vault caches across the app on Quick Exit or app backgrounding.
         */
        fun purgeAll() {
            val iterator = activeInstances.iterator()
            while (iterator.hasNext()) {
                val ref = iterator.next()
                val vault = ref.get()
                if (vault != null) {
                    vault.purge()
                } else {
                    activeInstances.remove(ref)
                }
            }
        }
    }
}

/**
 * Returns an existing JSON section or adds an empty one when it is missing.
 *
 * @receiver The JSON object containing the stored fields.
 * @param name Field, key, or display name.
 * @return JSON object representing child.
 */
fun JSONObject.child(name: String): JSONObject =
    optJSONObject(name) ?: JSONObject().also { put(name, it) }

// Users and approved viewers: encrypts media files before upload and decrypts them after download.

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
    private const val VER=1
    private const val IVLENGTH=12
    private const val TAGBITS=128
    private const val BUFFERSIZE=64*1024
    const val HEADERBYTES=6+IVLENGTH
    const val AUTHENTICATIONTAGBYTES=TAGBITS/Byte.SIZE_BITS

    /**
     * Returns the exact container size without reading or allocating the plaintext.
     *
     * @param plainSize Plain size supplied to [encryptedSize].
     * @return The computed encrypted size numeric value.
     */
    fun encryptedSize(plainSize:Long):Long {
        require(plainSize>=0)
        return HEADERBYTES+plainSize+AUTHENTICATIONTAGBYTES
    }

    /**
     * Streams plaintext from [input] and writes one complete authenticated container to [output].
     * Neither the whole plaintext nor ciphertext is retained in memory.
     *
     * @param input Input stream, matrix, or value to process.
     * @param output Output stream or target receiving processed data.
     * @param key Lookup key or cryptographic key.
     * @param onProgress Callback invoked when progress changes or occurs.
     */
    fun encrypt(input:InputStream,output:OutputStream,key:SecretKey,onProgress:(Long)->Unit={}) {
        // GCM requires a unique unpredictable IV for every encryption under a key.
        val iv=ByteArray(IVLENGTH).apply { SecureRandom().nextBytes(this) }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key,GCMParameterSpec(TAGBITS,iv))
        val target=DataOutputStream(output)
        target.write(magic);target.writeByte(VER);target.writeByte(iv.size);target.write(iv)
        transform(input,target,cipher,onProgress)
    }

    /**
     * Validates the versioned header and streams authenticated plaintext to [output].
     * Callers should write to a temporary file and publish it only after this method returns, because
     * GCM authenticates the final tag at the end of the stream.
     *
     * @param input Input stream, matrix, or value to process.
     * @param output Output stream or target receiving processed data.
     * @param key Lookup key or cryptographic key.
     * @param onProgress Callback invoked when progress changes or occurs.
     */
    fun decrypt(input:InputStream,output:OutputStream,key:SecretKey,onProgress:(Long)->Unit={}) {
        val source=DataInputStream(input)
        val foundMagic=ByteArray(magic.size).also(source::readFully)
        require(foundMagic.contentEquals(magic)) { "Unsupported encrypted media" }
        require(source.readUnsignedByte()==VER) { "Unsupported encrypted media version" }
        val foundIvLength=source.readUnsignedByte()
        require(foundIvLength==IVLENGTH) { "Unsupported encrypted media IV" }
        val iv=ByteArray(foundIvLength).also(source::readFully)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(TAGBITS,iv))
        transform(source,output,cipher,onProgress)
    }

    /**
     * Shared bounded-memory transform used in both encryption and decryption directions.
     *
     * @param input Input stream, matrix, or value to process.
     * @param output Output stream or target receiving processed data.
     * @param cipher Cipher supplied to [transform].
     * @param onProgress Callback invoked when progress changes or occurs.
     */
    private fun transform(input:InputStream,output:OutputStream,cipher:Cipher,onProgress:(Long)->Unit) {
        val buffer=ByteArray(BUFFERSIZE)
        var processed=0L
        // Turn an unexpected transform failure into a readable message.
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

/**
 * Checks whether Android temporarily owns the foreground for an approved app operation.
 *
 * @param deviceCredential True while a biometric or device-credential prompt is active.
 * @param runtimePermission True while a system runtime permission dialog is open.
 * @param externalFilePicker True while the system file or media picker is open.
 * @param callPermission True while a call-related permission prompt is open.
 * @param safeSystemActivity True while another approved safe-app system activity is open.
 * @return True if any trusted Android system handoff is currently in progress.
 */
internal fun trustedSystemHandoffActive(
    deviceCredential: Boolean,
    runtimePermission: Boolean,
    externalFilePicker: Boolean,
    callPermission: Boolean,
    safeSystemActivity: Boolean,
): Boolean = deviceCredential || runtimePermission || externalFilePicker ||
    callPermission || safeSystemActivity

/** Holds a credential result only until the private activity is visible again. */
internal class LifecycleResultGate<T>(private val discard: (T) -> Unit = {}) {
    private var pending: T? = null

    /**
     * Returns [value] immediately when the activity is [resumed], or stages it until resume.
     *
     * @param value Newly produced credential or prompt result.
     * @param resumed Whether the host activity is currently resumed.
     * @return [value] if [resumed] is true, or `null` when queued for delivery on resume.
     */
    fun offer(value: T, resumed: Boolean): T? {
        cancel()
        return if (resumed) value else null.also { pending = value }
    }

    /**
     * Consumes and returns the pending credential result staged before resume.
     *
     * @return The staged result, or `null` if none is waiting.
     */
    fun resume(): T? = pending.also { pending = null }

    /**
     * Discards any staged result and clears the pending reference.
     */
    fun cancel() {
        pending?.let(discard)
        pending = null
    }
}
