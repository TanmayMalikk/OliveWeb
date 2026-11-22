package com.olive.app.data.media

import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.firebase.appcheck.AppCheckToken
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GetTokenResult
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableReference
import com.google.firebase.functions.HttpsCallableResult
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.AccountKeysetWrappedKey
import com.olive.app.security.KeyManager
import com.olive.app.support.TestAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class MediaRepositorySdkTest {
    private val functions = mock(FirebaseFunctions::class.java)
    private val auth = mock(FirebaseAuth::class.java)
    private val check = mock(FirebaseAppCheck::class.java)
    private val user = mock(FirebaseUser::class.java)
    private val repository = MediaRepository(functions, auth, check)
    private val requests = mutableListOf<Pair<String, Map<String, Any>>>()
    private val responses = mutableMapOf<String, Any>()
    private val endpoint = "https://test.r2.cloudflarestorage.com/encrypted/object"
    private val record = mapOf(
        "mediaId" to "media", "ownerUid" to "owner", "objectKey" to "encrypted/object",
        "encryptedMetadata" to "locked-details", "wrappedKeys" to mapOf("owner" to "key"),
        "deviceWrappedKeys" to mapOf("owner--phone" to "device"), "allowedUsers" to listOf("owner"),
        "accountWrappedKeys" to mapOf("owner" to accountWrapper().toMap()),
        "ciphertextSize" to 500L, "ciphertextSha256" to "hash", "encryptionVersion" to 2, "createdAt" to 123L,
    )

    init {
        `when`(auth.currentUser).thenReturn(user)
        `when`(user.getIdToken(false)).thenReturn(Tasks.forResult(mock(GetTokenResult::class.java)))
        `when`(check.getAppCheckToken(false)).thenReturn(Tasks.forResult(mock(AppCheckToken::class.java)))
        `when`(functions.getHttpsCallable(anyString())).thenAnswer { invocation ->
            val name = invocation.getArgument<String>(0)
            val callable = mock(HttpsCallableReference::class.java)
            `when`(callable.call(anyMap<String, Any>())).thenAnswer { request ->
                val input = request.getArgument<Map<String, Any>>(0)
                requests.add(name to input)
                val result = HttpsCallableResult::class.java.getConstructor(Any::class.java)
                    .newInstance(responses[name] ?: record)
                Tasks.forResult(result)
            }
            callable
        }
    }

    @Test
    fun transferTicketsValidateCapabilitiesAndPreservePartNumbers() = runBlocking<Unit> {
        responses["mediaUploadPartTicket"] = mapOf("url" to endpoint, "headers" to mapOf("Content-Type" to "application/octet-stream"), "expiresAt" to 1000L)
        val ticket = repository.uploadTicket("job", 2)
        assertEquals(2, ticket.partNumber)
        assertEquals(endpoint, ticket.url)
        assertEquals(mapOf("Content-Type" to "application/octet-stream"), ticket.headers)
        assertEquals("mediaUploadPartTicket" to mapOf("jobId" to "job", "partNumber" to 2), requests.single())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.uploadTicket("job", 0) } }

        responses["mediaDownloadTicket"] = mapOf("url" to endpoint, "expiresAt" to 1000L)
        assertEquals("media", repository.downloadTicket("media").mediaId)
        for (url in listOf("http://test.r2.cloudflarestorage.com/file", "https://evil.invalid/file",
            "https://user:password@test.r2.cloudflarestorage.com/file")) {
            responses["mediaDownloadTicket"] = mapOf("url" to url, "expiresAt" to 1000L)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.downloadTicket("media") } }
        }
    }

    @Test
    fun uploadCompletionAndAccessUpdatesUseDistinctGrantSources() = runBlocking<Unit> {
        val complete = repository.completeUpload("job", listOf(MediaCompletedPart(2, "etag-two"), MediaCompletedPart(1, "etag-one")))
        assertEquals(CloudMediaRecord("media", "owner", "encrypted/object", "locked-details",
            mapOf("owner" to "key"), listOf("owner"), 500, "hash", 2, 123,
            mapOf("owner--phone" to "device"), mapOf("owner" to accountWrapper())), complete)
        assertEquals(listOf(mapOf("partNumber" to 2, "etag" to "etag-two"), mapOf("partNumber" to 1, "etag" to "etag-one")),
            requests.single().second["parts"])
        val keys = mapOf("owner" to "owner-key", "friend" to "friend-key")
        val accountKeys = keys.mapValues { accountWrapper() }
        repository.updateAccess("media", keys, emptyMap(), accountKeys, listOf("friend"), emptyList())
        assertEquals(listOf("friend"), requests.last().second["grantIndependentRecipients"])
        assertEquals(emptyList<String>(), requests.last().second["revokeIndependentRecipients"])
        repository.updatePlanAccess("media", keys, emptyMap(), accountKeys)
        assertEquals(true, requests.last().second["forPlan"])
        assertFalse(requests.last().second.containsKey("grantIndependentRecipients"))
        repository.updateDeviceKeys("media", mapOf("friend--web" to "wrapped"))
        assertEquals("mediaUpdateDeviceKeys", requests.last().first)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.updateAccess("media", emptyMap(), emptyMap(), emptyMap()) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repository.updateDeviceKeys("media", emptyMap()) } }
    }

    @Test
    fun evidenceAbortAndDeleteRemainSeparateAuthenticatedOperations() = runBlocking<Unit> {
        repository.updateEvidence("media", true)
        repository.abortUpload("job")
        repository.deleteMedia("media")
        assertEquals(listOf(
            "mediaUpdateEvidence" to mapOf("mediaId" to "media", "evidence" to true),
            "mediaAbortUpload" to mapOf("jobId" to "job"),
            "mediaDelete" to mapOf("mediaId" to "media"),
        ), requests)
        verify(user, times(3)).getIdToken(false)
        verify(check, times(3)).getAppCheckToken(false)
    }

    @Test
    fun appCheckTransientAndDeniedFailuresAreReportedBeforeCallingFirebase() = runBlocking<Unit> {
        `when`(check.getAppCheckToken(false)).thenReturn(Tasks.forException(IOException("Offline")))
        assertThrows(MediaDeviceVerificationRetryableException::class.java) { runBlocking { repository.deleteMedia("media") } }
        `when`(check.getAppCheckToken(false)).thenReturn(Tasks.forException(IllegalStateException("Unknown installation")))
        assertThrows(MediaDeviceVerificationException::class.java) { runBlocking { repository.deleteMedia("media") } }
        assertTrue(requests.isEmpty())
        `when`(auth.currentUser).thenReturn(null)
        assertThrows(IllegalStateException::class.java) { runBlocking { repository.deleteMedia("media") } }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun emergencyUploadLinksTheReportBeforeCreatingTheEncryptedTransfer() = runBlocking<Unit> {
        TestAndroidKeyStore.install().use {
            val key = EncryptionUtils.generateSymmetricKey()
            val emergency = "01234567-89ab-cdef-0123-456789abcdef"
            val metadata = EncryptionUtils.encryptText(
                JSONObject().put("name", "emergency-report-$emergency.pdf").toString(), key,
            )
            val wrapped = KeyManager.wrapKey(key, KeyManager.getOrCreatePublicKey())
            val source = PreparedEncryptedMedia("local-only", 500, "hash", metadata,
                mapOf("owner" to wrapped), listOf("owner"), KeyManager.CURRENT_KEY_WRAP_VERSION,
                MediaKind.DOCUMENT, OcrReview.NOT_APPLICABLE,
                accountWrappedKeys = mapOf("owner" to accountWrapper()))
            responses["mediaCreateUpload"] = mapOf("mediaId" to "media", "objectKey" to "server/object", "mode" to "SINGLE", "partSize" to 500)
            val session = repository.createUpload(MediaUploadJob("job", "owner", source))
            assertEquals("server/object", session.objectKey)
            assertEquals(MediaUploadMode.SINGLE, session.mode)
            assertEquals("emergencyAttachUpload" to mapOf("id" to emergency, "jobId" to "job"), requests.first())
            assertEquals("mediaCreateUpload", requests.last().first)
            assertFalse(requests.last().second.containsKey("filePath"))
            assertFalse(requests.last().second.containsKey("recoveryKeyToSeal"))
            assertEquals(accountWrapper().toMap(),
                (requests.last().second["accountWrappedKeys"] as Map<*, *>)["owner"])

            val outgoing = com.olive.app.data.messaging.OutgoingMessage("msg-1", "owner", "conv-1", "fixture_owner", "cipher", 123L)
            repository.shareInChat(
                "media",
                outgoing,
                listOf("peer"),
                mapOf("peer" to "w"),
                mapOf("peer--d" to "dw"),
                mapOf("owner" to accountWrapper(), "peer" to accountWrapper()),
            )
            assertEquals("mediaShareInChat", requests.last().first)
            assertEquals("conv-1", requests.last().second["conversationId"])

            val revokedCallable = mock(HttpsCallableReference::class.java)
            val denied = mock(com.google.firebase.functions.FirebaseFunctionsException::class.java).also {
                `when`(it.code).thenReturn(com.google.firebase.functions.FirebaseFunctionsException.Code.PERMISSION_DENIED)
            }
            val internal = mock(com.google.firebase.functions.FirebaseFunctionsException::class.java).also {
                `when`(it.code).thenReturn(com.google.firebase.functions.FirebaseFunctionsException.Code.INTERNAL)
            }
            `when`(revokedCallable.call(anyMap<String, Any>()))
                .thenReturn(Tasks.forException(denied))
                .thenReturn(Tasks.forException(internal))
            `when`(functions.getHttpsCallable("mediaDownloadTicket")).thenReturn(revokedCallable)
            val revoked = assertThrows(MediaAccessException::class.java) { runBlocking { repository.downloadTicket("media") } }
            assertEquals(MediaAccessError.ACCESS_REVOKED, revoked.reason)
            assertThrows(com.google.firebase.functions.FirebaseFunctionsException::class.java) {
                runBlocking { repository.downloadTicket("media") }
            }
        }
    }

    private fun accountWrapper() = AccountKeysetWrappedKey(
        keyId = "123e4567-e89b-42d3-a456-426614174000",
        epoch = 1,
        algorithm = "RSA-OAEP-256",
        wrappedKey = "opaque-wrapper",
    )
}
