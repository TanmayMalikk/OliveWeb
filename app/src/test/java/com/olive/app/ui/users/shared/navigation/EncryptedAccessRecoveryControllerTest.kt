package com.olive.app.ui.users.shared.navigation

import com.olive.app.data.messaging.ChatAccessRecoveryResult
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.RecoveredChatAccess
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.security.AccountKeysetEnvelope
import com.olive.app.security.AccountRecoveryKey
import com.olive.app.ui.users.user.documents.MediaAccessRecoveryResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EncryptedAccessRecoveryControllerTest {
    @Test
    fun oneConfirmationUnlocksOnceAndRestoresMediaAndChatIndependently() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val recoveryKey = AccountRecoveryKey.generate()
            val keyset = AccountKeysetEnvelope.create("owner", recoveryKey).unlock("owner", recoveryKey)
            val media = document("media")
            val conversation = conversation("chat")
            var unlocks = 0
            var mediaRestores = 0
            var chatRestores = 0
            val rememberedMedia = mutableListOf<String>()
            val rememberedChats = mutableListOf<String>()
            val controller = EncryptedAccessRecoveryController(
                scope = scope,
                deviceId = { "device" },
                unlock = { unlocks++; keyset },
                findChats = { items, _ -> items },
                restoreMedia = { items, _, _ ->
                    mediaRestores++
                    MediaAccessRecoveryResult(items, emptySet(), emptySet())
                },
                restoreChats = { items, _, _ ->
                    chatRestores++
                    ChatAccessRecoveryResult(
                        items.map { RecoveredChatAccess(it, "wrapped-room-key") },
                        emptySet(),
                    )
                },
                rememberMedia = { _, items -> rememberedMedia += items.map(SafetyDocument::id) },
                rememberChat = { _, access, _, _ -> rememberedChats += access.conversation.id },
                now = { 100L },
            )

            controller.evaluate("owner", listOf(media), listOf(conversation))
            assertTrue(controller.state.promptVisible)
            assertEquals(1, controller.state.mediaCount)
            assertEquals(1, controller.state.chatCount)

            controller.restore()

            assertEquals(1, unlocks)
            assertEquals(1, mediaRestores)
            assertEquals(1, chatRestores)
            assertEquals(listOf("media"), rememberedMedia)
            assertEquals(listOf("chat"), rememberedChats)
            assertEquals(EncryptedAccessRecoveryNotice.SUCCESS, controller.state.notice)
            assertFalse(controller.state.restoring)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun dismissalDoesNotAuthenticateAndPartialFailuresPreserveSuccessfulRecovery() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val recoveryKey = AccountRecoveryKey.generate()
            val keyset = AccountKeysetEnvelope.create("owner", recoveryKey).unlock("owner", recoveryKey)
            var unlocks = 0
            var remembered = 0
            val controller = EncryptedAccessRecoveryController(
                scope = scope,
                deviceId = { "device" },
                unlock = { unlocks++; keyset },
                findChats = { items, _ -> items },
                restoreMedia = { items, _, _ ->
                    MediaAccessRecoveryResult(items, setOf("bad-media"), emptySet())
                },
                restoreChats = { items, _, _ ->
                    ChatAccessRecoveryResult(
                        listOf(RecoveredChatAccess(items.first(), "wrapped-room-key")),
                        setOf("bad-chat"),
                    )
                },
                rememberMedia = { _, items -> remembered += items.size },
                rememberChat = { _, _, _, _ -> remembered++ },
            )
            val media = document("good-media")
            val chat = conversation("good-chat")

            controller.evaluate("owner", listOf(media), listOf(chat))
            controller.dismiss()
            controller.restore()
            assertEquals(0, unlocks)

            controller.evaluate("owner", listOf(media.copy(ciphertextSha256 = "changed")), listOf(chat))
            controller.restore()
            assertEquals(1, unlocks)
            assertEquals(2, remembered)
            assertEquals(EncryptedAccessRecoveryNotice.PARTIAL, controller.state.notice)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun skipsPromptWhenRecoveryIsNotEnrolledOnAccount() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var unlocks = 0
            val recoveryKey = AccountRecoveryKey.generate()
            val keyset = AccountKeysetEnvelope.create("owner", recoveryKey).unlock("owner", recoveryKey)
            val controller = EncryptedAccessRecoveryController(
                scope = scope,
                deviceId = { "device" },
                unlock = { unlocks++; keyset },
                findChats = { items, _ -> items },
                restoreMedia = { items, _, _ -> MediaAccessRecoveryResult(items, emptySet(), emptySet()) },
                restoreChats = { _, _, _ -> ChatAccessRecoveryResult(emptyList(), emptySet()) },
                rememberMedia = { _, _ -> },
                rememberChat = { _, _, _, _ -> },
                canRestore = { false },
            )

            controller.evaluate("owner", listOf(document("media")), listOf(conversation("chat")))
            assertFalse(controller.state.promptVisible)

            controller.restore()
            assertEquals(0, unlocks)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun restoreFailureSurfacesSpecificNoticeMessageAndClearsIt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = EncryptedAccessRecoveryController(
                scope = scope,
                deviceId = { "device" },
                unlock = { throw IllegalStateException("Not enrolled") },
                findChats = { items, _ -> items },
                restoreMedia = { items, _, _ -> MediaAccessRecoveryResult(items, emptySet(), emptySet()) },
                restoreChats = { _, _, _ -> ChatAccessRecoveryResult(emptyList(), emptySet()) },
                rememberMedia = { _, _ -> },
                rememberChat = { _, _, _, _ -> },
                failureMessage = { "Password recovery is not set up" },
            )

            controller.evaluate("owner", listOf(document("media")), listOf(conversation("chat")))
            assertTrue(controller.state.promptVisible)

            controller.restore()
            assertEquals(EncryptedAccessRecoveryNotice.FAILED, controller.state.notice)
            assertEquals("Password recovery is not set up", controller.state.noticeMessage)

            controller.clearNotice()
            assertNull(controller.state.notice)
            assertNull(controller.state.noticeMessage)
        } finally {
            scope.cancel()
        }
    }

    private fun document(id: String) = SafetyDocument(
        id = id,
        ownerUid = "owner",
        storagePath = "",
        name = "Encrypted document",
        mimeType = "application/octet-stream",
        allowedUsers = listOf("owner"),
        objectKey = "objects/$id",
        ciphertextSha256 = id,
    )

    private fun conversation(id: String) = ChatConversation(
        id = id,
        friendshipId = "pair-$id",
        participantIds = listOf("owner", "friend"),
        participantUsernames = mapOf("owner" to "owner_fixture", "friend" to "friend_fixture"),
    )
}
