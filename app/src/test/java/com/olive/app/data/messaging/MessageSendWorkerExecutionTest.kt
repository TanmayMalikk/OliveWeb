package com.olive.app.data.messaging

import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.media.MediaRepository
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.data.safety.SafetyDocumentRepository
import com.olive.app.security.EncryptionUtils
import com.olive.app.support.TestAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class MessageSendWorkerExecutionTest {
    private val owner = AccountIdentity("owner", "owner_fixture")
    private val room = "room"
    private val file = SafetyDocument("media", owner.uid, "fixture", "Attachment", "text/plain", listOf(owner.uid))
    private val conversation = ChatConversation(room, "group", listOf(owner.uid, "friend"),
        mapOf(owner.uid to owner.username, "friend" to "friend_fixture"))
    private fun outgoing(attachment: Boolean) = prepareOutgoingMessage(room, owner, "Synthetic content",
        EncryptionUtils.generateSymmetricKey()).copy(attachment = if (attachment) OutgoingAttachment(file.id) else null)

    private fun fixture(message: OutgoingMessage, signedIn: String = owner.uid,
        run: suspend (MessageSendWorker, MessageOutboxStore, SecureChatRepository, MessagingRepository, SafetyDocumentRepository) -> Unit
    ) = runBlocking<Unit> {
        val app = RuntimeEnvironment.getApplication()
        val executor = Executors.newSingleThreadExecutor()
        TestAndroidKeyStore.install().use {
            val store = MessageOutboxStore(app)
            store.write(message)
            val parameters = mock(WorkerParameters::class.java)
            `when`(parameters.backgroundExecutor).thenReturn(executor)
            `when`(parameters.taskExecutor).thenReturn(mock(TaskExecutor::class.java))
            `when`(parameters.inputData).thenReturn(Data.Builder().putString("ownerUid", owner.uid).putString("conversationId", room).build())
            val auth = mock(FirebaseAuth::class.java)
            val user = mock(FirebaseUser::class.java)
            `when`(user.uid).thenReturn(signedIn)
            `when`(auth.currentUser).thenReturn(user)
            try {
                mockStatic(FirebaseAuth::class.java).use { firebase ->
                    firebase.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
                    mockConstruction(SecureChatRepository::class.java).use { chats ->
                        mockConstruction(MessagingRepository::class.java).use { index ->
                            mockConstruction(MediaRepository::class.java).use {
                                mockConstruction(SafetyDocumentRepository::class.java).use { documents ->
                                    val worker = MessageSendWorker(app, parameters)
                                    run(worker, store, chats.constructed().single(), index.constructed().single(),
                                        documents.constructed().single())
                                }
                            }
                        }
                    }
                }
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun attachmentDeliveryChecksCurrentMembershipAndPassesOnlyOtherRecipients() {
        val message = outgoing(true)
        fixture(message) { worker, store, chat, index, documents ->
            `when`(index.getConversation(room)).thenReturn(conversation)
            `when`(documents.getFor(owner.uid, file.id)).thenReturn(file)
            assertEquals(Result.success(), worker.doWork())
            val delivered = mockingDetails(documents).invocations.single { it.method.name == "shareInChat" }
            assertEquals(file, delivered.arguments[0])
            assertEquals(message.id, (delivered.arguments[1] as OutgoingMessage).id)
            assertEquals(mapOf("friend" to "friend_fixture"), delivered.arguments[2])
            assertEquals(MessageDeliveryStatus.SENT, store.get(message.id)?.status)
            verifyNoInteractions(chat)
        }
    }

    @Test
    fun revokedMembershipBlocksAnAttachmentAndKeepsFailureForTheSender() {
        val message = outgoing(true)
        fixture(message) { worker, store, _, index, documents ->
            `when`(index.getConversation(room)).thenReturn(conversation.copy(participantIds = listOf("friend")))
            assertEquals(Result.failure(), worker.doWork())
            assertEquals(MessageDeliveryStatus.FAILED_PERMANENT, store.get(message.id)?.status)
            assertEquals("PERMISSION_DENIED", store.get(message.id)?.failureCode)
            verifyNoInteractions(documents)
        }
    }

    @Test
    fun transientTextDeliveryFailureQueuesARetryWithoutLosingCiphertext() {
        val message = outgoing(false)
        fixture(message) { worker, store, chat, _, _ ->
            doAnswer { throw IOException("Synthetic network failure") }.`when`(chat).deliver(any<OutgoingMessage>() ?: message)
            assertEquals(Result.retry(), worker.doWork())
            assertEquals(message.ciphertext, store.get(message.id)?.ciphertext)
            assertEquals(1, store.get(message.id)?.attemptCount)
        }
    }

    @Test
    fun anOldAccountsWorkerNeverDeliversItsMessageUnderTheNewAccount() {
        val message = outgoing(false)
        fixture(message, signedIn = "another-account") { worker, store, chat, index, documents ->
            worker.doWork()
            assertEquals(MessageDeliveryStatus.FAILED_RETRYABLE, store.get(message.id)?.status)
            assertEquals("ACCOUNT_UNAVAILABLE", store.get(message.id)?.failureCode)
            verifyNoInteractions(chat, index, documents)
        }
    }
}
