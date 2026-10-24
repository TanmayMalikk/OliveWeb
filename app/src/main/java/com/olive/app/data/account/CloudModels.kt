// Contains the cloud models code for this part of Olive.
package com.olive.app.data

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

// Firestore account data. Property annotations preserve the existing cloud field names.
data class User(
    // Firestore fills this value from the document ID.
    @DocumentId
    val uid: String = "",
    val username: String = "",
    val fullName: String = "",
    val affectedNames: String = "",
    val dob: String = "",
    val language: String = "",
    val situation: String = "",
    val region: String = "",
    val triggerHash: String = "",
    val contacts: List<Contact> = emptyList(),
    val address: Address? = null,

    @PropertyName("cover_app")
    val coverApp: String = "",
)

// Groups the data and actions for contact.
data class Contact(
    val id: String = "",
    val name: String = "",
    val phone: String = "",
    val details: String = ""
)

// Groups the data and actions for address.
data class Address(
    val line1: String = "",
    val line2: String = "",
    val city: String = "",
    val province: String = "",
    val postalCode: String = "",
    val country: String = ""
)

// Metadata stored for an uploaded document.
data class FileMetadata(
    @DocumentId
    val fileId: String = "",

    @PropertyName("owner_id")
    val ownerId: String = "",

    @PropertyName("storage_path")
    val storagePath: String = "",

    @PropertyName("file_name")
    val fileName: String = "",

    @PropertyName("file_type")
    val fileType: String = "",

    @PropertyName("allowed_users")
    val allowedUsers: List<String> = emptyList(),

    @ServerTimestamp
    @PropertyName("created_at")
    val createdAt: Date? = null
)

// Groups the data and actions for secure cloud entry.
data class SecureCloudEntry(
    val id: String,
    val ownerUid: String,
    val ciphertext: String, // The AES-encrypted JSON of title, body, and edits
    val wrappedKeys: Map<String, String>, // Map of UID -> RSA-wrapped AES Key
    val allowedUsers: List<String>, // Array of UIDs for Firestore security rules
    val createdAt: Long,
    val updatedAt: Long
)
