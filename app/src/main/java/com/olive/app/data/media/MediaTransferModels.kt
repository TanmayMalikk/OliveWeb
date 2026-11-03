// Users and authorized viewers: defines encrypted media transfer states and metadata.
package com.olive.app.data.media

import org.json.JSONArray
import org.json.JSONObject

/** Domain classification used to select preprocessing rules such as OCR and compression. */
enum class MediaKind { DOCUMENT, IMAGE, VIDEO, AUDIO, OTHER }

/**
 * Result of the advisory document-quality/OCR stage.
 *
 * OCR never decides whether bytes may be uploaded. [RETAKE_RECOMMENDED] pauses preparation so
 * the UI can recommend another capture; if the user declines, the caller retries with
 * [RETAKE_DECLINED]. This preserves user choice when the on-device model is unavailable or unsure.
 */
enum class OcrReview { NOT_APPLICABLE, NOT_RUN, PASSED, RETAKE_RECOMMENDED, RETAKE_DECLINED }

/** Transfer strategy selected by the trusted Firebase control plane from ciphertext size. */
enum class MediaUploadMode { SINGLE, MULTIPART }

/** Durable upload state persisted by [MediaTransferStore] and advanced by [MediaUploadWorker]. */
enum class MediaTransferStatus { QUEUED, CREATING_SESSION, UPLOADING, FINALIZING, COMPLETE, FAILED, CANCELLED }

/**
 * Immutable hand-off from device-side preparation to the background uploader.
 *
 * Relationship summary for architecture tooling:
 * - created by `MediaTransferController` after preprocessing and encryption;
 * - embedded in [MediaUploadJob];
 * - read by [MediaUploadWorker];
 * - described to Firebase by [MediaRepository], while [filePath] itself stays on-device.
 *
 * @property filePath app-private path containing ciphertext in the [com.olive.app.security.MediaCipher] container format.
 * @property ciphertextSize exact encrypted byte count used for upload validation and part planning.
 * @property ciphertextSha256 integrity checksum of the complete encrypted object.
 * @property encryptedMetadata AES-GCM encrypted JSON containing display name, MIME type, kind, and OCR result.
 * @property wrappedKeys per-user copies of the media AES key, wrapped with each allowed user's public key.
 * @property allowedUsers UIDs authorized to request a download ticket; must match [wrappedKeys] exactly.
 * @property encryptionVersion format/key-contract version stored with the remote metadata.
 * @property accountAccessKey callable-only recovery copy that lets the signed-in account open the
 * same encrypted R2 object on another phone or browser without device approval.
 */
data class PreparedEncryptedMedia(
    val filePath:String,
    val ciphertextSize:Long,
    val ciphertextSha256:String,
    val encryptedMetadata:String,
    val wrappedKeys:Map<String,String>,
    val allowedUsers:List<String>,
    val encryptionVersion:Int=1,
    val kind:MediaKind,
    val ocrReview:OcrReview,
    val deviceWrappedKeys:Map<String,String> = emptyMap(),
    val accountAccessKey:String = "",
)

/**
 * Complete, restartable state machine for one encrypted upload.
 *
 * The job is written before WorkManager starts. For multipart transfers, [completedParts] is updated
 * after every successful PUT, allowing a later worker instance or process to skip confirmed parts.
 * A single-part upload deliberately restarts because the extra multipart control-plane requests cost
 * more than retransmitting a small object in the common case.
 *
 * @property id client-generated idempotency key shared by WorkManager and the Firebase upload session.
 * @property source prepared ciphertext and zero-knowledge metadata.
 * @property mediaId server-generated stable identifier for the final `files` document and R2 object.
 * @property objectKey opaque R2 key returned by Firebase; the client never constructs or changes it.
 * @property uploadId R2 multipart session identifier; blank for single PUT uploads.
 * @property completedParts map of one-based part numbers to R2 ETags used during finalization/resume.
 */
data class MediaUploadJob(
    val id:String,
    val ownerUid:String,
    val source:PreparedEncryptedMedia,
    val status:MediaTransferStatus=MediaTransferStatus.QUEUED,
    val mode:MediaUploadMode?=null,
    val mediaId:String="",
    val objectKey:String="",
    val uploadId:String="",
    val partSize:Long=0,
    val completedParts:Map<Int,String> = emptyMap(),
    val error:String?=null,
    val createdAt:Long=System.currentTimeMillis(),
) {
    /** Serializes the entire resumable state for storage inside the Keystore-backed vault. */
    fun toJson()=JSONObject().apply {
        put("id",id);put("ownerUid",ownerUid);put("filePath",source.filePath)
        put("ciphertextSize",source.ciphertextSize);put("ciphertextSha256",source.ciphertextSha256)
        put("encryptedMetadata",source.encryptedMetadata);put("wrappedKeys",JSONObject(source.wrappedKeys))
        put("allowedUsers",JSONArray(source.allowedUsers));put("encryptionVersion",source.encryptionVersion)
        put("deviceWrappedKeys",JSONObject(source.deviceWrappedKeys))
        put("accountAccessKey",source.accountAccessKey)
        put("kind",source.kind.name);put("ocrReview",source.ocrReview.name);put("status",status.name)
        mode?.let { put("mode",it.name) };put("mediaId",mediaId);put("objectKey",objectKey)
        put("uploadId",uploadId);put("partSize",partSize);put("createdAt",createdAt)
        put("completedParts",JSONObject(completedParts.mapKeys { it.key.toString() }))
        error?.let { put("error",it) }
    }

    companion object {
        /** Rehydrates a job after process death, app restart, or WorkManager rescheduling. */
        fun fromJson(value:JSONObject):MediaUploadJob {
            val wrapped=value.getJSONObject("wrappedKeys").let { json ->
                json.keys().asSequence().associateWith(json::getString)
            }
            val allowed=value.getJSONArray("allowedUsers").let { array ->
                (0 until array.length()).map(array::getString)
            }
            val deviceWrapped=value.optJSONObject("deviceWrappedKeys")?.let { json ->
                json.keys().asSequence().associateWith(json::getString)
            }.orEmpty()
            val parts=value.optJSONObject("completedParts")?.let { json ->
                json.keys().asSequence().associate { it.toInt() to json.getString(it) }
            }.orEmpty()
            val source=PreparedEncryptedMedia(value.getString("filePath"),value.getLong("ciphertextSize"),
                value.getString("ciphertextSha256"),value.getString("encryptedMetadata"),wrapped,allowed,
                value.optInt("encryptionVersion",1),MediaKind.valueOf(value.getString("kind")),
                OcrReview.valueOf(value.optString("ocrReview",OcrReview.NOT_RUN.name)),deviceWrapped,
                value.optString("accountAccessKey"))
            return MediaUploadJob(value.getString("id"),value.getString("ownerUid"),source,
                MediaTransferStatus.valueOf(value.getString("status")),value.optString("mode").takeIf(String::isNotBlank)?.let(MediaUploadMode::valueOf),
                value.optString("mediaId"),value.optString("objectKey"),value.optString("uploadId"),
                value.optLong("partSize"),parts,value.optString("error").takeIf(String::isNotBlank),value.getLong("createdAt"))
        }
    }
}

/** Server-approved upload session and transfer strategy returned by `mediaCreateUpload`. */
data class MediaUploadSession(
    val jobId:String,val mediaId:String,val objectKey:String,val mode:MediaUploadMode,
    val uploadId:String,val partSize:Long,
)
/** Short-lived capability for one direct ciphertext PUT to Cloudflare R2. */
data class MediaUploadTicket(val url:String,val headers:Map<String,String>,val expiresAt:Long,val partNumber:Int)

/** Persisted proof that R2 accepted a multipart chunk. */
data class MediaCompletedPart(val partNumber:Int,val etag:String)

/** Short-lived capability for one authorized direct ciphertext GET from Cloudflare R2. */
data class MediaDownloadTicket(val mediaId:String,val url:String,val headers:Map<String,String>,val expiresAt:Long)

/** Zero-knowledge metadata returned after Firebase verifies and publishes an uploaded R2 object. */
data class CloudMediaRecord(
    val id:String,val ownerUid:String,val objectKey:String,val encryptedMetadata:String,
    val wrappedKeys:Map<String,String>,val allowedUsers:List<String>,val ciphertextSize:Long,
    val ciphertextSha256:String,val encryptionVersion:Int,val createdAt:Long,
    val deviceWrappedKeys:Map<String,String> = emptyMap(),
)

/**
 * UI-facing projection of preparation and WorkManager state.
 *
 * This is intentionally separate from [MediaTransferStatus]: the durable enum records recovery
 * state, while this sealed hierarchy carries progress and user-facing failure information.
 */
sealed interface MediaTransferState {
    data object Idle:MediaTransferState
    data class Preparing(val stage:String,val progress:Float?=null):MediaTransferState
    data class Queued(val jobId:String):MediaTransferState
    data class Uploading(val jobId:String,val bytesTransferred:Long,val totalBytes:Long):MediaTransferState
    data class Completed(val jobId:String,val mediaId:String):MediaTransferState
    data class Failed(val jobId:String?,val message:String,val retryable:Boolean):MediaTransferState
}

/**
 * Decrypted, device-local projection of one durable upload for presentation in Compose.
 *
 * The persisted [MediaUploadJob] remains the source of truth. This projection deliberately contains
 * no AES/RSA key material and is never sent to Firebase, WorkManager input data, or Cloudflare. Its
 * display name and MIME type are recovered on-device from `encryptedMetadata`, which lets an upload
 * remain understandable after process death without persisting plaintext metadata separately.
 */
data class MediaUploadPresentation(
    val jobId:String,
    val ownerUid:String,
    val name:String,
    val mimeType:String,
    val status:MediaTransferStatus,
    val bytesTransferred:Long,
    val totalBytes:Long,
    val mediaId:String,
    val error:String?,
    val createdAt:Long,
) {
    /** A bounded fraction suitable for a determinate progress indicator. */
    val progress:Float? get()=totalBytes.takeIf { it>0 }?.let {
        (bytesTransferred.toDouble()/it.toDouble()).coerceIn(0.0,1.0).toFloat()
    }
}
