package com.olive.app.data

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.security.PublicKey
import javax.crypto.SecretKey

class SecureDocumentRepository(private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()) {

    /**
     * Encrypts and uploads a Private Entry or Incident Report for multiple recipients.
     *
     * @param entryId The unique UUID of the document.
     * @param ownerUid The UID of the creator.
     * @param title The plaintext title.
     * @param body The plaintext body.
     * @param recipientPublicKeys Map of UID -> RSA PublicKey for everyone who needs access (including the owner).
     */
    suspend fun saveMultiRecipientEntry(
        entryId: String,
        ownerUid: String,
        title: String,
        body: String,
        recipientPublicKeys: Map<String, PublicKey>
    ) {
        // 1. Serialize the plaintext payload
        val payload = JSONObject().apply {
            put("title", title.trim())
            put("body", body.trim())
        }.toString()

        // 2. Generate a fresh AES Data Encryption Key for this specific document
        val documentKey: SecretKey = EncryptionUtils.generateSymmetricKey()

        // 3. Encrypt the payload using the AES key
        val ciphertext = EncryptionUtils.encryptText(payload, documentKey)

        // 4. Wrap the AES key using the RSA public key of EVERY recipient
        val wrappedKeysMap = mutableMapOf<String, String>()
        for ((uid, publicKey) in recipientPublicKeys) {
            wrappedKeysMap[uid] = KeyManager.wrapKey(documentKey, publicKey)
        }

        // 5. Construct the secure cloud entity
        val secureEntry = mapOf(
            "id" to entryId,
            "ownerUid" to ownerUid,
            "ciphertext" to ciphertext,
            "wrappedKeys" to wrappedKeysMap,
            "allowedUsers" to recipientPublicKeys.keys.toList(),
            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )

        // 6. Upload to Firestore (using set with merge to handle updates cleanly)
        firestore.collection("privateEntries")
            .document(entryId)
            .set(secureEntry, SetOptions.merge())
            .await()
    }
}