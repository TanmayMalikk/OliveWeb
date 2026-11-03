// Users and authorized viewers: requests encrypted media upload and download access.
package com.olive.app.data.media

import com.olive.app.BuildConfig
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
    /** Returns the callable-only account recovery key after the server checks file access. */
    suspend fun accountAccessKey(mediaId:String):String
    /** Saves a recovery key for an older owner document after this device decrypts it. */
    suspend fun storeAccountAccessKey(mediaId:String,accountAccessKey:String) = Unit
    /** Replaces the owner-controlled recipient list and wrapped AES keys without exposing plaintext. */
    suspend fun updateAccess(mediaId:String,wrappedKeys:Map<String,String>):CloudMediaRecord
    /** Adds AES-key copies encrypted for the signed-in account's other registered devices. */
    suspend fun updateDeviceKeys(mediaId:String,deviceWrappedKeys:Map<String,String>):CloudMediaRecord
    /** Deletes both the owner's R2 object and its Firestore metadata through trusted code. */
    suspend fun deleteMedia(mediaId:String)
}

/** The installed app could not obtain the App Check proof required by encrypted-media functions. */
class MediaDeviceVerificationException(message:String,cause:Exception):Exception(message,cause)

/**
 * Firebase Functions implementation of [MediaTicketDataSource].
 *
 * Relationship summary for diagram generation:
 * - called by [MediaUploadWorker] for upload session/ticket/finalization operations;
 * - called by `MediaTransferController` for download tickets;
 * - delegates authorization and R2 signing to `functions/src/mediaTickets.js`;
 * - never accepts plaintext file content; only transfer facts and the account recovery key cross it.
 *
 * Every returned URL is locally constrained to HTTPS on Cloudflare's R2 hostname before OkHttp sees
 * it. This limits the impact of a malformed or compromised callable response.
 */
class MediaRepository(
    private val functions:FirebaseFunctions=FirebaseFunctions.getInstance("northamerica-northeast1"),
    private val auth:FirebaseAuth=FirebaseAuth.getInstance(),
    private val appCheck:FirebaseAppCheck=FirebaseAppCheck.getInstance(),
):MediaTicketDataSource {
    // Media: Builds upload.
    override suspend fun createUpload(job:MediaUploadJob):MediaUploadSession {
        // Only ciphertext facts and already-encrypted metadata cross the Firebase boundary.
        val source=job.source
        val result=call("mediaCreateUpload",mapOf(
            "jobId" to job.id,"ciphertextSize" to source.ciphertextSize,
            "ciphertextSha256" to source.ciphertextSha256,"encryptedMetadata" to source.encryptedMetadata,
            "wrappedKeys" to source.wrappedKeys,"allowedUsers" to source.allowedUsers,
            "deviceWrappedKeys" to source.deviceWrappedKeys,
            "accountAccessKey" to source.accountAccessKey,
            "encryptionVersion" to source.encryptionVersion,
        ))
        return MediaUploadSession(job.id,result.string("mediaId"),result.string("objectKey"),
            MediaUploadMode.valueOf(result.string("mode")),result.optionalString("uploadId"),result.long("partSize"))
    }

    // Media: Sends ticket. It checks access, protects the file, and then reads or saves it.
    override suspend fun uploadTicket(jobId:String,partNumber:Int):MediaUploadTicket {
        require(partNumber>0)
        val result=call("mediaUploadPartTicket",mapOf("jobId" to jobId,"partNumber" to partNumber))
        return MediaUploadTicket(validateUrl(result.string("url")),result.stringMap("headers"),
            result.long("expiresAt"),partNumber)
    }

    // Media: returns the complete upload.
    override suspend fun completeUpload(jobId:String,parts:List<MediaCompletedPart>):CloudMediaRecord {
        val result=call("mediaCompleteUpload",mapOf("jobId" to jobId,"parts" to parts.map {
            mapOf("partNumber" to it.partNumber,"etag" to it.etag)
        }))
        return result.record()
    }

    // Media: returns the abort upload. It checks access, protects the file, and then reads or saves it.
    override suspend fun abortUpload(jobId:String) { call("mediaAbortUpload",mapOf("jobId" to jobId)) }

    // Media: Downloads ticket. It checks access, protects the file, and then reads or saves it.
    override suspend fun downloadTicket(mediaId:String):MediaDownloadTicket {
        val result=call("mediaDownloadTicket",mapOf("mediaId" to mediaId))
        return MediaDownloadTicket(mediaId,validateUrl(result.string("url")),result.stringMap("headers"),result.long("expiresAt"))
    }

    // returns the account access key for media. It checks access, protects the file, and then reads or saves it.
    override suspend fun accountAccessKey(mediaId:String):String =
        call("mediaAccountAccessKey",mapOf("mediaId" to mediaId)).string("accountAccessKey")

    // Saves account access key for media. It checks access, protects the file, and then reads or saves it.
    override suspend fun storeAccountAccessKey(mediaId:String,accountAccessKey:String) {
        call("mediaStoreAccountAccessKey",mapOf(
            "mediaId" to mediaId,
            "accountAccessKey" to accountAccessKey,
        ))
    }

    // Updates access for media.
    override suspend fun updateAccess(mediaId:String,wrappedKeys:Map<String,String>):CloudMediaRecord {
        require(wrappedKeys.isNotEmpty() && wrappedKeys.size<=25)
        return call("mediaUpdateAccess",mapOf("mediaId" to mediaId,"wrappedKeys" to wrappedKeys)).record()
    }

    // Media: Updates device keys.
    override suspend fun updateDeviceKeys(mediaId:String,deviceWrappedKeys:Map<String,String>):CloudMediaRecord {
        require(deviceWrappedKeys.isNotEmpty() && deviceWrappedKeys.size<=50)
        return call("mediaUpdateDeviceKeys",mapOf("mediaId" to mediaId,
            "deviceWrappedKeys" to deviceWrappedKeys)).record()
    }

    // Removes media for media.
    override suspend fun deleteMedia(mediaId:String) { call("mediaDelete",mapOf("mediaId" to mediaId)) }

    // Calls the matching backend service for media.
    private suspend fun call(name:String,data:Map<String,Any>):Map<*,*> {
        // WorkManager can start before the SDK has restored its cached tokens. Waiting here makes
        // sure the callable receives both the signed-in account and the required App Check proof.
        val user=auth.currentUser ?: error("Sign in again before uploading files")
        user.getIdToken(false).await()
        // A call error is handled here instead of closing the app.
        try {
            appCheck.getAppCheckToken(false).await()
        } catch(error:Exception) {
            // Media functions enforce App Check. Keep that protection, but replace Firebase's vague
            // retry-limit message with the exact action needed for an unregistered debug device.
            val message=if(BuildConfig.DEBUG)
                "This debug installation is not authorized for encrypted uploads. Register this device's Firebase App Check debug token, reopen the app, then tap Retry."
            else "This app installation could not be verified. Reinstall the official app, then tap Retry."
            throw MediaDeviceVerificationException(message,error)
        }
        return functions.getHttpsCallable(name).call(data).await().data as? Map<*,*>
            ?: error("Invalid $name response")
    }

    // Checks whether url for media. It checks access, protects the file, and then reads or saves it.
    private fun validateUrl(value:String):String {
        // Signed URLs are bearer capabilities. Reject credentials, plaintext HTTP, and foreign hosts.
        val uri=URI(value)
        require(uri.scheme.equals("https",true) && uri.userInfo==null && uri.host?.endsWith(".r2.cloudflarestorage.com")==true) {
            "Media ticket returned an untrusted URL"
        }
        return value
    }
}

// Encrypted media: Reads a required text field from a downloaded media record and rejects missing data.
private fun Map<*,*>.string(name:String)=get(name) as? String ?: error("Missing $name")
// Encrypted media: Reads an optional text field and uses an empty value when older records omit it.
private fun Map<*,*>.optionalString(name:String)=get(name) as? String ?: ""
// Encrypted media: Reads a required whole number from a downloaded media record.
private fun Map<*,*>.long(name:String)=(get(name) as? Number)?.toLong() ?: error("Missing $name")
// Encrypted media: Builds a text map by checking every downloaded key and value before use.
private fun Map<*,*>.stringMap(name:String):Map<String,String> = (get(name) as? Map<*,*>)?.entries?.associate { entry ->
    (entry.key as? String ?: error("Invalid $name key")) to (entry.value as? String ?: error("Invalid $name value"))
}.orEmpty()
// Encrypted media: Reads the list of account IDs allowed to open a downloaded file.
private fun Map<*,*>.stringList(name:String)=(get(name) as? List<*>)?.filterIsInstance<String>() ?: error("Missing $name")
// Encrypted media: Rebuilds a cloud media record by reading each validated field from the downloaded map.
private fun Map<*,*>.record()=CloudMediaRecord(string("mediaId"),string("ownerUid"),string("objectKey"),
    string("encryptedMetadata"),stringMap("wrappedKeys"),stringList("allowedUsers"),long("ciphertextSize"),
    string("ciphertextSha256"),(get("encryptionVersion") as? Number)?.toInt() ?: 1,long("createdAt"),
    stringMap("deviceWrappedKeys"))
