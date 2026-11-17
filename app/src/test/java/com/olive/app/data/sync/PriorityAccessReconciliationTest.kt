package com.olive.app.data.sync

import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GetTokenResult
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.ChatCacheStore
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.MessagingRepository
import com.olive.app.data.safety.*
import com.olive.app.support.TestAndroidKeyStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PriorityAccessReconciliationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val auth = mock(FirebaseAuth::class.java)
    private val user = mock(FirebaseUser::class.java)
    private val token = mock(GetTokenResult::class.java)
    private val repository = mock(SafetyPlanRepository::class.java)

    init {
        `when`(auth.currentUser).thenReturn(user)
        `when`(user.uid).thenReturn("viewer")
        `when`(user.getIdToken(false)).thenReturn(Tasks.forResult(token))
        `when`(user.getIdToken(true)).thenReturn(Tasks.forResult(token))
    }

    private fun plan(owner: String, note: String) =
        SafetyPlan(owner, "Synthetic owner", mapOf("primary_documents" to SafetyField(note, 2)))

    @Test
    fun accountReconciliationRefreshesTheAuthoritativeTokenAndRejectsPreviousAccounts() = runBlocking<Unit> {
        val reconciler = AccountAccessReconciler(auth)
        reconciler.reconcile("viewer")
        verify(user).getIdToken(true)
        assertThrows(IllegalStateException::class.java) { runBlocking { reconciler.reconcile("other") } }
        `when`(auth.currentUser).thenReturn(null)
        assertThrows(IllegalStateException::class.java) { runBlocking { reconciler.reconcile("viewer") } }
    }

    @Test
    fun safetyPlanReconciliationRemovesRevokedPlansAndLoadsOnlyTheRoleAuthorizedOwners() = runBlocking<Unit> {
        TestAndroidKeyStore.install().use {
            val store = SafetyPlanStore(app, false)
            val reconciler = SafetyPlanReconciler(app, auth, repository)
            for (role in AccountRole.entries) {
                val owner = if (role == AccountRole.USER) "viewer" else "assigned"
                `when`(token.claims).thenReturn(mapOf("role" to role.name))
                `when`(repository.sharedWithMe()).thenReturn(listOf(SharedSafetyPlan(owner, "Shared owner")))
                `when`(repository.consultations()).thenReturn(true to listOf(SafetyConsultation(owner, "Assigned owner")))
                `when`(repository.get(owner)).thenReturn(plan(owner, "Latest plan"))
                store.write("viewer", "revoked", plan("revoked", "No longer authorized"))
                store.write("different-account", "unchanged", plan("unchanged", "Separate account"))

                reconciler.reconcile("viewer")
                assertEquals(listOf(owner), store.all("viewer").map { it.ownerUid })
                assertEquals("Latest plan", store.read("viewer", owner)?.fields?.get("primary_documents")?.value)
                assertNotNull(store.read("different-account", "unchanged"))
            }
        }
    }

    @Test
    fun conversationReconciliationDropsRevokedChatsAndDoesNotApplyResultsAfterAccountSwitch() = runBlocking<Unit> {
        TestAndroidKeyStore.install().use {
            val messaging = mock(MessagingRepository::class.java)
            val cache = ChatCacheStore(app)
            val permitted = ChatConversation("allowed", "pair", listOf("viewer", "peer"), emptyMap())
            cache.replaceIndex("viewer", listOf(permitted.copy(id = "revoked")), System.currentTimeMillis())
            `when`(messaging.listConversations("viewer")).thenReturn(listOf(permitted))
            mockStatic(FirebaseAuth::class.java).use { firebase ->
                firebase.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
                val reconciler = ConversationAccessReconciler(app, messaging)
                reconciler.reconcile("viewer")
                assertEquals(listOf("allowed"), cache.conversations("viewer").map { it.id })
                `when`(user.uid).thenReturn("different-account")
                `when`(messaging.listConversations("viewer")).thenReturn(emptyList())
                assertThrows(IllegalStateException::class.java) { runBlocking { reconciler.reconcile("viewer") } }
                assertEquals(listOf("allowed"), cache.conversations("viewer").map { it.id })
            }
        }
    }
}
