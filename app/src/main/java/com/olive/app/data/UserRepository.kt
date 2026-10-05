@file:Suppress("unused") // This merged repository is ready for the profile screen that will call it later.

package com.olive.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.olive.app.data.models.User
import kotlinx.coroutines.tasks.await

class UserRepository {
    // Initialize our Firestore database instance
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    // 1. Save or Update a User Profile
    suspend fun saveUserProfile(user: User): Boolean {
        require(auth.currentUser?.uid == user.uid) { "Sign in again before saving this profile" }
        return try {
            // We use the user's Auth UID directly as the document ID
            db.collection("users").document(user.uid).set(user).await()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // 2. Retrieve a User Profile
    suspend fun getUserProfile(uid: String): User? {
        require(auth.currentUser?.uid == uid) { "Sign in again before loading this profile" }
        return try {
            // Fetch the document matching the UID
            val snapshot = db.collection("users").document(uid).get().await()

            // Automatically convert the NoSQL document back into our Kotlin User object
            snapshot.toObject(User::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
