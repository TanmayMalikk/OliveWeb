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
