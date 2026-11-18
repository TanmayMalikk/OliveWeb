package com.olive.app.ui.session

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.olive.app.support.FirestoreTestDriver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EmergencyResponseControllerTest {
    @Test
    fun alertIndexIgnoresCachedAndMalformedRecordsAndSharesCurrentResolutionStatus() {
        val firestore = FirestoreTestDriver()
        val controller = EmergencyResponseController(RuntimeEnvironment.getApplication())
        firestore.records["emergencyReports/older"] = mapOf(
            "ownerUid" to "first", "createdAt" to Timestamp(100, 0), "mediaId" to "fixture-report",
        )
        firestore.records["emergencyReports/newer"] = mapOf(
            "ownerUid" to "second", "ownerName" to "Synthetic account", "createdAt" to Timestamp(200, 0),
        )
        firestore.records["emergencyReports/invalid"] = mapOf("mediaId" to "invalid")
        mockStatic(FirebaseFirestore::class.java).use { sdk ->
            sdk.`when`<FirebaseFirestore> { FirebaseFirestore.getInstance() }.thenReturn(firestore.db)
            val updates = mutableListOf<List<EmergencyAlert>>()
            val errors = mutableListOf<String?>()
            val subscription = controller.observeAlerts(updates::add, errors::add)
            try {
                firestore.emit("emergencyReports", cached = true)
                assertTrue(updates.isEmpty())
                firestore.emit("emergencyReports")
                assertEquals(listOf("newer", "older"), updates.single().map { it.id })
                assertEquals("first", updates.single().last().ownerName)
                assertEquals("fixture-report", updates.single().last().mediaId)
                assertEquals(0L, updates.single().first().resolvedAt)
                val existing = firestore.records.getValue("emergencyReports/newer")
                firestore.records["emergencyReports/newer"] = existing + ("resolvedAt" to Timestamp(300, 0))
                firestore.emit("emergencyReports")
                assertEquals(300_000L, updates.last().first().resolvedAt)
                firestore.fail("emergencyReports", FirebaseFirestoreException(
                    "Synthetic permission failure", FirebaseFirestoreException.Code.PERMISSION_DENIED,
                ))
                assertEquals(listOf("Synthetic permission failure"), errors)
            } finally {
                subscription.close()
            }
            firestore.emit("emergencyReports")
            assertEquals(2, updates.size)
            assertTrue(firestore.listeners.single().closed)
        }
    }

    @Test
    fun resolvingAnAlertRequiresTheSameAuthenticatedAccount() = runBlocking<Unit> {
        val auth = mock(FirebaseAuth::class.java)
        val user = mock(FirebaseUser::class.java)
        `when`(auth.currentUser).thenReturn(user)
        `when`(user.uid).thenReturn("current-admin")
        val controller = EmergencyResponseController(RuntimeEnvironment.getApplication())
        mockStatic(FirebaseAuth::class.java).use { sdk ->
            sdk.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
            val failure = runCatching { controller.setResolved("previous-admin", "fixture", true) }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            verify(user, never()).getIdToken(anyBoolean())
        }
    }
}
