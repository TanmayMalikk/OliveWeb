// Signed-in accounts: validates content-free push events before any work is scheduled.
package com.olive.app.data.sync

internal data class SyncInvalidation(val revision: Long, val urgent: Boolean)

internal fun syncInvalidationFrom(data: Map<String, String>): SyncInvalidation? {
    if (data.keys != setOf("type", "revision", "urgent") || data["type"] != "sync_invalidation") return null
    val revision = data["revision"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
    val urgent = when (data["urgent"]) { "0" -> false; "1" -> true; else -> return null }
    return SyncInvalidation(revision, urgent)
}

