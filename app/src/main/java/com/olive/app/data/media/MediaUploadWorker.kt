// Safe-app users: resumes encrypted uploads in the background.
package com.olive.app.data.media

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctionsException
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.olive.app.BuildConfig
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.functions.FirebaseFunctions
import java.net.URI
import kotlinx.coroutines.tasks.await


/**
 * WorkManager executor for durable, network-constrained ciphertext uploads.
 *
 * Collaborators:
 * - [MediaTransferStore] is the source of truth for restart/resume state;
 * - [MediaTicketDataSource] supplies short-lived Firebase-authorized R2 capabilities;
 * - [OkHttpClient] streams encrypted file slices directly to Cloudflare R2;
 * - WorkManager supplies connectivity constraints, exponential backoff, and process recovery.
 *
 * State transition:
 * `QUEUED -> CREATING_SESSION -> UPLOADING -> FINALIZING -> COMPLETE`.
 * Retryable failures return the durable job to `QUEUED`; validation/authorization failures become
 * `FAILED`. Multipart progress is committed after each accepted ETag, so retrying never retransmits
 * a completed part. Single PUTs have one part and therefore restart from byte zero.
 *
 * Plaintext invariant: this worker only opens the app-private `.olive` ciphertext file created by
 * `MediaTransferController`; it has no decryption or key-management dependency.
 */
class MediaUploadWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    private val store=MediaTransferStore(context)
    private val offlineCache=OfflineMediaCache(context)
    private val repository:MediaTicketDataSource=MediaRepository()
    private val client=mediaUploadHttpClient()

    // Uploads one encrypted file in the background and saves its cloud record.
    override suspend fun doWork():Result {
        // WorkManager input contains only the durable job id; sensitive state is loaded from the vault.
        val jobId=inputData.getString(JOB_ID) ?: return Result.failure()
        var job=store.get(jobId) ?: return Result.success()
        if(job.status in setOf(MediaTransferStatus.COMPLETE,MediaTransferStatus.CANCELLED)) return Result.success()

        // Never continue another signed-in user's queued transfer after an account switch.
        if(FirebaseAuth.getInstance().currentUser?.uid!=job.ownerUid) {
            store.write(job.copy(status=MediaTransferStatus.QUEUED,
                error="Waiting for the account that queued this upload"))
            return Result.retry()
        }
        val file=File(job.source.filePath)
        if(!file.isFile || file.length()!=job.source.ciphertextSize) return permanent(job,"Encrypted upload staging file is missing")
        return try {
            // Stop a damaged upload job that has no media ID.
            if(job.mediaId.isBlank()) {
                // Session creation is idempotent by job id. Persist the response before any PUT so a
                // process death cannot create a second R2 object for the same local job.
                store.write(job.copy(status=MediaTransferStatus.CREATING_SESSION,error=null))
                val session=repository.createUpload(job)
                job=store.update(job.id) { it.copy(status=MediaTransferStatus.UPLOADING,mode=session.mode,
                    mediaId=session.mediaId,objectKey=session.objectKey,uploadId=session.uploadId,partSize=session.partSize,error=null) }
            }
            val parts=upload(file,job)

            // Finalization tells R2 to assemble multipart chunks, verifies the remote byte count,
            // and atomically publishes the zero-knowledge Firestore record.
            store.update(job.id) { it.copy(status=MediaTransferStatus.FINALIZING,error=null) }
            val record=repository.completeUpload(job.id,parts)
            store.update(job.id) { it.copy(status=MediaTransferStatus.COMPLETE,mediaId=record.id,error=null) }
            // Preserve a bounded reuse window so an owner can pin the already-uploaded ciphertext
            // without downloading it from R2. Cleanup remains durable if the UI never opens.
            runCatching {
                offlineCache.registerUploadCandidate(job.ownerUid,record.id,file,
                    job.source.ciphertextSize,job.source.ciphertextSha256)
                OfflineMediaCleanupWorker.enqueue(applicationContext,record.id)
            }.onFailure { file.delete() }
            Result.success(workDataOf(MEDIA_ID to record.id))
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(error:MediaDeviceVerificationException) {
            Log.e("OliveMediaUpload", "App Check rejected encrypted upload ${job.id}", error)
            permanent(job,error.message ?: "This app installation is not authorized for encrypted uploads")
        }
        catch(error:FirebaseFunctionsException) {
            Log.e("OliveMediaUpload", "Firebase rejected encrypted upload ${job.id}", error)
            if(error.code in setOf(FirebaseFunctionsException.Code.INVALID_ARGUMENT,FirebaseFunctionsException.Code.PERMISSION_DENIED,
                    FirebaseFunctionsException.Code.UNAUTHENTICATED,
                    FirebaseFunctionsException.Code.NOT_FOUND, FirebaseFunctionsException.Code.FAILED_PRECONDITION))
                permanent(job,error.localizedMessage ?: "Media upload was rejected")
            else retry(job,error)
        } catch(error:Exception) {
            Log.e("OliveMediaUpload", "Encrypted upload ${job.id} will retry", error)
            retry(job,error)
        }
    }

    // Media: Uploads the selected file.
    private suspend fun upload(file:File,initial:MediaUploadJob):List<MediaCompletedPart> {
        // The server chooses the strategy: a single PUT uses the whole file as part one; multipart
        // uses the server-returned part size so client and R2 boundaries cannot diverge.
        val partSize=if(initial.mode==MediaUploadMode.SINGLE) file.length() else initial.partSize
        require(partSize>0) { "Invalid upload part size" }
        val partCount=((file.length()+partSize-1)/partSize).toInt()
        var job=initial
        for(partNumber in 1..partCount) {
            // ETags written by a previous worker invocation are the durable resume checkpoints.
            if(job.completedParts.containsKey(partNumber)) continue
            val offset=(partNumber-1)*partSize
            val length=minOf(partSize,file.length()-offset)
            val ticket=repository.uploadTicket(job.id,partNumber)
            val etag=put(ticket,file,offset,length) { sent ->
                setProgressAsync(workDataOf(BYTES_SENT to (offset+sent),TOTAL_BYTES to file.length()))
            }
            // Persist only after OkHttp receives a successful response and captures the ETag.
            job=store.update(job.id) { current -> current.copy(status=MediaTransferStatus.UPLOADING,
                completedParts=current.completedParts+(partNumber to etag),error=null) }
        }
        return job.completedParts.entries.sortedBy { it.key }.map { MediaCompletedPart(it.key,it.value) }
    }

    // Uploads one encrypted file section and reports its byte progress.
    private suspend fun put(ticket:MediaUploadTicket,file:File,offset:Long,length:Long,onProgress:(Long)->Unit):String {
        // FileSliceRequestBody prevents loading a large encrypted object or part into memory.
        val body=FileSliceRequestBody(file,offset,length,onProgress)
        val builder=Request.Builder().url(ticket.url).put(body)
        ticket.headers.forEach(builder::header)
        val response=client.newCall(builder.build()).await()
        response.use {
            if(!it.isSuccessful) throw IOException("Cloud upload failed with HTTP ${it.code}")
            return it.header("ETag")?.trim()?.trim('"') ?: if(ticket.partNumber==1) "single" else error("Missing upload ETag")
        }
    }

    // Separates upload errors that can be retried from errors that need user action.
    private fun permanent(job:MediaUploadJob,message:String):Result {
        // A permanent result requires explicit user/app intervention rather than automatic backoff.
        store.write(job.copy(status=MediaTransferStatus.FAILED,error=message.take(240)))
        return Result.failure(workDataOf(ERROR to message.take(240)))
    }
    // Saves the upload error and asks WorkManager to retry after a delay.
    private fun retry(job:MediaUploadJob,error:Exception):Result {
        // WorkManager keeps the same unique work chain and applies the configured exponential delay.
        val message=when(error) {
            is SocketTimeoutException -> "Encrypted upload timed out. Retrying automatically."
            else -> error.localizedMessage?.take(240) ?: "Encrypted upload interrupted. Retrying automatically."
        }
        store.write(job.copy(status=MediaTransferStatus.QUEUED,error=message))
        return Result.retry()
    }

    companion object {
        private const val JOB_ID = "jobId"
        const val MEDIA_ID = "mediaId"
        const val ERROR = "error"
        const val BYTES_SENT = "bytesSent"
        const val TOTAL_BYTES = "totalBytes"
        /** Stable unique-work name shared with the controller's observer. */
        fun workName(jobId: String): String {
            return "olive_media_$jobId"
        }

        /**
         * Schedules one network-constrained worker per job. KEEP prevents repeated UI taps from
         * creating concurrent writers for the same multipart session.
         */
        fun enqueue(context:Context,jobId:String,replaceDelayedWork:Boolean=false) {
            val request=OneTimeWorkRequestBuilder<MediaUploadWorker>().setInputData(workDataOf(JOB_ID to jobId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
            val policy=if(replaceDelayedWork) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context).enqueueUniqueWork(workName(jobId),policy,request)
        }
    }
}

/**
 * Large ciphertext PUTs need a longer write window than OkHttp's short interactive-request
 * default. The total call remains below the server's ten-minute signed-ticket lifetime so a stalled
 * request fails before its capability expires and WorkManager can obtain a fresh ticket.
 */
internal fun mediaUploadHttpClient():OkHttpClient=OkHttpClient.Builder()
    .retryOnConnectionFailure(true)
    .connectTimeout(30,TimeUnit.SECONDS)
    .readTimeout(1,TimeUnit.MINUTES)
    .writeTimeout(5,TimeUnit.MINUTES)
    .callTimeout(9,TimeUnit.MINUTES)
    .build()

/** Streams a bounded region of the encrypted staging file into one OkHttp request body. */
private class FileSliceRequestBody(
    private val file:File,private val offset:Long,private val length:Long,
    private val onProgress:(Long)->Unit,
):RequestBody() {
    // Reads the encrypted upload response content type.
    override fun contentType()="application/octet-stream".toMediaType()
    // Reads the encrypted upload response size.
    override fun contentLength()=length
    // Streams encrypted upload bytes to the remote request body.
    override fun writeTo(sink:BufferedSink) {
        file.inputStream().use { input ->
            // The file is ciphertext, so seeking/re-reading a part cannot expose plaintext.
            var skipped=0L
            while(skipped<offset) {
                val count=input.skip(offset-skipped)
                if(count<=0) throw IOException("Unable to seek encrypted upload")
                skipped+=count
            }
            val buffer = ByteArray(64 * 1024)
            var remaining = length
            var sent = 0L
            // Turn an unexpected write to failure into a readable message.
            try {
                // Media: Keeps write to active while remaining>0.
                while(remaining>0) {
                    val count=input.read(buffer,0,minOf(buffer.size.toLong(),remaining).toInt())
                    if(count<0) throw IOException("Encrypted upload ended early")
                    sink.write(buffer,0,count);remaining-=count;sent+=count
                    onProgress(sent)
                }
            } finally { buffer.fill(0) }
        }
    }
}

/** Bridges OkHttp's callback API to structured coroutine cancellation. */
private suspend fun Call.await():Response=suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object:Callback {
        // Turns an R2 upload failure into a clear media-upload result.
        override fun onFailure(call:Call,e:IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
        // Reads the R2 upload response and confirms that the encrypted file was accepted.
        override fun onResponse(call:Call,response:Response) { if(continuation.isActive) continuation.resume(response) else response.close() }
    })
}

// Users and authorized viewers: requests encrypted media upload and download access.

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
    /** Atomically replaces recipients plus their account/device-wrapped AES keys. */
    suspend fun updateAccess(
        mediaId:String,
        wrappedKeys:Map<String,String>,
        deviceWrappedKeys:Map<String,String>,
    ):CloudMediaRecord
    /** Adds AES-key copies encrypted for the signed-in account's other registered devices. */
    suspend fun updateDeviceKeys(mediaId:String,deviceWrappedKeys:Map<String,String>):CloudMediaRecord
    /** Changes the owner's evidence label without changing or decrypting file contents. */
    suspend fun updateEvidence(mediaId:String,evidence:Boolean)
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
    // Requests short-lived upload URLs for one encrypted file.
    override suspend fun createUpload(job:MediaUploadJob):MediaUploadSession {
        // Only ciphertext facts and already-encrypted metadata cross the Firebase boundary.
        val source=job.source
        val result=call("mediaCreateUpload",mapOf(
            "jobId" to job.id,"ciphertextSize" to source.ciphertextSize,
            "ciphertextSha256" to source.ciphertextSha256,"encryptedMetadata" to source.encryptedMetadata,
            "wrappedKeys" to source.wrappedKeys,"allowedUsers" to source.allowedUsers,
            "deviceWrappedKeys" to source.deviceWrappedKeys,
            "encryptionVersion" to source.encryptionVersion,
        ))
        return MediaUploadSession(job.id,result.string("mediaId"),result.string("objectKey"),
            MediaUploadMode.valueOf(result.string("mode")),result.optionalString("uploadId"),result.long("partSize"))
    }

    // Requests a protected upload ticket for an encrypted media file.
    override suspend fun uploadTicket(jobId:String,partNumber:Int):MediaUploadTicket {
        require(partNumber>0)
        val result=call("mediaUploadPartTicket",mapOf("jobId" to jobId,"partNumber" to partNumber))
        return MediaUploadTicket(validateUrl(result.string("url")),result.stringMap("headers"),
            result.long("expiresAt"),partNumber)
    }

    // Saves a pending media upload so WorkManager can resume it later.
    override suspend fun completeUpload(jobId:String,parts:List<MediaCompletedPart>):CloudMediaRecord {
        val result=call("mediaCompleteUpload",mapOf("jobId" to jobId,"parts" to parts.map {
            mapOf("partNumber" to it.partNumber,"etag" to it.etag)
        }))
        return result.record()
    }

    // Deletes a completed or cancelled upload from the local queue.
    override suspend fun abortUpload(jobId:String) { call("mediaAbortUpload",mapOf("jobId" to jobId)) }

    // Requests a protected download ticket for an encrypted media file.
    override suspend fun downloadTicket(mediaId:String):MediaDownloadTicket {
        try {
            val result=call("mediaDownloadTicket",mapOf("mediaId" to mediaId))
            return MediaDownloadTicket(mediaId,validateUrl(result.string("url")),result.stringMap("headers"),result.long("expiresAt"))
        } catch (failure: FirebaseFunctionsException) {
            if (failure.code in setOf(
                    FirebaseFunctionsException.Code.PERMISSION_DENIED,
                    FirebaseFunctionsException.Code.NOT_FOUND,
                )) {
                throw MediaAccessException(MediaAccessError.ACCESS_REVOKED, failure)
            }
            throw failure
        }
    }

    // Saves the latest encrypted-media access keys for this account.
    override suspend fun updateAccess(
        mediaId:String,
        wrappedKeys:Map<String,String>,
        deviceWrappedKeys:Map<String,String>,
    ):CloudMediaRecord {
        require(wrappedKeys.isNotEmpty() && wrappedKeys.size<=25)
        require(deviceWrappedKeys.size<=250)
        return call("mediaUpdateAccess",mapOf(
            "mediaId" to mediaId,
            "wrappedKeys" to wrappedKeys,
            "deviceWrappedKeys" to deviceWrappedKeys,
        )).record()
    }

    // Adds the wrapped file keys needed by newly authorized devices.
    override suspend fun updateDeviceKeys(mediaId:String,deviceWrappedKeys:Map<String,String>):CloudMediaRecord {
        require(deviceWrappedKeys.isNotEmpty() && deviceWrappedKeys.size<=50)
        return call("mediaUpdateDeviceKeys",mapOf("mediaId" to mediaId,
            "deviceWrappedKeys" to deviceWrappedKeys)).record()
    }

    // Updates the evidence label through the owner-only media function.
    override suspend fun updateEvidence(mediaId:String,evidence:Boolean) {
        call("mediaUpdateEvidence", mapOf("mediaId" to mediaId, "evidence" to evidence))
    }

    // Removes media for media.
    override suspend fun deleteMedia(mediaId:String) { call("mediaDelete",mapOf("mediaId" to mediaId)) }

    // Calls the protected media function and checks its response.
    private suspend fun call(name:String,data:Map<String,Any>):Map<*,*> {
        // WorkManager can start before the SDK has restored its cached tokens. Waiting here makes
        // sure the callable receives both the signed-in account and the required App Check proof.
        val user=auth.currentUser ?: error("Sign in again before uploading files")
        user.getIdToken(false).await()
        // Ask Firebase for a fresh proof before protected media work. This is important after a
        // debug device is approved because the SDK may still hold the rejection from its first try.
        try {
            appCheck.getAppCheckToken(true).await()
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

    // Accepts only secure signed R2 links without embedded usernames or passwords.
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
// Keeps only valid text fields from downloaded encrypted metadata.
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

/** Deletes expired, unpinned upload-reuse ciphertext even if no document screen is opened. */
class OfflineMediaCleanupWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = runCatching {
        OfflineMediaCache(applicationContext).cleanupExpiredCandidates()
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        fun enqueue(context: Context, candidateId: String) {
            val request = OneTimeWorkRequestBuilder<OfflineMediaCleanupWorker>()
                .setInitialDelay(OfflineMediaCache.UPLOAD_REUSE_WINDOW_MS, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "offline_media_cleanup_$candidateId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
