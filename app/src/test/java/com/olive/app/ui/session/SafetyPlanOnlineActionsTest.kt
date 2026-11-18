package com.olive.app.ui.session

import android.net.*
import androidx.work.impl.WorkManagerImpl
import com.google.firebase.firestore.ListenerRegistration
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.Friend
import com.olive.app.data.safety.*
import com.olive.app.support.SessionRuntimeFixture
import com.olive.app.ui.users.shared.navigation.SafePage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SafetyPlanOnlineActionsTest {
    private val original=SafetyPlan("owner","Synthetic owner",mapOf("primary_documents" to SafetyField("Identification",2)))
    private fun online(f:SessionRuntimeFixture) {
        val manager=mock(ConnectivityManager::class.java)
        val network=mock(Network::class.java)
        val caps=mock(NetworkCapabilities::class.java)
        doReturn(manager).`when`(f.application).getSystemService(ConnectivityManager::class.java)
        `when`(manager.activeNetwork).thenReturn(network)
        `when`(manager.getNetworkCapabilities(network)).thenReturn(caps)
        `when`(caps.hasCapability(anyInt())).thenReturn(true)
        WorkManagerImpl.setDelegate(mock(WorkManagerImpl::class.java))
        f.runtime.state.page=SafePage.SafetyPlan
    }
    private suspend fun open(
        f: SessionRuntimeFixture,
        repo: SafetyPlanRepository,
        initial: SafetyPlan = original,
    ): SessionSafetyPlans {
        val store=SafetyPlanStore(f.application,false)
        store.write("owner","owner",initial)
        `when`(repo.observe(anyString(),any<(SafetyPlan)->Unit>() ?: {},any<(Exception)->Unit>() ?: {})).thenReturn(mock(ListenerRegistration::class.java))
        `when`(repo.sync(any<SafetyPlan>() ?: original,anyMap<String,Any>())).thenAnswer { call ->
            val sent=call.getArgument<SafetyPlan>(0)
            sent.copy(fields=sent.fields.mapValues { (_,v)->v.copy(revision=v.revision+1,mutationId=null,baseValue=null) })
        }
        // The optional conflict argument is null for ordinary synchronization.
        doAnswer { call -> val sent=call.getArgument<SafetyPlan>(0);sent.copy(fields=sent.fields.mapValues { (_,v)->v.copy(revision=v.revision+1,mutationId=null,baseValue=null) }) }
            .`when`(repo).sync(any<SafetyPlan>() ?: original,isNull())
        return SessionSafetyPlans(f.runtime){repo}.also { it.open("owner","Synthetic owner",emptyMap(),Any()) }
    }
    @Test
    fun discreteEditsFlushToTheServerAndConsultationRequestAndStopKeepTheirState()=runBlocking<Unit> {
        SessionRuntimeFixture().use { f ->
            online(f)
            val repo=mock(SafetyPlanRepository::class.java)
            val controller=open(f,repo)
            try {
                controller.edit("primary_documents","Updated documents")
                f.await { controller.saving==0 }
                controller.flushEdits()
                f.await { controller.plan?.pending==false }
                assertEquals("Updated documents",controller.plan?.fields?.get("primary_documents")?.value)
                `when`(repo.consult("request")).thenReturn(original.copy(staffUid="staff",consultationRequested=true))
                controller.consult()
                f.await { controller.saving==0 }
                assertEquals("staff",controller.plan?.staffUid)
                assertTrue(controller.plan!!.consultationRequested)
                `when`(repo.consult("stop")).thenReturn(original)
                controller.consult(true)
                f.await { controller.saving==0 }
                assertFalse(controller.plan!!.consultationRequested)
                assertEquals("",controller.plan?.shareAction)
                controller.retrySave()
                f.await { controller.saving==0 }
            } finally { controller.close();WorkManagerImpl.setDelegate(null) }
        }
    }
    @Test
    fun sharingWithAcceptedContactsAllowsOnlyApprovedSupportMembersAndListsMediaRecipients()=runBlocking<Unit> {
        SessionRuntimeFixture().use { f ->
            online(f)
            val repo=mock(SafetyPlanRepository::class.java)
            val controller=open(f,repo)
            try {
                val friends=listOf(Friend("accepted","friend","fixture_friend"))
                val shared=original.copy(staffUid="staff",safeContactUids=listOf("friend"))
                `when`(repo.shareContacts(friends)).thenReturn(shared)
                controller.shareWithContacts(friends)
                f.await { controller.saving==0 }
                assertEquals(listOf("friend"),controller.plan?.safeContactUids)
                controller.setSupportContact("unapproved")
                verify(repo,never()).setSupportContact("unapproved")
                controller.setSupportContact("friend")
                f.await { controller.saving==0 }
                assertEquals("friend",controller.plan?.supportSafeContactUid)
                `when`(repo.mediaRecipients()).thenReturn(mapOf("staff" to "fixture_staff"))
                assertEquals(mapOf("staff" to "fixture_staff"),controller.mediaRecipients())
                `when`(repo.setSupportContact("")).thenAnswer { error("Denied") }
                controller.setSupportContact("")
                f.await { controller.saving==0 }
                assertNotNull(controller.error)
                assertEquals("friend",controller.plan?.supportSafeContactUid)
            } finally { controller.close();WorkManagerImpl.setDelegate(null) }
        }
    }

    @Test
    fun staffConsultationsReplaceTheAccessIndexAndRemoveRevokedCachedPlans() = runBlocking<Unit> {
        SessionRuntimeFixture(AccountRole.STAFF).use { f ->
            online(f)
            val repository = mock(SafetyPlanRepository::class.java)
            val store = SafetyPlanStore(f.application, false)
            store.write("owner", "revoked", original.copy(ownerUid = "revoked"))
            store.write("owner", "assigned", original.copy(ownerUid = "assigned"))
            val assigned = SafetyConsultation("assigned", "Synthetic assigned owner", "ticket", ticketNumber = 41)
            `when`(repository.consultations()).thenReturn(false to listOf(assigned))
            val listener = mock(ListenerRegistration::class.java)
            var invalidate: (() -> Unit)? = null
            doAnswer { call -> invalidate = call.getArgument(1); listener }.`when`(repository)
                .observeAccess(anyString(), any<() -> Unit>() ?: {})
            val controller = SessionSafetyPlans(f.runtime) { repository }
            try {
                controller.observeAccess()
                controller.observeAccess()
                verify(repository, times(1)).observeAccess(anyString(), any<() -> Unit>() ?: {})
                checkNotNull(invalidate).invoke()
                f.await { !controller.loadingAccess && controller.consultations == listOf(assigned) }
                assertFalse(controller.available)
                assertNull(store.read("owner", "revoked"))
                assertNotNull(store.read("owner", "assigned"))
                doAnswer { error("Synthetic consultation outage") }.`when`(repository).consultations()
                controller.refreshConsultations()
                assertEquals(listOf(assigned), controller.consultations)
                assertEquals(com.olive.app.R.string.safety_sync_error, controller.error)
            } finally {
                controller.close()
                verify(listener).remove()
                WorkManagerImpl.setDelegate(null)
            }
        }
    }

    @Test
    fun closingTheSharedPlansSessionImmediatelyClearsItsPrivateAccessList() = runBlocking<Unit> {
        SessionRuntimeFixture(AccountRole.SAFE_CONTACT).use { f ->
            f.runtime.state.page = SafePage.SafetyPlan
            val repository = mock(SafetyPlanRepository::class.java)
            val shared = SharedSafetyPlan("person", "Synthetic shared user")
            `when`(repository.sharedWithMe()).thenReturn(listOf(shared))
            val controller = SessionSafetyPlans(f.runtime) { repository }
            controller.refreshSharedPlans()
            f.await { !controller.loadingAccess }
            assertEquals(listOf(shared), controller.sharedPlans)
            controller.close()
            assertTrue(controller.sharedPlans.isEmpty())
        }
    }

    @Test
    fun anAssignedConsultationOpensItsTicketAndSharesOnlyIncludedPlanAttachments() = runBlocking<Unit> {
        val documents = mock(SafetyDocumentDataSource::class.java)
        SessionRuntimeFixture(documents = documents).use { f ->
            online(f)
            val repository = mock(SafetyPlanRepository::class.java)
            val controller = open(f, repository)
            val included = SafetyDocument("included", "owner", "", "Synthetic document", "text/plain", listOf("owner"))
            val checklist = writeSafetyChecklist(listOf(
                SafetyChecklistItem("row", "Synthetic checklist item", attachmentIds = listOf("included", "excluded"),
                    excludedAttachmentIds = listOf("excluded")),
            ))
            val assigned = original.copy(
                fields = mapOf("primary_documents" to SafetyField(checklist, 10)),
                consultationRequested = true, staffUid = "staff", consultationConversationId = "consult",
            )
            val ticket = com.olive.app.data.messaging.ChatConversation(
                "consult", "consult", listOf("owner"), mapOf("owner" to "fixture_owner"), type = "SUPPORT", ticketSequence = 42,
            )
            `when`(f.messaging.listConversations("owner")).thenReturn(listOf(ticket))
            `when`(repository.consult("request")).thenReturn(assigned)
            `when`(repository.mediaRecipients()).thenReturn(mapOf("staff" to "fixture_staff"))
            `when`(documents.getFor("owner", "included")).thenReturn(included)
            // The ticket listener is covered separately. This test isolates assignment, routing, and media grants.
            f.runtime.state.messagingBusy = true
            try {
                controller.consult()
                f.await { f.runtime.state.activeConversation == ticket && controller.saving == 0 }
                f.await { mockingDetails(documents).invocations.any { it.method.name == "grantAccess" } }
                assertEquals(SafePage.Messages, f.runtime.state.page)
                assertEquals("#42", f.runtime.state.activeConversation?.ticketNumber())
                verify(documents).grantAccess(included, "owner", "staff", "fixture_staff", forPlan = true)
                verify(documents, never()).getFor("owner", "excluded")
            } finally {
                controller.close()
                WorkManagerImpl.setDelegate(null)
            }
        }
    }

    @Test
    fun signaledStaffAccessRevocationRemovesTheSharedPlanAndClosesItsListener() = runBlocking<Unit> {
        SessionRuntimeFixture(AccountRole.STAFF).use { f ->
            online(f)
            val repo = mock(SafetyPlanRepository::class.java)
            val store = SafetyPlanStore(f.application, false)
            val shared = original.copy(ownerUid = "shared")
            store.write("owner", "shared", shared)
            var failed: ((Exception) -> Unit)? = null
            val listener = mock(ListenerRegistration::class.java)
            doAnswer { call -> failed = call.getArgument(2); listener }.`when`(repo)
                .observe(eq("shared") ?: "shared", any<(SafetyPlan) -> Unit>() ?: {}, any<(Exception) -> Unit>() ?: {})
            `when`(repo.sync(shared)).thenReturn(shared)
            val controller = SessionSafetyPlans(f.runtime) { repo }
            try {
                controller.open("shared", "Synthetic shared owner", emptyMap(), Any())
                assertEquals("shared", controller.plan?.ownerUid)
                failed!!.invoke(
                    com.google.firebase.firestore.FirebaseFirestoreException(
                        "Synthetic access revoked", com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED,
                    ),
                )
                f.await { controller.plan == null && store.read("owner", "shared") == null }
                assertEquals(com.olive.app.R.string.safety_access_revoked, controller.error)
            } finally {
                controller.close()
                verify(listener).remove()
                WorkManagerImpl.setDelegate(null)
            }
        }
    }


    @Test
    fun resolvingAStaffProposalUsesItsRevisionAndPreservesUnrelatedChecklistChanges() = runBlocking<Unit> {
        SessionRuntimeFixture().use { fixture ->
            online(fixture)
            val repo = mock(SafetyPlanRepository::class.java)
            val key = "primary_documents"
            val unrelated = "primary_grab"
            val proposal = SafetyConflict("New documents", "proposal-id")
            val saved = original.copy(
                fields = original.fields + (unrelated to SafetyField("Unrelated updated checklist", 8)),
                pendingConflicts = mapOf(key to proposal),
            )
            val controller = open(fixture, repo, saved)
            try {
                val proposed = checkNotNull(controller.plan)
                val revision = proposed.fields.getValue(key).revision
                val remote = proposed.copy(
                    fields = proposed.fields + (key to SafetyField(proposal.value, revision + 1)),
                    pendingConflicts = emptyMap(),
                )
                val resolution = mapOf(
                    "key" to key, "revision" to revision, "mutationId" to "proposal-id", "useIncoming" to true,
                )
                `when`(repo.sync(proposed, resolution)).thenReturn(remote)
                controller.resolve(key, keepMine = false)
                fixture.await { controller.saving == 0 }
                val updated = checkNotNull(controller.plan)
                assertFalse(updated.hasConflicts)
                assertEquals(proposal.value, updated.fields.getValue(key).value)
                assertEquals("Unrelated updated checklist", updated.fields.getValue(unrelated).value)
                verify(repo).sync(proposed, resolution)
            } finally {
                controller.close()
                WorkManagerImpl.setDelegate(null)
            }
        }
    }

}
