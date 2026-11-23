// Safe-app users: resumes encrypted uploads in the background.
package com.olive.app.data.media

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctionsException
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.messaging.MessageOutboxStore
import com.olive.app.data.messaging.MessageSendWorker
import com.olive.app.data.messaging.OutgoingAttachment
import com.olive.app.data.messaging.OutgoingMessage
import com.olive.app.data.messaging.prepareOutgoingMessage
import com.olive.app.data.network.optionalString
import com.olive.app.data.network.requireLong
import com.olive.app.data.network.requireString
import com.olive.app.data.network.requireStringList
import com.olive.app.data.network.stringMap
import com.olive.app.data.safety.SafetyPlanSyncWorker
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
import com.olive.app.security.AccountKeysetWrappedKey
import com.olive.app.security.KeyManager
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

    /**
     * Uploads one encrypted file in the background and saves its cloud record.
     *
     * @return WorkManager [Result] indicating success, retry, or failure.
     */
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
            job.voiceDraft?.let { draft ->
                val voice = prepareOutgoingMessage(
                    draft.conversationId,
                    AccountIdentity(job.ownerUid, draft.senderUsername),
                    "SHARED_VOICE:${job.mediaId}" + draft.metadata.takeIf(String::isNotEmpty)?.let { ":$it" }.orEmpty(),
                    KeyManager.unwrapKey(draft.wrappedRoomKey, KeyManager.CURRENT_KEY_WRAP_VERSION),
                    id = job.id,
                    now = job.createdAt,
                ).copy(attachment = OutgoingAttachment(job.mediaId))
                job = store.update(job.id) { it.copy(voiceDraft = null, voiceMessage = voice) }
            }
            val parts=upload(file,job)

            // Finalization tells R2 to assemble multipart chunks, verifies the remote byte count,
            // and atomically publishes the encrypted Firestore record.
            store.update(job.id) { it.copy(status=MediaTransferStatus.FINALIZING,error=null) }
            val record=repository.completeUpload(job.id,parts)
            if (job.attachToSafetyPlan) {
                SafetyPlanSyncWorker.enqueueAttachment(applicationContext,job.ownerUid,record.id)
            }
            job.voiceMessage?.let { voice ->
                val queued = MessageOutboxStore(applicationContext).write(voice)
                MessageSendWorker.enqueue(applicationContext, queued)
            }
            store.update(job.id) {
                it.copy(status=MediaTransferStatus.COMPLETE,mediaId=record.id,error=null,voiceMessage=null)
            }
            // Preserve a bounded reuse window so an owner can pin the already-uploaded ciphertext
            // without downloading it from R2. Cleanup remains durable if the UI never opens.
            runCatching {
                offlineCache.registerUploadCandidate(job.ownerUid,record.id,file,
                    job.source.ciphertextSize,job.source.ciphertextSha256)
                OfflineMediaCleanupWorker.enqueue(applicationContext,record.id)
            }.onFailure { file.delete() }
            Result.success(workDataOf(MEDIA_ID to record.id))
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(error:MediaDeviceVerificationRetryableException) {
            Log.w("OliveMediaUpload", "App Check temporarily unavailable for encrypted upload ${job.id}", error)
            retry(job,error)
        }
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

    /**
     * Media: Uploads the selected file.
     *
     * @param file Local file to read, hash, process, or write.
     * @param initial Initial supplied to [upload].
     * @return List of resulting upload items.
     */
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

    /**
     * Uploads one encrypted file section and reports its byte progress.
     *
     * @param ticket Ticket supplied to [put].
     * @param file Local file to read, hash, process, or write.
     * @param offset Offset supplied to [put].
     * @param length Length supplied to [put].
     * @param onProgress Callback invoked when progress changes or occurs.
     * @return The resulting put string.
     */
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

    /**
     * Separates upload errors that can be retried from errors that need user action.
     *
     * @param job Job supplied to [permanent].
     * @param message Chat message or remote payload.
     * @return The resulting [Result] instance.
     */
    private fun permanent(job:MediaUploadJob,message:String):Result {
        // A permanent result requires explicit user/app intervention rather than automatic backoff.
        store.update(job.id) { it.copy(status=MediaTransferStatus.FAILED,error=message.take(240)) }
        return Result.failure(workDataOf(ERROR to message.take(240)))
    }
    /**
     * Saves the upload error and asks WorkManager to retry after a delay.
     *
     * @param job Job supplied to [retry].
     * @param error Throwable or error message describing a failure.
     * @return The resulting [Result] instance.
     */
    private fun retry(job:MediaUploadJob,error:Exception):Result {
        // WorkManager keeps the same unique work chain and applies the configured exponential delay.
        val message=when(error) {
            is SocketTimeoutException -> "Encrypted upload timed out. Retrying automatically."
            else -> error.localizedMessage?.take(240) ?: "Encrypted upload interrupted. Retrying automatically."
        }
        store.update(job.id) { it.copy(status=MediaTransferStatus.QUEUED,error=message) }
        return Result.retry()
    }

    /** Provides the foreground notification used when WorkManager promotes an encrypted upload. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val channelId = "olive_upload_channel"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(android.app.NotificationManager::class.java)
            if (manager?.getNotificationChannel(channelId) == null) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    "Media Upload",
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    setShowBadge(false)
                }
                manager?.createNotificationChannel(channel)
            }
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Uploading encrypted file")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        return ForegroundInfo(7001, notification)
    }

    companion object {
        private const val JOB_ID = "jobId"
        const val MEDIA_ID = "mediaId"
        const val ERROR = "error"
        const val BYTES_SENT = "bytesSent"
        const val TOTAL_BYTES = "totalBytes"
        /**
         * Stable unique-work name shared with the controller's observer.
         *
         * @param jobId Unique identifier of the durable background job.
         * @return The resulting work name string.
         */
        fun workName(jobId: String): String {
            return "olive_media_$jobId"
        }

        /**
         * Schedules one network-constrained worker per job. KEEP prevents repeated UI taps from
         * creating concurrent writers for the same multipart session.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @param jobId Unique identifier of the durable background job.
         * @param replaceDelayedWork Whether to apply replace delayed work.
         */
        fun enqueue(context:Context,jobId:String,replaceDelayedWork:Boolean=false) {
            val request=OneTimeWorkRequestBuilder<MediaUploadWorker>().setInputData(workDataOf(JOB_ID to jobId))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    10L,
                    TimeUnit.SECONDS,
                ).build()
            val policy=if(replaceDelayedWork) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context).enqueueUniqueWork(workName(jobId),policy,request)
        }
    }
}

/**
 * Large ciphertext PUTs need a longer write window than OkHttp's short interactive-request
 * default. The total call remains below the server's ten-minute signed-ticket lifetime so a stalled
 * request fails before its capability expires and WorkManager can obtain a fresh ticket.
 *
 * @return The resulting [OkHttpClient] for media upload HTTP client.
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
    /**
     * Reads the encrypted upload response content type.
     *
     * @return The resulting content type.
     */
    override fun contentType()="application/octet-stream".toMediaType()
    /**
     * Reads the encrypted upload response size.
     *
     * @return The resulting content length.
     */
    override fun contentLength()=length
    /**
     * Streams encrypted upload bytes to the remote request body.
     *
     * @param sink Sink supplied to [writeTo].
     */
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

/**
 * Bridges OkHttp's callback API to structured coroutine cancellation.
 *
 * @return The resulting [Response] instance.
 */
private suspend fun Call.await():Response=suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object:Callback {
        /**
         * Turns an R2 upload failure into a clear media-upload result.
         *
         * @param call Call supplied to [onFailure].
         * @param e E supplied to [onFailure].
         */
        override fun onFailure(call:Call,e:IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
        /**
         * Reads the R2 upload response and confirms that the encrypted file was accepted.
         *
         * @param call Call supplied to [onResponse].
         * @param response Response supplied to [onResponse].
         */
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
    /**
     * Creates or recovers an idempotent server upload session for [job].
     *
     * @param job Job supplied to [createUpload].
     * @return The resulting [MediaUploadSession] for create upload.
     */
    suspend fun createUpload(job:MediaUploadJob):MediaUploadSession
    /**
     * Requests a short-lived PUT capability for exactly one single/multipart part.
     *
     * @param jobId Unique identifier of the durable background job.
     * @param partNumber Part number supplied to [uploadTicket].
     * @return The resulting [MediaUploadTicket] for upload ticket.
     */
    suspend fun uploadTicket(jobId:String,partNumber:Int):MediaUploadTicket
    /**
     * Commits accepted ETags and publishes immutable media metadata after R2 size verification.
     *
     * @param jobId Unique identifier of the durable background job.
     * @param parts Collection of parts values to process.
     * @return The resulting [CloudMediaRecord] for complete upload.
     */
    suspend fun completeUpload(jobId:String,parts:List<MediaCompletedPart>):CloudMediaRecord
    /**
     * Aborts a pending R2 multipart session and removes its pending Firestore state.
     *
     * @param jobId Unique identifier of the durable background job.
     */
    suspend fun abortUpload(jobId:String)
    /**
     * Returns a short-lived GET capability after the callable checks `allowed_users`.
     *
     * @param mediaId Unique identifier of the encrypted media object.
     * @return The resulting [MediaDownloadTicket] for download ticket.
     */
    suspend fun downloadTicket(mediaId:String):MediaDownloadTicket
    /**
     * Atomically replaces recipients plus their account/device-wrapped AES keys.
     *
     * @param mediaId Unique identifier of the encrypted media object.
     * @param wrappedKeys Key-value mapping of wrapped keys entries.
     * @param deviceWrappedKeys Key-value mapping of device wrapped keys entries.
     * @param accountWrappedKeys Key-value mapping of account keyset wrapped keys entries.
     * @param grantIndependentRecipients Collection of grant independent recipients values to process.
     * @param revokeIndependentRecipients Collection of revoke independent recipients values to process.
     * @return The resulting [CloudMediaRecord] for update access.
     */
    suspend fun updateAccess(
        mediaId:String,
        wrappedKeys:Map<String,String>,
        deviceWrappedKeys:Map<String,String>,
        accountWrappedKeys:Map<String,AccountKeysetWrappedKey>,
        grantIndependentRecipients:List<String> = emptyList(),
        revokeIndependentRecipients:List<String> = emptyList(),
    ):CloudMediaRecord
    /**
     * Updates plan access.
     *
     * @param mediaId Unique identifier of the encrypted media object.
     * @param wrappedKeys Key-value mapping of wrapped keys entries.
     * @param deviceWrappedKeys Key-value mapping of device wrapped keys entries.
     * @return The resulting [CloudMediaRecord] for update plan access.
     */
    suspend fun updatePlanAccess(
        mediaId: String,
        wrappedKeys: Map<String, String>,
        deviceWrappedKeys: Map<String, String>,
        accountWrappedKeys: Map<String, AccountKeysetWrappedKey>,
    ): CloudMediaRecord = error("Plan attachment sharing is unavailable")
    /**
     * Shares in chat.
     *
     * @param mediaId Unique identifier of the encrypted media object.
     * @param outgoing Outgoing supplied to [shareInChat].
     * @param recipientUids Collection of recipient uids values to process.
     * @param wrappedKeys Key-value mapping of wrapped keys entries.
     * @param deviceWrappedKeys Key-value mapping of device wrapped keys entries.
     * @param accountWrappedKeys Key-value mapping of account keyset wrapped keys entries.
     */
    suspend fun shareInChat(
        mediaId:String,
        outgoing:OutgoingMessage,
        recipientUids:List<String>,
        wrappedKeys:Map<String,String>,
        deviceWrappedKeys:Map<String,String>,
        accountWrappedKeys:Map<String,AccountKeysetWrappedKey>,
    ) { error("Chat attachment sharing is unavailable") }
    /**
     * Adds AES-key copies encrypted for the signed-in account's other registered devices.
     *
     * @param mediaId Unique identifier of the encrypted media object.
     * @param deviceWrappedKeys Key-value mapping of device wrapped keys entries.
     * @return The resulting [CloudMediaRecord] for update device keys.
     */
    suspend fun updateDeviceKeys(mediaId:String,deviceWrappedKeys:Map<String,String>):CloudMediaRecord
    /**
     * Changes the owner's evidence label without changing or decrypting file contents.
     *
     * @param mediaId Unique identifier of the encrypted media object.
     * @param evidence Whether to apply evidence.
     */
    suspend fun updateEvidence(mediaId:String,evidence:Boolean)
    /**
     * Deletes both the owner's R2 object and its Firestore metadata through trusted code.
     *
     * @param mediaId Unique identifier of the encrypted media object.
     */
    suspend fun deleteMedia(mediaId:String)
}

/** The installed app could not obtain the App Check proof required by encrypted-media functions. */
class MediaDeviceVerificationException(message:String,cause:Exception):Exception(message,cause)
class MediaDeviceVerificationRetryableException(message:String,cause:Exception):Exception(message,cause)

/**
 * Checks whether retryable app check failure.
 *
 * @param failure Exception or failure reason encountered during execution.
 * @return `true` if is retryable app check failure, or `false` otherwise.
 */
internal fun isRetryableAppCheckFailure(failure: Throwable): Boolean {
    val visited = mutableSetOf<Throwable>()
    var current: Throwable? = failure
    while (current != null && visited.add(current)) {
        if (current is IOException || current is SocketTimeoutException ||
            current.message?.contains("too many attempts", ignoreCase = true) == true
        ) return true
        current = current.cause
    }
    return false
}

/** Builds the zero-knowledge upload manifest without unwrapping or exporting the media AES key. */
internal fun mediaCreateUploadRequest(job:MediaUploadJob):Map<String,Any> = job.source.let { source ->
    require(source.allowedUsers.toSet()==source.accountWrappedKeys.keys) {
        "Every authorized account requires an account-keyset wrapper"
    }
    mapOf(
        "jobId" to job.id,"ciphertextSize" to source.ciphertextSize,
        "ciphertextSha256" to source.ciphertextSha256,"encryptedMetadata" to source.encryptedMetadata,
        "wrappedKeys" to source.wrappedKeys,"allowedUsers" to source.allowedUsers,
        "deviceWrappedKeys" to source.deviceWrappedKeys,
        "accountWrappedKeys" to source.accountWrappedKeys.mapValues { it.value.toMap() },
        "encryptionVersion" to source.encryptionVersion,
    )
}

/**
 * Firebase Functions implementation of [MediaTicketDataSource].
 *
 * Relationship summary for diagram generation:
 * - called by [MediaUploadWorker] for upload session/ticket/finalization operations;
 * - called by `MediaTransferController` for download tickets;
 * - delegates authorization and R2 signing to `functions/src/mediaTickets.js`;
 * - never accepts plaintext file content or raw AES keys; only ciphertext facts and opaque wrappers cross it.
 *
 * Every returned URL is locally constrained to HTTPS on Cloudflare's R2 hostname before OkHttp sees
 * it. This limits the impact of a malformed or compromised callable response.
 */
class MediaRepository(
    private val functions:FirebaseFunctions=FirebaseFunctions.getInstance("northamerica-northeast1"),
    private val auth:FirebaseAuth=FirebaseAuth.getInstance(),
    private val appCheck:FirebaseAppCheck=FirebaseAppCheck.getInstance(),
):MediaTicketDataSource {
    /**
     * Requests short-lived upload URLs for one encrypted file.
     *
     * @param job Job supplied to [createUpload].
     * @return The resulting [MediaUploadSession] for create upload.
     */
    override suspend fun createUpload(job:MediaUploadJob):MediaUploadSession {
        val source=job.source
        val wrapped=source.wrappedKeys.getValue(job.ownerUid)
        val key=KeyManager.unwrapKey(wrapped,source.encryptionVersion)
        val metadata=org.json.JSONObject(com.olive.app.security.EncryptionUtils.decryptText(source.encryptedMetadata,key))
        val emergencyId=Regex("^emergency-report-([0-9a-f-]{36})\\.pdf$")
            .matchEntire(metadata.optString("name"))?.groupValues?.get(1)
        if (emergencyId != null) call(
            "emergencyAttachUpload", mapOf("id" to emergencyId, "jobId" to job.id)
        )
        val result=call("mediaCreateUpload",mediaCreateUploadRequest(job))
        return MediaUploadSession(job.id,result.requireString("mediaId"),result.requireString("objectKey"),
            MediaUploadMode.valueOf(result.requireString("mode")),result.optionalString("uploadId"),result.requireLong("partSize"))
    }

    /**
     * Requests a protected upload ticket for an encrypted media file.
     *
     * @param jobId Unique identifier of the durable background job.
     * @param partNumber Part number supplied to [uploadTicket].
     * @return The resulting [MediaUploadTicket] for upload ticket.
     */
    override suspend fun uploadTicket(jobId:String,partNumber:Int):MediaUploadTicket {
        require(partNumber>0)
        val result=call("mediaUploadPartTicket",mapOf("jobId" to jobId,"partNumber" to partNumber))
        return MediaUploadTicket(validateUrl(result.requireString("url")),result.stringMap("headers"),
            result.requireLong("expiresAt"),partNumber)
    }

    /**
     * Saves a pending media upload so WorkManager can resume it later.
     *
     * @param jobId Unique identifier of the durable background job.
     * @param parts Collection of parts values to process.
     * @return The resulting [CloudMediaRecord] for complete upload.
     */
    override suspend fun completeUpload(jobId:String,parts:List<MediaCompletedPart>):CloudMediaRecord {
        val result=call("mediaCompleteUpload",mapOf("jobId" to jobId,"parts" to parts.map {
            mapOf("partNumber" to it.partNumber,"etag" to it.etag)
        }))
        return result.record()
    }

    /**
     * Deletes a completed or cancelled upload from the local queue.
     *
     * @param jobId Unique identifier of the durable background job.
     */
    override suspend fun abortUpload(jobId:String) { call("mediaAbortUpload",mapOf("jobId" to jobId)) }

    /**
     * Requests a protected download ticket for an encrypted media file.
     *
     * @param mediaId Unique identifier of the encrypted media object.
     * @return The resulting [MediaDownloadTicket] for download ticket.
     */
    override suspend fun downloadTicket(mediaId:String):MediaDownloadTicket {
        try {
            val result=call("mediaDownloadTicket",mapOf("mediaId" to mediaId))
            return MediaDownloadTicket(mediaId,validateUrl(result.requireString("url")),result.stringMap("headers"),result.requireLong("expiresAt"))
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

    /**
     * Saves the latest encrypted-media access keys for this account.
     *
     * @param mediaId Unique identifier of the encrypted media object.
     * @param wrappedKeys Key-value mapping of wrapped keys entries.
     * @param deviceWrappedKeys Key-value mapping of device wrapped keys entries.
     * @param grantIndependentRecipients Collection of grant independent recipients values to process.
     * @param revokeIndependentRecipients Collection of revoke independent recipients values to process.
     * @return The resulting [CloudMediaRecord] for update access.
     */
    override suspend fun updateAccess(
        mediaId:String,
        wrappedKeys:Map<String,String>,
        deviceWrappedKeys:Map<String,String>,
        accountWrappedKeys:Map<String,AccountKeysetWrappedKey>,
        grantIndependentRecipients:List<String>,
        revokeIndependentRecipients:List<String>,
    ):CloudMediaRecord {
        require(wrappedKeys.isNotEmpty() && wrappedKeys.size<=25)
        require(deviceWrappedKeys.size<=250)
        require(wrappedKeys.keys.containsAll(accountWrappedKeys.keys))
        return call("mediaUpdateAccess",mapOf(
            "mediaId" to mediaId,
            "wrappedKeys" to wrappedKeys,
            "deviceWrappedKeys" to deviceWrappedKeys,
            "accountWrappedKeys" to accountWrappedKeys.mapValues { it.value.toMap() },
            "grantIndependentRecipients" to grantIndependentRecipients,
            "revokeIndependentRecipients" to revokeIndependentRecipients,
        )).record()
    }

    /**
     * Updates plan access.
     *
     * @param mediaId Unique identifier of the encrypted media object.
     * @param wrappedKeys Key-value mapping of wrapped keys entries.
     * @param deviceWrappedKeys Key-value mapping of device wrapped keys entries.
     * @return The resulting [CloudMediaRecord] for update plan access.
     */
    override suspend fun updatePlanAccess(
        mediaId: String,
        wrappedKeys: Map<String, String>,
        deviceWrappedKeys: Map<String, String>,
        accountWrappedKeys: Map<String, AccountKeysetWrappedKey>,
    ): CloudMediaRecord = call("mediaUpdateAccess", mapOf(
        "mediaId" to mediaId,
        "wrappedKeys" to wrappedKeys,
        "deviceWrappedKeys" to deviceWrappedKeys,
        "accountWrappedKeys" to accountWrappedKeys.mapValues { it.value.toMap() },
        "forPlan" to true,
    )).record()

    /**
     * Shares in chat.
     *
     * @param mediaId Unique identifier of the encrypted media object.
     * @param outgoing Outgoing supplied to [shareInChat].
     * @param recipientUids Collection of recipient uids values to process.
     * @param wrappedKeys Key-value mapping of wrapped keys entries.
     * @param deviceWrappedKeys Key-value mapping of device wrapped keys entries.
     */
    override suspend fun shareInChat(
        mediaId:String,
        outgoing:OutgoingMessage,
        recipientUids:List<String>,
        wrappedKeys:Map<String,String>,
        deviceWrappedKeys:Map<String,String>,
        accountWrappedKeys:Map<String,AccountKeysetWrappedKey>,
    ) {
        call("mediaShareInChat",mapOf(
            "mediaId" to mediaId,
            "conversationId" to outgoing.conversationId,
            "messageId" to outgoing.id,
            "ciphertext" to outgoing.ciphertext,
            "clientSentAt" to outgoing.clientSentAt,
            "recipientUids" to recipientUids,
            "wrappedKeys" to wrappedKeys,
            "deviceWrappedKeys" to deviceWrappedKeys,
            "accountWrappedKeys" to accountWrappedKeys.mapValues { it.value.toMap() },
        ))
    }

    /**
     * Adds the wrapped file keys needed by newly authorized devices.
     *
     * @param mediaId Unique identifier of the encrypted media object.
     * @param deviceWrappedKeys Key-value mapping of device wrapped keys entries.
     * @return The resulting [CloudMediaRecord] for update device keys.
     */
    override suspend fun updateDeviceKeys(mediaId:String,deviceWrappedKeys:Map<String,String>):CloudMediaRecord {
        require(deviceWrappedKeys.isNotEmpty() && deviceWrappedKeys.size<=50)
        return call("mediaUpdateDeviceKeys",mapOf("mediaId" to mediaId,
            "deviceWrappedKeys" to deviceWrappedKeys)).record()
    }

    /**
     * Updates the evidence label through the owner-only media function.
     *
     * @param mediaId Unique identifier of the encrypted media object.
     * @param evidence Whether to apply evidence.
     */
    override suspend fun updateEvidence(mediaId:String,evidence:Boolean) {
        call("mediaUpdateEvidence", mapOf("mediaId" to mediaId, "evidence" to evidence))
    }

    /**
     * Removes media for media.
     *
     * @param mediaId Unique identifier of the encrypted media object.
     */
    override suspend fun deleteMedia(mediaId:String) { call("mediaDelete",mapOf("mediaId" to mediaId)) }

    /**
     * Calls the protected media function and checks its response.
     *
     * @param name Field, key, or display name.
     * @param data Key-value mapping of data entries.
     * @return Map containing the resulting call entries.
     */
    private suspend fun call(name:String,data:Map<String,Any>):Map<*,*> {
        // WorkManager can start before the SDK has restored its cached tokens. Waiting here makes
        // sure the callable receives both the signed-in account and the required App Check proof.
        val user=auth.currentUser ?: error("Sign in again before uploading files")
        user.getIdToken(false).await()
        // Let the SDK reuse a valid proof across the calls that make up one transfer or list refresh.
        try {
            appCheck.getAppCheckToken(false).await()
        } catch(error:Exception) {
            if (isRetryableAppCheckFailure(error)) {
                throw MediaDeviceVerificationRetryableException(
                    "Device verification is temporarily unavailable. Retrying automatically.",
                    error,
                )
            }
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

    /**
     * Accepts only secure signed R2 links without embedded usernames or passwords.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting validate URL string.
     */
    private fun validateUrl(value:String):String {
        // Signed URLs are bearer capabilities. Reject credentials, plaintext HTTP, and foreign hosts.
        val uri=URI(value)
        require(uri.scheme.equals("https",true) && uri.userInfo==null && uri.host?.endsWith(".r2.cloudflarestorage.com")==true) {
            "Media ticket returned an untrusted URL"
        }
        return value
    }
}

/**
 * Encrypted media: Reads the account-keyset wrapped AES keys from a downloaded media record.
 */
private fun Map<*,*>.accountWrappedKeyMap(name:String):Map<String,AccountKeysetWrappedKey> =
    (get(name) as? Map<*,*>)?.entries?.associate { entry ->
        (entry.key as? String ?: error("Invalid $name key")) to
            AccountKeysetWrappedKey.fromMap(entry.value as? Map<*,*> ?: error("Invalid $name value"))
    }.orEmpty()

/**
 * Encrypted media: Rebuilds a cloud media record by reading each validated field from the downloaded map.
 *
 * @return The resulting record.
 */
private fun Map<*,*>.record()=CloudMediaRecord(requireString("mediaId"),requireString("ownerUid"),requireString("objectKey"),
    requireString("encryptedMetadata"),stringMap("wrappedKeys"),requireStringList("allowedUsers"),requireLong("ciphertextSize"),
    requireString("ciphertextSha256"),(get("encryptionVersion") as? Number)?.toInt() ?: 1,requireLong("createdAt"),
    stringMap("deviceWrappedKeys"),accountWrappedKeyMap("accountWrappedKeys"))

/** Deletes expired, unpinned upload-reuse ciphertext even if no document screen is opened. */
class OfflineMediaCleanupWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    /**
     * Executes the scheduled WorkManager background task and returns the resulting work status.
     *
     * @return WorkManager [Result] indicating success, retry, or failure.
     */
    override suspend fun doWork(): Result = runCatching {
        OfflineMediaCache(applicationContext).cleanupExpiredCandidates()
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        /**
         * Enqueues the offline media cleanup worker.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @param candidateId Candidate ID supplied to [enqueue].
         */
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
