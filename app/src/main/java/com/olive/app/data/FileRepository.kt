@file:Suppress("unused") // This merged repository is ready for the evidence screen that will call it later.

package com.olive.app.data

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.olive.app.data.models.FileMetadata
import kotlinx.coroutines.tasks.await
import java.util.UUID

class FileRepository {
    // Initialize our database and storage instances
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val storage = FirebaseStorage.getInstance()

    // 'suspend' allows this to run safely in the background without freezing the UI
    suspend fun uploadEvidence(ownerUid: String, fileUri: Uri, fileType: String): Boolean {
        require(auth.currentUser?.uid == ownerUid) { "Sign in again before uploading a file" }
        require(fileType.isNotBlank() && fileType.length <= 120) { "Choose a valid file type" }
        var uploadedPath: String? = null
        return try {
            // 1. Generate a random unique ID for the file
            val newFileId = UUID.randomUUID().toString()

            // 2. Define exactly where the file goes in Google's cloud buckets
            val storagePath = "users/$ownerUid/media/$newFileId"
            uploadedPath = storagePath
            val storageRef = storage.reference.child(storagePath)

            // 3. Upload the raw file to Storage (Server-Side Encryption automatically secures it here)
            storageRef.putFile(fileUri).await()

            // 4. Create our Metadata object using your new data class
            val metadata = FileMetadata(
                fileId = newFileId,
                ownerId = ownerUid,
                storagePath = storagePath,
                fileName = "SecureUpload_$newFileId", // Generic name to mask the actual content
                fileType = fileType,
                allowedUsers = listOf(ownerUid) // Initially, only the owner can read it
            )

            // 5. Save the metadata pointer to the Firestore NoSQL database
            db.collection("files").document(newFileId).set(metadata).await()

            // Return true if the whole pipeline succeeds
            true

        } catch (e: Exception) {
            uploadedPath?.let { path -> runCatching { storage.reference.child(path).delete().await() } }
            // If the upload fails (e.g., WiFi drops), log it and return false
            e.printStackTrace()
            false
        }
    }
}
