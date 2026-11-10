// Signed-in accounts: describes content-free server revisions that require local reconciliation.
package com.olive.app.data.sync

internal enum class SyncDomain { ACCOUNT, SAFETY, MEDIA, CONVERSATION }

internal data class SyncRevisions(
    val revision: Long = 0,
    val account: Long = 0,
    val safety: Long = 0,
    val media: Long = 0,
    val conversation: Long = 0,
) {
    init {
        require(listOf(revision, account, safety, media, conversation).all { it >= 0 })
    }

    fun value(domain: SyncDomain): Long = when (domain) {
        SyncDomain.ACCOUNT -> account
        SyncDomain.SAFETY -> safety
        SyncDomain.MEDIA -> media
        SyncDomain.CONVERSATION -> conversation
    }

    fun withApplied(domain: SyncDomain, value: Long): SyncRevisions = when (domain) {
        SyncDomain.ACCOUNT -> copy(account = value)
        SyncDomain.SAFETY -> copy(safety = value)
        SyncDomain.MEDIA -> copy(media = value)
        SyncDomain.CONVERSATION -> copy(conversation = value)
    }
}

internal fun syncRevisionsFrom(uid: String, data: Map<String, Any?>?): SyncRevisions? {
    data ?: return null
    if (data["ownerUid"] != uid || data["allowedUsers"] != listOf(uid)) return null
    fun revision(name: String) = (data[name] as? Number)?.toLong()?.takeIf { it >= 0 }
    return SyncRevisions(
        revision = revision("revision") ?: return null,
        account = revision("accountRevision") ?: return null,
        safety = revision("safetyRevision") ?: return null,
        media = revision("mediaRevision") ?: return null,
        conversation = revision("conversationRevision") ?: return null,
    )
}

