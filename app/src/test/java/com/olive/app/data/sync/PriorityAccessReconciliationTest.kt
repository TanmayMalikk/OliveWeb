package com.olive.app.data.sync

import androidx.work.ListenableWorker.Result as WorkResult
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import androidx.work.workDataOf
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GetTokenResult
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.ChatCacheStore
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.MessagingRepository
import com.olive.app.data.safety.*
import com.olive.app.support.TestAndroidKeyStore
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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

    @Test
    fun workerEnqueueDeduplicatesByRevisionAndDoWorkHandlesSuccessAndFailures() = runBlocking<Unit> {
        TestAndroidKeyStore.install().use {
            val work = mock(WorkManagerImpl::class.java)
            WorkManagerImpl.setDelegate(work)
            try {
                PriorityReconciliationWorker.enqueue(app, "")
                verifyNoInteractions(work)
                PriorityReconciliationWorker.enqueue(app, "viewer")
                PriorityReconciliationWorker.enqueue(app, "viewer", revision = 5L, expedited = true)
                PriorityReconciliationWorker.enqueue(app, "viewer", revision = 3L)
                PriorityReconciliationWorker.cancel(app, "viewer")
                assertEquals(2, mockingDetails(work).invocations.count { it.method.name == "enqueueUniqueWork" })
                verify(work).cancelUniqueWork("olive-priority-reconciliation-viewer")
            } finally {
                WorkManagerImpl.setDelegate(null)
            }

            val params = mock(WorkerParameters::class.java).apply {
                `when`(backgroundExecutor).thenReturn(Executor(Runnable::run))
                `when`(taskExecutor).thenReturn(mock(TaskExecutor::class.java))
            }
            var currentInput = workDataOf()
            `when`(params.inputData).thenAnswer { currentInput }
            val worker = PriorityReconciliationWorker(app, params)
            val createdFirebaseApp = if (FirebaseApp.getApps(app).isEmpty()) {
                FirebaseApp.initializeApp(
                    app,
                    FirebaseOptions.Builder()
                        .setApplicationId("1:1234567890:android:abcdef123456")
                        .setApiKey("AIzaSyTestKeyForUnitTestsOnly000000000")
                        .setProjectId("olive-unit-test")
                        .build(),
                )
            } else null
            val completionEvents = mutableListOf<String>()
            val revocationEvents = mutableListOf<String>()
            val completionJob = launch(start = CoroutineStart.UNDISPATCHED) {
                SyncCompletionEvents.events.collect { completionEvents += it }
            }
            val revocationJob = launch(start = CoroutineStart.UNDISPATCHED) {
                AccountRevocationEvents.events.collect { revocationEvents += it }
            }
            try {
                mockStatic(FirebaseAuth::class.java).use { firebase ->
                    firebase.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
                    mockStatic(FirebaseFirestore::class.java).use { fsSdk ->
                        fsSdk.`when`<FirebaseFirestore> { FirebaseFirestore.getInstance() }
                            .thenReturn(mock(FirebaseFirestore::class.java))
                        currentInput = workDataOf()
                        assertEquals(WorkResult.failure(), worker.doWork())
                        currentInput = workDataOf("uid" to "other")
                        assertEquals(WorkResult.success(), worker.doWork())

                        currentInput = workDataOf("uid" to "viewer")
                        mockConstruction(SyncInvalidationRepository::class.java) { repo, _ ->
                            `when`(runBlocking { repo.get("viewer") }).thenReturn(SyncRevisions())
                        }.use {
                            mockConstruction(AccountAccessReconciler::class.java).use {
                                mockConstruction(SafetyPlanReconciler::class.java).use {
                                    mockConstruction(DocumentAccessReconciler::class.java).use {
                                        mockConstruction(ConversationAccessReconciler::class.java).use {
                                            mockConstruction(PriorityReconciliationRunner::class.java) { runner, context ->
                                                val count = context.count
                                                runBlocking {
                                                    when (count) {
                                                        1 -> `when`(runner.reconcile("viewer", SyncRevisions())).thenReturn(ReconciliationOutcome.COMPLETE)
                                                        2 -> `when`(runner.reconcile("viewer", SyncRevisions())).thenAnswer { throw FirebaseAuthInvalidUserException("USER_NOT_FOUND", "Revoked") }
                                                        3 -> `when`(runner.reconcile("viewer", SyncRevisions())).thenAnswer { throw FirebaseFirestoreException("Denied", FirebaseFirestoreException.Code.PERMISSION_DENIED) }
                                                        4 -> `when`(runner.reconcile("viewer", SyncRevisions())).thenAnswer { throw FirebaseFirestoreException("Offline", FirebaseFirestoreException.Code.UNAVAILABLE) }
                                                        5 -> `when`(runner.reconcile("viewer", SyncRevisions())).thenAnswer { throw FirebaseNetworkException("Timeout") }
                                                        else -> `when`(runner.reconcile("viewer", SyncRevisions())).thenAnswer { throw IllegalStateException("Transient") }
                                                    }
                                                }
                                            }.use {
                                                assertEquals(WorkResult.success(), worker.doWork())
                                                yield()
                                                assertEquals(listOf("viewer"), completionEvents)

                                                assertEquals(WorkResult.success(), worker.doWork())
                                                verify(auth).signOut()
                                                yield()
                                                assertEquals(listOf("viewer"), revocationEvents)

                                                assertEquals(WorkResult.failure(), worker.doWork())
                                                assertEquals(WorkResult.retry(), worker.doWork())
                                                assertEquals(WorkResult.retry(), worker.doWork())
                                                assertEquals(WorkResult.retry(), worker.doWork())
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                completionJob.cancel()
                revocationJob.cancel()
                createdFirebaseApp?.delete()
            }
        }
    }

    @Test
    fun domainReconcilersVerifyAccountConversationsDocumentsAndSafetyPlans() = runBlocking<Unit> {
        TestAndroidKeyStore.install().use {
            val viewerUser = mock(FirebaseUser::class.java)
            `when`(viewerUser.uid).thenReturn("viewer")
            `when`(viewerUser.getIdToken(true)).thenReturn(
                com.google.android.gms.tasks.Tasks.forResult(mock(com.google.firebase.auth.GetTokenResult::class.java)),
            )
            `when`(auth.currentUser).thenReturn(viewerUser)

            val doc = com.olive.app.data.safety.SafetyDocument("d1", "viewer", "", "Doc.pdf", "application/pdf", listOf("viewer"))
            val snapshot = com.olive.app.data.safety.DocumentListSnapshot(
                listOf(doc),
                com.olive.app.data.safety.DocumentListSource.SERVER,
                55L,
            )
            val messaging = mock(com.olive.app.data.messaging.MessagingRepository::class.java)
            `when`(messaging.listConversations("viewer")).thenReturn(emptyList())

            mockStatic(FirebaseAuth::class.java).use { authSdk ->
                authSdk.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
                AccountAccessReconciler().reconcile("viewer")
                ConversationAccessReconciler(app, messaging).reconcile("viewer")
                verify(messaging).listConversations("viewer")

                mockConstruction(com.olive.app.data.media.MediaRepository::class.java).use {
                    mockConstruction(com.olive.app.data.safety.SafetyDocumentRepository::class.java) { docs, _ ->
                        runBlocking { `when`(docs.listFor("viewer")).thenReturn(snapshot) }
                    }.use {
                        mockConstruction(com.olive.app.data.safety.DocumentIndexStore::class.java).use { indexMock ->
                            mockConstruction(com.olive.app.data.media.OfflineMediaCache::class.java).use { cacheMock ->
                                DocumentAccessReconciler(app).reconcile("viewer")
                                verify(indexMock.constructed().single()).replace("viewer", listOf(doc), 55L)
                                verify(cacheMock.constructed().single()).reconcile("viewer", listOf(doc))
                            }
                        }
                    }
                }
            }
        }
    }
}
