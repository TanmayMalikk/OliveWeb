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
    fun completionFlushesOnlyTheOpenConsultationAndDoesNotHideSyncFailure() = runBlocking<Unit> {
        SessionRuntimeFixture().use { fixture ->
            online(fixture)
            val repository = mock(SafetyPlanRepository::class.java)
            val controller = open(fixture, repository, original.copy(consultationConversationId = "ticket-42"))
            try {
                clearInvocations(repository)
                controller.flushConsultation("unrelated-ticket")
                verify(repository, never()).sync(any<SafetyPlan>() ?: original, isNull())
                val outage = IllegalStateException("Fixture sync outage")
                doAnswer { throw outage }.`when`(repository).sync(any<SafetyPlan>() ?: original, isNull())
                val failure = runCatching { controller.flushConsultation("ticket-42") }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
                assertEquals(outage.message, failure?.message)
                assertEquals("ticket-42", controller.plan?.consultationConversationId)
                doReturn(original.copy(consultationConversationId = "ticket-42")).`when`(repository)
                    .sync(any<SafetyPlan>() ?: original, isNull())
                controller.flushConsultation("ticket-42")
                verify(repository, times(2)).sync(any<SafetyPlan>() ?: original, isNull())
            } finally {
                controller.close()
                WorkManagerImpl.setDelegate(null)
            }
        }
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

    @Test
    fun adminConsultationIndexSurvivesPageChangesButFullCloseClearsIt() = runBlocking<Unit> {
        SessionRuntimeFixture(AccountRole.ADMIN).use { f ->
            online(f)
            val repository = mock(SafetyPlanRepository::class.java)
            val assigned = SafetyConsultation("assigned", "Synthetic owner", "ticket", ticketNumber = 42)
            `when`(repository.consultations()).thenReturn(true to listOf(assigned))
            val controller = SessionSafetyPlans(f.runtime) { repository }
            try {
                controller.refreshConsultations(force = false)
                assertEquals(listOf(assigned), controller.consultations)
                assertFalse(controller.loadingAccess)
                controller.close(preserveAdminIndex = true)
                assertEquals(listOf(assigned), controller.consultations)
                controller.refreshConsultations(force = false)
                verify(repository, times(1)).consultations()
                controller.close()
                assertTrue(controller.consultations.isEmpty())
                assertTrue(controller.loadingAccess)
                controller.refreshConsultations(force = false)
                verify(repository, times(2)).consultations()
            } finally { controller.close(); WorkManagerImpl.setDelegate(null) }
        }
    }

    @Test
    fun shareContactsSupportContactConflictFallbackAndErrorBranchesWorkCleanly() = runBlocking<Unit> {
        SessionRuntimeFixture(localMode = true).use { localFixture ->
            val localField = SafetyField("Local", 1)
            localFixture.runtime.state.page = SafePage.SafetyPlan
            val localController = SessionSafetyPlans(localFixture.runtime)
            try {
                val localAccount = localFixture.runtime.coverAccountId ?: "owner"
                localController.open(localAccount, "Owner", mapOf("primary_documents" to "Local"), Any())
                localFixture.await { localController.plan != null }
                localController.consult()
                assertEquals(com.olive.app.R.string.safety_account_required, localController.error)
                assertTrue(localController.mediaRecipients().isEmpty())
                assertEquals(localField.value, localController.plan?.fields?.get("primary_documents")?.value)
            } finally {
                localController.close()
            }
        }

        SessionRuntimeFixture().use { f ->
            online(f)
            val repo = mock(SafetyPlanRepository::class.java)
            val store = SafetyPlanStore(f.application, false)
            val conflictField = SafetyField("Mine", 2).edit("Mine").copy(conflictValue = "Theirs", conflictRevision = 4)
            val pendingPlan = original.copy(
                fields = mapOf("primary_documents" to conflictField),
                staffUid = "staff-1",
                safeContactUids = listOf("friend-1"),
            )
            store.write("owner", "owner", pendingPlan)
            var onUpdate: ((SafetyPlan) -> Unit)? = null
            var onError: ((Exception) -> Unit)? = null
            `when`(repo.observe(anyString(), any<(SafetyPlan) -> Unit>() ?: {}, any<(Exception) -> Unit>() ?: {})).thenAnswer { call ->
                onUpdate = call.getArgument(1)
                onError = call.getArgument(2)
                mock(ListenerRegistration::class.java)
            }
            `when`(repo.sync(any<SafetyPlan>() ?: pendingPlan, isNull())).thenAnswer { call ->
                call.getArgument<SafetyPlan>(0).let { sent ->
                    sent.copy(fields = sent.fields.mapValues { (_, v) -> v.copy(mutationId = null, baseValue = null) })
                }
            }
            `when`(repo.mediaRecipients()).thenReturn(mapOf("staff-1" to "staff_user"))
            val controller = SessionSafetyPlans(f.runtime) { repo }
            try {
                controller.open("owner", "Owner", emptyMap(), Any())
                assertEquals(mapOf("staff-1" to "staff_user"), controller.mediaRecipients())

                // Local conflict resolution (without pendingConflicts) both keepMine=true and keepMine=false
                controller.resolve("primary_documents", keepMine = true)
                f.await { controller.saving == 0 }
                val connectivity = f.application.getSystemService(ConnectivityManager::class.java)
                val cachedNetwork = connectivity.activeNetwork
                `when`(connectivity.activeNetwork).thenReturn(null)
                controller.edit("primary_documents", "Edited offline")
                f.await { controller.saving == 0 && store.read("owner", "owner")?.pending == true }
                `when`(connectivity.activeNetwork).thenReturn(cachedNetwork)
                onUpdate?.invoke(
                    original.copy(fields = mapOf("primary_documents" to SafetyField("Server edit", 99))),
                )
                f.await { controller.plan?.hasConflicts == true }
                controller.resolve("primary_documents", keepMine = false)
                f.await { controller.saving == 0 }

                // onUpdate with pending plan + onError non-PERMISSION_DENIED branch
                onUpdate?.invoke(pendingPlan)
                onError?.invoke(IllegalStateException("Transient listener glitch"))
                f.await { controller.saving == 0 }

                // shareWithContacts success and failure
                val friends = listOf(Friend("pair-1", "friend-1", "Friend 1"))
                val sharedRemote = pendingPlan.copy(safeContactUids = listOf("friend-1"))
                `when`(repo.shareContacts(friends))
                    .thenReturn(sharedRemote)
                    .thenThrow(IllegalStateException("Share failed"))
                controller.shareWithContacts(friends)
                f.await { controller.saving == 0 }
                assertNull(controller.error)
                controller.shareWithContacts(friends)
                f.await { controller.saving == 0 }
                assertEquals(com.olive.app.R.string.safety_sync_error, controller.error)

                // setSupportContact success and failure
                controller.setSupportContact("friend-1")
                f.await { controller.saving == 0 }
                assertEquals("friend-1", controller.plan?.supportSafeContactUid)
                `when`(repo.setSupportContact("friend-1")).thenThrow(IllegalStateException("Contact failed"))
                controller.setSupportContact("friend-1")
                f.await { controller.saving == 0 }
                assertEquals(com.olive.app.R.string.safety_sync_error, controller.error)

                // resolve server proposal failure
                val proposed = pendingPlan.copy(
                    fields = mapOf("primary_documents" to SafetyField("Mine", 2)),
                    pendingConflicts = mapOf("primary_documents" to SafetyConflict("Incoming", "mut-1")),
                )
                onUpdate?.invoke(proposed)
                f.await { controller.plan?.pendingConflicts?.isNotEmpty() == true }
                `when`(repo.sync(any<SafetyPlan>() ?: proposed, anyMap<String, Any>())).thenThrow(IllegalStateException("Resolve failed"))
                controller.resolve("primary_documents", keepMine = false)
                f.await { controller.saving == 0 }
                assertEquals(com.olive.app.R.string.safety_sync_error, controller.error)

                // consult() failure and offline enqueue branch
                `when`(repo.consult("request")).thenThrow(IllegalStateException("Consult failed"))
                controller.consult()
                f.await { controller.saving == 0 }
                assertEquals(com.olive.app.R.string.safety_sync_error, controller.error)

                val manager = mock(ConnectivityManager::class.java)
                doReturn(manager).`when`(f.application).getSystemService(ConnectivityManager::class.java)
                `when`(manager.activeNetwork).thenReturn(null)
                controller.consult()
                f.await { controller.saving == 0 }
            } finally {
                controller.close()
                WorkManagerImpl.setDelegate(null)
            }
        }
    }

    @Test
    fun safeContactRefreshesSharedPlansAndSurfacesLoadErrors() = runBlocking<Unit> {
        SessionRuntimeFixture(AccountRole.SAFE_CONTACT).use { f ->
            online(f)
            val repo = mock(SafetyPlanRepository::class.java)
            val sharedSummary = SharedSafetyPlan("owner-2", "Shared Owner", 22L)
            `when`(repo.sharedWithMe())
                .thenReturn(listOf(sharedSummary))
                .thenThrow(IllegalStateException("Load failed"))
            val controller = SessionSafetyPlans(f.runtime) { repo }
            try {
                controller.refreshSharedPlans()
                f.await { !controller.loadingAccess }
                assertEquals(listOf(sharedSummary), controller.sharedPlans)
                controller.refreshSharedPlans()
                f.await { !controller.loadingAccess }
                assertEquals(com.olive.app.R.string.safety_sync_error, controller.error)
            } finally {
                controller.close()
                WorkManagerImpl.setDelegate(null)
            }
        }
    }

    @Test
    fun staffRefreshPermissionDeniedRemovesCachedSharedPlan() = runBlocking<Unit> {
        SessionRuntimeFixture(AccountRole.STAFF).use { f ->
            online(f)
            val repo = mock(SafetyPlanRepository::class.java)
            val store = SafetyPlanStore(f.application, false)
            val shared = original.copy(ownerUid = "owner-3")
            store.write("owner", "owner-3", shared)
            val denied = mock(com.google.firebase.functions.FirebaseFunctionsException::class.java).also {
                `when`(it.code).thenReturn(com.google.firebase.functions.FirebaseFunctionsException.Code.PERMISSION_DENIED)
            }
            `when`(repo.observe(eq("owner-3") ?: "owner-3", any<(SafetyPlan) -> Unit>() ?: {}, any<(Exception) -> Unit>() ?: {}))
                .thenReturn(mock(ListenerRegistration::class.java))
            `when`(repo.sync(shared)).thenAnswer { throw denied }
            val controller = SessionSafetyPlans(f.runtime) { repo }
            try {
                controller.open("owner-3", "Shared Owner", emptyMap(), Any())
                f.await { controller.plan == null && store.read("owner", "owner-3") == null }
                assertEquals(com.olive.app.R.string.safety_access_revoked, controller.error)
            } finally {
                controller.close()
                WorkManagerImpl.setDelegate(null)
            }
        }
    }
}
