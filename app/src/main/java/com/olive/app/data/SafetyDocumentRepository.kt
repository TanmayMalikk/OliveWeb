package com.olive.app.data

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File

/**
 * Domain projection spanning both legacy Firebase Storage files and new encrypted R2 media.
 *
 * [storagePath] identifies a legacy object; [objectKey] identifies the new R2-backed form. New records
 * keep display metadata encrypted in [encryptedMetadata], with the viewer's decryptable media key in
 * [wrappedKeys]. Keeping both shapes here allows the UI migration without rewriting old documents.
 */
data class SafetyDocument(
    val id:String,val ownerUid:String,val storagePath:String,val name:String,val mimeType:String,
    val allowedUsers:List<String>,val createdAt:Long=0,val objectKey:String="",
    val encryptedMetadata:String="",val wrappedKeys:Map<String,String> = emptyMap(),
    val ciphertextSize:Long=0,val ciphertextSha256:String="",val encryptionVersion:Int=0,
)

/** Domain repository contract consumed by `SafetyDocumentsController`. */
interface SafetyDocumentDataSource {
    suspend fun listFor(uid:String):List<SafetyDocument>
    fun observeFor(uid:String,onUpdate:(List<SafetyDocument>)->Unit,onError:(String)->Unit):ChatSubscription
    suspend fun getFor(uid:String,id:String):SafetyDocument
    suspend fun share(document:SafetyDocument,ownerUid:String,recipientUids:List<String>)
    suspend fun delete(document:SafetyDocument,ownerUid:String)
    suspend fun download(context:Context,document:SafetyDocument):File
}

/**
 * Safety-document metadata repository and legacy-storage compatibility adapter.
 *
 * New encrypted upload/download byte transfer is intentionally delegated to `MediaTransferController`;
 * this repository observes Firestore metadata, decrypts authorized display metadata, and routes delete
 * operations. Legacy Firebase Storage download/sharing remains here until chat attachments and existing
 * records complete migration.
 *
 * Relationship summary:
 * `SafetyDocumentsController -> SafetyDocumentRepository -> Firestore/Firebase Storage`, while
 * `SafetyDocumentsController -> MediaTransferController -> Cloudflare R2` handles encrypted bytes.
 */
class SafetyDocumentRepository(
    private val firestore:FirebaseFirestore=FirebaseFirestore.getInstance(),
    private val storage:FirebaseStorage=FirebaseStorage.getInstance(),
    private val media:MediaTicketDataSource=MediaRepository(),
):SafetyDocumentDataSource {
    override suspend fun listFor(uid:String):List<SafetyDocument> = firestore.collection("files")
        .whereArrayContains("allowed_users",uid).get().await().documents.mapNotNull { documentFrom(it,uid) }
        .sortedByDescending { it.createdAt }

    override fun observeFor(uid:String,onUpdate:(List<SafetyDocument>)->Unit,onError:(String)->Unit):ChatSubscription {
        val listener=firestore.collection("files").whereArrayContains("allowed_users",uid)
            .addSnapshotListener { snapshot,error ->
                if(error!=null) { onError(error.localizedMessage.orEmpty());return@addSnapshotListener }
                onUpdate(snapshot?.documents.orEmpty().mapNotNull { documentFrom(it,uid) }.sortedByDescending { it.createdAt })
            }
        return ChatSubscription { listener.remove() }
    }

    override suspend fun getFor(uid:String,id:String):SafetyDocument {
        val document=documentFrom(firestore.collection("files").document(id).get().await(),uid)
            ?: error("File is no longer available")
        require(uid in document.allowedUsers) { "You no longer have access to this file" }
        return document
    }

    override suspend fun share(document:SafetyDocument,ownerUid:String,recipientUids:List<String>) {
        require(document.ownerUid==ownerUid && recipientUids.distinct().size<=10)
        require(document.objectKey.isBlank()) { "Encrypted media sharing will be enabled with chat attachment migration" }
        val current=getFor(ownerUid,document.id)
        val updated=(listOf(ownerUid)+recipientUids.distinct()).distinct()
        updateStorageAccess(current,current.allowedUsers.toSet(),updated.toSet())
        try { firestore.collection("files").document(document.id).update("allowed_users",updated).await() }
        catch(failure:Exception) {
            runCatching { updateStorageAccess(current,updated.toSet(),current.allowedUsers.toSet()) }
            throw failure
        }
    }

    suspend fun grantAccess(document:SafetyDocument,ownerUid:String,recipientUid:String) {
        require(document.ownerUid==ownerUid && recipientUid!=ownerUid)
        require(document.objectKey.isBlank()) { "Encrypted media sharing will be enabled with chat attachment migration" }
        val current=getFor(ownerUid,document.id);val updated=current.allowedUsers.toSet()+recipientUid
        updateStorageAccess(current,current.allowedUsers.toSet(),updated)
        try { firestore.collection("files").document(document.id)
            .update("allowed_users",FieldValue.arrayUnion(recipientUid)).await() }
        catch(failure:Exception) {
            runCatching { updateStorageAccess(current,updated,current.allowedUsers.toSet()) }
            throw failure
        }
    }

    suspend fun revokeAccess(documentId:String,ownerUid:String,recipientUid:String) {
        val document=getFor(ownerUid,documentId)
        require(document.ownerUid==ownerUid && recipientUid!=ownerUid)
        require(document.objectKey.isBlank()) { "Encrypted media sharing will be enabled with chat attachment migration" }
        val updated=document.allowedUsers.toSet()-recipientUid
        updateStorageAccess(document,document.allowedUsers.toSet(),updated)
        try { firestore.collection("files").document(documentId)
            .update("allowed_users",FieldValue.arrayRemove(recipientUid)).await() }
        catch(failure:Exception) {
            runCatching { updateStorageAccess(document,updated,document.allowedUsers.toSet()) }
            throw failure
        }
    }

    override suspend fun delete(document:SafetyDocument,ownerUid:String) {
        require(document.ownerUid==ownerUid)
        // R2 objects must be deleted by the trusted callable so object and metadata cannot diverge.
        if(document.objectKey.isNotBlank()) { media.deleteMedia(document.id);return }
        storage.reference.child(document.storagePath).delete().await()
        firestore.collection("files").document(document.id).delete().await()
    }

    override suspend fun download(context:Context,document:SafetyDocument):File {
        // This method deliberately supports legacy objects only. New downloads require ticketing,
        // checksum verification, key unwrap, and authenticated decryption in MediaTransferController.
        require(document.objectKey.isBlank()) { "Encrypted media downloads use MediaTransferController" }
        val folder=File(context.cacheDir,"opened-documents").apply { mkdirs() }
        folder.listFiles()?.filter { System.currentTimeMillis()-it.lastModified()>CACHE_LIFETIME }?.forEach(File::delete)
        val extension=document.name.substringAfterLast('.',"").takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) }
        val target=File(folder,document.id+(extension?.let { ".$it" } ?: ""))
        storage.reference.child(document.storagePath).getFile(target).await()
        return target
    }

    private suspend fun updateStorageAccess(document:SafetyDocument,before:Set<String>,after:Set<String>) {
        val metadata=StorageMetadata.Builder().apply {
            after.forEach { setCustomMetadata(accessKey(it),"true") }
            (before-after).forEach { setCustomMetadata(accessKey(it),null) }
        }.build()
        storage.reference.child(document.storagePath).updateMetadata(metadata).await()
    }

    private fun documentFrom(row:DocumentSnapshot,viewerUid:String):SafetyDocument? {
        val owner=row.getString("owner_id") ?: return null
        val encryptedMetadata=row.getString("encrypted_metadata").orEmpty()
        @Suppress("UNCHECKED_CAST")
        val wrapped=(row.get("wrapped_keys") as? Map<String,String>).orEmpty()
        // Only an allowed viewer with a matching wrapped key can reveal name/MIME metadata.
        val metadata=if(encryptedMetadata.isBlank()) null else runCatching {
            val key=wrapped[viewerUid]?.let(KeyManager::unwrapKey) ?: error("Encryption key is unavailable")
            JSONObject(EncryptionUtils.decryptText(encryptedMetadata,key))
        }.getOrNull()
        return SafetyDocument(row.id,owner,row.getString("storage_path").orEmpty(),
            metadata?.optString("name")?.takeIf(String::isNotBlank) ?: row.getString("file_name").orEmpty().ifBlank { "Encrypted document" },
            metadata?.optString("mimeType")?.takeIf(String::isNotBlank) ?: row.getString("file_type").orEmpty().ifBlank { "application/octet-stream" },
            (row.get("allowed_users") as? List<*>)?.filterIsInstance<String>().orEmpty(),row.getTimestamp("created_at")?.toDate()?.time ?: 0,
            row.getString("object_key").orEmpty(),encryptedMetadata,wrapped,row.getLong("ciphertext_size") ?: 0,
            row.getString("ciphertext_sha256").orEmpty(),row.getLong("encryption_version")?.toInt() ?: 0)
    }

    private companion object {
        const val CACHE_LIFETIME=60L*60L*1000L
        fun accessKey(uid:String)="access_$uid"
    }
}
