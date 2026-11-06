// All signed-in roles: stores device activity state.
package com.olive.app.security

import com.olive.app.data.*
import org.json.JSONArray
import org.json.JSONObject
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import java.security.KeyFactory
import java.security.GeneralSecurityException
import java.security.spec.X509EncodedKeySpec
import java.security.spec.MGF1ParameterSpec
import java.security.MessageDigest
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import android.app.KeyguardManager
import android.os.Build
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.olive.app.R
import com.olive.app.ui.session.DeviceLockRequest


// Encrypted, account-scoped activity and private-entry storage; no authentication responsibilities.
internal class DeviceActivityStore(private val activityVault: EncryptedVault) {
    // These histories stay encrypted on this device under the current account ID.
    fun recordSettings(uid: String, event: SettingsEvent) {
        activityVault.update { append(it, uid, "settingsHistory", event.toJson()) }
    }

    // Adds one setting change to the phone's encrypted history.
    fun settingsHistory(uid: String): List<SettingsEvent> =
        rows(uid, "settingsHistory").map(SettingsEvent::fromJson)

    // Records trigger for device activity store.
    fun recordTrigger(uid: String, event: TriggerEvent) {
        activityVault.update { append(it, uid, "triggerHistory", event.toJson()) }
    }

    // Loads discreet-trigger history from encrypted phone storage.
    fun triggerHistory(uid: String): List<TriggerEvent> =
        rows(uid, "triggerHistory").map(TriggerEvent::fromJson).map { event ->
            // Hide old first-try Accepted rows too; the trigger itself still stays in history.
            if (event.lastResult == "Accepted" && event.pinAttempts == 1) event.copy(
                pinAttempts = 0,
                lastResult = "No PIN attempt"
            ) else event
        }

    // Records trigger attempt for device activity store.
    fun recordTriggerAttempt(uid: String, id: String, accepted: Boolean) {
        activityVault.update { root ->
            val events = root.optJSONObject("triggerHistory")?.optJSONArray(uid) ?: return@update
            for (index in 0 until events.length()) {
                val item = events.getJSONObject(index)
                // Only do this when item opt string id is id.
                if (item.optString("id") == id) {
                    // A clean first-try unlock is routine; only show Accepted after this trigger already had a wrong PIN.
                    if (accepted && item.optInt("pinAttempts") == 0) break
                    item.put("pinAttempts", item.optInt("pinAttempts") + 1)
                    item.put("lastResult", if (accepted) "Accepted" else "Incorrect PIN")
                    root.child("historySynced").child(uid).put("triggers/$id", false)
                    break
                }
            }
        }
    }

    // Adds a private-entry event to encrypted device activity history.
    fun savePrivateEntry(uid: String, entry: PrivateEntry) {
        activityVault.update { root ->
            val byAccount = root.child("privateEntries")
            val entries = byAccount.optJSONArray(uid) ?: JSONArray().also { byAccount.put(uid, it) }
            val index = (0 until entries.length()).firstOrNull {
                entries.getJSONObject(it).optString("id") == entry.id
            }
            if (index == null) entries.put(entry.toJson()) else entries.put(index, entry.toJson())
            root.child("historySynced").child(uid).put("private_entries/${entry.id}", false)
        }
    }

    // Loads private notes and incident records from encrypted phone storage.
    fun privateEntries(uid: String): List<PrivateEntry> =
        rows(uid, "privateEntries").map(PrivateEntry::fromJson)
            .filter { it.purgedAt == null }

    // After the configured retention period, erase private content but keep a tiny sync tombstone so another device cannot resurrect it.
    fun purgeExpiredPrivateEntries(uid: String, now: Long = System.currentTimeMillis()): Boolean {
        var changed = false
        activityVault.update { root ->
            val entries = root.optJSONObject("privateEntries")?.optJSONArray(uid) ?: return@update
            for (index in 0 until entries.length()) {
                val entry = PrivateEntry.fromJson(entries.getJSONObject(index))
                // Permanently delete trashed entries only after their recovery timer ends.
                if (entry.purgedAt == null && entry.trashExpired(now)) {
                    val tombstone = entry.copy(
                        updatedAt = now,
                        kind = PrivateEntryKind.Note,
                        title = "",
                        body = "",
                        pinned = false,
                        edits = emptyList(),
                        purgedAt = now
                    )
                    entries.put(index, tombstone.toJson())
                    root.child("historySynced").child(uid).put("private_entries/${entry.id}", false)
                    changed = true
                }
            }
        }
        return changed
    }

    // Loads activity records that still need cloud sync.
    fun pendingHistory(uid: String): List<HistoryRecord> {
        val root = activityVault.read()
        val synced = root.optJSONObject("historySynced")?.optJSONObject(uid)
        return listOf(
            "settings" to "settingsHistory",
            "triggers" to "triggerHistory",
            "private_entries" to "privateEntries"
        )
            .flatMap { (category, section) ->
                val rows = root.optJSONObject(section)?.optJSONArray(uid) ?: JSONArray()
                (0 until rows.length()).map { rows.getJSONObject(it) }
                    .filter { synced?.optBoolean("$category/${it.getString("id")}") != true }
                    .map { HistoryRecord(category, it.getString("id"), it.toString()) }
            }
    }

    // Updates the saved history synced used by app security.
    fun markHistorySynced(uid: String, record: HistoryRecord) {
        activityVault.update { root ->
            val section = when (record.category) {
                "settings" -> "settingsHistory"
                "triggers" -> "triggerHistory"
                "private_entries" -> "privateEntries"
                else -> return@update
            }
            val rows = root.optJSONObject(section)?.optJSONArray(uid) ?: return@update
            val current = (0 until rows.length()).map { rows.getJSONObject(it) }
                .firstOrNull { it.optString("id") == record.id }
            if (current?.toString() == record.json)
                root.child("historySynced").child(uid).put("${record.category}/${record.id}", true)
        }
    }

    // Merges cloud history into the encrypted phone copy.
    fun mergeHistory(uid: String, records: List<HistoryRecord>) {
        activityVault.update { root ->
            val sections = mapOf(
                "settings" to "settingsHistory",
                "triggers" to "triggerHistory",
                "private_entries" to "privateEntries"
            )
            records.forEach { record ->
                val section = sections[record.category] ?: return@forEach
                val byAccount = root.child(section)
                val rows =
                    byAccount.optJSONArray(uid) ?: JSONArray().also { byAccount.put(uid, it) }
                val incoming = JSONObject(record.json)
                val existing = (0 until rows.length()).firstOrNull {
                    rows.getJSONObject(it).optString("id") == record.id
                }
                val localIsNewer = record.category == "triggers" && existing != null &&
                        rows.getJSONObject(existing)
                            .optInt("pinAttempts") > incoming.optInt("pinAttempts")
                var privateNeedsUpload = false
                if (existing == null) rows.put(incoming)
                else if (record.category == "triggers" && !localIsNewer) rows.put(
                    existing,
                    incoming
                )
                else if (record.category == "private_entries") {
                    // A purge tombstone wins by timestamp and never pulls old note text or edit history back in.
                    val local = PrivateEntry.fromJson(rows.getJSONObject(existing))
                    val cloud = PrivateEntry.fromJson(incoming)
                    val newest = when {
                        local.purgedAt != null -> local
                        cloud.purgedAt != null -> cloud
                        local.updatedAt > cloud.updatedAt -> local
                        else -> cloud
                    }
                    val edits = if (newest.purgedAt != null) emptyList() else
                        (local.edits + cloud.edits).distinctBy { it.id }.sortedBy { it.timestamp }
                    val merged = newest.copy(edits = edits)
                    rows.put(existing, merged.toJson())
                    privateNeedsUpload =
                        (local.purgedAt != null && cloud.purgedAt == null) || local.updatedAt > cloud.updatedAt ||
                                (newest.purgedAt == null && edits.any { localEdit -> cloud.edits.none { it.id == localEdit.id } })
                }
                if (!localIsNewer && !privateNeedsUpload) root.child("historySynced").child(uid)
                    .put("${record.category}/${record.id}", true)
            }
        }
    }

    // Adds one security event to the correct account and history section.
    private fun append(root: JSONObject, uid: String, section: String, value: JSONObject) {
        val byAccount = root.child(section)
        val entries = byAccount.optJSONArray(uid) ?: JSONArray().also { byAccount.put(uid, it) }
        entries.put(value)
        val category = when (section) {
            "settingsHistory" -> "settings"
            "triggerHistory" -> "triggers"
            "privateEntries" -> "private_entries"
            else -> error("Unknown private storage section")
        }
        root.child("historySynced").child(uid).put("$category/${value.getString("id")}", false)
    }

    // Reads one saved history list from the encrypted vault.
    private fun rows(uid: String, section: String): List<JSONObject> {
        val entries = activityVault.read().optJSONObject(section)?.optJSONArray(uid) ?: JSONArray()
        return (0 until entries.length()).map { entries.getJSONObject(it) }.asReversed()
    }
}

// All signed-in roles: creates and opens keys for encrypted media.

object KeyManager {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val RSA_MODE = "RSA/ECB/OAEPPadding"
    const val CURRENT_KEY_WRAP_VERSION = 2

    // The alias used to locate the user's RSA keys securely inside the Android hardware
    private const val USER_KEY_ALIAS = "olive.user.rsa.v2"
    private const val LEGACY_KEY_ALIAS = "olive.user.rsa.v1"

    /**
     * Generates a persistent RSA Public/Private KeyPair in the Android Keystore.
     * This should be called once when the user first creates their account.
     */
    fun generateUserKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            ANDROID_KEYSTORE
        )

        val parameterSpec = KeyGenParameterSpec.Builder(
            USER_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setDigests(
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA512,
                KeyProperties.DIGEST_SHA1
            )
            .setKeySize(2048)
            .build()

        keyPairGenerator.initialize(parameterSpec)
        return keyPairGenerator.generateKeyPair()
    }

    /**
     * Retrieves the user's Public Key to encrypt AES keys before sending to Firestore.
     */
    fun getPublicKey(): PublicKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val certificate = keyStore.getCertificate(USER_KEY_ALIAS)
        return certificate?.publicKey
    }

    // Loads this device's key pair or creates it the first time it is needed.
    fun getOrCreatePublicKey(): PublicKey = getPublicKey() ?: generateUserKeyPair().public

    // Secure messaging: identifies this installation without exposing its private key.
    fun deviceId(publicKey: PublicKey = getOrCreatePublicKey()): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
        return android.util.Base64.encodeToString(
            digest.copyOfRange(0, 18),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
    }

    /**
     * Retrieves the user's Private Key to decrypt AES keys pulled from Firestore.
     */
    private fun getPrivateKeys(): List<PrivateKey> {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return listOf(USER_KEY_ALIAS, LEGACY_KEY_ALIAS).mapNotNull { alias ->
            keyStore.getKey(alias, null) as? PrivateKey
        }
    }

    /**
     * Wraps (Encrypts) the AES SecretKey using an RSA Public Key.
     * Returns a Base64 string safe for Firestore.
     */
    fun wrapKey(secretKey: SecretKey, publicKey: PublicKey): String {
        return wrapKey(secretKey, publicKey, MGF1ParameterSpec.SHA1)
    }

    // Secure messaging: uses the mask digest declared by the receiving device.
    fun wrapKey(secretKey: SecretKey, publicKey: PublicKey, mgf1Digest: MGF1ParameterSpec): String {
        val cipher = Cipher.getInstance(RSA_MODE)
        val spec = oaepSpec(mgf1Digest = mgf1Digest)
        // Bypass WRAP_MODE entirely
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, spec)
        val wrappedBytes = cipher.doFinal(secretKey.encoded)
        return android.util.Base64.encodeToString(wrappedBytes, android.util.Base64.NO_WRAP)
    }

    /**
     * Converts a Base64 string from Firestore back into an RSA PublicKey.
     */
    fun decodePublicKey(base64Key: String): PublicKey {
        val keyBytes = Base64.getDecoder().decode(base64Key)
        val spec = X509EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    /**
     * Unwraps (Decrypts) the Base64 string back into a usable AES SecretKey.
     * Uses the user's secure hardware Private Key.
     */
    fun unwrapKey(wrappedKeyBase64: String, keyWrapVersion: Int? = null): SecretKey {
        val decodedBytes = android.util.Base64.decode(wrappedKeyBase64, android.util.Base64.NO_WRAP)
        val privateKeys = getPrivateKeys()
        if (privateKeys.isEmpty()) error("Private key missing")
        val candidates = oaepProfiles(keyWrapVersion)
        var lastFailure: GeneralSecurityException? = null
        privateKeys.forEach { privateKey ->
            candidates.forEach { profile ->
                // Use the fallback when unwrap key is unavailable.
                try {
                    val cipher = Cipher.getInstance(RSA_MODE)
                    // Bypass UNWRAP_MODE entirely and manually rebuild the AES key.
                    cipher.init(Cipher.DECRYPT_MODE, privateKey, oaepSpec(profile.digest, profile.mgf1Digest))
                    val decryptedBytes = cipher.doFinal(decodedBytes)
                    return try {
                        SecretKeySpec(decryptedBytes, "AES")
                    } finally {
                        decryptedBytes.fill(0)
                    }
                } catch (failure: GeneralSecurityException) {
                    lastFailure = failure
                }
            }
        }
        throw checkNotNull(lastFailure) { "No key-wrap algorithm was attempted" }
    }

    /**
     * Tries the device-specific key first and then the account recovery copy. Historical media can
     * contain keys wrapped at different times with different OAEP profiles, so the record-level
     * encryption version must not prevent a compatible recipient key from being considered.
     */
    fun unwrapFirst(wrappedKeysBase64: Iterable<String>, keyWrapVersion: Int? = null): SecretKey =
        unwrapFirstWithEnvelope(wrappedKeysBase64, keyWrapVersion).secretKey

    /** Returns both the successful envelope and its key so callers cache only usable key copies. */
    fun unwrapFirstWithEnvelope(
        wrappedKeysBase64: Iterable<String>,
        keyWrapVersion: Int? = null,
    ): UnwrappedKeyEnvelope {
        var lastFailure: Exception? = null
        wrappedKeysBase64.filter(String::isNotBlank).distinct().forEach { wrappedKey ->
            try {
                return UnwrappedKeyEnvelope(wrappedKey, unwrapKey(wrappedKey, keyWrapVersion))
            } catch (failure: Exception) {
                lastFailure = failure
            }
        }
        throw lastFailure ?: IllegalStateException("Encryption key is unavailable")
    }

    data class UnwrappedKeyEnvelope(
        val wrappedKeyBase64: String,
        val secretKey: SecretKey,
    )

    /**
     * New Android keys use SHA-256 as the OAEP digest and SHA-1 only for MGF1. The digest parameter
     * remains explicit so historical WebCrypto SHA-1/SHA-1 keys can be read without provider
     * defaults.
     */
    private fun oaepSpec(
        digest: String = "SHA-256",
        mgf1Digest: MGF1ParameterSpec,
    ) = OAEPParameterSpec(
        digest,
        "MGF1",
        mgf1Digest,
        PSource.PSpecified.DEFAULT
    )

    private data class OaepProfile(
        val digest: String,
        val mgf1Digest: MGF1ParameterSpec,
    )

    /** Explicit compatibility profiles; this never falls back to provider defaults. */
    private fun oaepProfiles(keyWrapVersion: Int?): List<OaepProfile> {
        val current = OaepProfile("SHA-256", MGF1ParameterSpec.SHA1)
        val legacyAndroidOrWeb = OaepProfile("SHA-256", MGF1ParameterSpec.SHA256)
        val legacyWeb = OaepProfile("SHA-1", MGF1ParameterSpec.SHA1)
        return when {
            keyWrapVersion != null && keyWrapVersion >= CURRENT_KEY_WRAP_VERSION ->
                listOf(current, legacyAndroidOrWeb, legacyWeb)
            keyWrapVersion == 1 ->
                listOf(legacyAndroidOrWeb, current, legacyWeb)
            else ->
                listOf(current, legacyAndroidOrWeb, legacyWeb)
        }
    }
}

// All signed-in roles: checks device credentials before safe-app entry.

// Hosts Android authentication without retaining an Activity in the session ViewModel.
internal class AndroidDeviceLock(
    private val activity: FragmentActivity,
    private val onAbandoned: () -> Unit
) {
    private var request: DeviceLockRequest? = null
    private var pendingCipher: javax.crypto.Cipher? = null
    var awaitingCredential: Boolean = false
        private set
    private val prompt = BiometricPrompt(
        activity,
        activity.mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
            // Continues private entry after Android confirms the device owner.
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val current = request ?: return
                val cipher = result.cryptoObject?.cipher
                    ?: runCatching { current.challenge.legacyCipher() }.getOrNull()
                finish(current, cipher)
            }

            // Any cancellation or rejected credential keeps the Safe App locked.
            override fun onAuthenticationError(code: Int, error: CharSequence) {
                request?.let { finish(it, null) }
            }
        }
    )

    // Opens Android device authentication and returns its result.
    fun show(value: DeviceLockRequest) {
        cancel()
        request = value
        val keyguard = activity.getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            finish(value, null)
            return
        }
        // AndroidX chooses the correct system prompt for this Android version.
        try {
            awaitingCredential = true
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.entry_device_lock))
                .setSubtitle(subtitle(value))
            // Android 11+ can bind the exact cipher operation to biometrics or the device lock.
            if (Build.VERSION.SDK_INT >= 30) {
                promptInfo.setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                prompt.authenticate(
                    promptInfo.build(),
                    BiometricPrompt.CryptoObject(checkNotNull(value.challenge.promptCipher))
                )
            } else {
                // AndroidX handles the device-lock fallback on Android 9/10. The cipher is opened
                // immediately after authentication because these versions cannot bind it to a PIN.
                promptInfo.setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                prompt.authenticate(promptInfo.build())
            }
        } catch (_: Exception) { // If Android cannot open its lock prompt, deny access by returning no authentication result.
            finish(value, null)
        }
    }

    // Formats the subtitle shown by app security.
    private fun subtitle(value: DeviceLockRequest) = activity.getString(
        when {
            value.settingsChange -> R.string.entry_confirm_settings
            value.challenge.enrolling -> R.string.entry_confirm_setup
            else -> R.string.entry_unlock_description
        }
    )

    // Ends the authentication request once and sends its final result.
    private fun finish(value: DeviceLockRequest, cipher: javax.crypto.Cipher?) {
        if (request !== value) return
        // Wait to show device authentication until the activity is visible.
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            request = null
            awaitingCredential = false
            pendingCipher = null
            value.complete(null)
            onAbandoned()
            return
        }
        if (cipher != null && !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            pendingCipher = cipher
            return
        }
        request = null
        awaitingCredential = false
        pendingCipher = null
        value.complete(cipher)
    }

    // Delivers the pending device-lock result when the app resumes.
    fun resume() {
        activity.window.decorView.post {
            val value = request
            val cipher = pendingCipher
            if (value != null && cipher != null) finish(value, cipher)
        }
    }

    // Android device lock: Cancels the active task.
    fun cancel() {
        val previous = request
        request = null
        awaitingCredential = false
        pendingCipher = null
        prompt.cancelAuthentication()
        previous?.complete(null)
    }
}
