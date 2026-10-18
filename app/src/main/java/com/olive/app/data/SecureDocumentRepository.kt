package com.olive.app.data

import com.google.firebase.firestore.FieldValue
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
    /**
     * Revokes a recipient's access to a shared entry.
     * Deletes their wrapped AES key and removes them from the Firestore security rules array.
     */
    suspend fun revokeAccessToEntry(entryId: String, recipientUid: String) {
        firestore.collection("privateEntries").document(entryId).update(
            "wrappedKeys.${recipientUid}", com.google.firebase.firestore.FieldValue.delete(),
            "allowedUsers", com.google.firebase.firestore.FieldValue.arrayRemove(recipientUid),
            "updatedAt", FieldValue.serverTimestamp()
        ).await()
    }

    /**
     * Grants a new recipient access to an existing encrypted entry.
     * Extracts the AES key using the owner's private key, wraps it for the recipient, and updates Firestore.
     */
    suspend fun grantAccessToEntry(
        entryId: String,
        ownerUid: String,
        recipientUid: String,
        recipientPublicKey: PublicKey
    ) {
        val documentRef = firestore.collection("privateEntries").document(entryId)
        val snapshot = documentRef.get().await()

        if (!snapshot.exists()) throw Exception("Document not found.")

        @Suppress("UNCHECKED_CAST")
        val wrappedKeys = snapshot.get("wrappedKeys") as? Map<String, String>
            ?: throw Exception("Encryption keys missing.")

        val ownerWrappedKey = wrappedKeys[ownerUid]
            ?: throw Exception("You do not have the cryptographic key to share this document.")

        // 1. Unwrap the AES key using the owner's hardware Private Key
        val documentKey = KeyManager.unwrapKey(ownerWrappedKey)

        // 2. Wrap the AES key using the new recipient's Public Key
        val newRecipientWrappedKey = KeyManager.wrapKey(documentKey, recipientPublicKey)

        // 3. Append the new key and update security rule arrays
        documentRef.update(
            "wrappedKeys.${recipientUid}", newRecipientWrappedKey,
            "allowedUsers", FieldValue.arrayUnion(recipientUid),
            "updatedAt", FieldValue.serverTimestamp()
        ).await()
    }

    /**
     * Retrieves and decrypts a shared Private Entry.
     */
    suspend fun loadAndDecryptEntry(entryId: String, currentUid: String): PrivateEntry {
        val snapshot = firestore.collection("privateEntries").document(entryId).get().await()

        if (!snapshot.exists()) throw Exception("Document not found.")

        @Suppress("UNCHECKED_CAST")
        val wrappedKeysMap = snapshot.get("wrappedKeys") as? Map<String, String>
            ?: throw Exception("Encryption keys missing from document.")

        // 1. Find the current user's specific wrapped key
        val myWrappedKey = wrappedKeysMap[currentUid]
            ?: throw Exception("You do not have the cryptographic key to read this document.")

        // 2. Unwrap the AES key using the hardware Private Key
        val documentKey = KeyManager.unwrapKey(myWrappedKey)

        // 3. Decrypt the ciphertext
        val ciphertext = snapshot.getString("ciphertext") ?: throw Exception("Ciphertext missing.")
        val plaintextJson = EncryptionUtils.decryptText(ciphertext, documentKey)

        // 4. Parse the JSON back into the local data model
        val jsonObject = JSONObject(plaintextJson)
        val title = jsonObject.optString("title", "Decryption Error")
        val body = jsonObject.optString("body", "Decryption Error")

        return PrivateEntry(
            id = entryId,
            title = title,
            body = body
        )
    }

    /**
     * Completely deletes an encrypted entry from the cloud.
     */
    suspend fun deleteEntry(entryId: String) {
        firestore.collection("privateEntries").document(entryId).delete().await()
    }
}