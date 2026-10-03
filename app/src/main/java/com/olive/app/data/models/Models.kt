package com.olive.app.data.models

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

//Implement User data collection schema while preserving naming conventions
data class User(
    @DocumentId
    val uid: String = "",
    val username: String = "",

    @PropertyName("cover_app")
    val coverApp: String = "",

    @PropertyName("safe_contacts")
    val safeContacts: List<SafeContact> = emptyList(),

    @PropertyName("situation_tag")
    val situationTag: String? = null
)

data class SafeContact(
    val name: String = "",
    val phone: String = "",
    val relationship: String = ""
)
//implement file collection schema
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