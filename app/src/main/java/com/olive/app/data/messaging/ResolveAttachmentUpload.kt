package com.olive.app.data

import com.olive.app.data.media.MediaTransferStatus
import com.olive.app.data.media.MediaUploadJob

/** Resolves one account-owned upload without allowing failed or foreign media into chat. */
internal fun resolveAttachmentUpload(ownerUid: String, job: MediaUploadJob?): String? {
    job ?: throw AttachmentDeliveryRejected("ATTACHMENT_NOT_FOUND")
    if (job.ownerUid != ownerUid) throw AttachmentDeliveryRejected("ACCOUNT_MISMATCH")
    return when (job.status) {
        MediaTransferStatus.COMPLETE -> job.mediaId.takeIf(String::isNotBlank)
            ?: throw AttachmentDeliveryRejected("ATTACHMENT_NOT_FOUND")
        MediaTransferStatus.FAILED,
        MediaTransferStatus.CANCELLED -> throw AttachmentDeliveryRejected("ATTACHMENT_UPLOAD_FAILED")
        else -> null
    }
}
