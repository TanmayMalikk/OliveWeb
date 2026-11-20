package com.olive.app.ui.covers.fitness

import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import androidx.work.workDataOf
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.olive.app.support.FirestoreTestDriver
import java.io.IOException
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FitnessUploadWorkerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val uid = "fitness-test-owner"
    private val outbox = FitnessAccountSync.outbox(context, uid)
    private val firestore = FirestoreTestDriver()
    private val user = mock(FirebaseUser::class.java)
    private val auth = mock(FirebaseAuth::class.java)
    private val parameters = mock(WorkerParameters::class.java).also {
        `when`(it.backgroundExecutor).thenReturn(Executor(Runnable::run))
        `when`(it.taskExecutor).thenReturn(mock(TaskExecutor::class.java))
        `when`(it.inputData).thenReturn(workDataOf("uid" to uid))
    }

    @Test
    fun uploadsAfterHydrationAndClearsOnlyTheVersionItSent() = withWorker { worker ->
        outbox.edit().putString("goal", "pending").commit()
        assertEquals(Result.retry(), worker.doWork())
        assertTrue(firestore.records.isEmpty())
        outbox.edit().putBoolean("_hydrated", true).commit()
        val reference = firestore.document("users/$uid/fitness/goal")
        doAnswer {
            outbox.edit().putString("goal", "newer").commit()
            Tasks.forResult<Void>(null)
        }.`when`(reference).set(anyMap<String, Any>())
        assertEquals(Result.retry(), worker.doWork())
        assertEquals("newer", outbox.getString("goal", null))

        doReturn(Tasks.forResult<Void>(null)).`when`(reference).set(anyMap<String, Any>())
        assertEquals(Result.success(), worker.doWork())
        assertFalse(outbox.contains("goal"))
        verify(reference).set(argThat<Map<String, Any>> { it["payload"] == "pending" && it["schemaVersion"] == 1 })
        verify(reference).set(argThat<Map<String, Any>> { it["payload"] == "newer" && it["schemaVersion"] == 1 })
    }

    @Test
    fun anotherAccountCannotUploadAndTemporaryFailureLeavesTheOutboxIntact() = withWorker { worker ->
        outbox.edit().putBoolean("_hydrated", true).putString("goal", "pending").commit()
        `when`(parameters.inputData).thenReturn(workDataOf())
        assertEquals(Result.failure(), worker.doWork())
        `when`(parameters.inputData).thenReturn(workDataOf("uid" to uid))
        `when`(user.uid).thenReturn("other")
        assertEquals(Result.success(), worker.doWork())
        assertTrue(firestore.records.isEmpty())

        `when`(user.uid).thenReturn(uid)
        val reference = firestore.document("users/$uid/fitness/goal")
        doReturn(Tasks.forException<Void>(IOException("Offline"))).`when`(reference).set(anyMap<String, Any>())
        assertEquals(Result.retry(), worker.doWork())
        assertEquals("pending", outbox.getString("goal", null))
        doThrow(CancellationException("Cancelled")).`when`(reference).set(anyMap<String, Any>())
        assertTrue(runCatching { worker.doWork() }.exceptionOrNull() is CancellationException)
    }

    private fun withWorker(test: suspend (FitnessUploadWorker) -> Unit) = runBlocking<Unit> {
        `when`(auth.currentUser).thenReturn(user)
        `when`(user.uid).thenReturn(uid)
        mockStatic(FirebaseAuth::class.java).use { authSdk ->
            authSdk.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
            mockStatic(FirebaseFirestore::class.java).use { firestoreSdk ->
                firestoreSdk.`when`<FirebaseFirestore> { FirebaseFirestore.getInstance() }.thenReturn(firestore.db)
                mockStatic(FirebaseStorage::class.java).use { storageSdk ->
                    storageSdk.`when`<FirebaseStorage> { FirebaseStorage.getInstance() }.thenThrow(
                        IllegalStateException("No storage in this fixture"),
                    )
                    test(FitnessUploadWorker(context, parameters))
                }
            }
        }
    }
}
