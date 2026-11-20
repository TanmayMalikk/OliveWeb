// Users and authorized viewers: defines encrypted media transfer states and metadata.
package com.olive.app.data.media

import com.olive.app.data.messaging.OutgoingMessage
import com.olive.app.data.network.decodeJsonObject
import com.olive.app.data.network.encodeToJsonObject
import kotlinx.serialization.Serializable
import org.json.JSONObject

/** A requested send waiting for the upload service to assign the encrypted media ID. */
@Serializable
data class PendingVoiceMessage(
    val conversationId: String,
    val senderUsername: String,
    val wrappedRoomKey: String,
    val metadata: String = "",
) {
    /**
     * Serializes this pending voice message into a JSON object.
     *
     * @return JSON representation of this object.
     */
    internal fun toJson(): JSONObject = encodeToJsonObject()

    companion object {
        /**
         * Deserializes a pending voice message from the provided JSON representation.
         *
         * @param value Input value to store, parse, or format.
         * @return Parsed instance constructed from the JSON data.
         */
        internal fun fromJson(value: JSONObject): PendingVoiceMessage {
            value.getString("conversationId")
            value.getString("senderUsername")
            value.getString("wrappedRoomKey")
            return value.decodeJsonObject()
        }
    }
}

/** Approved upper bound for one encrypted R2 media object. */
internal const val MAX_MEDIA_CIPHERTEXT_BYTES=250L*1024L*1024L

/** Leaves room for the versioned AES-GCM container when an original file bypasses compression. */
internal const val MAX_MEDIA_PLAINTEXT_FALLBACK_BYTES=MAX_MEDIA_CIPHERTEXT_BYTES-64L

/** Safe domain signal used instead of exposing VM allocation details to the UI. */
internal class MediaPreparationMemoryException:IllegalStateException()

/** Domain classification used to select preprocessing rules such as OCR and compression. */
enum class MediaKind { DOCUMENT, IMAGE, VIDEO, AUDIO, OTHER }

/**
 * Classifies picker inputs without treating document photos as generic gallery images.
 *
 * @param mimeType MIME content type of the payload.
 * @return The resulting picker media kind.
 */
internal fun pickerMediaKind(mimeType:String)=when {
    mimeType.substringBefore(';').trim().lowercase().startsWith("video/") -> MediaKind.VIDEO
    mimeType.substringBefore(';').trim().lowercase().startsWith("audio/") -> MediaKind.AUDIO
    else -> MediaKind.DOCUMENT
}

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
 * @property source prepared ciphertext and encrypted metadata.
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
    val attachToSafetyPlan:Boolean=false,
    val voiceDraft: PendingVoiceMessage? = null,
    val voiceMessage: OutgoingMessage? = null,
) {
    /**
     * Serializes the entire resumable state for storage inside the Keystore-backed vault.
     *
     * @return JSON representation of this object.
     */
    fun toJson(): JSONObject = StoredMediaUploadJob.from(this).encodeToJsonObject()

    companion object {
        /**
         * Rehydrates a job after process death, app restart, or WorkManager rescheduling.
         *
         * @param value Input value to store, parse, or format.
         * @return Parsed instance constructed from the JSON data.
         */
        fun fromJson(value: JSONObject): MediaUploadJob =
            value.decodeJsonObject<StoredMediaUploadJob>().toModel()
    }
}

@Serializable
private data class StoredMediaUploadJob(
    val id: String,
    val ownerUid: String,
    val filePath: String,
    val ciphertextSize: Long,
    val ciphertextSha256: String,
    val encryptedMetadata: String,
    val wrappedKeys: Map<String, String>,
    val allowedUsers: List<String>,
    val encryptionVersion: Int = 1,
    val deviceWrappedKeys: Map<String, String> = emptyMap(),
    val kind: String,
    val ocrReview: String = OcrReview.NOT_RUN.name,
    val status: String,
    val mode: String? = null,
    val mediaId: String = "",
    val objectKey: String = "",
    val uploadId: String = "",
    val partSize: Long = 0,
    val completedParts: Map<String, String> = emptyMap(),
    val error: String? = null,
    val createdAt: Long,
    val attachToSafetyPlan: Boolean = false,
    val voiceDraft: PendingVoiceMessage? = null,
    val voiceMessage: OutgoingMessage? = null,
) {
    fun toModel(): MediaUploadJob {
        val source = PreparedEncryptedMedia(
            filePath = filePath,
            ciphertextSize = ciphertextSize,
            ciphertextSha256 = ciphertextSha256,
            encryptedMetadata = encryptedMetadata,
            wrappedKeys = wrappedKeys,
            allowedUsers = allowedUsers,
            encryptionVersion = encryptionVersion,
            kind = MediaKind.valueOf(kind),
            ocrReview = OcrReview.valueOf(ocrReview),
            deviceWrappedKeys = deviceWrappedKeys,
        )
        return MediaUploadJob(
            id = id,
            ownerUid = ownerUid,
            source = source,
            status = MediaTransferStatus.valueOf(status),
            mode = mode?.takeIf(String::isNotBlank)?.let(MediaUploadMode::valueOf),
            mediaId = mediaId,
            objectKey = objectKey,
            uploadId = uploadId,
            partSize = partSize,
            completedParts = completedParts.entries.associate { it.key.toInt() to it.value },
            error = error?.takeIf(String::isNotBlank),
            createdAt = createdAt,
            attachToSafetyPlan = attachToSafetyPlan,
            voiceDraft = voiceDraft,
            voiceMessage = voiceMessage,
        )
    }

    companion object {
        fun from(job: MediaUploadJob) = StoredMediaUploadJob(
            id = job.id,
            ownerUid = job.ownerUid,
            filePath = job.source.filePath,
            ciphertextSize = job.source.ciphertextSize,
            ciphertextSha256 = job.source.ciphertextSha256,
            encryptedMetadata = job.source.encryptedMetadata,
            wrappedKeys = job.source.wrappedKeys,
            allowedUsers = job.source.allowedUsers,
            encryptionVersion = job.source.encryptionVersion,
            deviceWrappedKeys = job.source.deviceWrappedKeys,
            kind = job.source.kind.name,
            ocrReview = job.source.ocrReview.name,
            status = job.status.name,
            mode = job.mode?.name,
            mediaId = job.mediaId,
            objectKey = job.objectKey,
            uploadId = job.uploadId,
            partSize = job.partSize,
            completedParts = job.completedParts.mapKeys { it.key.toString() },
            error = job.error,
            createdAt = job.createdAt,
            attachToSafetyPlan = job.attachToSafetyPlan,
            voiceDraft = job.voiceDraft,
            voiceMessage = job.voiceMessage,
        )
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

/** Encrypted metadata returned after Firebase verifies and publishes an uploaded R2 object. */
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
    val voiceConversationId: String? = null,
) {
    /** A bounded fraction suitable for a determinate progress indicator. */
    val progress:Float? get()=totalBytes.takeIf { it>0 }?.let {
        (bytesTransferred.toDouble()/it.toDouble()).coerceIn(0.0,1.0).toFloat()
    }
}
