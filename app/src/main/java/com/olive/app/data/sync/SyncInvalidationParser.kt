// Signed-in accounts: validates content-free push events before any work is scheduled.
package com.olive.app.data.sync

internal data class SyncInvalidation(val revision: Long)

internal fun syncInvalidationFrom(data: Map<String, String>): SyncInvalidation? {
    if (data.keys != setOf("type", "revision") || data["type"] != "sync_invalidation") return null
    val revision = data["revision"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
    return SyncInvalidation(revision)
}

