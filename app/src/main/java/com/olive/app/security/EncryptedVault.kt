package com.olive.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// The phone's private stash: one atomic AES-encrypted file backed by Android Keystore.
class EncryptedVault(context: Context, mode: String) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "olive-$mode.vault"))
    // Foreground editors and background workers use separate instances of the same file.
    private val fileLock=locks.computeIfAbsent(file.baseFile.absolutePath) { Any() }
    private val alias = "olive.vault.$mode.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }
    fun read():JSONObject = synchronized(fileLock) { readUnlocked() }
    private fun readUnlocked(): JSONObject {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return JSONObject()
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size > 29 && bytes[0] == 1.toByte()) { "Unsupported encrypted store" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1,13)))
        val plain = cipher.doFinal(bytes.copyOfRange(13,bytes.size))
        return try { JSONObject(String(plain, Charsets.UTF_8)) } finally { plain.fill(0) }
    }
    fun update(change: (JSONObject) -> Unit) = synchronized(fileLock) {
        val root = readUnlocked()
        change(root)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = root.toString().toByteArray(Charsets.UTF_8)
        val bytes = try { byteArrayOf(1) + cipher.iv + cipher.doFinal(plain) } finally { plain.fill(0) }
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    private companion object { val locks=java.util.concurrent.ConcurrentHashMap<String,Any>() }
}

fun JSONObject.child(name: String): JSONObject = optJSONObject(name) ?: JSONObject().also { put(name,it) }
