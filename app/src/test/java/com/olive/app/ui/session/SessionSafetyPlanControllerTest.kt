package com.olive.app.ui.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.olive.app.R
import com.olive.app.data.account.AccountRole
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
class SessionSafetyPlanControllerTest {
    private fun plan(owner: String = "owner") = SafetyPlan(
        owner, "Sample owner", mapOf(
            "primary_documents" to SafetyField("Identification", 2),
            "primary_grab" to SafetyField("Emergency bag", 1),
        ),
    )

    @Test
    fun successfulConsultationRefreshPrunesRevokedPlansAndARecoveryFailureKeepsTheIndex() = runBlocking<Unit> {
        SessionRuntimeFixture(AccountRole.ADMIN).use { fixture ->
            val connectivity = mock(ConnectivityManager::class.java)
            val network = mock(Network::class.java)
            val capabilities = mock(NetworkCapabilities::class.java)
            doReturn(connectivity).`when`(fixture.application).getSystemService(Context.CONNECTIVITY_SERVICE)
            `when`(connectivity.activeNetwork).thenReturn(network)
            `when`(connectivity.getNetworkCapabilities(network)).thenReturn(capabilities)
            `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
            `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)
            val repository = mock(SafetyPlanRepository::class.java)
            val next = SafetyConsultation("current", "Synthetic current owner", "ticket", "staff")
            `when`(repository.consultations()).thenReturn(false to listOf(next))
            val store = SafetyPlanStore(fixture.application, false)
            store.write("owner", "current", plan("current"))
            store.write("owner", "revoked", plan("revoked"))
            val controller = SessionSafetyPlans(fixture.runtime) { repository }
            controller.refreshConsultations()
            assertFalse(controller.loadingAccess)
            assertFalse(controller.available)
            assertEquals(listOf(next), controller.consultations)
            assertNotNull(store.read("owner", "current"))
            assertNull(store.read("owner", "revoked"))

            controller.refreshConsultations(force = false)
            verify(repository).consultations()
            `when`(repository.consultations()).thenThrow(IllegalStateException("Temporary fixture failure"))
            controller.refreshConsultations()
            assertEquals(listOf(next), controller.consultations)
            assertEquals(R.string.safety_sync_error, controller.error)
            controller.close(preserveAdminIndex = true)
            assertEquals(listOf(next), controller.consultations)
            controller.close()
            assertTrue(controller.consultations.isEmpty())
        }
    }

    @Test
    fun pendingEditsRemainDurableWhenRemoteUpdatesAnotherSection() = runBlocking<Unit> {
        SessionRuntimeFixture().use { fixture ->
            val runtime = fixture.runtime
            runtime.state.page = SafePage.SafetyPlan
            val repository = mock(SafetyPlanRepository::class.java)
            val listener = mock(ListenerRegistration::class.java)
            var update: ((SafetyPlan) -> Unit)? = null
            doAnswer { invocation ->
                update = invocation.getArgument(1)
                listener
            }.`when`(repository).observe(eq("owner") ?: "", any<(SafetyPlan) -> Unit>() ?: {},
                any<(Exception) -> Unit>() ?: {})
            val store = SafetyPlanStore(fixture.application, false)
            store.write("owner", "owner", plan())
            val controller = SessionSafetyPlans(runtime) { repository }
            val lease = Any()
            controller.open("owner", "Sample owner", emptyMap(), lease)
            assertFalse(controller.loadingPlan)
            assertEquals("Identification", controller.plan?.fields?.get("primary_documents")?.value)

            controller.edit("primary_documents", "My identification")
            fixture.await { controller.saving == 0 }
            assertEquals("My identification", store.read("owner", "owner")?.fields?.get("primary_documents")?.value)
            assertTrue(controller.plan?.pending == true)
            update?.invoke(plan().copy(fields = plan().fields + ("primary_grab" to SafetyField("Updated bag", 2))))
            fixture.await { controller.plan?.fields?.get("primary_grab")?.value == "Updated bag" }
            assertEquals("My identification", controller.plan?.fields?.get("primary_documents")?.value)
            controller.edit("unknown-field", "Invalid")
            assertFalse(controller.plan!!.fields.containsKey("unknown-field"))
            controller.close(Any())
            assertNotNull(controller.plan)
            controller.close(lease)
            assertNull(controller.plan)
            verify(listener).remove()
        }
    }

    @Test
    fun acceptingIncomingConflictReplacesOnlyTheConflictingField() = runBlocking<Unit> {
        SessionRuntimeFixture().use { fixture ->
            val runtime = fixture.runtime
            runtime.state.page = SafePage.SafetyPlan
            val repository = mock(SafetyPlanRepository::class.java)
            val controller = SessionSafetyPlans(runtime) { repository }
            val store = SafetyPlanStore(fixture.application, false)
            val conflicting = plan().copy(fields = plan().fields + (
                "primary_documents" to SafetyField("My wording", 2, mutationId = "local",
                    conflictValue = "Incoming wording", conflictRevision = 3)
            ))
            store.write("owner", "owner", conflicting)
            `when`(repository.observe(eq("owner") ?: "", any<(SafetyPlan) -> Unit>() ?: {},
                any<(Exception) -> Unit>() ?: {})).thenReturn(mock(ListenerRegistration::class.java))
            controller.open("owner", "Sample owner", emptyMap(), Any())
            assertTrue(controller.plan!!.hasConflicts)
            controller.resolve("primary_documents", keepMine = false)
            fixture.await { controller.saving == 0 }
            val resolved = requireNotNull(controller.plan)
            assertEquals(SafetyField("Incoming wording", 3), resolved.fields.getValue("primary_documents"))
            assertEquals(SafetyField("Emergency bag", 1), resolved.fields.getValue("primary_grab"))
            assertFalse(resolved.hasConflicts)
            controller.close()
        }
    }

    @Test
    fun sharedPlanPermissionLossRemovesTheCachedCopyAndLateListenerUpdatesAreIgnored() = runBlocking<Unit> {
        SessionRuntimeFixture(AccountRole.SAFE_CONTACT).use { fixture ->
            fixture.runtime.state.page = SafePage.SafetyPlan
            val repository = mock(SafetyPlanRepository::class.java)
            var update: ((SafetyPlan) -> Unit)? = null
            var fail: ((Exception) -> Unit)? = null
            `when`(repository.observe(eq("shared-owner") ?: "", any<(SafetyPlan) -> Unit>() ?: {},
                any<(Exception) -> Unit>() ?: {})).thenAnswer { invocation ->
                    update = invocation.getArgument(1)
                    fail = invocation.getArgument(2)
                    mock(ListenerRegistration::class.java)
                }
            val store = SafetyPlanStore(fixture.application, false)
            store.write("owner", "shared-owner", plan("shared-owner"))
            val controller = SessionSafetyPlans(fixture.runtime) { repository }
            controller.open("shared-owner", "Sample owner", emptyMap(), Any())
            controller.edit("primary_documents", "Disallowed edit")
            assertEquals("Identification", controller.plan?.fields?.get("primary_documents")?.value)
            fail?.invoke(FirebaseFirestoreException("Access removed", FirebaseFirestoreException.Code.PERMISSION_DENIED))
            fixture.await {
                controller.plan == null && controller.error == R.string.safety_access_revoked &&
                    store.read("owner", "shared-owner") == null
            }
            controller.close()
            update?.invoke(plan("shared-owner"))
            fixture.await { controller.saving == 0 }
            assertNull(controller.plan)
            assertNull(store.read("owner", "shared-owner"))
        }
    }

    @Test
    fun sharedContactAndStaffAvailabilityChangesAreBoundToTheCurrentRole() = runBlocking<Unit> {
        SessionRuntimeFixture(AccountRole.SAFE_CONTACT).use { fixture ->
            fixture.runtime.state.page = SafePage.SafetyPlan
            val repository = mock(SafetyPlanRepository::class.java)
            val shared = listOf(SharedSafetyPlan("person", "Person fixture", 10))
            `when`(repository.sharedWithMe()).thenReturn(shared)
            val controller = SessionSafetyPlans(fixture.runtime) { repository }
            controller.refreshSharedPlans()
            fixture.await { !controller.loadingAccess }
            assertEquals(shared, controller.sharedPlans)
            controller.changeAvailability(false)
            verify(repository, never()).available(false)
            controller.close()
        }
        SessionRuntimeFixture(AccountRole.STAFF).use { fixture ->
            val repository = mock(SafetyPlanRepository::class.java)
            val controller = SessionSafetyPlans(fixture.runtime) { repository }
            controller.changeAvailability(false)
            assertFalse(controller.available)
            fixture.await { !controller.savingAvailability }
            verify(repository).available(false)
            `when`(repository.available(true)).thenAnswer { throw IllegalStateException("Service unavailable") }
            controller.changeAvailability(true)
            fixture.await { !controller.savingAvailability && controller.error != null }
            assertFalse(controller.available)
            assertEquals(R.string.safety_sync_error, controller.error)
            controller.close()
        }
    }

    @Test
    fun resolvePendingProposalConsultAttachDocumentsAndFlushWorkAcrossNetworkStates() = runBlocking<Unit> {
        SessionRuntimeFixture(AccountRole.USER).use { fixture ->
            val runtime = fixture.runtime
            runtime.state.page = SafePage.SafetyPlan
            val repository = mock(SafetyPlanRepository::class.java)
            val docsRepo = mock(SafetyDocumentDataSource::class.java)
            val fields = mapOf(
                "primary_documents" to SafetyField(
                    writeSafetyChecklist(listOf(SafetyChecklistItem("row-1", "Passport"))),
                    revision = 2,
                ),
            )
            val conflictPlan = plan().copy(
                fields = fields,
                pendingConflicts = mapOf("primary_documents" to SafetyConflict("Incoming", "mut-1")),
                consultationConversationId = "chat-consult",
                staffUid = "staff-1",
                safeContactUids = listOf("safe-1"),
            )
            val store = SafetyPlanStore(fixture.application, false)
            store.write("owner", "owner", conflictPlan)
            `when`(repository.observe(eq("owner") ?: "", any<(SafetyPlan) -> Unit>() ?: {}, any<(Exception) -> Unit>() ?: {}))
                .thenReturn(mock(ListenerRegistration::class.java))
            `when`(repository.observeAccess(eq("owner") ?: "", any<() -> Unit>() ?: {})).thenReturn(mock(ListenerRegistration::class.java))
            `when`(repository.sync(any<SafetyPlan>() ?: conflictPlan, any())).thenReturn(conflictPlan)
            `when`(repository.consult("request")).thenReturn(conflictPlan)
            `when`(repository.mediaRecipients()).thenReturn(mapOf("staff-1" to "staff_user"))
            `when`(repository.shareContacts(emptyList())).thenReturn(conflictPlan)
            `when`(repository.observeRecipientDevices(emptyList()) {}).thenReturn(listOf(mock(ListenerRegistration::class.java)))
            val doc = SafetyDocument(
                "doc-1", "owner", "", "Passport.pdf", "application/pdf", listOf("owner"),
                tags = listOf(safetyChecklistAttachmentTag("primary_documents", "row-1")),
            )
            `when`(docsRepo.getFor("owner", "doc-1")).thenReturn(doc)
            `when`(fixture.messaging.listConversations("owner")).thenReturn(emptyList())

            val controller = SessionSafetyPlans(runtime) { repository }
            controller.observeAccess()
            controller.open("owner", "Owner", emptyMap(), Any())

            controller.resolve("primary_documents", keepMine = true)
            fixture.await { controller.saving == 0 }
            assertEquals(mapOf("staff-1" to "staff_user"), controller.mediaRecipients())
            assertNotNull(controller.observeRecipientDevices(emptyList()) {})
            controller.attachPublishedDocuments(listOf(doc))
            controller.flushEdits()
            controller.retrySave()
            controller.setSupportContact("safe-1")
            controller.shareWithContacts(emptyList())
            controller.consult(stop = false)
            controller.openConsultation("chat-consult")
            fixture.await { controller.saving == 0 }

            controller.close()
        }
    }
}
