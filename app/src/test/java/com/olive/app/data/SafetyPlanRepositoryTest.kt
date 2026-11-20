package com.olive.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.ListenableWorker.Result as WorkResult
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import androidx.work.workDataOf
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.EventListener
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SnapshotMetadata
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableReference
import com.google.firebase.functions.HttpsCallableResult
import com.olive.app.data.media.MediaRepository
import com.olive.app.data.messaging.Friend
import com.olive.app.data.safety.*
import com.olive.app.support.TestAndroidKeyStore
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafetyPlanRepositoryTest {
    private val functions = mock(FirebaseFunctions::class.java)
    private val firestore = mock(FirebaseFirestore::class.java)
    private val repository = SafetyPlanRepository(functions, firestore)
    private val planRow = mapOf("ownerUid" to "owner", "ownerName" to "Sample owner", "fields" to emptyMap<String, Any>())

    @Test
    fun syncOnlySendsPendingNonConflictingEditsWithOriginalBaselines() = runBlocking<Unit> {
        val changes = listOf(
            mapOf("key" to "primary_documents", "value" to "Mine", "revision" to 2L, "mutationId" to "m1",
                "baseValue" to "Original", "writerId" to "browser", "clientSequence" to 5L),
        )
        val fields = mapOf(
            "primary_documents" to SafetyField("Mine", 2, mutationId = "m1", baseValue = "Original", writerId = "browser", clientSequence = 5),
            "backup_documents" to SafetyField("Stable", 3),
            "primary_grab" to SafetyField("Conflict", 7, mutationId = "m2", conflictRevision = 8),
        )
        val resolution = mapOf("key" to "primary_grab", "revision" to 7L, "useIncoming" to true)
        val request = mapOf(
            "ownerUid" to "owner", "defaults" to fields.mapValues { it.value.value },
            "changes" to changes, "resolution" to resolution,
        )
        val call = response("safetyPlanSync", request, mapOf("plan" to planRow))

        val synced = repository.sync(SafetyPlan(ownerUid = "owner", ownerName = "", fields = fields), resolution)

        assertEquals("Sample owner", synced.ownerName)
        verify(call).call(request)
    }

    @Test
    fun consultationAndContactUpdatesUseSeparateCallableContracts() = runBlocking {
        val consult = response("safetyPlanConsult", mapOf("action" to "request"), mapOf("plan" to planRow + ("consultationConversationId" to "consult")))
        assertEquals("consult", repository.consult("request").consultationConversationId)
        verify(consult).call(mapOf("action" to "request"))

        val friend = Friend("trusted-pair", "trusted", "Trusted fixture")
        val contacts = mapOf("contacts" to listOf(mapOf("uid" to "trusted", "friendshipId" to "trusted-pair", "name" to "Trusted fixture")))
        val share = response("safetyPlanShareContacts", contacts, mapOf("plan" to planRow))
        assertEquals("owner", repository.shareContacts(listOf(friend)).ownerUid)
        verify(share).call(contacts)

        val availability = response("safetyPlanAvailability", mapOf("available" to false), emptyMap<String, Any>())
        repository.available(false)
        verify(availability).call(mapOf("available" to false))
        val supportContact = response("safetySupportContact", mapOf("safeContactUid" to "trusted"), emptyMap<String, Any>())
        repository.setSupportContact("trusted")
        verify(supportContact).call(mapOf("safeContactUid" to "trusted"))
        Unit
    }

    @Test
    fun accessListsAndMediaRecipientsPreserveServerIdentityAndTicketNumbers() = runBlocking {
        response("safetyPlansSharedWithMe", emptyMap<String, Any>(), mapOf("plans" to listOf(planRow + ("updatedAt" to 25L))))
        val shared = repository.sharedWithMe().single()
        assertEquals("owner", shared.ownerUid)
        assertEquals(25L, shared.updatedAt)

        val ticket = planRow + mapOf("conversationId" to "consult", "staffUid" to "staff", "ticketNumber" to 520L)
        response("safetyPlanConsultations", emptyMap<String, Any>(), mapOf("available" to true, "plans" to listOf(ticket)))
        val (available, consultations) = repository.consultations()
        assertTrue(available)
        assertEquals("consult", consultations.single().conversationId)
        assertEquals(520L, consultations.single().ticketNumber)

        response("safetyPlanMediaRecipients", emptyMap<String, Any>(), mapOf("recipients" to listOf(mapOf("uid" to "staff", "username" to "Olive Staff"))))
        assertEquals(mapOf("staff" to "Olive Staff"), repository.mediaRecipients())
    }

    @Test
    fun cachedPlanEventsNeverReopenAnUnverifiedPlan() = runBlocking {
        val reference = reference("safetyPlans", "owner")
        val registration = mock(ListenerRegistration::class.java)
        `when`(reference.addSnapshotListener(eq(MetadataChanges.INCLUDE), any<EventListener<DocumentSnapshot>>())).thenReturn(registration)
        val updates = mutableListOf<SafetyPlan>()
        val errors = mutableListOf<Exception>()
        val listener = repository.observe("owner", updates::add, errors::add)
        val events = documentListener(reference)
        events.onEvent(snapshot(planRow, cached = true), null)
        assertTrue(updates.isEmpty())
        val server = snapshot(planRow, cached = false)
        events.onEvent(server, null)
        assertEquals("owner", updates.single().ownerUid)
        val failure = FirebaseFirestoreException("Permission removed", FirebaseFirestoreException.Code.PERMISSION_DENIED)
        events.onEvent(null, failure)
        assertEquals(listOf(failure), errors)

        `when`(reference.get(Source.SERVER)).thenReturn(Tasks.forResult(server))
        assertEquals("owner", repository.get("owner")?.ownerUid)
        listener.remove()
        verify(registration).remove()
    }

    @Test
    fun accessObserverIgnoresCachedAndDuplicateRevisions() {
        val reference = reference("syncInvalidations", "actor")
        `when`(reference.addSnapshotListener(eq(MetadataChanges.INCLUDE), any<EventListener<DocumentSnapshot>>()))
            .thenReturn(mock(ListenerRegistration::class.java))
        var changes = 0
        repository.observeAccess("actor") { changes++ }
        val events = documentListener(reference)
        val server = snapshot(emptyMap(), cached = false)
        `when`(server.getLong("safetyRevision")).thenReturn(1L, 1L, 2L)
        events.onEvent(snapshot(emptyMap(), cached = true), null)
        events.onEvent(server, null)
        events.onEvent(server, null)
        events.onEvent(server, null)
        assertEquals(2, changes)
    }

    private fun response(name: String, input: Map<String, Any>, data: Map<String, Any>): HttpsCallableReference {
        val call = mock(HttpsCallableReference::class.java)
        // The SDK exposes the payload as a JVM field and keeps this constructor internal to Kotlin.
        val result = HttpsCallableResult::class.java.getConstructor(Any::class.java).newInstance(data)
        `when`(functions.getHttpsCallable(name)).thenReturn(call)
        `when`(call.call(input)).thenReturn(Tasks.forResult(result))
        return call
    }

    private fun reference(collection: String, id: String): DocumentReference {
        val entries = mock(CollectionReference::class.java)
        val reference = mock(DocumentReference::class.java)
        `when`(firestore.collection(collection)).thenReturn(entries)
        `when`(entries.document(id)).thenReturn(reference)
        return reference
    }

    private fun snapshot(data: Map<String, Any>, cached: Boolean): DocumentSnapshot {
        val snapshot = mock(DocumentSnapshot::class.java)
        val metadata = mock(SnapshotMetadata::class.java)
        `when`(snapshot.metadata).thenReturn(metadata)
        `when`(metadata.isFromCache).thenReturn(cached)
        `when`(snapshot.data).thenReturn(data)
        return snapshot
    }

    @Suppress("UNCHECKED_CAST")
    private fun documentListener(reference: DocumentReference): EventListener<DocumentSnapshot> {
        val capture = ArgumentCaptor.captor<EventListener<DocumentSnapshot>>()
        verify(reference).addSnapshotListener(eq(MetadataChanges.INCLUDE), capture.capture())
        return capture.value
    }

    @Test
    fun syncWorkerEnqueuesAndHandlesSyncAttachmentAndErrors() = runBlocking<Unit> {
        TestAndroidKeyStore.install().use {
            val baseApp = RuntimeEnvironment.getApplication()
            val work = mock(WorkManagerImpl::class.java)
            WorkManagerImpl.setDelegate(work)
            try {
                SafetyPlanSyncWorker.enqueue(baseApp, "owner", "owner")
                SafetyPlanSyncWorker.enqueueAttachment(baseApp, "owner", "doc-1")
                assertEquals(2, mockingDetails(work).invocations.count { it.method.name == "enqueueUniqueWork" })
            } finally {
                WorkManagerImpl.setDelegate(null)
            }

            val app = spy(baseApp)
            val connectivity = mock(ConnectivityManager::class.java)
            val network = mock(Network::class.java)
            val capabilities = mock(NetworkCapabilities::class.java)
            var currentActiveNetwork: Network? = null
            var currentInput = workDataOf()
            doReturn(connectivity).`when`(app).getSystemService(Context.CONNECTIVITY_SERVICE)
            `when`(connectivity.activeNetwork).thenAnswer { currentActiveNetwork }
            `when`(connectivity.getNetworkCapabilities(network)).thenReturn(capabilities)
            `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)).thenReturn(true)
            `when`(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).thenReturn(true)

            val auth = mock(FirebaseAuth::class.java)
            val user = mock(FirebaseUser::class.java)
            var currentAuthUid = "owner"
            `when`(user.uid).thenAnswer { currentAuthUid }
            `when`(auth.currentUser).thenReturn(user)

            val store = SafetyPlanStore(baseApp, false)
            val checklistKey = "primary_documents"
            val docTag = safetyChecklistAttachmentTag(checklistKey, "row-1")
            val doc = SafetyDocument("doc-1", "owner", "", "Passport", "application/pdf", listOf("owner"), tags = listOf(docTag))
            val remotePlan = SafetyPlan("owner", "Owner", mapOf(
                checklistKey to SafetyField(writeSafetyChecklist(listOf(SafetyChecklistItem("row-1", "Passport"))), 1),
            ))
            store.write("owner", "owner", remotePlan)

            val params = mock(WorkerParameters::class.java).apply {
                `when`(backgroundExecutor).thenReturn(Executor(Runnable::run))
                `when`(taskExecutor).thenReturn(mock(TaskExecutor::class.java))
                `when`(inputData).thenAnswer { currentInput }
            }
            val worker = SafetyPlanSyncWorker(app, params)

            val createdFirebaseApp = if (FirebaseApp.getApps(baseApp).isEmpty()) {
                FirebaseApp.initializeApp(
                    baseApp,
                    FirebaseOptions.Builder()
                        .setApplicationId("1:1234567890:android:abcdef123456")
                        .setApiKey("AIzaSyTestKeyForUnitTestsOnly000000000")
                        .setProjectId("olive-unit-test")
                        .build(),
                )
            } else null
            try {
                mockStatic(FirebaseAuth::class.java).use { authSdk ->
                    authSdk.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
                    mockStatic(FirebaseFirestore::class.java).use { firestoreSdk ->
                        firestoreSdk.`when`<FirebaseFirestore> { FirebaseFirestore.getInstance() }.thenReturn(firestore)
                        currentInput = workDataOf()
                        assertEquals(WorkResult.failure(), worker.doWork())
                        currentInput = workDataOf("actor" to "owner")
                        assertEquals(WorkResult.failure(), worker.doWork())
                        currentInput = workDataOf("actor" to "other", "owner" to "owner")
                        assertEquals(WorkResult.success(), worker.doWork())
                        currentInput = workDataOf("actor" to "owner", "owner" to "owner")
                        currentActiveNetwork = null
                        assertEquals(WorkResult.retry(), worker.doWork())
                        currentActiveNetwork = network

                        mockConstruction(MediaRepository::class.java).use {
                            mockConstruction(SafetyDocumentRepository::class.java) { docs, _ ->
                                runBlocking {
                                    `when`(docs.getFor("owner", "doc-1")).thenReturn(doc)
                                }
                            }.use { docsMock ->
                                var syncCalls = 0
                                mockConstruction(SafetyPlanRepository::class.java) { repo, _ ->
                                    val denied = mock(FirebaseFunctionsException::class.java).also {
                                        `when`(it.code).thenReturn(FirebaseFunctionsException.Code.PERMISSION_DENIED)
                                    }
                                    val invalid = mock(FirebaseFunctionsException::class.java).also {
                                        `when`(it.code).thenReturn(FirebaseFunctionsException.Code.INVALID_ARGUMENT)
                                    }
                                    runBlocking {
                                        `when`(repo.get("owner")).thenReturn(
                                            remotePlan.copy(fields = mapOf(
                                                checklistKey to SafetyField(writeSafetyChecklist(listOf(SafetyChecklistItem("row-1", "Passport", attachmentIds = listOf("doc-1")))), 2),
                                            )),
                                        )
                                        `when`(repo.mediaRecipients()).thenReturn(mapOf("staff-1" to "staff_user"))
                                        `when`(repo.sync(any<SafetyPlan>() ?: remotePlan, any())).thenAnswer { invocation ->
                                            when (++syncCalls) {
                                                1, 2 -> invocation.getArgument<SafetyPlan>(0)
                                                3 -> throw denied
                                                4 -> throw invalid
                                                else -> throw IllegalStateException("Network")
                                            }
                                        }
                                    }
                                }.use {
                                    currentInput = workDataOf("actor" to "owner", "owner" to "owner")
                                    assertEquals(WorkResult.success(), worker.doWork())

                                    currentInput = workDataOf("actor" to "owner", "owner" to "owner", "attachment" to "doc-1")
                                    assertEquals(WorkResult.success(), worker.doWork())
                                    verify(docsMock.constructed().first()).grantAccess(doc, "owner", "staff-1", "staff_user", true)

                                    currentAuthUid = "staff"
                                    currentInput = workDataOf("actor" to "staff", "owner" to "owner")
                                    store.write("staff", "owner", remotePlan)
                                    assertEquals(WorkResult.success(), worker.doWork())
                                    assertNull(store.read("staff", "owner"))

                                    currentAuthUid = "owner"
                                    currentInput = workDataOf("actor" to "owner", "owner" to "owner")
                                    assertEquals(WorkResult.failure(), worker.doWork())
                                    assertEquals(WorkResult.retry(), worker.doWork())
                                }
                            }
                        }
                    }
                }
            } finally {
                createdFirebaseApp?.delete()
            }
        }
    }
}
