package com.olive.app.data.media

import android.annotation.SuppressLint
import android.content.Context
import com.olive.app.data.messaging.OutgoingMessage
import com.olive.app.data.network.decodeJsonObject
import com.olive.app.data.network.encodeToJsonObject
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.security.AccountKeysetWrappedKey
import com.olive.app.security.EncryptedVault
import com.olive.app.security.JsonVault
import com.olive.app.security.KeyManager
import com.olive.app.security.child
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import org.json.JSONArray
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

    /**
     * Computes availability.
     *
     * @param uid Firebase UID of the target user account.
     * @param document Safety document metadata record.
     * @return The resulting [OfflineMediaAvailability] instance.
     */
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

    /**
     * Performs the expensive hash check only when bytes are about to be decrypted.
     *
     * @param uid Firebase UID of the target user account.
     * @param document Safety document metadata record.
     * @return The resulting [File], or `null` if unavailable.
     */
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

    /**
     * Registers upload candidate.
     *
     * @param uid Firebase UID of the target user account.
     * @param documentId Unique identifier of the safety document.
     * @param source Source input or object to read from.
     * @param size Size or count limit for the operation.
     * @param checksum Checksum supplied to [registerUploadCandidate].
     */
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

    /**
     * Promotes upload candidate.
     *
     * @param uid Firebase UID of the target user account.
     * @param document Safety document metadata record.
     * @return `true` if promote upload candidate, or `false` otherwise.
     */
    @Synchronized
    fun promoteUploadCandidate(uid: String, document: SafetyDocument): Boolean =
        promoteUploadCandidate(uid, document, CacheRetention.MANUAL)

    /**
     * Promotes upload candidate as recent.
     *
     * @param uid Firebase UID of the target user account.
     * @param document Safety document metadata record.
     * @return `true` if promote upload candidate as recent, or `false` otherwise.
     */
    @Synchronized
    fun promoteUploadCandidateAsRecent(uid: String, document: SafetyDocument): Boolean {
        if (!recentCachingEnabled(uid)) return false
        val promoted = promoteUploadCandidate(uid, document, CacheRetention.RECENT)
        if (promoted) enforceAutomaticLimit(uid)
        return promoted
    }

    /**
     * Promotes upload candidate.
     *
     * @param uid Firebase UID of the target user account.
     * @param document Safety document metadata record.
     * @param retention Retention supplied to [promoteUploadCandidate].
     * @return `true` if promote upload candidate, or `false` otherwise.
     */
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

    /**
     * Retains downloaded.
     *
     * @param uid Firebase UID of the target user account.
     * @param document Safety document metadata record.
     * @param source Source input or object to read from.
     */
    @Synchronized
    fun retainDownloaded(uid: String, document: SafetyDocument, source: File) =
        retain(uid, document, source, CacheRetention.MANUAL)

    /**
     * Retains an authenticated online opening only when this account opted into recent caching.
     *
     * @param uid Firebase UID of the target user account.
     * @param document Safety document metadata record.
     * @param source Source input or object to read from.
     * @return `true` if retain recent downloaded, or `false` otherwise.
     */
    @Synchronized
    fun retainRecentDownloaded(uid: String, document: SafetyDocument, source: File): Boolean {
        if (!recentCachingEnabled(uid)) return false
        retain(uid, document, source, CacheRetention.RECENT)
        enforceAutomaticLimit(uid)
        return entry(uid, document.id) != null
    }

    /**
     * Promotes an evictable recent copy to an explicit user-selected offline copy.
     *
     * @param uid Firebase UID of the target user account.
     * @param documentId Unique identifier of the safety document.
     */
    @Synchronized
    fun pin(uid: String, documentId: String) {
        requireIdentity(uid, documentId)
        vault.update { root -> root.optJSONObject(ACCOUNTS)?.optJSONObject(uid)
            ?.optJSONObject(ENTRIES)?.optJSONObject(documentId)
            ?.put("retention", CacheRetention.MANUAL.name) }
    }

    /**
     * Computes recent caching enabled.
     *
     * @param uid Firebase UID of the target user account.
     * @return `true` if recent caching enabled, or `false` otherwise.
     */
    @Synchronized
    fun recentCachingEnabled(uid: String): Boolean {
        requireIdentity(uid)
        return safeRoot().optJSONObject(ACCOUNTS)?.optJSONObject(uid)
            ?.optBoolean(RECENT_CACHING_ENABLED, false) == true
    }

    /**
     * Disabling the preference removes only evictable recent copies, never manually pinned files.
     *
     * @param uid Firebase UID of the target user account.
     * @param enabled Whether this feature or control is currently enabled.
     */
    @Synchronized
    fun setRecentCachingEnabled(uid: String, enabled: Boolean) {
        requireIdentity(uid)
        vault.update { it.child(ACCOUNTS).child(uid).put(RECENT_CACHING_ENABLED, enabled) }
        if (!enabled) removeRecentEntries(uid) else enforceAutomaticLimit(uid)
    }

    /**
     * Computes mark revalidated.
     *
     * @param uid Firebase UID of the target user account.
     * @param documentId Unique identifier of the safety document.
     */
    @Synchronized
    fun markRevalidated(uid: String, documentId: String) {
        requireIdentity(uid, documentId)
        vault.update { root -> root.optJSONObject(ACCOUNTS)?.optJSONObject(uid)
            ?.optJSONObject(ENTRIES)?.optJSONObject(documentId)?.put("validatedAt", now()) }
    }

    /**
     * Called only with a server-confirmed list; absent documents are treated as revoked.
     *
     * @param uid Firebase UID of the target user account.
     * @param documents Collection of documents values to process.
     */
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

    /**
     * Removes the offline media cache.
     *
     * @param uid Firebase UID of the target user account.
     * @param documentId Unique identifier of the safety document.
     */
    @Synchronized
    fun remove(uid: String, documentId: String) {
        requireIdentity(uid, documentId)
        val entry = safeRoot().optJSONObject(ACCOUNTS)?.optJSONObject(uid)
            ?.optJSONObject(ENTRIES)?.optJSONObject(documentId)
        entry?.optString("file")?.let { cachedFile(uid, it).delete() }
        vault.update { it.optJSONObject(ACCOUNTS)?.optJSONObject(uid)?.optJSONObject(ENTRIES)?.remove(documentId) }
    }

    /**
     * Purges only one account; another account's ciphertext is never touched.
     *
     * @param uid Firebase UID of the target user account.
     */
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

    /**
     * Removes stale unpinned upload bytes even if the document screen was never opened.
     */
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

    /**
     * Retains the offline media cache.
     *
     * @param uid Firebase UID of the target user account.
     * @param document Safety document metadata record.
     * @param source Source input or object to read from.
     * @param requestedRetention Requested retention supplied to [retain].
     */
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

    /**
     * Computes touch.
     *
     * @param uid Firebase UID of the target user account.
     * @param documentId Unique identifier of the safety document.
     */
    private fun touch(uid: String, documentId: String) = vault.update { root ->
        root.optJSONObject(ACCOUNTS)?.optJSONObject(uid)?.optJSONObject(ENTRIES)
            ?.optJSONObject(documentId)?.put("lastAccessedAt", now())
    }

    /**
     * Computes entry.
     *
     * @param uid Firebase UID of the target user account.
     * @param documentId Unique identifier of the safety document.
     * @return The resulting [CacheEntry], or `null` if unavailable.
     */
    private fun entry(uid: String, documentId: String): CacheEntry? {
        requireIdentity(uid, documentId)
        val value = safeRoot().optJSONObject(ACCOUNTS)?.optJSONObject(uid)
            ?.optJSONObject(ENTRIES)?.optJSONObject(documentId) ?: return null
        return runCatching { CacheEntry(value.getString("file"), value.getLong("size"),
            value.getString("checksum"), value.getLong("validatedAt"), value.getString("deviceId"),
            runCatching { CacheRetention.valueOf(value.optString("retention")) }
                .getOrDefault(CacheRetention.MANUAL)) }.getOrNull()
    }

    /**
     * Removes recent entries.
     *
     * @param uid Firebase UID of the target user account.
     */
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

    /**
     * Enforces one deterministic per-account budget across all automatically retained ciphertext.
     *
     * @param uid Firebase UID of the target user account.
     */
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

    /**
     * Returns the safe root.
     *
     * @return JSON object representing safe root.
     */
    private fun safeRoot(): JSONObject = try { vault.read() } catch (_: Exception) {
        vault.clear()
        root.deleteRecursively()
        JSONObject()
    }

    /**
     * Computes entries.
     *
     * @param rootJson Root JSON supplied to [entries].
     * @param uid Firebase UID of the target user account.
     * @return JSON object representing entries.
     */
    private fun entries(rootJson: JSONObject, uid: String): JSONObject =
        rootJson.child(ACCOUNTS).child(uid).child(ENTRIES)

    /**
     * Computes candidates.
     *
     * @param rootJson Root JSON supplied to [candidates].
     * @param uid Firebase UID of the target user account.
     * @return `true` if candidates, or `false` otherwise.
     */
    private fun candidates(rootJson: JSONObject, uid: String): JSONObject =
        rootJson.child(ACCOUNTS).child(uid).child(CANDIDATES)

    /**
     * Computes account directory.
     *
     * @param uid Firebase UID of the target user account.
     * @return The resulting account directory.
     */
    private fun accountDirectory(uid: String) = File(root, opaqueName(uid))
    /**
     * Computes cached file.
     *
     * @param uid Firebase UID of the target user account.
     * @param fileName File name supplied to [cachedFile].
     * @return The resulting [File] for cached file.
     */
    private fun cachedFile(uid: String, fileName: String): File =
        File(accountDirectory(uid), fileName.takeIf { it.matches(CACHE_FILE) } ?: "invalid")

    /**
     * Computes matches.
     *
     * @param file Local file to read, hash, process, or write.
     * @param size Size or count limit for the operation.
     * @param checksum Checksum supplied to [matches].
     * @return `true` if matches, or `false` otherwise.
     */
    private fun matches(file: File, size: Long, checksum: String): Boolean =
        file.isFile && file.length() == size && checksum.matches(SHA_256) && sha256(file).equals(checksum, true)

    /**
     * Computes the lowercase hexadecimal SHA-256 digest of a file's contents.
     *
     * @param file Local file to stream and hash.
     * @return Lowercase 64-character hexadecimal SHA-256 checksum.
     */
    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        try { file.inputStream().use { input -> while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        } } } finally { buffer.fill(0) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Computes opaque name.
     *
     * @param value Input value to store, parse, or format.
     * @return The resulting opaque name string.
     */
    private fun opaqueName(value: String): String = sha256Hex(value)

    /**
     * Checks whether app private candidate.
     *
     * @param file Local file to read, hash, process, or write.
     * @return `true` if is app private candidate, or `false` otherwise.
     */
    private fun isAppPrivateCandidate(file: File): Boolean = runCatching {
        file.canonicalFile.toPath().startsWith(checkNotNull(root.parentFile).canonicalFile.toPath()) &&
            file.canonicalFile != root.canonicalFile
    }.getOrDefault(false)

    /**
     * Discards candidate.
     *
     * @param uid Firebase UID of the target user account.
     * @param documentId Unique identifier of the safety document.
     * @param source Source input or object to read from.
     */
    private fun discardCandidate(uid: String, documentId: String, source: File) {
        if (isAppPrivateCandidate(source)) source.delete()
        removeCandidateRecord(uid, documentId)
    }

    /**
     * Removes candidate record.
     *
     * @param uid Firebase UID of the target user account.
     * @param documentId Unique identifier of the safety document.
     */
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

/**
 * Computes the lowercase hexadecimal SHA-256 digest of a UTF-8 string.
 *
 * @param value UTF-8 input string to hash cleanly into a cache-safe identifier.
 * @return Lowercase 64-character hexadecimal SHA-256 digest.
 */
internal fun sha256Hex(value: String): String = com.olive.app.data.network.sha256Hex(value)

/**
 * Validates that an account UID and optional document ID are non-blank and within bounds.
 *
 * @param uid Firebase UID of the target user account.
 * @param documentId Optional unique identifier of the referenced document, or null when omitted.
 */
internal fun requireIdentity(uid: String, documentId: String? = null) {
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

    /**
     * Reads one job by its client-generated idempotency key.
     *
     * @param id ID supplied to [get].
     * @return The resulting [MediaUploadJob], or `null` if unavailable.
     */
    fun get(id:String):MediaUploadJob?=vault.read().optJSONObject(JOBS)?.optJSONObject(id)?.let(MediaUploadJob::fromJson)

    /**
     * Returns all durable jobs, including completed/failed records available for diagnostics.
     *
     * @return List of resulting all items.
     */
    fun all():List<MediaUploadJob> {
        val jobs=vault.read().optJSONObject(JOBS) ?: return emptyList()
        return jobs.keys().asSequence().mapNotNull { jobs.optJSONObject(it)?.let(MediaUploadJob::fromJson) }.toList()
    }

    /**
     * Inserts or replaces one complete job snapshot in a single encrypted-vault update.
     *
     * @param job Job supplied to [write].
     */
    fun write(job:MediaUploadJob) { vault.update { root -> jobs(root).put(job.id,job.toJson()) } }

    /**
     * Atomically derives and persists the next state from the current state.
     *
     * @param id ID supplied to [update].
     * @param change Function used to transform or provide change.
     * @return The resulting [MediaUploadJob] instance.
     */
    fun update(id:String,change:(MediaUploadJob)->MediaUploadJob):MediaUploadJob {
        var result:MediaUploadJob?=null
        vault.update { root ->
            val collection=jobs(root)
            val current=collection.optJSONObject(id)?.let(MediaUploadJob::fromJson) ?: error("Media transfer was not found")
            result=change(current);collection.put(id, result.toJson())
        }
        return checkNotNull(result)
    }

    /**
     * Removes bookkeeping only; callers must separately decide whether ciphertext should be deleted.
     *
     * @param id ID supplied to [remove].
     */
    fun remove(id:String) { vault.update { it.optJSONObject(JOBS)?.remove(id) } }

    /**
     * Loads pending media-transfer jobs that belong to this account.
     *
     * @param root Root JSON object or directory being read or updated.
     * @return The resulting jobs.
     */
    private fun jobs(root:JSONObject)=root.optJSONObject(JOBS) ?: JSONObject().also { root.put(JOBS,it) }
    private companion object { const val JOBS="jobs" }
}

// Approved viewers: checks encrypted download size and checksum before decryption.

/**
 * Streams ciphertext once while enforcing its immutable byte count and SHA-256 digest.
 *
 * @param input Input stream, matrix, or value to process.
 * @param output Output stream or target receiving processed data.
 * @param expectedSize Expected size supplied to [copyAndVerifyEncryptedDownload].
 * @param expectedSha256 Expected sha 256 supplied to [copyAndVerifyEncryptedDownload].
 * @return The computed copy and verify encrypted download numeric value.
 */
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

    /**
     * Computes with document lock.
     *
     * @param documentId Unique identifier of the safety document.
     * @param block Callback or suspending lambda executed within this scope.
     * @param T The result type returned by the locked operation.
     * @return The resulting [T] for with document lock.
     */
    suspend fun <T> withDocumentLock(documentId:String,block:suspend ()->T):T =
        locks.computeIfAbsent(documentId) { Mutex() }.withLock { block() }
}

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
    val accountWrappedKeys:Map<String,AccountKeysetWrappedKey> = emptyMap(),
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
    fun toJson()=JSONObject().apply {
        put("id",id);put("ownerUid",ownerUid);put("filePath",source.filePath)
        put("ciphertextSize",source.ciphertextSize);put("ciphertextSha256",source.ciphertextSha256)
        put("encryptedMetadata",source.encryptedMetadata);put("wrappedKeys",JSONObject(source.wrappedKeys))
        put("allowedUsers",JSONArray(source.allowedUsers));put("encryptionVersion",source.encryptionVersion)
        put("deviceWrappedKeys",JSONObject(source.deviceWrappedKeys))
        put("accountWrappedKeys",JSONObject(source.accountWrappedKeys.mapValues { it.value.toJson() }))
        put("kind",source.kind.name);put("ocrReview",source.ocrReview.name);put("status",status.name)
        mode?.let { put("mode",it.name) };put("mediaId",mediaId);put("objectKey",objectKey)
        put("uploadId",uploadId);put("partSize",partSize);put("createdAt",createdAt)
        put("attachToSafetyPlan",attachToSafetyPlan)
        voiceDraft?.let { put("voiceDraft", it.toJson()) }
        voiceMessage?.let { put("voiceMessage", it.toJson()) }
        put("completedParts",JSONObject(completedParts.mapKeys { it.key.toString() }))
        error?.let { put("error",it) }
    }

    companion object {
        /**
         * Rehydrates a job after process death, app restart, or WorkManager rescheduling.
         *
         * @param value Input value to store, parse, or format.
         * @return Parsed instance constructed from the JSON data.
         */
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
            val accountWrapped=value.optJSONObject("accountWrappedKeys")?.let { json ->
                json.keys().asSequence().associateWith { AccountKeysetWrappedKey.fromJson(json.getJSONObject(it)) }
            }.orEmpty()
            val parts=value.optJSONObject("completedParts")?.let { json ->
                json.keys().asSequence().associate { it.toInt() to json.getString(it) }
            }.orEmpty()
            val source=PreparedEncryptedMedia(value.getString("filePath"),value.getLong("ciphertextSize"),
                value.getString("ciphertextSha256"),value.getString("encryptedMetadata"),wrapped,allowed,
                value.optInt("encryptionVersion",1),MediaKind.valueOf(value.getString("kind")),
                OcrReview.valueOf(value.optString("ocrReview",OcrReview.NOT_RUN.name)),deviceWrapped,
                accountWrapped)
            return MediaUploadJob(value.getString("id"),value.getString("ownerUid"),source,
                MediaTransferStatus.valueOf(value.getString("status")),value.optString("mode").takeIf(String::isNotBlank)?.let(MediaUploadMode::valueOf),
                value.optString("mediaId"),value.optString("objectKey"),value.optString("uploadId"),
                value.optLong("partSize"),parts,value.optString("error").takeIf(String::isNotBlank),
                value.getLong("createdAt"),value.optBoolean("attachToSafetyPlan"),
                value.optJSONObject("voiceDraft")?.let(PendingVoiceMessage::fromJson),
                value.optJSONObject("voiceMessage")?.let(OutgoingMessage::fromJson))
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

/** Encrypted metadata returned after Firebase verifies and publishes an uploaded R2 object. */
data class CloudMediaRecord(
    val id:String,val ownerUid:String,val objectKey:String,val encryptedMetadata:String,
    val wrappedKeys:Map<String,String>,val allowedUsers:List<String>,val ciphertextSize:Long,
    val ciphertextSha256:String,val encryptionVersion:Int,val createdAt:Long,
    val deviceWrappedKeys:Map<String,String> = emptyMap(),
    val accountWrappedKeys:Map<String,AccountKeysetWrappedKey> = emptyMap(),
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
    val chatOnly: Boolean = false,
) {
    /** A bounded fraction suitable for a determinate progress indicator. */
    val progress:Float? get()=totalBytes.takeIf { it>0 }?.let {
        (bytesTransferred.toDouble()/it.toDouble()).coerceIn(0.0,1.0).toFloat()
    }
}
