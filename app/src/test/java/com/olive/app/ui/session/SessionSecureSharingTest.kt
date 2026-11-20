package com.olive.app.ui.session

import com.olive.app.data.account.*
import com.olive.app.data.messaging.*
import com.olive.app.data.safety.SafetyDocumentDataSource
import com.olive.app.data.safety.SafetyDocument
import androidx.work.impl.WorkManagerImpl
import com.olive.app.security.*
import com.olive.app.support.SessionRuntimeFixture
import com.olive.app.ui.users.user.documents.MediaTransferController
import com.olive.app.ui.users.user.location.LocationMapConfiguration
import com.olive.app.ui.users.user.location.LocationMapController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.security.KeyPairGenerator
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class SessionSecureSharingTest {
    private val chat = ChatConversation("chat", "pair", listOf("owner", "friend"),
        mapOf("owner" to "fixture_owner", "friend" to "friend_fixture"))

    private suspend fun prepare(f: SessionRuntimeFixture): javax.crypto.SecretKey {
        val peer = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        `when`(f.messaging.searchUsers("friend_fixture", "owner")).thenReturn(listOf(
            DirectoryUser("friend", "friend_fixture", Base64.getEncoder().encodeToString(peer.public.encoded))))
        val key = EncryptionUtils.generateSymmetricKey()
        `when`(f.secureChat.ensureRoomKey(any<ChatConversation>() ?: chat, eq("owner") ?: "owner", anyMap())).thenReturn(key)
        f.runtime.state.activeConversation = chat
        return key
    }

    @Test
    fun roomSetupResolvesEveryPeerAndFallbackNeverReplacesAnEstablishedKey() = runBlocking<Unit> {
        SessionRuntimeFixture().use { f ->
            val key = prepare(f)
            assertArrayEquals(key.encoded, f.runtime.secureSharing.roomKey(chat).encoded)
            `when`(f.messaging.searchUsers("friend_fixture", "owner")).thenReturn(emptyList())
            `when`(f.secureChat.existingRoomKey("chat", "owner")).thenReturn(key)
            assertArrayEquals(key.encoded, f.runtime.secureSharing.roomKey(chat).encoded)
            `when`(f.secureChat.existingRoomKey("chat", "owner")).thenReturn(null)
            assertThrows(IllegalStateException::class.java) { runBlocking { f.runtime.secureSharing.roomKey(chat) } }
        }
    }

    @Test
    fun privateEntriesUseTheCorrectTimelineTypeAndFailuresLeaveTheOriginalEntryUnchanged() = runBlocking<Unit> {
        SessionRuntimeFixture().use { f ->
            prepare(f)
            val entry = PrivateEntry(id = "entry", kind = PrivateEntryKind.Incident, title = "", body = "Synthetic incident")
            f.runtime.secureSharing.share(entry)
            f.await { !f.runtime.state.messagingBusy }
            val saved = mockingDetails(f.secureDocuments).invocations.single { it.method.name == "saveMultiRecipientEntry" }
            assertEquals(entry, saved.arguments[0])
            assertEquals(setOf("owner", "friend"), (saved.arguments[2] as Map<*, *>).keys)
            val sent = mockingDetails(f.secureChat).invocations.single { it.method.name == "send" }
            assertEquals("SHARED_INCIDENT:entry:untitled_note", sent.arguments[2])
            assertEquals("private_entry_shared", f.runtime.state.message)
            `when`(f.messaging.searchUsers("friend_fixture", "owner")).thenReturn(emptyList())
            f.runtime.secureSharing.share(entry.copy(kind = PrivateEntryKind.Note))
            f.await { !f.runtime.state.messagingBusy }
            assertEquals("unable_to_share_private_entry", f.runtime.state.message)
            assertEquals("Synthetic incident", entry.body)
        }
    }

    @Test
    fun sharedEntryPreviewClearsOnCloseAndOpeningErrorsKeepPrivateContentHidden() = runBlocking<Unit> {
        SessionRuntimeFixture().use { f ->
            val entry = PrivateEntry(id = "entry", title = "Shared", body = "Synthetic private body")
            `when`(f.secureDocuments.loadAndDecryptEntry("entry", "owner")).thenReturn(entry)
            f.runtime.secureSharing.open("entry")
            f.await { !f.runtime.state.busy }
            assertEquals(entry, f.runtime.state.viewedSharedEntry)
            f.runtime.secureSharing.close()
            assertNull(f.runtime.state.viewedSharedEntry)
            `when`(f.secureDocuments.loadAndDecryptEntry("entry", "owner")).thenAnswer { error("Revoked") }
            f.runtime.secureSharing.open("entry")
            f.await { !f.runtime.state.busy }
            assertNull(f.runtime.state.viewedSharedEntry)
            assertEquals("unable_to_open_shared_entry", f.runtime.state.message)
        }
    }

    @Test
    fun unsendingAnAttachmentRevokesOnlyOtherParticipantsAndNormalTextRevokesNothing() = runBlocking<Unit> {
        val documents = mock(SafetyDocumentDataSource::class.java)
        SessionRuntimeFixture(documents = documents).use { f ->
            fun message(body: String) = ChatMessage("message", "owner", "fixture_owner", body, 1)
            f.runtime.secureSharing.revokeIfShared(message("SHARED_MEDIA:media:Report"), chat, "owner")
            verify(documents).revokeAccess("media", "owner", "friend")
            f.runtime.secureSharing.revokeIfShared(message("SHARED_NOTE:entry:Note"), chat, "owner")
            verify(f.secureDocuments).revokeAccessToEntry("entry", "friend")
            clearInvocations(documents, f.secureDocuments)
            f.runtime.secureSharing.revokeIfShared(message("Normal text"), chat, "owner")
            verifyNoInteractions(documents, f.secureDocuments)
        }
    }

    @Test
    fun safetyPlanSharingIsRestrictedToUserTicketsAndUsesTheSameRoomEncryption() = runBlocking<Unit> {
        SessionRuntimeFixture().use { f ->
            val key = prepare(f)
            f.runtime.secureSharing.shareSafetyPlan()
            verify(f.secureChat, never()).send(anyString(), any<AccountIdentity>() ?: AccountIdentity("", ""), anyString(), any<javax.crypto.SecretKey>() ?: key)
            val ticket = chat.copy(type = "SUPPORT")
            f.runtime.state.activeConversation = ticket
            `when`(f.messaging.prepareTicketParticipantNames("chat")).thenReturn(chat.participantUsernames)
            f.runtime.secureSharing.shareSafetyPlan()
            f.await { !f.runtime.state.messagingBusy }
            verify(f.secureChat).send("chat", AccountIdentity("owner", "fixture_owner"), "SHARED_SAFETY_PLAN:owner:Safety plan", key)
            assertEquals("safety_plan_shared_in_chat", f.runtime.state.message)
        }
    }

    @Test
    fun queuedVoiceUsesTheOpenedRoomKeyDeletesItsTemporaryFileAndRejectsClosedRooms() = runBlocking<Unit> {
        SessionRuntimeFixture().use { f ->
            val transfers = mock(MediaTransferController::class.java)
            val key = prepare(f)
            f.runtime.activeRoomKey = key
            val file = File(f.application.cacheDir, "synthetic-voice.m4a").apply { writeBytes(byteArrayOf(1,2,3)) }
            assertTrue(f.runtime.secureSharing.sendVoice(file, "duration=2", transfers))
            f.await { f.runtime.secureSharing.preparingVoiceConversationId == null }
            assertFalse(file.exists())
            val queued = mockingDetails(transfers).invocations.single { it.method.name == "queueVoiceMessage" }
            val voice = queued.arguments[2] as com.olive.app.data.media.PendingVoiceMessage
            assertEquals("chat", voice.conversationId)
            f.runtime.state.activeConversation = chat.copy(closedAt = 20)
            assertFalse(f.runtime.secureSharing.sendVoice(file, "", transfers))
        }
    }

    @Test
    fun ownedPublishedMediaQueuesAnEncryptedChatAttachmentWithoutRenewingRecipientAccessEarly() = runBlocking<Unit> {
        val documents = mock(SafetyDocumentDataSource::class.java)
        SessionRuntimeFixture(documents = documents).use { fixture ->
            val runtime = fixture.runtime
            val key = EncryptionUtils.generateSymmetricKey()
            runtime.activeRoomKey = key
            val document = SafetyDocument(
                "attachment", "owner", "", "Synthetic document", "text/plain", listOf("owner"),
                objectKey = "fixture/attachment",
            )
            val work = mock(WorkManagerImpl::class.java)
            WorkManagerImpl.setDelegate(work)
            try {
                for (conversation in listOf(
                    chat.copy(type = "SUPPORT", id = "ticket"),
                    chat.copy(id = "group", type = "GROUP", participantIds = listOf("owner", "friend", "additional")),
                )) {
                    runtime.state.activeConversation = conversation
                    runtime.secureSharing.share(document.copy(ownerUid = "friend"))
                    runtime.secureSharing.share(document.copy(objectKey = ""))
                    assertTrue(runtime.messageOutbox.all("owner").none { it.conversationId == conversation.id })
                    runtime.secureSharing.share(document)
                    fixture.await { !runtime.state.messagingBusy }
                    val result = runtime.messageOutbox.all("owner").single { it.conversationId == conversation.id }
                    assertEquals(OutgoingAttachment(document.id), result.attachment)
                    assertEquals("SHARED_MEDIA:attachment:Synthetic document", EncryptionUtils.decryptText(result.ciphertext, key))
                }
                verifyNoInteractions(documents, fixture.messaging)
                assertTrue(mockingDetails(work).invocations.any { it.method.name == "enqueueUniqueWork" })
            } finally {
                WorkManagerImpl.setDelegate(null)
            }
        }
    }

    @Test
    fun chatLocationIsQueuedOnlyAfterSharingStartsForAContactInThatChat() = runBlocking<Unit> {
        SessionRuntimeFixture().use { fixture ->
            val runtime = fixture.runtime
            val controller = mock(LocationMapController::class.java)
            val key = EncryptionUtils.generateSymmetricKey()
            val friend = Friend("chat", "friend", "friend_fixture")
            runtime.state.activeConversation = chat
            runtime.activeRoomKey = key
            runtime.state.profile = UserProfile("fixture_owner", "Synthetic owner", "", "", "2000-01-01", "English", "", "Edmonton", "")
            runtime.state.friends = emptyList()
            runtime.secureSharing.shareLiveLocation(60_000, controller) { false }
            verifyNoInteractions(controller)

            runtime.state.friends = listOf(friend, Friend("other-chat", "other", "other_fixture"))
            val work = mock(WorkManagerImpl::class.java)
            WorkManagerImpl.setDelegate(work)
            try {
                runtime.secureSharing.shareLiveLocation(60_000, controller) { true }
                val arguments = mockingDetails(controller).invocations.single().arguments
                val config = arguments[0] as LocationMapConfiguration
                assertEquals("owner", config.identity.uid)
                assertEquals(listOf(friend), arguments[1])
                assertEquals(60_000L, arguments[2])
                assertTrue(runtime.messageOutbox.all("owner").isEmpty())

                @Suppress("UNCHECKED_CAST")
                val failed = arguments[6] as (String) -> Unit
                failed("Synthetic location error")
                assertEquals("Synthetic location error", runtime.state.message)
                assertTrue(runtime.messageOutbox.all("owner").isEmpty())

                @Suppress("UNCHECKED_CAST")
                val started = arguments[7] as (Long) -> Unit
                started(1_800_000_000_000)
                fixture.await { !runtime.state.messagingBusy }
                val queued = runtime.messageOutbox.all("owner").single()
                assertEquals(chat.id, queued.conversationId)
                assertEquals("SHARED_LOCATION:1800000000000", EncryptionUtils.decryptText(queued.ciphertext, key))
            } finally {
                WorkManagerImpl.setDelegate(null)
            }
        }
    }

}
