package com.olive.app.data.media

/** Wakes durable work that was blocked on a media upload without owning that work's policy. */
internal class MediaUploadDependencyNotifier(
    private val wakeMessages: (ownerUid: String) -> Unit,
) {
    fun uploadCompleted(ownerUid: String) {
        require(ownerUid.isNotBlank()) { "Upload owner is required" }
        wakeMessages(ownerUid)
    }
}
