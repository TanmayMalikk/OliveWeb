package com.olive.app.ui.session

import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.olive.app.R
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
class SessionSafetyPlanControllerTest {
    private fun plan(owner: String = "owner") = SafetyPlan(
        owner, "Sample owner", mapOf(
            "primary_documents" to SafetyField("Identification", 2),
            "primary_grab" to SafetyField("Emergency bag", 1),
        ),
    )

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
            fixture.await { !controller.available }
            verify(repository).available(false)
            `when`(repository.available(true)).thenAnswer { throw IllegalStateException("Service unavailable") }
            controller.changeAvailability(true)
            fixture.await { controller.error != null }
            assertFalse(controller.available)
            assertEquals(R.string.safety_sync_error, controller.error)
            controller.close()
        }
    }
}
