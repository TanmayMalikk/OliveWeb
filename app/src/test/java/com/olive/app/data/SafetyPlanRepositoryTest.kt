package com.olive.app.data

import com.google.android.gms.tasks.Tasks
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
import com.google.firebase.functions.HttpsCallableReference
import com.google.firebase.functions.HttpsCallableResult
import com.olive.app.data.messaging.Friend
import com.olive.app.data.safety.SafetyField
import com.olive.app.data.safety.SafetyPlan
import com.olive.app.data.safety.SafetyPlanRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
}
