package com.olive.app.data

import com.olive.app.data.media.MediaAccessError
import com.olive.app.data.media.MediaAccessException
import com.olive.app.data.media.OfflineMediaAvailability
import com.olive.app.data.media.OfflineMediaCache
import com.olive.app.security.JsonVault
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineMediaCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `accounts remain isolated and selected sign out does not purge another account`() {
        val cache = cache()
        val alice = document("alice-file", ALICE, ALICE)
        val bob = document("bob-file", BOB, BOB)
        cache.retainDownloaded(ALICE, alice, ciphertext(alice))
        cache.retainDownloaded(BOB, bob, ciphertext(bob))
        cache.purge(ALICE)
        assertEquals(OfflineMediaAvailability.NOT_CACHED, cache.availability(ALICE, alice))
        assertEquals(OfflineMediaAvailability.AVAILABLE, cache.availability(BOB, bob))
    }

    @Test
    fun `lease accepts exact boundary and rejects expiry and clock rollback`() {
        var clock = 10_000L
        val cache = cache(clock = { clock })
        val shared = document("shared", ALICE, BOB)
        cache.retainDownloaded(BOB, shared, ciphertext(shared))
        clock += OfflineMediaCache.SHARED_REVALIDATION_LEASE_MS
        assertEquals(OfflineMediaAvailability.AVAILABLE, cache.availability(BOB, shared))
        clock++
        assertEquals(OfflineMediaAvailability.REVALIDATION_REQUIRED, cache.availability(BOB, shared))
        cache.markRevalidated(BOB, shared.id)
        clock--
        assertEquals(OfflineMediaAvailability.REVALIDATION_REQUIRED, cache.availability(BOB, shared))
    }

    @Test
    fun `same length corruption is rejected at open and removed`() {
        val root = temporary.newFolder("corruption")
        val cache = OfflineMediaCache(FakeJsonVault(), root, currentDeviceId = { DEVICE })
        val document = document("corrupt", ALICE, ALICE)
        cache.retainDownloaded(ALICE, document, ciphertext(document))
        root.walkTopDown().first { it.extension == "olive" }
            .writeBytes("x".repeat(document.ciphertextSize.toInt()).toByteArray())
        val failure = runCatching { cache.verifiedCiphertext(ALICE, document) }.exceptionOrNull()
        assertEquals(MediaAccessError.CORRUPT_CIPHERTEXT, (failure as MediaAccessException).reason)
        assertEquals(OfflineMediaAvailability.NOT_CACHED, cache.availability(ALICE, document))
    }

    @Test
    fun `server reconciliation purges revoked file and refreshes retained lease`() {
        var clock = 1_000L
        val cache = cache(clock = { clock })
        val kept = document("kept", ALICE, BOB)
        val revoked = document("revoked", ALICE, BOB)
        cache.retainDownloaded(BOB, kept, ciphertext(kept))
        cache.retainDownloaded(BOB, revoked, ciphertext(revoked))
        clock += OfflineMediaCache.SHARED_REVALIDATION_LEASE_MS + 1
        cache.reconcile(BOB, listOf(kept))
        assertEquals(OfflineMediaAvailability.AVAILABLE, cache.availability(BOB, kept))
        assertEquals(OfflineMediaAvailability.NOT_CACHED, cache.availability(BOB, revoked))
    }

    @Test
    fun `upload candidate promotes original ciphertext and expired candidate is deleted`() {
        var clock = 1_000L
        val cache = cache(clock = { clock })
        val promoted = document("promoted", ALICE, ALICE)
        val first = ciphertext(promoted)
        cache.registerUploadCandidate(ALICE, promoted.id, first, promoted.ciphertextSize, promoted.ciphertextSha256)
        assertTrue(cache.promoteUploadCandidate(ALICE, promoted))
        assertFalse(first.exists())

        val expired = document("expired", ALICE, ALICE)
        val second = ciphertext(expired)
        cache.registerUploadCandidate(ALICE, expired.id, second, expired.ciphertextSize, expired.ciphertextSha256)
        clock += OfflineMediaCache.UPLOAD_REUSE_WINDOW_MS + 1
        cache.cleanupExpiredCandidates()
        assertFalse(second.exists())
    }

    @Test
    fun `enabled recent caching promotes sender ciphertext without an R2 download`() {
        val cache = cache()
        val document = document("recent-upload", ALICE, ALICE)
        val source = ciphertext(document)
        cache.setRecentCachingEnabled(ALICE, true)
        cache.registerUploadCandidate(ALICE, document.id, source,
            document.ciphertextSize, document.ciphertextSha256)

        assertTrue(cache.promoteUploadCandidateAsRecent(ALICE, document))
        assertFalse(source.exists())
        assertEquals(OfflineMediaAvailability.RECENT_AVAILABLE, cache.availability(ALICE, document))
    }

    @Test
    fun `recent caching defaults off and remains isolated by account`() {
        val cache = cache()
        val alice = document("recent-alice", ALICE, ALICE)
        val source = ciphertext(alice)
        assertFalse(cache.recentCachingEnabled(ALICE))
        assertFalse(cache.retainRecentDownloaded(ALICE, alice, source))
        assertTrue(source.exists())
        assertEquals(OfflineMediaAvailability.NOT_CACHED, cache.availability(ALICE, alice))

        cache.setRecentCachingEnabled(ALICE, true)
        assertTrue(cache.recentCachingEnabled(ALICE))
        assertFalse(cache.recentCachingEnabled(BOB))
    }

    @Test
    fun `disabling recent caching removes recent copies but preserves manual copies`() {
        val cache = cache()
        val recent = document("recent", ALICE, ALICE)
        val promoted = document("promoted-recent", ALICE, ALICE)
        val manual = document("manual", ALICE, ALICE)
        cache.setRecentCachingEnabled(ALICE, true)
        assertTrue(cache.retainRecentDownloaded(ALICE, recent, ciphertext(recent)))
        assertTrue(cache.retainRecentDownloaded(ALICE, promoted, ciphertext(promoted)))
        cache.pin(ALICE, promoted.id)
        cache.retainDownloaded(ALICE, manual, ciphertext(manual))
        assertEquals(OfflineMediaAvailability.RECENT_AVAILABLE, cache.availability(ALICE, recent))

        cache.setRecentCachingEnabled(ALICE, false)

        assertEquals(OfflineMediaAvailability.NOT_CACHED, cache.availability(ALICE, recent))
        assertEquals(OfflineMediaAvailability.AVAILABLE, cache.availability(ALICE, promoted))
        assertEquals(OfflineMediaAvailability.AVAILABLE, cache.availability(ALICE, manual))
    }

    @Test
    fun `automatic budget evicts least recent across recent files and upload candidates`() {
        var clock = 1_000L
        val first = document("first", ALICE, ALICE)
        val candidate = document("candidate", ALICE, ALICE)
        val newest = document("newest", ALICE, ALICE)
        val manual = document("manual-pinned", ALICE, ALICE)
        val limit = maxOf(first.ciphertextSize, candidate.ciphertextSize, newest.ciphertextSize)
        val cache = cache({ clock }, limit)
        cache.setRecentCachingEnabled(ALICE, true)
        cache.retainRecentDownloaded(ALICE, first, ciphertext(first))
        clock++
        val candidateFile = ciphertext(candidate)
        cache.registerUploadCandidate(ALICE, candidate.id, candidateFile,
            candidate.ciphertextSize, candidate.ciphertextSha256)
        assertEquals(OfflineMediaAvailability.NOT_CACHED, cache.availability(ALICE, first))
        assertTrue(candidateFile.exists())

        cache.retainDownloaded(ALICE, manual, ciphertext(manual))
        clock++
        cache.retainRecentDownloaded(ALICE, newest, ciphertext(newest))

        assertFalse(candidateFile.exists())
        assertEquals(OfflineMediaAvailability.RECENT_AVAILABLE, cache.availability(ALICE, newest))
        assertEquals(OfflineMediaAvailability.AVAILABLE, cache.availability(ALICE, manual))
    }

    @Test
    fun `automatic cache accepts exact limit and uses access time for LRU eviction`() {
        var clock = 1_000L
        val first = document("one", ALICE, ALICE)
        val second = document("two", ALICE, ALICE)
        val third = document("six", ALICE, ALICE)
        val limit = first.ciphertextSize + second.ciphertextSize
        val cache = cache({ clock }, limit)
        cache.setRecentCachingEnabled(ALICE, true)
        cache.retainRecentDownloaded(ALICE, first, ciphertext(first))
        clock++
        cache.retainRecentDownloaded(ALICE, second, ciphertext(second))
        assertEquals(OfflineMediaAvailability.RECENT_AVAILABLE, cache.availability(ALICE, first))
        assertEquals(OfflineMediaAvailability.RECENT_AVAILABLE, cache.availability(ALICE, second))

        clock++
        assertTrue(cache.verifiedCiphertext(ALICE, first)?.isFile == true)
        clock++
        cache.retainRecentDownloaded(ALICE, third, ciphertext(third))

        assertEquals(OfflineMediaAvailability.RECENT_AVAILABLE, cache.availability(ALICE, first))
        assertEquals(OfflineMediaAvailability.NOT_CACHED, cache.availability(ALICE, second))
        assertEquals(OfflineMediaAvailability.RECENT_AVAILABLE, cache.availability(ALICE, third))
    }

    @Test
    fun `equal access times use stable document ID eviction order`() {
        val laterId = document("tie-z", ALICE, ALICE)
        val earlierId = document("tie-a", ALICE, ALICE)
        val cache = cache(limit = laterId.ciphertextSize)
        cache.setRecentCachingEnabled(ALICE, true)
        cache.retainRecentDownloaded(ALICE, laterId, ciphertext(laterId))
        cache.retainRecentDownloaded(ALICE, earlierId, ciphertext(earlierId))

        assertEquals(OfflineMediaAvailability.RECENT_AVAILABLE, cache.availability(ALICE, laterId))
        assertEquals(OfflineMediaAvailability.NOT_CACHED, cache.availability(ALICE, earlierId))
    }

    @Test
    fun `legacy entries without retention metadata migrate as manual`() {
        val vault = FakeJsonVault()
        val cache = OfflineMediaCache(vault, temporary.newFolder(), currentDeviceId = { DEVICE })
        val legacy = document("legacy", ALICE, ALICE)
        cache.retainDownloaded(ALICE, legacy, ciphertext(legacy))
        vault.update { root -> root.getJSONObject("accounts").getJSONObject(ALICE)
            .getJSONObject("entries").getJSONObject(legacy.id).remove("retention") }

        cache.setRecentCachingEnabled(ALICE, false)

        assertEquals(OfflineMediaAvailability.AVAILABLE, cache.availability(ALICE, legacy))
    }

    @Test
    fun `repeated mixed media cache cycles remain deterministic`() {
        val cache = cache()
        repeat(120) { index ->
            val owner = if (index % 2 == 0) ALICE else BOB
            val document = document("stress-$index", owner, owner,
                mime = listOf("application/pdf", "image/jpeg", "video/mp4", "audio/mpeg")[index % 4])
            cache.retainDownloaded(owner, document, ciphertext(document))
            assertTrue(cache.verifiedCiphertext(owner, document)?.isFile == true)
            cache.remove(owner, document.id)
            assertEquals(OfflineMediaAvailability.NOT_CACHED, cache.availability(owner, document))
        }
    }

    @Test
    fun `one hundred mixed recent files never exceed automatic budget`() {
        var clock = 1_000L
        val sample = document("recent-000", ALICE, ALICE)
        val cache = cache({ clock }, sample.ciphertextSize * 5)
        cache.setRecentCachingEnabled(ALICE, true)
        val documents = mutableListOf<SafetyDocument>()
        repeat(100) { index ->
            val document = document("recent-${index.toString().padStart(3, '0')}", ALICE, ALICE,
                mime = listOf("application/pdf", "image/jpeg", "video/mp4", "audio/mpeg")[index % 4])
            documents += document
            cache.retainRecentDownloaded(ALICE, document, ciphertext(document))
            clock++
            assertTrue(documents.count {
                cache.availability(ALICE, it) == OfflineMediaAvailability.RECENT_AVAILABLE
            } <= 5)
        }
        assertTrue(documents.takeLast(5).all {
            cache.availability(ALICE, it) == OfflineMediaAvailability.RECENT_AVAILABLE
        })
    }

    private fun cache(
        clock: () -> Long = { 1_000L },
        limit: Long = OfflineMediaCache.AUTOMATIC_CACHE_LIMIT_BYTES,
    ) = OfflineMediaCache(
        FakeJsonVault(), temporary.newFolder(), clock,
        currentDeviceId = { DEVICE }, automaticCacheLimitBytes = limit,
    )

    private fun document(id: String, owner: String, viewer: String, mime: String = "application/pdf"): SafetyDocument {
        val bytes = "encrypted-$id".toByteArray()
        return SafetyDocument(id, owner, "", "$id.bin", mime, listOf(owner, viewer).distinct(),
            objectKey = "objects/$id", wrappedKeys = mapOf(viewer to "wrapped"),
            ciphertextSize = bytes.size.toLong(), ciphertextSha256 = sha256(bytes), encryptionVersion = 2)
    }

    private fun ciphertext(document: SafetyDocument): File = temporary.newFile().apply {
        writeBytes("encrypted-${document.id}".toByteArray())
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private class FakeJsonVault : JsonVault {
        private var root = JSONObject()
        override fun read() = JSONObject(root.toString())
        override fun update(change: (JSONObject) -> Unit) { root = read().also(change) }
        override fun clear() { root = JSONObject() }
    }

    private companion object {
        const val ALICE = "alice"; const val BOB = "bob"; const val DEVICE = "device"
    }
}
