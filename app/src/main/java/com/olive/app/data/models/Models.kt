package com.olive.app.data.models

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

//Implement User data collection schema while preserving naming conventions
data class User(
    //set documentsId from firestore to uid
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

data class Contact(
    val id: String = "",
    val name: String = "",
    val phone: String = "",
    val details: String = ""
)

data class Address(
    val line1: String = "",
    val line2: String = "",
    val city: String = "",
    val province: String = "",
    val postalCode: String = "",
    val country: String = ""
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