package com.olive.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.UUID

data class SafetyDocument(
    val id:String,val ownerUid:String,val storagePath:String,val name:String,val mimeType:String,
    val allowedUsers:List<String>,val createdAt:Long=0,
)

interface SafetyDocumentDataSource {
    suspend fun listFor(uid:String):List<SafetyDocument>
    suspend fun getFor(uid:String,id:String):SafetyDocument
    suspend fun upload(context:Context,uri:Uri,owner:AccountIdentity):SafetyDocument
    suspend fun share(document:SafetyDocument,ownerUid:String,recipientUids:List<String>)
    suspend fun delete(document:SafetyDocument,ownerUid:String)
    suspend fun download(context:Context,document:SafetyDocument):File
}

class SafetyDocumentRepository(
    private val firestore:FirebaseFirestore=FirebaseFirestore.getInstance(),
    private val storage:FirebaseStorage=FirebaseStorage.getInstance(),
):SafetyDocumentDataSource {
    override suspend fun listFor(uid:String):List<SafetyDocument> = firestore.collection("files")
        .whereArrayContains("allowed_users",uid).get().await().documents.mapNotNull(::documentFrom)
        .sortedByDescending { it.createdAt }

    override suspend fun getFor(uid:String,id:String):SafetyDocument {
        val document=documentFrom(firestore.collection("files").document(id).get().await())
            ?: error("File is no longer available")
        require(uid in document.allowedUsers) { "You no longer have access to this file" }
        return document
    }

    override suspend fun upload(context:Context,uri:Uri,owner:AccountIdentity):SafetyDocument {
        val resolver=context.contentResolver
        val mime=resolver.getType(uri)?.take(120)?.ifBlank { null } ?: "application/octet-stream"
        require(mime.startsWith("image/") || mime.startsWith("video/") || mime in DOCUMENT_TYPES) { "Unsupported file type" }
        val size=resolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use { cursor ->
            if(cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
        require(size==null || size in 1..MAX_BYTES) { "File must be smaller than 250 MB" }
        val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
            if(cursor.moveToFirst()) cursor.getString(0) else null
        }?.trim()?.take(160).orEmpty().ifBlank { "Document" }
        val id=UUID.randomUUID().toString();val path="users/${owner.uid}/media/$id"
        val reference=storage.reference.child(path)
        try {
            resolver.openInputStream(uri)?.use { reference.putStream(it).await() } ?: error("Unable to read the selected file")
            firestore.collection("files").document(id).set(mapOf(
                "owner_id" to owner.uid,"storage_path" to path,"file_name" to name,"file_type" to mime,
                "allowed_users" to listOf(owner.uid),"created_at" to FieldValue.serverTimestamp(),
            )).await()
        } catch(failure:Exception) {
            runCatching { reference.delete().await() }
            throw failure
        }
        return SafetyDocument(id,owner.uid,path,name,mime,listOf(owner.uid),System.currentTimeMillis())
    }

    override suspend fun share(document:SafetyDocument,ownerUid:String,recipientUids:List<String>) {
        require(document.ownerUid==ownerUid && recipientUids.distinct().size<=10)
        firestore.collection("files").document(document.id).update("allowed_users",listOf(ownerUid)+recipientUids.distinct()).await()
    }

    suspend fun grantAccess(document:SafetyDocument,ownerUid:String,recipientUid:String) {
        require(document.ownerUid==ownerUid && recipientUid!=ownerUid)
        firestore.collection("files").document(document.id)
            .update("allowed_users",FieldValue.arrayUnion(recipientUid)).await()
    }

    suspend fun revokeAccess(documentId:String,ownerUid:String,recipientUid:String) {
        val document=getFor(ownerUid,documentId)
        require(document.ownerUid==ownerUid && recipientUid!=ownerUid)
        firestore.collection("files").document(documentId)
            .update("allowed_users",FieldValue.arrayRemove(recipientUid)).await()
    }

    override suspend fun delete(document:SafetyDocument,ownerUid:String) {
        require(document.ownerUid==ownerUid)
        storage.reference.child(document.storagePath).delete().await()
        firestore.collection("files").document(document.id).delete().await()
    }

    override suspend fun download(context:Context,document:SafetyDocument):File {
        val folder=File(context.cacheDir,"opened-documents").apply { mkdirs() }
        folder.listFiles()?.filter { System.currentTimeMillis()-it.lastModified()>CACHE_LIFETIME }?.forEach(File::delete)
        val extension=document.name.substringAfterLast('.',"").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
        val target=File(folder,document.id+(extension?.let { ".$it" } ?: ""))
        storage.reference.child(document.storagePath).getFile(target).await()
        return target
    }

    private fun documentFrom(row:DocumentSnapshot):SafetyDocument? {
        val owner=row.getString("owner_id") ?: return null
        return SafetyDocument(row.id,owner,row.getString("storage_path").orEmpty(),row.getString("file_name").orEmpty(),
            row.getString("file_type").orEmpty(),(row.get("allowed_users") as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            row.getTimestamp("created_at")?.toDate()?.time ?: 0)
    }

    private companion object {
        const val MAX_BYTES=250L*1024L*1024L
        const val CACHE_LIFETIME=60L*60L*1000L
        val DOCUMENT_TYPES=setOf("application/pdf","text/plain","application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    }
}
