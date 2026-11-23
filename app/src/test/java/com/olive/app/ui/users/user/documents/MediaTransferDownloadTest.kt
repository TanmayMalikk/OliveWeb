package com.olive.app.ui.users.user.documents

import android.net.*
import com.olive.app.data.media.*
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.security.*
import com.olive.app.support.TestAndroidKeyStore
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.*
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class MediaTransferDownloadTest {
    private class Fixture : AutoCloseable {
        val keys = TestAndroidKeyStore.install()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val app = spy(RuntimeEnvironment.getApplication())
        val repository = mock(MediaTicketDataSource::class.java)
        val cache = mock(OfflineMediaCache::class.java)
        val store = mock(MediaTransferStore::class.java)
        var online = true
        var status = 200
        var bytes = byteArrayOf()
        var requests = 0
        val manager = mock(ConnectivityManager::class.java)
        val key = EncryptionUtils.generateSymmetricKey()
        val plaintext = "Synthetic confidential document".toByteArray()
        val encrypted: File
        val document: SafetyDocument
        val controller: MediaTransferController
        init {
            doReturn(app).`when`(app).applicationContext
            doReturn(manager).`when`(app).getSystemService(ConnectivityManager::class.java)
            val network = mock(Network::class.java)
            val capabilities = mock(NetworkCapabilities::class.java)
            `when`(manager.activeNetwork).thenReturn(network)
            `when`(manager.getNetworkCapabilities(network)).thenAnswer { if(online) capabilities else null }
            `when`(capabilities.hasCapability(anyInt())).thenReturn(true)
            encrypted = File.createTempFile("synthetic-", ".olive", app.cacheDir)
            encrypted.outputStream().use { MediaCipher.encrypt(ByteArrayInputStream(plaintext), it, key) }
            bytes = encrypted.readBytes()
            document = SafetyDocument("doc", "owner", "", "report.pdf", "application/pdf", listOf("owner"),
                objectKey = "objects/doc", wrappedKeys = mapOf("owner" to KeyManager.wrapKey(key, KeyManager.getOrCreatePublicKey())),
                ciphertextSize = bytes.size.toLong(), ciphertextSha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
                encryptionVersion = KeyManager.CURRENT_KEY_WRAP_VERSION)
            `when`(cache.availability("owner", document)).thenReturn(OfflineMediaAvailability.NOT_CACHED)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                    .body(bytes.toResponseBody()).build()
            }.build()
            controller = MediaTransferController(app, scope, repository, store, cache, client)
        }
        suspend fun ticket() { `when`(repository.downloadTicket("doc")).thenReturn(MediaDownloadTicket("doc", "https://media.example.test/doc", mapOf("x-fixture" to "test"), Long.MAX_VALUE)) }
        override fun close() { scope.cancel(); encrypted.delete(); keys.close() }
    }

    @Test
    fun remoteDownloadVerifiesCiphertextAndOnlyReturnsAuthenticatedPlaintext() = runBlocking<Unit> {
        Fixture().use { f ->
            f.ticket()
            val opened = f.controller.downloadFile(f.document, "owner")
            assertArrayEquals(f.plaintext, opened.readBytes())
            assertTrue(opened.name.endsWith(".pdf"))
            assertEquals(MediaTransferState.Idle, f.controller.state)
            assertEquals(1, f.requests)
            verify(f.cache).retainRecentDownloaded(eq("owner") ?: "", eq(f.document) ?: f.document, any<File>() ?: f.encrypted)
            opened.delete()
        }
    }

    @Test
    fun verifiedOfflineCopyOpensWithoutNetworkOrAnyCloudRequest() = runBlocking<Unit> {
        Fixture().use { f ->
            f.online = false
            `when`(f.cache.verifiedCiphertext("owner", f.document)).thenReturn(f.encrypted)
            val opened = f.controller.downloadFile(f.document, "owner")
            assertArrayEquals(f.plaintext, opened.readBytes())
            assertEquals(0, f.requests)
            verifyNoInteractions(f.repository)
            opened.delete()
        }
    }

    @Test
    fun freshDeviceOpensMediaUsingOnlyItsRecoveredAccountWrapper() = runBlocking<Unit> {
        Fixture().use { f ->
            val recoveryKey = AccountRecoveryKey.generate()
            val envelope = AccountKeysetEnvelope.create("owner", recoveryKey)
            val recoveredKeyset = envelope.unlock("owner", recoveryKey)
            val accountWrapper = AccountKeysetWrappedKey(
                envelope.keyId,
                envelope.epoch,
                "RSA-OAEP-256",
                KeyManager.wrapForAccountKeyset(f.key, recoveredKeyset.publicKey),
            )
            val freshDeviceDocument = f.document.copy(
                wrappedKeys = emptyMap(),
                deviceWrappedKeys = emptyMap(),
                accountRecoveryReady = true,
                accountWrappedKeys = mapOf("owner" to accountWrapper),
            )
            `when`(f.cache.availability("owner", freshDeviceDocument))
                .thenReturn(OfflineMediaAvailability.NOT_CACHED)
            f.ticket()
            val controller = MediaTransferController(
                f.app,
                f.scope,
                f.repository,
                f.store,
                f.cache,
                OkHttpClient.Builder().addInterceptor { chain ->
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                        .message("fixture").body(f.bytes.toResponseBody()).build()
                }.build(),
                accountKeysetUnlocker = { recoveredKeyset },
            )

            val opened = controller.downloadFile(freshDeviceDocument, "owner")

            assertArrayEquals(f.plaintext, opened.readBytes())
            opened.delete()
        }
    }

    @Test
    fun expiredOfflineAccessRequiresRevalidationAndRevocationRemovesCachedBytes() = runBlocking<Unit> {
        Fixture().use { f ->
            `when`(f.cache.availability("owner", f.document)).thenReturn(OfflineMediaAvailability.REVALIDATION_REQUIRED)
            f.online = false
            val expired = assertThrows(MediaAccessException::class.java) { runBlocking { f.controller.downloadFile(f.document, "owner") } }
            assertEquals(MediaAccessError.LEASE_EXPIRED, expired.reason)
            f.online = true
            `when`(f.repository.downloadTicket("doc")).thenAnswer { throw MediaAccessException(MediaAccessError.ACCESS_REVOKED) }
            assertThrows(MediaAccessException::class.java) { runBlocking { f.controller.downloadFile(f.document, "owner") } }
            verify(f.cache).remove("owner", "doc")
            assertTrue(f.controller.state is MediaTransferState.Failed)
        }
    }

    @Test
    fun corruptionAndTruncationNeverLeavePartialPlaintextPreviews() = runBlocking<Unit> {
        Fixture().use { f ->
            f.ticket()
            val folder = File(f.app.cacheDir, "opened-documents")
            val before = folder.listFiles().orEmpty().map { it.name }.toSet()
            f.bytes[5] = (f.bytes[5].toInt() xor 1).toByte()
            assertThrows(Exception::class.java) { runBlocking { f.controller.downloadFile(f.document, "owner") } }
            f.bytes = f.bytes.copyOf(3)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { f.controller.downloadFile(f.document, "owner") } }
            assertEquals(before, folder.listFiles().orEmpty().map { it.name }.toSet())
            assertTrue(File(f.app.cacheDir, "encrypted-media").listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun pinningRecentAndExpiredCopiesPreservesLeaseChecksAndRemovalUpdatesUi() = runBlocking<Unit> {
        Fixture().use { f ->
            `when`(f.cache.availability("owner", f.document)).thenReturn(OfflineMediaAvailability.RECENT_AVAILABLE)
            f.controller.makeAvailableOffline(f.document, "owner")
            verify(f.cache).pin("owner", "doc")
            clearInvocations(f.cache)
            f.ticket()
            `when`(f.cache.availability("owner", f.document)).thenReturn(OfflineMediaAvailability.RECENT_REVALIDATION_REQUIRED)
            f.controller.makeAvailableOffline(f.document, "owner")
            verify(f.repository).downloadTicket("doc")
            verify(f.cache).markRevalidated("owner", "doc")
            verify(f.cache).pin("owner", "doc")
            f.controller.removeOfflineCopy(f.document, "owner")
            assertEquals(OfflineMediaAvailability.NOT_CACHED, f.controller.offlineCopies["doc"])
        }
    }

    @Test
    fun savingUncachedCiphertextUsesPromotionBeforeDownloadingAndReconcilesOnlyConfirmedSnapshots() = runBlocking<Unit> {
        Fixture().use { f ->
            `when`(f.cache.promoteUploadCandidate("owner", f.document)).thenReturn(true)
            f.controller.makeAvailableOffline(f.document, "owner")
            assertEquals(0, f.requests)
            `when`(f.cache.promoteUploadCandidate("owner", f.document)).thenReturn(false)
            f.ticket()
            f.controller.makeAvailableOffline(f.document, "owner")
            assertEquals(1, f.requests)
            f.controller.onDocumentSnapshot("owner", listOf(f.document), true)
            withTimeout(5000) { while(mockingDetails(f.cache).invocations.none { it.method.name == "reconcile" }) delay(10) }
            verify(f.cache).reconcile("owner", listOf(f.document))
            f.controller.clearState()
            assertTrue(f.controller.offlineCopies.isEmpty())
            assertTrue(f.controller.uploads.isEmpty())
        }
    }

    @Test
    fun missingDeviceAndAccountEnvelopesNeverExposePlaintext() = runBlocking<Unit> {
        Fixture().use { f ->
            f.ticket()
            val missing = f.document.copy(wrappedKeys = emptyMap())
            `when`(f.cache.availability("owner", missing)).thenReturn(OfflineMediaAvailability.NOT_CACHED)
            val failure = assertThrows(MediaAccessException::class.java) { runBlocking { f.controller.downloadFile(missing, "owner") } }
            assertEquals(MediaAccessError.ACCOUNT_WRAPPER_MISSING, failure.reason)
            val recoverable = missing.copy(accountRecoveryReady = true)
            `when`(f.cache.availability("owner", recoverable)).thenReturn(OfflineMediaAvailability.NOT_CACHED)
            val unavailable = assertThrows(MediaAccessException::class.java) {
                runBlocking { f.controller.downloadFile(recoverable, "owner") }
            }
            assertEquals(MediaAccessError.ACCOUNT_WRAPPER_MISSING, unavailable.reason)
        }
    }
}
