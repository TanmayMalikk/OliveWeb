// Safe-app users: saves pending encrypted uploads across app restarts.
package com.olive.app.data.media

import android.content.Context
import com.olive.app.security.EncryptedVault
import org.json.JSONObject

/**
 * Keystore-encrypted persistence adapter for restartable [MediaUploadJob] state.
 *
 * `MediaTransferController` creates jobs here before scheduling work. [MediaUploadWorker] then reads
 * and atomically rewrites the same records as parts complete. Only paths, encrypted metadata, wrapped
 * keys, and transfer bookkeeping live in the vault; bulk ciphertext remains in app-private files.
 *
 * This class is the process-death boundary: neither Compose state nor a ViewModel is required for an
 * upload to resume after Android recreates the application.
 */
class MediaTransferStore(context:Context) {
    private val vault=EncryptedVault(context,"media-transfers")

    /** Reads one job by its client-generated idempotency key. */
    fun get(id:String):MediaUploadJob?=vault.read().optJSONObject(JOBS)?.optJSONObject(id)?.let(MediaUploadJob::fromJson)

    /** Returns all durable jobs, including completed/failed records available for diagnostics. */
    fun all():List<MediaUploadJob> {
        val jobs=vault.read().optJSONObject(JOBS) ?: return emptyList()
        return jobs.keys().asSequence().mapNotNull { jobs.optJSONObject(it)?.let(MediaUploadJob::fromJson) }.toList()
    }

    /** Inserts or replaces one complete job snapshot in a single encrypted-vault update. */
    fun write(job:MediaUploadJob) { vault.update { root -> jobs(root).put(job.id,job.toJson()) } }

    /** Atomically derives and persists the next state from the current state. */
    fun update(id:String,change:(MediaUploadJob)->MediaUploadJob):MediaUploadJob {
        var result:MediaUploadJob?=null
        vault.update { root ->
            val collection=jobs(root)
            val current=collection.optJSONObject(id)?.let(MediaUploadJob::fromJson) ?: error("Media transfer was not found")
            result=change(current);collection.put(id,checkNotNull(result).toJson())
        }
        return checkNotNull(result)
    }

    /** Removes bookkeeping only; callers must separately decide whether ciphertext should be deleted. */
    fun remove(id:String) { vault.update { it.optJSONObject(JOBS)?.remove(id) } }

    // returns the jobs for media.
    private fun jobs(root:JSONObject)=root.optJSONObject(JOBS) ?: JSONObject().also { root.put(JOBS,it) }
    private companion object { const val JOBS="jobs" }
}
