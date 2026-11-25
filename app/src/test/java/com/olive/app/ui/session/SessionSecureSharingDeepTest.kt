package com.olive.app.ui.session

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.account.UserProfile
import com.olive.app.data.location.LocationSharingDataSource
import com.olive.app.data.media.MediaTicketDataSource
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.ChatMessage
import com.olive.app.data.messaging.DirectoryUser
import com.olive.app.data.messaging.Friend
import com.olive.app.data.safety.PrivateEntriesSnapshot
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.data.safety.SafetyDocumentDataSource
import com.olive.app.data.safety.SharedPrivateEntry
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.UnlockedAccountKeyset
import com.olive.app.support.SessionRuntimeFixture
import com.olive.app.ui.users.user.documents.MediaTransferController
import com.olive.app.ui.users.user.location.LocationMapController
import java.io.File
import java.security.KeyPairGenerator
import java.security.Security
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SessionSecureSharingDeepTest {
    @Before
    fun cleanTestEnvironment() {
        Security.removeProvider("AndroidKeyStore")
        Mockito.framework().clearInlineMocks()
    }

    private val peerKeyBase64: String by lazy {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        Base64.getEncoder().encodeToString(pair.public.encoded)
    }

    private fun supportConversation() = ChatConversation(
        id = "conv-sup",
        friendshipId = "",
        participantIds = listOf("owner", "peer1"),
        participantUsernames = mapOf("owner" to "fixture_owner", "peer1" to "peeruser"),
        type = "SUPPORT",
        ownerUid = "owner",
        staffUid = "peer1",
    )

    private fun ensureWorkManager(app: Application) {
        if (runCatching { WorkManager.getInstance(app) }.isFailure) {
            WorkManager.initialize(app, Configuration.Builder().build())
        }
    }

    @Test
    fun sharePrivateEntryAndSafetyPlanAndDocumentsCoverSuccessAndErrorBranches() = runBlocking {
        val docsDataSource = mock(SafetyDocumentDataSource::class.java)
        SessionRuntimeFixture(documents = docsDataSource).use { f ->
            ensureWorkManager(f.application)
            val sharing = f.runtime.secureSharing
            val roomKey = EncryptionUtils.generateSymmetricKey()
            f.runtime.state.activeConversation = supportConversation()
            f.runtime.state.profile = UserProfile(username = "fixture_owner", role = AccountRole.USER)

            `when`(f.messaging.prepareTicketParticipantNames("conv-sup")).thenReturn(
                mapOf("owner" to "fixture_owner", "peer1" to "peeruser"),
            )
            `when`(f.messaging.searchUsers("peeruser", "owner")).thenReturn(
                listOf(DirectoryUser("peer1", "peeruser", peerKeyBase64)),
            )
            val dummyConv = supportConversation()
            val dummyId = com.olive.app.data.account.AccountIdentity("owner", "fixture_owner")
            `when`(f.secureChat.ensureRoomKey(any() ?: dummyConv, eq("owner") ?: "owner", anyMap())).thenReturn(roomKey)

            val note = PrivateEntry(
                id = "note-1",
                kind = PrivateEntryKind.Note,
                title = "",
                body = "Safe details",
                attachmentIds = listOf("att-1"),
            )
            sharing.share(note)
            f.await { !f.runtime.state.messagingBusy }
            assertEquals("private_entry_shared", f.runtime.state.message)
            verify(docsDataSource).ensureChatMediaAccess(eq("att-1") ?: "att-1", eq("owner") ?: "owner", anyMap())

            // Incident failure branch
            Mockito.doThrow(RuntimeException("network")).`when`(f.secureChat).send(
                anyString(),
                any() ?: dummyId,
                anyString(),
                any() ?: roomKey,
            )
            val incident = PrivateEntry(id = "inc-1", kind = PrivateEntryKind.Incident, title = "Event", body = "Log")
            sharing.share(incident)
            f.await { !f.runtime.state.messagingBusy }
            assertEquals("unable_to_share_private_entry", f.runtime.state.message)

            // Share safety plan in SUPPORT conversation (fails on mocked chat.send)
            sharing.shareSafetyPlan()
            f.await { !f.runtime.state.messagingBusy }
            assertEquals("safety_sync_error", f.runtime.state.message)

            // Share durable document (queues message work without throwing)
            val doc = SafetyDocument(
                id = "doc-1",
                ownerUid = "owner",
                storagePath = "users/owner/media/doc-1",
                name = "evidence.jpg",
                mimeType = "image/jpeg",
                allowedUsers = listOf("owner"),
                objectKey = "users/owner/media/doc-1",
            )
            sharing.share(doc)
            f.await { !f.runtime.state.messagingBusy }

            // sendVoice branches: valid queueing + closed conversation early return
            val ticketSource = mock(MediaTicketDataSource::class.java)
            val transfers = MediaTransferController(f.application, f.scope, ticketSource)
            val audioFile = File.createTempFile("voice", ".m4a").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            assertTrue(sharing.sendVoice(audioFile, "1500,123", transfers))
            f.await { sharing.preparingVoiceConversationId == null }

            f.runtime.state.activeConversation = supportConversation().copy(closedAt = 123L)
            assertFalse(sharing.sendVoice(audioFile, "1000,1", transfers))
        }
    }

    @Test
    fun openRevokeObserveShareWithFriendsAndSyncConversationCoverRemainingMethods() = runBlocking {
        val docsDataSource = mock(SafetyDocumentDataSource::class.java)
        SessionRuntimeFixture(documents = docsDataSource).use { f ->
            ensureWorkManager(f.application)
            val sharing = f.runtime.secureSharing
            val sharedEntry = PrivateEntry(id = "shared-1", title = "Plan Note", body = "Body", attachmentIds = listOf("att-2"))
            val unlocked = { unlocker: suspend (String) -> UnlockedAccountKeyset? -> unlocker }
            `when`(
                f.secureDocuments.loadAndDecryptEntry(
                    eq("shared-1") ?: "shared-1",
                    eq("owner") ?: "owner",
                    any<suspend (String) -> UnlockedAccountKeyset?>() ?: unlocked { null },
                ),
            ).thenReturn(sharedEntry)
            sharing.open("shared-1")
            f.await { !f.runtime.state.busy }
            assertEquals(sharedEntry, f.runtime.state.viewedSharedEntry)
            sharing.close()
            assertNull(f.runtime.state.viewedSharedEntry)

            `when`(
                f.secureDocuments.loadAndDecryptEntry(
                    eq("bad-1") ?: "bad-1",
                    eq("owner") ?: "owner",
                    any<suspend (String) -> UnlockedAccountKeyset?>() ?: unlocked { null },
                ),
            ).thenThrow(IllegalStateException("denied"))
            sharing.open("bad-1")
            f.await { !f.runtime.state.busy }
            assertEquals("unable_to_open_shared_entry", f.runtime.state.message)

            // revokeIfShared on both SHARED_MEDIA and SHARED_NOTE
            val conv = supportConversation()
            sharing.revokeIfShared(
                ChatMessage(id = "m1", senderUid = "owner", senderUsername = "fixture_owner", body = "SHARED_MEDIA:doc-9:photo.jpg", sentAt = 1000L),
                conv,
                "owner",
            )
            verify(docsDataSource).revokeAccess("doc-9", "owner", "peer1")
            sharing.revokeIfShared(
                ChatMessage(id = "m2", senderUid = "owner", senderUsername = "fixture_owner", body = "SHARED_NOTE:note-9:Title", sentAt = 1000L),
                conv,
                "owner",
            )
            verify(f.secureDocuments).revokeAccessToEntry("note-9", "peer1")

            // observeSharedEntries + stopSharedEntriesObservation
            var onSnapshotUpdate: ((PrivateEntriesSnapshot) -> Unit)? = null
            doAnswer { inv ->
                onSnapshotUpdate = inv.getArgument(1)
                com.google.firebase.firestore.ListenerRegistration {}
            }.`when`(f.secureDocuments).observeUserSharedEntries(
                eq("owner") ?: "owner",
                any<(PrivateEntriesSnapshot) -> Unit>() ?: {},
                any<(String) -> Unit>() ?: {},
            )
            sharing.observeSharedEntries()
            val snap = PrivateEntriesSnapshot(
                sharedWithMe = listOf(SharedPrivateEntry(sharedEntry, "peer1")),
                ownedSharedRecipients = mapOf("shared-1" to listOf("peer1")),
            )
            onSnapshotUpdate?.invoke(snap)
            assertEquals(1, f.runtime.state.sharedPrivateEntries.size)
            assertEquals(listOf("peer1"), f.runtime.state.sharedEntryRecipients["shared-1"])
            sharing.stopSharedEntriesObservation()

            // shareWithFriends: selected peer & empty revoke-all
            `when`(f.messaging.searchUsers("peeruser", "owner")).thenReturn(
                listOf(DirectoryUser("peer1", "peeruser", peerKeyBase64)),
            )
            var completed = 0
            val friend = Friend("f-1", "peer1", "peeruser")
            f.runtime.state.friends = listOf(friend)
            sharing.shareWithFriends(sharedEntry, listOf(friend)) { completed++ }
            f.await { !f.runtime.state.busy }
            assertEquals(1, completed)

            sharing.shareWithFriends(sharedEntry, emptyList()) { completed++ }
            f.await { !f.runtime.state.busy }
            verify(f.secureDocuments).revokeAllRecipients("shared-1", "owner")

            // syncEditedSharedEntry & syncSharedItemsForConversation & shareLiveLocation
            f.runtime.state.sharedEntryRecipients = mapOf("shared-1" to listOf("peer1"))
            sharing.syncEditedSharedEntry(sharedEntry)
            f.await { true }

            f.runtime.state.privateEntries = listOf(sharedEntry)
            `when`(f.messaging.prepareTicketParticipantNames("conv-sup")).thenReturn(
                mapOf("owner" to "fixture_owner", "peer1" to "peeruser"),
            )
            sharing.syncSharedItemsForConversation(
                conv,
                listOf(
                    ChatMessage(id = "cm1", senderUid = "owner", senderUsername = "fixture_owner", body = "SHARED_NOTE:shared-1:Plan Note", sentAt = 1000L),
                    ChatMessage(id = "cm2", senderUid = "owner", senderUsername = "fixture_owner", body = "SHARED_MEDIA:m-1:img.jpg", sentAt = 2000L),
                ),
            )
            sharing.invalidateConversationSharedSync()

            // shareLiveLocation permission denied branch
            val locationController = LocationMapController(
                f.application,
                f.scope,
                mock(LocationSharingDataSource::class.java),
            )
            f.runtime.state.profile = UserProfile(username = "fixture_owner", role = AccountRole.USER)
            sharing.shareLiveLocation(60_000L, locationController) { false }
            f.await { f.runtime.state.message != null }
            assertNotNull(f.runtime.state.message)
        }
    }
}
