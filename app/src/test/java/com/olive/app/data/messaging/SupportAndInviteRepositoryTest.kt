package com.olive.app.data.messaging

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableReference
import com.google.firebase.functions.HttpsCallableResult
import com.olive.app.support.FirestoreTestDriver
import com.olive.app.support.TestAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SupportAndInviteRepositoryTest {
    private val functions = mock(FirebaseFunctions::class.java)

    private fun reply(name: String, payload: Any): HttpsCallableReference {
        val endpoint = mock(HttpsCallableReference::class.java)
        val result = HttpsCallableResult::class.java.getConstructor(Any::class.java).newInstance(payload)
        `when`(endpoint.call(anyMap<String, Any>())).thenReturn(Tasks.forResult(result))
        `when`(functions.getHttpsCallable(name)).thenReturn(endpoint)
        return endpoint
    }

    @Test
    fun supportRequestsAndCancellationPreserveTheExistingTicketId() = runBlocking<Unit> {
        val tickets = reply("staffSupportRequest", mapOf("ticket" to mapOf(
            "requested" to true, "staffUid" to "staff", "conversationId" to "ticket",
        )))
        val support = SupportRepository(functions)
        assertEquals(StaffSupportTicket(true, "staff", "ticket"), support.requestHelp())
        verify(tickets).call(mapOf("action" to "request", "conversationId" to ""))

        reply("staffSupportRequest", mapOf("ticket" to mapOf("conversationId" to "ticket"))).also {
            assertEquals(StaffSupportTicket(false, "", "ticket"), support.cancelRequest("ticket"))
            verify(it).call(mapOf("action" to "stop", "conversationId" to "ticket"))
        }
    }

    @Test
    fun supportEscalationMembershipAndClosureTargetTheSelectedTicket() = runBlocking {
        val escalate = reply("safetySupportAdmin", emptyMap<String, Any>())
        val add = reply("conversationAddParticipant", emptyMap<String, Any>())
        val remove = reply("conversationRemoveParticipant", emptyMap<String, Any>())
        val close = reply("staffSupportClose", emptyMap<String, Any>())
        val request = reply("conversationRequestStaff", mapOf("conversationId" to "new-ticket"))
        val support = SupportRepository(functions)

        assertEquals("new-ticket", support.requestStaffForConversation("group"))
        support.requestAdmin("new-ticket")
        support.addParticipant("new-ticket", "friend")
        support.removeParticipant("new-ticket", "friend")
        support.close("new-ticket", planCompleted = true)

        verify(request).call(mapOf("conversationId" to "group"))
        verify(escalate).call(mapOf("conversationId" to "new-ticket"))
        val membership = mapOf("conversationId" to "new-ticket", "participantUid" to "friend")
        verify(add).call(membership)
        verify(remove).call(membership)
        verify(close).call(mapOf("conversationId" to "new-ticket", "planCompleted" to true))

        reply("conversationRequestStaff", emptyMap<String, Any>())
        val absent = runCatching { support.requestStaffForConversation("group") }.exceptionOrNull()
        assertEquals("The support chat was not created.", absent?.message)
        reply("staffSupportRequest", "unexpected")
        val invalid = runCatching { support.requestHelp() }.exceptionOrNull()
        assertEquals("Invalid response", invalid?.message)
    }

    @Test
    fun refreshedInvitesAreAccountScopedAndClaimsRegisterTheLocalMessagingKey() = runBlocking<Unit> {
        val owner = mock(FirebaseUser::class.java)
        val auth = mock(FirebaseAuth::class.java)
        val firestore = FirestoreTestDriver()
        `when`(owner.uid).thenReturn("owner")
        `when`(owner.email).thenReturn("owner_name@user.olive.invalid")
        `when`(auth.currentUser).thenReturn(owner)
        val invite = FriendInvite("5BGbsH13", "https://yourolivebranch.ca/get/?invite=5BGbsH13", 48L)
        val create = reply("friendInviteCreate", mapOf(
            "code" to invite.code, "link" to invite.link, "expiresAt" to invite.expiresAt,
        ))
        val claim = reply("friendInviteClaim", mapOf("username" to "friend"))
        TestAndroidKeyStore.install().use {
            mockStatic(FirebaseAuth::class.java).use { authSdk ->
                authSdk.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
                mockStatic(FirebaseFirestore::class.java).use { dbSdk ->
                    dbSdk.`when`<FirebaseFirestore> { FirebaseFirestore.getInstance() }.thenReturn(firestore.db)
                    val repository = FriendInviteRepository("owner") { functions }
                    assertEquals(invite, repository.create(refresh = true))
                    verify(create).call(mapOf("refresh" to true))
                    assertEquals("friend", repository.claim("  ${invite.code}  "))
                    verify(claim).call(mapOf("code" to invite.code))
                    assertEquals("owner", firestore.records["messageDirectory/owner_name"]?.get("uid"))
                    assertTrue(firestore.records.keys.any { it.startsWith("messageDirectory/owner_name/devices/") })
                    `when`(owner.uid).thenReturn("new-owner")
                    val changed = runCatching { repository.create() }.exceptionOrNull()
                    assertEquals("Sign in again to reconnect this account.", changed?.message)
                    verifyNoMoreInteractions(create)
                }
            }
        }
    }

    @Test
    fun inviteWatcherClosesAndInvalidatesChangedOrClaimedCodes() {
        val firestore = FirestoreTestDriver()
        val path = "friendInviteOwners/owner"
        var unavailable = 0
        mockStatic(FirebaseFirestore::class.java).use { sdk ->
            sdk.`when`<FirebaseFirestore> { FirebaseFirestore.getInstance() }.thenReturn(firestore.db)
            FriendInviteRepository().observeUnavailable("code") { unavailable++ }.close()
            assertTrue(firestore.listeners.isEmpty())
            val listener = FriendInviteRepository("owner").observeUnavailable("code") { unavailable++ }
            firestore.records[path] = mapOf("code" to "code")
            firestore.emit(path)
            assertEquals(0, unavailable)
            firestore.records[path] = mapOf("code" to "code", "claimedBy" to "friend")
            firestore.emit(path)
            firestore.records[path] = mapOf("code" to "replacement")
            firestore.emit(path)
            assertEquals(2, unavailable)
            listener.close()
            assertTrue(firestore.listeners.single().closed)
        }
    }
}
