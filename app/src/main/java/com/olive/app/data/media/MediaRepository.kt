// Users and approved viewers: requests encrypted media upload and download access.
package com.olive.app.data.media

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import java.net.URI
import kotlinx.coroutines.tasks.await

/**
 * Control-plane contract for encrypted media.
 *
 * Implementations exchange authorization and metadata only. Bulk bytes are deliberately absent from
 * this interface so they cannot accidentally transit Firebase. [MediaUploadWorker] and
 * `MediaTransferController` use the returned short-lived capabilities for direct R2 PUT/GET requests.
 */
interface MediaTicketDataSource {
    /** Creates or recovers an idempotent server upload session for [job]. */
    suspend fun createUpload(job:MediaUploadJob):MediaUploadSession
    /** Requests a short-lived PUT capability for exactly one single/multipart part. */
    suspend fun uploadTicket(jobId:String,partNumber:Int):MediaUploadTicket
    /** Commits accepted ETags and publishes immutable media metadata after R2 size verification. */
    suspend fun completeUpload(jobId:String,parts:List<MediaCompletedPart>):CloudMediaRecord
    /** Aborts a pending R2 multipart session and removes its pending Firestore state. */
    suspend fun abortUpload(jobId:String)
    /** Returns a short-lived GET capability after the callable checks `allowed_users`. */
    suspend fun downloadTicket(mediaId:String):MediaDownloadTicket
    /** Replaces the owner-controlled recipient list and wrapped AES keys without exposing plaintext. */
    suspend fun updateAccess(mediaId:String,wrappedKeys:Map<String,String>):CloudMediaRecord
    /** Deletes both the owner's R2 object and its Firestore metadata through trusted code. */
    suspend fun deleteMedia(mediaId:String)
}

/**
 * Firebase Functions implementation of [MediaTicketDataSource].
 *
 * Relationship summary for diagram generation:
 * - called by [MediaUploadWorker] for upload session/ticket/finalization operations;
 * - called by `MediaTransferController` for download tickets;
 * - delegates authorization and R2 signing to `functions/src/mediaTickets.js`;
 * - never accepts an `InputStream`, `ByteArray`, or plaintext payload.
 *
 * Every returned URL is locally constrained to HTTPS on Cloudflare's R2 hostname before OkHttp sees
 * it. This limits the impact of a malformed or compromised callable response.
 */
class MediaRepository(
    private val functions:FirebaseFunctions=FirebaseFunctions.getInstance("northamerica-northeast1"),
    private val auth:FirebaseAuth=FirebaseAuth.getInstance(),
    private val appCheck:FirebaseAppCheck=FirebaseAppCheck.getInstance(),
):MediaTicketDataSource {
    override suspend fun createUpload(job:MediaUploadJob):MediaUploadSession {
        // Only ciphertext facts and already-encrypted metadata cross the Firebase boundary.
        val source=job.source
        val result=call("mediaCreateUpload",mapOf(
            "jobId" to job.id,"ciphertextSize" to source.ciphertextSize,
            "ciphertextSha256" to source.ciphertextSha256,"encryptedMetadata" to source.encryptedMetadata,
            "wrappedKeys" to source.wrappedKeys,"allowedUsers" to source.allowedUsers,
            "encryptionVersion" to source.encryptionVersion,
        ))
        return MediaUploadSession(job.id,result.string("mediaId"),result.string("objectKey"),
            MediaUploadMode.valueOf(result.string("mode")),result.optionalString("uploadId"),result.long("partSize"))
    }

    override suspend fun uploadTicket(jobId:String,partNumber:Int):MediaUploadTicket {
        require(partNumber>0)
        val result=call("mediaUploadPartTicket",mapOf("jobId" to jobId,"partNumber" to partNumber))
        return MediaUploadTicket(validateUrl(result.string("url")),result.stringMap("headers"),
            result.long("expiresAt"),partNumber)
    }

    override suspend fun completeUpload(jobId:String,parts:List<MediaCompletedPart>):CloudMediaRecord {
        val result=call("mediaCompleteUpload",mapOf("jobId" to jobId,"parts" to parts.map {
            mapOf("partNumber" to it.partNumber,"etag" to it.etag)
        }))
        return result.record()
    }

    override suspend fun abortUpload(jobId:String) { call("mediaAbortUpload",mapOf("jobId" to jobId)) }

    override suspend fun downloadTicket(mediaId:String):MediaDownloadTicket {
        val result=call("mediaDownloadTicket",mapOf("mediaId" to mediaId))
        return MediaDownloadTicket(mediaId,validateUrl(result.string("url")),result.stringMap("headers"),result.long("expiresAt"))
    }

    override suspend fun updateAccess(mediaId:String,wrappedKeys:Map<String,String>):CloudMediaRecord {
        require(wrappedKeys.isNotEmpty() && wrappedKeys.size<=25)
        return call("mediaUpdateAccess",mapOf("mediaId" to mediaId,"wrappedKeys" to wrappedKeys)).record()
    }

    override suspend fun deleteMedia(mediaId:String) { call("mediaDelete",mapOf("mediaId" to mediaId)) }

    private suspend fun call(name:String,data:Map<String,Any>):Map<*,*> {
        // WorkManager can start before the SDK has restored its cached tokens. Waiting here makes
        // sure the callable receives both the signed-in account and the required App Check proof.
        val user=auth.currentUser ?: error("Sign in again before uploading files")
        user.getIdToken(false).await()
        appCheck.getAppCheckToken(false).await()
        return functions.getHttpsCallable(name).call(data).await().data as? Map<*,*>
            ?: error("Invalid $name response")
    }

    private fun validateUrl(value:String):String {
        // Signed URLs are bearer capabilities. Reject credentials, plaintext HTTP, and foreign hosts.
        val uri=URI(value)
        require(uri.scheme.equals("https",true) && uri.userInfo==null && uri.host?.endsWith(".r2.cloudflarestorage.com")==true) {
            "Media ticket returned an untrusted URL"
        }
        return value
    }
}

private fun Map<*,*>.string(name:String)=get(name) as? String ?: error("Missing $name")
private fun Map<*,*>.optionalString(name:String)=get(name) as? String ?: ""
private fun Map<*,*>.long(name:String)=(get(name) as? Number)?.toLong() ?: error("Missing $name")
private fun Map<*,*>.stringMap(name:String):Map<String,String> = (get(name) as? Map<*,*>)?.entries?.associate { entry ->
    (entry.key as? String ?: error("Invalid $name key")) to (entry.value as? String ?: error("Invalid $name value"))
}.orEmpty()
private fun Map<*,*>.stringList(name:String)=(get(name) as? List<*>)?.filterIsInstance<String>() ?: error("Missing $name")
private fun Map<*,*>.record()=CloudMediaRecord(string("mediaId"),string("ownerUid"),string("objectKey"),
    string("encryptedMetadata"),stringMap("wrappedKeys"),stringList("allowedUsers"),long("ciphertextSize"),
    string("ciphertextSha256"),(get("encryptionVersion") as? Number)?.toInt() ?: 1,long("createdAt"))
