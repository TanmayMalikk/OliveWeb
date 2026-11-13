package com.olive.app.data.media

import android.annotation.SuppressLint
import android.content.Context
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.security.EncryptedVault
import com.olive.app.security.JsonVault
import com.olive.app.security.KeyManager
import com.olive.app.security.child
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext


enum class OfflineMediaAvailability {
    NOT_CACHED, SAVING, AVAILABLE, REVALIDATION_REQUIRED,
    RECENT_AVAILABLE, RECENT_REVALIDATION_REQUIRED,
}

private enum class CacheRetention { MANUAL, RECENT }

enum class MediaAccessError {
    NOT_CACHED, NETWORK_REQUIRED, LEASE_EXPIRED, ACCESS_REVOKED, CORRUPT_CIPHERTEXT,
    KEY_UNAVAILABLE, ACCOUNT_MISMATCH, INSUFFICIENT_STORAGE, DOWNLOAD_FAILED, DEVICE_KEY_CHANGED,
}

class MediaAccessException(val reason: MediaAccessError, cause: Throwable? = null) :
    Exception(reason.name, cause)

/** App-private ciphertext cache with a Keystore-encrypted, account-scoped manifest. */
class OfflineMediaCache internal constructor(
    private val vault: JsonVault,
    private val root: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val currentDeviceId: () -> String = KeyManager::deviceId,
    private val automaticCacheLimitBytes: Long = AUTOMATIC_CACHE_LIMIT_BYTES,
) {
    constructor(context: Context) : this(
        EncryptedVault(context.applicationContext, "offline-media"),
        File(context.applicationContext.noBackupFilesDir, "offline-media"),
    )

    @Synchronized
    fun availability(uid: String, document: SafetyDocument): OfflineMediaAvailability {
        val entry = entry(uid, document.id) ?: return OfflineMediaAvailability.NOT_CACHED
        if (entry.deviceId != currentDeviceId() || entry.size != document.ciphertextSize ||
            !entry.checksum.equals(document.ciphertextSha256, true)) {
            remove(uid, document.id)
            return OfflineMediaAvailability.NOT_CACHED
        }
        val file = cachedFile(uid, entry.fileName)
        if (!file.isFile || file.length() != entry.size) {
            remove(uid, document.id)
            return OfflineMediaAvailability.NOT_CACHED
        }
        val expired = document.ownerUid != uid && (now() < entry.validatedAt ||
            now() - entry.validatedAt > SHARED_REVALIDATION_LEASE_MS)
        return when {
            entry.retention == CacheRetention.RECENT && expired ->
                OfflineMediaAvailability.RECENT_REVALIDATION_REQUIRED
            entry.retention == CacheRetention.RECENT -> OfflineMediaAvailability.RECENT_AVAILABLE
            expired -> OfflineMediaAvailability.REVALIDATION_REQUIRED
            else -> OfflineMediaAvailability.AVAILABLE
        }
    }

    /** Performs the expensive hash check only when bytes are about to be decrypted. */
    @Synchronized
    fun verifiedCiphertext(uid: String, document: SafetyDocument): File? {
        if (availability(uid, document) !in setOf(
                OfflineMediaAvailability.AVAILABLE,
                OfflineMediaAvailability.RECENT_AVAILABLE,
            )) return null
        val entry = entry(uid, document.id) ?: return null
        val file = cachedFile(uid, entry.fileName)
        if (!matches(file, entry.size, entry.checksum)) {
            remove(uid, document.id)
            throw MediaAccessException(MediaAccessError.CORRUPT_CIPHERTEXT)
        }
        touch(uid, document.id)
        return file
    }

    @Synchronized
    fun registerUploadCandidate(
        uid: String,
        documentId: String,
        source: File,
        size: Long,
        checksum: String,
    ) {
        requireIdentity(uid, documentId)
        require(size in 1..MAX_CIPHERTEXT_BYTES && automaticCacheLimitBytes > 0)
        require(isAppPrivateCandidate(source) && matches(source, size, checksum))
        cleanupExpiredCandidates()
        vault.update { root -> candidates(root, uid).put(documentId, JSONObject()
            .put("path", source.absolutePath)
            .put("size", size)
            .put("checksum", checksum.lowercase())
            .put("lastAccessedAt", now())
            .put("expiresAt", now() + UPLOAD_REUSE_WINDOW_MS)) }
        enforceAutomaticLimit(uid)
    }

    @Synchronized
    fun promoteUploadCandidate(uid: String, document: SafetyDocument): Boolean =
        promoteUploadCandidate(uid, document, CacheRetention.MANUAL)

    @Synchronized
    fun promoteUploadCandidateAsRecent(uid: String, document: SafetyDocument): Boolean {
        if (!recentCachingEnabled(uid)) return false
        val promoted = promoteUploadCandidate(uid, document, CacheRetention.RECENT)
        if (promoted) enforceAutomaticLimit(uid)
        return promoted
    }

    private fun promoteUploadCandidate(
        uid: String,
        document: SafetyDocument,
        retention: CacheRetention,
    ): Boolean {
        cleanupExpiredCandidates()
        val row = safeRoot().optJSONObject(ACCOUNTS)?.optJSONObject(uid)
            ?.optJSONObject(CANDIDATES)?.optJSONObject(document.id) ?: return false
        val source = File(row.optString("path"))
        if (!isAppPrivateCandidate(source) || row.optLong("size") != document.ciphertextSize ||
            !row.optString("checksum").equals(document.ciphertextSha256, true) ||
            !matches(source, document.ciphertextSize, document.ciphertextSha256)) {
            discardCandidate(uid, document.id, source)
            return false
        }
        retain(uid, document, source, retention)
        removeCandidateRecord(uid, document.id)
        return true
    }

    @Synchronized
    fun retainDownloaded(uid: String, document: SafetyDocument, source: File) =
        retain(uid, document, source, CacheRetention.MANUAL)

    /** Retains an authenticated online opening only when this account opted into recent caching. */
    @Synchronized
    fun retainRecentDownloaded(uid: String, document: SafetyDocument, source: File): Boolean {
        if (!recentCachingEnabled(uid)) return false
        retain(uid, document, source, CacheRetention.RECENT)
        enforceAutomaticLimit(uid)
        return entry(uid, document.id) != null
    }

    /** Promotes an evictable recent copy to an explicit user-selected offline copy. */
    @Synchronized
    fun pin(uid: String, documentId: String) {
        requireIdentity(uid, documentId)
        vault.update { root -> root.optJSONObject(ACCOUNTS)?.optJSONObject(uid)
            ?.optJSONObject(ENTRIES)?.optJSONObject(documentId)
            ?.put("retention", CacheRetention.MANUAL.name) }
    }

    @Synchronized
    fun recentCachingEnabled(uid: String): Boolean {
        requireIdentity(uid)
        return safeRoot().optJSONObject(ACCOUNTS)?.optJSONObject(uid)
            ?.optBoolean(RECENT_CACHING_ENABLED, false) == true
    }

    /** Disabling the preference removes only evictable recent copies, never manually pinned files. */
    @Synchronized
    fun setRecentCachingEnabled(uid: String, enabled: Boolean) {
        requireIdentity(uid)
        vault.update { it.child(ACCOUNTS).child(uid).put(RECENT_CACHING_ENABLED, enabled) }
        if (!enabled) removeRecentEntries(uid) else enforceAutomaticLimit(uid)
    }

    @Synchronized
    fun markRevalidated(uid: String, documentId: String) {
        requireIdentity(uid, documentId)
        vault.update { root -> root.optJSONObject(ACCOUNTS)?.optJSONObject(uid)
            ?.optJSONObject(ENTRIES)?.optJSONObject(documentId)?.put("validatedAt", now()) }
    }

    /** Called only with a server-confirmed list; absent documents are treated as revoked. */
    @Synchronized
    fun reconcile(uid: String, documents: List<SafetyDocument>) {
        val accessible = documents.filter { uid in it.allowedUsers }.associateBy(SafetyDocument::id)
        val account = safeRoot().optJSONObject(ACCOUNTS)?.optJSONObject(uid) ?: return
        val entries = account.optJSONObject(ENTRIES) ?: JSONObject()
        entries.keys().asSequence().toList().filterNot(accessible::containsKey).forEach { id ->
            entries.optJSONObject(id)?.optString("file")?.let { cachedFile(uid, it).delete() }
            entries.remove(id)
        }
        accessible.values.filter { it.ownerUid != uid }.forEach { entries.optJSONObject(it.id)?.put("validatedAt", now()) }
        vault.update { root -> root.optJSONObject(ACCOUNTS)?.optJSONObject(uid)?.put(ENTRIES, entries) }
        cleanupExpiredCandidates()
    }

    @Synchronized
    fun remove(uid: String, documentId: String) {
        requireIdentity(uid, documentId)
        val entry = safeRoot().optJSONObject(ACCOUNTS)?.optJSONObject(uid)
            ?.optJSONObject(ENTRIES)?.optJSONObject(documentId)
        entry?.optString("file")?.let { cachedFile(uid, it).delete() }
        vault.update { it.optJSONObject(ACCOUNTS)?.optJSONObject(uid)?.optJSONObject(ENTRIES)?.remove(documentId) }
    }

    /** Purges only one account; another account's ciphertext is never touched. */
    @Synchronized
    fun purge(uid: String) {
        requireIdentity(uid)
        val account = runCatching { safeRoot().optJSONObject(ACCOUNTS)?.optJSONObject(uid) }.getOrNull()
        account?.optJSONObject(CANDIDATES)?.let { rows -> rows.keys().forEach { id ->
            rows.optJSONObject(id)?.optString("path")?.let(::File)?.takeIf(::isAppPrivateCandidate)?.delete()
        } }
        accountDirectory(uid).deleteRecursively()
        runCatching { vault.update { it.optJSONObject(ACCOUNTS)?.remove(uid) } }
    }

    /** Removes stale unpinned upload bytes even if the document screen was never opened. */
    @Synchronized
    fun cleanupExpiredCandidates() {
        val rootJson = safeRoot()
        val accounts = rootJson.optJSONObject(ACCOUNTS) ?: return
        var changed = false
        accounts.keys().forEach { uid ->
            val rows = accounts.optJSONObject(uid)?.optJSONObject(CANDIDATES) ?: return@forEach
            rows.keys().asSequence().toList().forEach { id ->
                val row = rows.optJSONObject(id)
                if (row == null || row.optLong("expiresAt") <= now()) {
                    row?.optString("path")?.let(::File)?.takeIf(::isAppPrivateCandidate)?.delete()
                    rows.remove(id)
                    changed = true
                }
            }
        }
        if (changed) vault.update { it.put(ACCOUNTS, accounts) }
        accounts.keys().asSequence().toList().forEach(::enforceAutomaticLimit)
    }

    @SuppressLint("UsableSpace")
    private fun retain(
        uid: String,
        document: SafetyDocument,
        source: File,
        requestedRetention: CacheRetention,
    ) {
        requireIdentity(uid, document.id)
        if (uid !in document.allowedUsers) throw MediaAccessException(MediaAccessError.ACCOUNT_MISMATCH)
        if (document.ciphertextSize !in 1..MAX_CIPHERTEXT_BYTES || !matches(source,
                document.ciphertextSize, document.ciphertextSha256)) {
            throw MediaAccessException(MediaAccessError.CORRUPT_CIPHERTEXT)
        }
        val directory = accountDirectory(uid).apply { mkdirs() }
        //TODO: determine if the warning here is valid or not.
        // The current method usable space is not recommended for this.
        // However the recommended functions are not defined inside the class.
        if (directory.usableSpace < document.ciphertextSize + MIN_FREE_SPACE_BYTES) {
            throw MediaAccessException(MediaAccessError.INSUFFICIENT_STORAGE)
        }
        val fileName = opaqueName(document.id) + ".olive"
        val target = File(directory, fileName)
        val partial = File(directory, "$fileName.part")
        partial.delete()
        try {
            if (!source.renameTo(partial)) source.inputStream().use { input -> partial.outputStream().use(input::copyTo) }
            if (!matches(partial, document.ciphertextSize, document.ciphertextSha256)) {
                throw MediaAccessException(MediaAccessError.CORRUPT_CIPHERTEXT)
            }
            target.delete()
            check(partial.renameTo(target))
            if (source.exists() && source.canonicalFile != target.canonicalFile) source.delete()
            val retention = if (entry(uid, document.id)?.retention == CacheRetention.MANUAL)
                CacheRetention.MANUAL else requestedRetention
            vault.update { root -> entries(root, uid).put(document.id, JSONObject()
                .put("file", fileName)
                .put("size", document.ciphertextSize)
                .put("checksum", document.ciphertextSha256.lowercase())
                .put("validatedAt", now())
                .put("lastAccessedAt", now())
                .put("retention", retention.name)
                .put("deviceId", currentDeviceId())) }
            val candidate = safeRoot().optJSONObject(ACCOUNTS)?.optJSONObject(uid)
                ?.optJSONObject(CANDIDATES)?.optJSONObject(document.id)
                ?.optString("path")?.let(::File)
            candidate?.takeIf(::isAppPrivateCandidate)?.takeIf { it.canonicalFile != target.canonicalFile }
                ?.delete()
            removeCandidateRecord(uid, document.id)
        } catch (failure: Exception) {
            partial.delete()
            throw failure
        }
    }

    private fun touch(uid: String, documentId: String) = vault.update { root ->
        root.optJSONObject(ACCOUNTS)?.optJSONObject(uid)?.optJSONObject(ENTRIES)
            ?.optJSONObject(documentId)?.put("lastAccessedAt", now())
    }

    private fun entry(uid: String, documentId: String): CacheEntry? {
        requireIdentity(uid, documentId)
        val value = safeRoot().optJSONObject(ACCOUNTS)?.optJSONObject(uid)
            ?.optJSONObject(ENTRIES)?.optJSONObject(documentId) ?: return null
        return runCatching { CacheEntry(value.getString("file"), value.getLong("size"),
            value.getString("checksum"), value.getLong("validatedAt"), value.getString("deviceId"),
            runCatching { CacheRetention.valueOf(value.optString("retention")) }
                .getOrDefault(CacheRetention.MANUAL)) }.getOrNull()
    }

    private fun removeRecentEntries(uid: String) {
        val rootJson = safeRoot()
        val rows = rootJson.optJSONObject(ACCOUNTS)?.optJSONObject(uid)
            ?.optJSONObject(ENTRIES) ?: return
        rows.keys().asSequence().toList().forEach { id ->
            val row = rows.optJSONObject(id) ?: return@forEach
            if (row.optString("retention") == CacheRetention.RECENT.name) {
                row.optString("file").let { cachedFile(uid, it).delete() }
                rows.remove(id)
            }
        }
        vault.update { entries(it, uid).let { stored ->
            stored.keys().asSequence().toList().forEach(stored::remove)
            rows.keys().forEach { id -> stored.put(id, rows.get(id)) }
        } }
    }

    /** Enforces one deterministic per-account budget across all automatically retained ciphertext. */
    private fun enforceAutomaticLimit(uid: String) {
        val rootJson = safeRoot()
        val account = rootJson.optJSONObject(ACCOUNTS)?.optJSONObject(uid) ?: return
        val entries = account.optJSONObject(ENTRIES) ?: JSONObject()
        val candidates = account.optJSONObject(CANDIDATES) ?: JSONObject()
        val automatic = mutableListOf<AutomaticItem>()
        entries.keys().asSequence().toList().forEach { id ->
            val row = entries.optJSONObject(id) ?: return@forEach
            if (row.optString("retention") != CacheRetention.RECENT.name) return@forEach
            val file = cachedFile(uid, row.optString("file"))
            val size = row.optLong("size", -1)
            if (!file.isFile || size < 0 || file.length() != size) {
                file.delete(); entries.remove(id)
            } else automatic += AutomaticItem(false, id, size, row.optLong("lastAccessedAt"), file)
        }
        candidates.keys().asSequence().toList().forEach { id ->
            val row = candidates.optJSONObject(id) ?: return@forEach
            val file = File(row.optString("path"))
            val size = row.optLong("size", -1)
            if (!isAppPrivateCandidate(file) || !file.isFile || size < 0 || file.length() != size) {
                if (isAppPrivateCandidate(file)) file.delete()
                candidates.remove(id)
            } else automatic += AutomaticItem(true, id, size, row.optLong("lastAccessedAt"), file)
        }
        var total = automatic.fold(0L) { sum, item ->
            if (Long.MAX_VALUE - sum < item.size) Long.MAX_VALUE else sum + item.size
        }
        automatic.sortedWith(compareBy({ it.lastAccessedAt }, { it.id }, { it.candidate }))
            .forEach { item ->
                if (total <= automaticCacheLimitBytes) return@forEach
                item.file.delete()
                if (item.candidate) candidates.remove(item.id) else entries.remove(item.id)
                total -= item.size
            }
        vault.update { root -> root.child(ACCOUNTS).child(uid).put(ENTRIES, entries).put(CANDIDATES, candidates) }
    }

    private fun safeRoot(): JSONObject = try { vault.read() } catch (_: Exception) {
        vault.clear()
        root.deleteRecursively()
        JSONObject()
    }

    private fun entries(rootJson: JSONObject, uid: String): JSONObject =
        rootJson.child(ACCOUNTS).child(uid).child(ENTRIES)

    private fun candidates(rootJson: JSONObject, uid: String): JSONObject =
        rootJson.child(ACCOUNTS).child(uid).child(CANDIDATES)

    private fun accountDirectory(uid: String) = File(root, opaqueName(uid))
    private fun cachedFile(uid: String, fileName: String): File =
        File(accountDirectory(uid), fileName.takeIf { it.matches(CACHE_FILE) } ?: "invalid")

    private fun matches(file: File, size: Long, checksum: String): Boolean =
        file.isFile && file.length() == size && checksum.matches(SHA_256) && sha256(file).equals(checksum, true)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        try { file.inputStream().use { input -> while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        } } } finally { buffer.fill(0) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun opaqueName(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun isAppPrivateCandidate(file: File): Boolean = runCatching {
        file.canonicalFile.toPath().startsWith(checkNotNull(root.parentFile).canonicalFile.toPath()) &&
            file.canonicalFile != root.canonicalFile
    }.getOrDefault(false)

    private fun discardCandidate(uid: String, documentId: String, source: File) {
        if (isAppPrivateCandidate(source)) source.delete()
        removeCandidateRecord(uid, documentId)
    }

    private fun removeCandidateRecord(uid: String, documentId: String) = vault.update {
        it.optJSONObject(ACCOUNTS)?.optJSONObject(uid)?.optJSONObject(CANDIDATES)?.remove(documentId)
    }

    private data class CacheEntry(
        val fileName: String,
        val size: Long,
        val checksum: String,
        val validatedAt: Long,
        val deviceId: String,
        val retention: CacheRetention,
    )

    private data class AutomaticItem(
        val candidate: Boolean,
        val id: String,
        val size: Long,
        val lastAccessedAt: Long,
        val file: File,
    )

    companion object {
        const val SHARED_REVALIDATION_LEASE_MS = 24L * 60L * 60L * 1000L
        const val AUTOMATIC_CACHE_LIMIT_BYTES = 250L * 1024L * 1024L
        const val UPLOAD_REUSE_WINDOW_MS = 24L * 60L * 60L * 1000L
        private const val MAX_CIPHERTEXT_BYTES = 250L * 1024L * 1024L
        private const val MIN_FREE_SPACE_BYTES = 16L * 1024L * 1024L
        private const val ACCOUNTS = "accounts"
        private const val ENTRIES = "entries"
        private const val CANDIDATES = "candidates"
        private const val RECENT_CACHING_ENABLED = "recentCachingEnabled"
        private val SHA_256 = Regex("^[0-9a-fA-F]{64}$")
        private val CACHE_FILE = Regex("^[0-9a-f]{64}\\.olive$")
    }
}

private fun requireIdentity(uid: String, documentId: String? = null) {
    require(uid.isNotBlank() && uid.length <= 160)
    documentId?.let { require(it.isNotBlank() && it.length <= 160) }
}

// Safe-app users: saves pending encrypted uploads across app restarts.

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
            result=change(current);collection.put(id, result.toJson())
        }
        return checkNotNull(result)
    }

    /** Removes bookkeeping only; callers must separately decide whether ciphertext should be deleted. */
    fun remove(id:String) { vault.update { it.optJSONObject(JOBS)?.remove(id) } }

    // Loads pending media-transfer jobs that belong to this account.
    private fun jobs(root:JSONObject)=root.optJSONObject(JOBS) ?: JSONObject().also { root.put(JOBS,it) }
    private companion object { const val JOBS="jobs" }
}

// Approved viewers: checks encrypted download size and checksum before decryption.

/** Streams ciphertext once while enforcing its immutable byte count and SHA-256 digest. */
internal suspend fun copyAndVerifyEncryptedDownload(
    input:InputStream,
    output:OutputStream,
    expectedSize:Long,
    expectedSha256:String,
):Long = withContext(Dispatchers.IO) {
    require(expectedSize>0) { "Encrypted download size is invalid" }
    require(expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Encrypted download checksum is invalid" }
    val digest=MessageDigest.getInstance("SHA-256")
    val buffer=ByteArray(64*1024)
    var received=0L
    // Use the fallback when copy and verify encrypted download is unavailable.
    try {
        // Media: Keeps copy and verify encrypted download active while true.
        while(true) {
            currentCoroutineContext().ensureActive()
            val count= input.read(buffer)
            if(count<0) break
            if(count==0) continue
            received+=count
            require(received<=expectedSize) {
                "Encrypted download size does not match (expected $expectedSize bytes, received more than $expectedSize bytes)"
            }
            digest.update(buffer,0,count)
                output.write(buffer, 0, count)
        }
    } finally {
        buffer.fill(0)
    }
    require(received==expectedSize) {
        "Encrypted download size does not match (expected $expectedSize bytes, received $received bytes)"
    }
    val actual=digest.digest().joinToString("") { "%02x".format(it) }
    require(actual.equals(expectedSha256,true)) { "Encrypted download checksum does not match" }
    received
}

// Approved viewers: serializes document opens so downloads cannot overwrite each other.

/** Prevents two opens of the same document from racing over its decrypted output. */
internal class DocumentDownloadGate {
    private val locks=ConcurrentHashMap<String,Mutex>()

    suspend fun <T> withDocumentLock(documentId:String,block:suspend ()->T):T =
        locks.computeIfAbsent(documentId) { Mutex() }.withLock { block() }
}
