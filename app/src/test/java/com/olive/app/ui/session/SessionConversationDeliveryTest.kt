package com.olive.app.ui.session

import androidx.work.impl.WorkManagerImpl
import com.olive.app.data.messaging.*
import com.olive.app.security.*
import com.olive.app.support.SessionRuntimeFixture
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
class SessionConversationDeliveryTest {
    private class Fixture: AutoCloseable {
        val f=SessionRuntimeFixture()
        val r=f.runtime
        val chat=ChatConversation("chat","pair",listOf("owner","friend"),mapOf("owner" to "fixture_owner","friend" to "friend_fixture"))
        val key=EncryptionUtils.generateSymmetricKey()
        lateinit var messages:(List<ChatMessage>)->Unit
        lateinit var failure:(String)->Unit
        lateinit var ciphertext:(List<EncryptedChatMessage>)->Unit
        var closed=0
        init { WorkManagerImpl.setDelegate(mock(WorkManagerImpl::class.java)) }
        suspend fun open() {
            // This is an existing conversation. Device enrollment and first-time key setup have their own tests.
            `when`(f.messaging.searchUsers("friend_fixture", "owner")).thenReturn(emptyList())
            `when`(f.secureChat.existingRoomKey(chat.id, "owner")).thenReturn(key)
            val wrapped = KeyManager.wrapKey(key, KeyManager.getOrCreatePublicKey())
            `when`(f.secureChat.wrappedRoomKey(chat.id, "owner")).thenReturn(wrapped)
            doAnswer { call ->
                messages=call.getArgument(2);failure=call.getArgument(3);ciphertext=call.getArgument(4)
                ChatSubscription { closed++ }
            }.`when`(f.secureChat).observeMessages(eq("chat") ?: "",any<SecretKey>() ?: key,any<(List<ChatMessage>)->Unit>() ?: {},any<(String)->Unit>() ?: {},any<(List<EncryptedChatMessage>)->Unit>())
            r.conversationActions.openConversation(chat)
            f.await { !r.state.messagingBusy }
            assertNotNull("Room key should be available: ${r.state.message}",r.activeRoomKey)
        }
        override fun close() { r.conversationActions.closeConversation();f.close();WorkManagerImpl.setDelegate(null) }
    }

    @Test
    fun openingClearsPreviousMessagesThenLiveUpdatesReplaceThemWithoutReopening() = runBlocking<Unit> {
        Fixture().use { t ->
            t.r.state.chatMessages=listOf(ChatMessage("old","friend","fixture","Stale",1))
            t.open()
            t.f.await { t.r.state.chatMessages.isEmpty() }
            val remote=ChatMessage("received","friend","friend_fixture","Synthetic message",10)
            t.messages(listOf(remote))
            t.f.await { t.r.state.chatMessages==listOf(remote) }
            t.failure("Synthetic transient failure")
            assertNull(t.r.state.message)
            t.r.conversationActions.dismissChatQuickExitHint()
            assertFalse(t.r.state.chatQuickExitHintVisible)
            t.r.conversationActions.closeConversation()
            assertTrue(t.r.state.chatMessages.isEmpty())
            assertNull(t.r.activeRoomKey)
            assertEquals(1,t.closed)
            t.messages(listOf(remote))
            assertTrue(t.r.state.chatMessages.isEmpty())
        }
    }

    @Test
    fun sentQueueRowsDisappearWhenServerAcknowledgementArrivesAndDoNotDuplicateMessages() = runBlocking<Unit> {
        Fixture().use { t ->
            t.open()
            var sent=false
            t.r.conversationActions.sendMessage("Synthetic queued text") { sent=true }
            t.f.await { !t.r.state.messagingBusy && sent }
            val queued=t.r.messageOutbox.all("owner").single()
            assertEquals("Synthetic queued text",EncryptionUtils.decryptText(queued.ciphertext,t.key))
            t.r.messageOutbox.update(queued.id) { it.copy(status=MessageDeliveryStatus.SENT) }
            val server=ChatMessage(queued.id,"owner","fixture_owner","Synthetic queued text",queued.clientSentAt)
            t.messages(listOf(server))
            t.f.await { t.r.messageOutbox.all("owner").isEmpty() && t.r.state.chatMessages==listOf(server) }
            assertEquals(MessageDeliveryStatus.SENT,t.r.state.chatMessages.single().deliveryStatus)
        }
    }

    @Test
    fun retryAndDiscardOperateOnlyOnTheOwnersFailedQueueHead() = runBlocking<Unit> {
        Fixture().use { t ->
            t.open()
            val outgoing=prepareOutgoingMessage("chat",com.olive.app.data.account.AccountIdentity("owner","fixture_owner"),"Synthetic",t.key)
                .copy(status=MessageDeliveryStatus.FAILED_RETRYABLE,attemptCount=3,failureCode="NETWORK")
            t.r.messageOutbox.write(outgoing)
            val ui=ChatMessage(outgoing.id,"owner","fixture_owner","Synthetic",outgoing.clientSentAt,deliveryStatus=outgoing.status)
            t.r.conversationActions.retryMessage(ui)
            t.f.await { t.r.messageOutbox.get(outgoing.id)?.status==MessageDeliveryStatus.QUEUED }
            assertEquals(0,t.r.messageOutbox.get(outgoing.id)?.attemptCount)
            t.r.messageOutbox.update(outgoing.id) { it.copy(status=MessageDeliveryStatus.FAILED_PERMANENT) }
            t.r.conversationActions.discardFailedMessage(ui.copy(deliveryStatus=MessageDeliveryStatus.FAILED_PERMANENT))
            t.f.await { t.r.messageOutbox.get(outgoing.id)==null }
        }
    }

    @Test
    fun nonMembersCannotOpenConversationAndAnEmptyFailedLoadShowsAnError() = runBlocking<Unit> {
        Fixture().use { t ->
            t.r.conversationActions.openConversation(t.chat.copy(participantIds=listOf("other","friend")))
            assertNull(t.r.state.activeConversation)
            t.open()
            t.failure("Access denied")
            assertFalse(t.r.conversationActions.loadingConversation)
            assertTrue(t.r.state.message.orEmpty().contains("unable_to_load_this_conversation"))
        }
    }

    @Test
    fun temporaryOfflineFailureOpensCachedMessagesButExplicitAccessDenialDoesNot() = runBlocking<Unit> {
        Fixture().use { t ->
            t.open()
            t.r.conversationActions.closeConversation()
            val cached = ChatMessage("cached", "friend", "friend_fixture", "Synthetic cached message", 10)
            doReturn(listOf(cached)).`when`(t.f.secureChat).decryptMessages(anyList(), any<SecretKey>() ?: t.key)
            doAnswer { throw java.io.IOException("Synthetic connection failure") }.`when`(t.f.secureChat)
                .wrappedRoomKey(t.chat.id, "owner")
            t.r.conversationActions.openConversation(t.chat)
            t.f.await { !t.r.state.messagingBusy && t.r.state.chatMessages == listOf(cached) }
            assertArrayEquals(t.key.encoded, t.r.activeRoomKey?.encoded)

            t.r.conversationActions.closeConversation()
            doAnswer {
                throw com.google.firebase.firestore.FirebaseFirestoreException(
                    "Synthetic access revoked", com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED,
                )
            }.`when`(t.f.secureChat).wrappedRoomKey(t.chat.id, "owner")
            t.r.conversationActions.openConversation(t.chat)
            t.f.await { !t.r.state.messagingBusy }
            assertNull(t.r.activeRoomKey)
            assertTrue(t.r.state.chatMessages.isEmpty())
            assertEquals("unable_to_open_secure_conversation", t.r.state.message)
        }
    }

    @Test
    fun cacheRevalidationUsesTheNewWrappedKeyAndRestartsMessageObservation() = runBlocking<Unit> {
        Fixture().use { t ->
            t.open()
            val nextKey = EncryptionUtils.generateSymmetricKey()
            val wrapped = KeyManager.wrapKey(nextKey, KeyManager.getOrCreatePublicKey())
            `when`(t.f.secureChat.wrappedRoomKey(t.chat.id, "owner")).thenReturn(wrapped)
            t.ciphertext(emptyList())
            t.f.await { t.closed == 1 && t.r.activeRoomKey?.encoded?.contentEquals(nextKey.encoded) == true }
            val cached = t.r.chatCache.open("owner", t.chat.id, KeyManager.deviceId(), System.currentTimeMillis())
            assertEquals(wrapped, cached.wrappedRoomKey)
            assertFalse(t.r.conversationActions.loadingConversation)
        }
    }

    @Test
    fun newRoomEnvelopeRetriesFailedOpenAndOnlyNewDeviceFingerprintsTriggerApproval() = runBlocking<Unit> {
        Fixture().use { t ->
            lateinit var onKey: (String, String) -> Unit
            lateinit var onDevices: (String, String) -> Unit
            var closed = false
            doAnswer { call ->
                onKey = call.getArgument(2)
                onDevices = call.getArgument(3)
                ChatSubscription { closed = true }
            }.`when`(t.f.secureChat).observeKeyChanges(
                eq(t.chat) ?: t.chat, eq("owner") ?: "owner",
                any<(String, String) -> Unit>() ?: { _, _ -> },
                any<(String, String) -> Unit>() ?: { _, _ -> },
            )
            t.open()
            t.r.conversationActions.closeConversation()
            assertTrue(closed)
            doReturn(null).`when`(t.f.secureChat).wrappedRoomKey(t.chat.id, "owner")
            t.r.chatCache.remove("owner", t.chat.id)
            t.r.conversationActions.openConversation(t.chat)
            t.f.await { !t.r.state.messagingBusy }
            assertNull(t.r.activeRoomKey)
            onDevices("friend/devices", "initial")

            val wrapped = KeyManager.wrapKey(t.key, KeyManager.getOrCreatePublicKey())
            doReturn(wrapped).`when`(t.f.secureChat).wrappedRoomKey(t.chat.id, "owner")
            onKey("chat/deviceKeys", wrapped)
            t.f.await { !t.r.state.messagingBusy && t.r.activeRoomKey != null }
            clearInvocations(t.f.secureChat)
            onKey("chat/deviceKeys", wrapped)
            onDevices("friend/devices", "initial")
            onDevices("friend/devices", "changed")
            t.f.await {
                mockingDetails(t.f.secureChat).invocations.any { it.method.name == "shareWithRegisteredDevices" }
            }
            onDevices("friend/devices", "changed")
            verify(t.f.secureChat).shareWithRegisteredDevices(t.chat, t.key)
            verify(t.f.secureChat, never()).existingRoomKey(t.chat.id, "owner")

            t.r.conversationActions.closeConversation()
            onDevices("friend/devices", "after-close")
            onKey("chat/deviceKeys", "after-close")
            assertNull(t.r.state.activeConversation)
            verify(t.f.secureChat).shareWithRegisteredDevices(t.chat, t.key)
        }
    }

    @Test
    fun archivingAChatAndRestoringAClosedTicketAreSeparatePerAccountPreferences() = runBlocking<Unit> {
        Fixture().use { t ->
            val ticket = t.chat.copy(id = "ticket", type = "SUPPORT", closedAt = 10L)
            t.r.conversationActions.setArchived(t.chat, true)
            t.f.await { t.chat.id in t.r.store.archivedConversationIds("owner") }
            t.r.conversationActions.setArchived(ticket, false)
            t.f.await { ticket.id in t.r.store.restoredClosedConversationIds("owner") }
            assertEquals(setOf(t.chat.id), t.r.state.archivedConversationIds)
            assertEquals(setOf(ticket.id), t.r.state.restoredClosedConversationIds)
            assertTrue(t.r.store.archivedConversationIds("other").isEmpty())
            assertTrue(t.r.store.restoredClosedConversationIds("other").isEmpty())
            t.r.conversationActions.setArchived(ticket, true)
            t.r.conversationActions.setArchived(t.chat, false)
            t.f.await {
                t.r.store.restoredClosedConversationIds("owner").isEmpty() &&
                    t.r.store.archivedConversationIds("owner").isEmpty()
            }
        }
    }

    @Test
    fun initialDirectorySnapshotDuringFailedRecoveryDoesNotTriggerRetryLoopOrBiometrics() = runBlocking<Unit> {
        Fixture().use { t ->
            lateinit var onKey: (String, String) -> Unit
            doAnswer { call ->
                onKey = call.getArgument(2)
                ChatSubscription {}
            }.`when`(t.f.secureChat).observeKeyChanges(
                eq(t.chat) ?: t.chat, eq("owner") ?: "owner",
                any<(String, String) -> Unit>() ?: { _, _ -> },
                any<(String, String) -> Unit>() ?: { _, _ -> },
            )
            `when`(t.f.messaging.searchUsers("friend_fixture", "owner")).thenReturn(emptyList())
            `when`(t.f.secureChat.existingRoomKey(t.chat.id, "owner")).thenReturn(null)
            `when`(t.f.secureChat.wrappedRoomKey(t.chat.id, "owner")).thenReturn(null)
            `when`(t.f.secureChat.hasAccountRoomKey(t.chat.id, "owner")).thenAnswer {
                onKey("messageDirectory/owner_fixture", "owner-public-key")
                onKey("messageDirectory/friend_fixture", "friend-public-key")
                false
            }

            t.r.conversationActions.openConversation(t.chat)
            t.f.await { !t.r.state.messagingBusy }

            assertNull(t.r.activeRoomKey)
            assertNull(t.r.state.deviceLockRequest)
            assertEquals("unable_to_open_secure_conversation", t.r.state.message)
            verify(t.f.secureChat, times(1)).hasAccountRoomKey(t.chat.id, "owner")
        }
    }

    @Test
    fun openingConversationRecoversRoomKeyWithoutBiometricsAndCachesWrappedAccessForReopen() = runBlocking<Unit> {
        Fixture().use { t ->
            val ark = AccountRecoveryKey.generate()
            val envelope = AccountKeysetEnvelope.create("owner", ark)
            val sampleKeyset = envelope.unlock("owner", ark)
            t.r.settings.protectDeviceRecoveryKey("owner", ark, envelope, t.r.epoch)

            val wrapped = KeyManager.wrapKey(t.key, KeyManager.getOrCreatePublicKey())
            var cachedAccessSavedOnServer = false

            `when`(t.f.messaging.searchUsers("friend_fixture", "owner")).thenReturn(emptyList())
            `when`(t.f.secureChat.existingRoomKey(t.chat.id, "owner")).thenReturn(null)
            `when`(t.f.secureChat.wrappedRoomKey(t.chat.id, "owner")).thenAnswer {
                if (cachedAccessSavedOnServer) wrapped else null
            }
            `when`(t.f.secureChat.hasAccountRoomKey(t.chat.id, "owner")).thenReturn(true)
            `when`(
                t.f.secureChat.existingRoomKey(
                    eq(t.chat.id) ?: t.chat.id,
                    eq("owner") ?: "owner",
                    any<UnlockedAccountKeyset>() ?: sampleKeyset,
                )
            ).thenReturn(t.key)
            `when`(t.f.secureChat.persistCurrentDeviceRoomKey(t.chat, "owner", t.key)).thenAnswer {
                cachedAccessSavedOnServer = true
                wrapped
            }
            doAnswer { call ->
                t.messages = call.getArgument(2); t.failure = call.getArgument(3); t.ciphertext = call.getArgument(4)
                ChatSubscription { t.closed++ }
            }.`when`(t.f.secureChat).observeMessages(
                eq("chat") ?: "",
                any<SecretKey>() ?: t.key,
                any<(List<ChatMessage>) -> Unit>() ?: {},
                any<(String) -> Unit>() ?: {},
                any<(List<EncryptedChatMessage>) -> Unit>(),
            )

            t.r.conversationActions.openConversation(t.chat)
            t.f.await { !t.r.state.messagingBusy }

            assertNull(t.r.state.deviceLockRequest)
            assertArrayEquals(t.key.encoded, t.r.activeRoomKey?.encoded)
            val cached = t.r.chatCache.open("owner", t.chat.id, KeyManager.deviceId(), System.currentTimeMillis())
            assertEquals(wrapped, cached.wrappedRoomKey)

            t.r.conversationActions.closeConversation()
            doAnswer { throw java.io.IOException("Temporary network hiccup") }
                .`when`(t.f.secureChat).wrappedRoomKey(t.chat.id, "owner")
            clearInvocations(t.f.secureChat)

            t.r.conversationActions.openConversation(t.chat)
            t.f.await { !t.r.state.messagingBusy }

            assertNull(t.r.state.deviceLockRequest)
            assertArrayEquals(t.key.encoded, t.r.activeRoomKey?.encoded)
            verify(t.f.secureChat, never()).hasAccountRoomKey(t.chat.id, "owner")
        }
    }

}
