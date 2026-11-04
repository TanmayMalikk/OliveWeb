// Safe-app users and support staff: opens encrypted documents for approved viewers.
package com.olive.app.data

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
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
     * @param entry The full note or incident report that the recipient will read.
     * @param ownerUid The UID of the creator.
     * @param title The plaintext title.
     * @param body The plaintext body.
     * @param recipientPublicKeys Map of UID -> RSA PublicKey for everyone who needs access (including the owner).
     */
    suspend fun saveMultiRecipientEntry(
        entry: PrivateEntry,
        ownerUid: String,
        recipientPublicKeys: Map<String, PublicKey>
    ) {
        // Encrypt the full report so chat recipients see its date, time, location, body, and links.
        val payload = entry.toJson().toString()

        val documentRef = firestore.collection("privateEntries").document(entry.id)
        // A first-time read of a missing protected document is denied by Firestore rules.
        // Treat that specific result as a new share; existing owner documents remain readable.
        val existing = try {
            documentRef.get().await()
        } catch (error: FirebaseFirestoreException) {
            if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) null else throw error
        }

        @Suppress("UNCHECKED_CAST")
        val existingWrapped = existing?.get("wrappedKeys") as? Map<String, String> ?: emptyMap()
        val documentKey: SecretKey = existingWrapped[ownerUid]?.let(KeyManager::unwrapKey)
            ?: EncryptionUtils.generateSymmetricKey()

        // 3. Encrypt the payload using the AES key
        val ciphertext = EncryptionUtils.encryptText(payload, documentKey)

        // 4. Wrap the AES key using the RSA public key of EVERY recipient
        val wrappedKeysMap = existingWrapped.toMutableMap()
        for ((uid, publicKey) in recipientPublicKeys) {
            wrappedKeysMap[uid] = KeyManager.wrapKey(documentKey, publicKey)
        }

        // 5. Construct the secure cloud entity
        val secureEntry = mapOf(
            "id" to entry.id,
            "ownerUid" to ownerUid,
            "ciphertext" to ciphertext,
            "wrappedKeys" to wrappedKeysMap,
            "allowedUsers" to wrappedKeysMap.keys.toList(),
            "createdAt" to (existing?.get("createdAt") ?: FieldValue.serverTimestamp()),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        // 6. Upload to Firestore (using set with merge to handle updates cleanly)
        documentRef.set(secureEntry).await()
    }

    /**
     * Revokes a recipient's access to a shared entry.
     * Deletes their wrapped AES key and removes them from the Firestore security rules array.
     */
    suspend fun revokeAccessToEntry(entryId: String, recipientUid: String) {
        firestore.collection("privateEntries").document(entryId).update(
            "wrappedKeys.${recipientUid}", FieldValue.delete(),
            "allowedUsers", FieldValue.arrayRemove(recipientUid),
            "updatedAt", FieldValue.serverTimestamp()
        ).await()
    }

    /**
     * Grants access to an existing encrypted entry for a new recipient.
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
        return if (jsonObject.has("timestamp")) PrivateEntry.fromJson(jsonObject)
        else PrivateEntry(
            id = entryId,
            title = jsonObject.optString("title", "Decryption Error"),
            body = jsonObject.optString("body", "Decryption Error"),
        )
    }

    /**
     * Completely deletes an encrypted entry from the cloud.
     */
    suspend fun deleteEntry(entryId: String) {
        firestore.collection("privateEntries").document(entryId).delete().await()
    }

    /**
     * Retrieves all Private Entries that this user has access to.
     */
    suspend fun fetchMyEntries(currentUid: String): List<PrivateEntry> {
        val snapshot = firestore.collection("privateEntries")
            .whereArrayContains("allowedUsers", currentUid)
            .get()
            .await()

        val entries = mutableListOf<PrivateEntry>()
        for (doc in snapshot.documents) {
            // Use the fallback when fetch my entries is unavailable.
            try {
                // We reuse loadAndDecryptEntry but pass the already fetched snapshot ID
                val decryptedEntry = loadAndDecryptEntry(doc.id, currentUid)
                entries.add(decryptedEntry)
            } catch (_: Exception) { // Skip one unreadable entry so the rest of the private list can still load.
            }
        }
        return entries
    }
}
