package com.olive.app.data.messaging

import android.content.Context
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import androidx.work.workDataOf
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.olive.app.data.account.AccountRole
import java.io.IOException
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class VoipPushRegistrationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val auth = mock(FirebaseAuth::class.java)
    private val user = mock(FirebaseUser::class.java)
    private val preferences = context.getSharedPreferences("voip_push_token", Context.MODE_PRIVATE)
    private val parameters = mock(WorkerParameters::class.java).also {
        `when`(it.backgroundExecutor).thenReturn(Executor(Runnable::run))
        `when`(it.taskExecutor).thenReturn(mock(TaskExecutor::class.java))
    }

    @Test
    fun pushRefreshDeduplicatesWorkAndStaffSignInCancelsRegistration() = withAuth {
        assumeTrue(isVoipApiConfigured())
        val work = mock(WorkManagerImpl::class.java)
        WorkManagerImpl.setDelegate(work)
        try {
            `when`(auth.currentUser).thenReturn(user)
            val blank = runCatching { VoipPushTokenRegistration.onTokenRefreshed(context, " ") }.exceptionOrNull()
            assertTrue(blank is IllegalArgumentException)
            VoipPushTokenRegistration.onTokenRefreshed(context, "new-token")
            assertEquals("new-token", preferences.getString("token", null))
            verifyNoInteractions(work)

            preferences.edit().putBoolean("enabled_for_account", true).commit()
            VoipPushTokenRegistration.onTokenRefreshed(context, "newer-token")
            val device = preferences.getString("device_id", null)
            assertFalse(device.isNullOrBlank())
            VoipPushTokenRegistration.onTokenRefreshed(context, "latest-token")
            assertEquals(device, preferences.getString("device_id", null))
            assertEquals(2, mockingDetails(work).invocations.count { it.method.name == "enqueueUniqueWork" })
            mockConstruction(VoipManager::class.java).use { managers ->
                VoipPushTokenRegistration.configureForRole(context, AccountRole.STAFF)
                assertFalse(VoipPushTokenRegistration.isEnabled(context))
                verify(work).cancelUniqueWork("voip-push-token-registration")
                verify(managers.constructed().single()).unregisterPushToken(requireNotNull(device))
            }
        } finally {
            WorkManagerImpl.setDelegate(null)
        }
    }

    @Test
    fun signingInEnablesCallInvitationsAndRotationReplacesTheTokenForTheSameDevice() = withAuth {
        assumeTrue(isVoipApiConfigured())
        val work = mock(WorkManagerImpl::class.java)
        val messaging = mock(FirebaseMessaging::class.java)
        val installations = mock(FirebaseInstallations::class.java)
        `when`(messaging.register()).thenReturn(Tasks.forResult(null))
        `when`(messaging.unregister()).thenReturn(Tasks.forResult(null))
        `when`(installations.id).thenReturn(Tasks.forResult("old-token"), Tasks.forResult("replacement-token"))
        `when`(installations.delete()).thenReturn(Tasks.forResult(null))
        WorkManagerImpl.setDelegate(work)
        try {
            val signedOut = runCatching { VoipPushTokenRegistration.registerCurrentToken(context) }.exceptionOrNull()
            assertEquals("Sign in before registering this device for call invitations", signedOut?.message)
            `when`(auth.currentUser).thenReturn(user)
            val disabled = runCatching { VoipPushTokenRegistration.registerCurrentToken(context) }.exceptionOrNull()
            assertEquals("Voice calls are not enabled for this account type", disabled?.message)
            mockStatic(FirebaseMessaging::class.java).use { firebaseMessaging ->
                firebaseMessaging.`when`<FirebaseMessaging> { FirebaseMessaging.getInstance() }.thenReturn(messaging)
                mockStatic(FirebaseInstallations::class.java).use { firebaseInstallations ->
                    firebaseInstallations.`when`<FirebaseInstallations> { FirebaseInstallations.getInstance() }.thenReturn(installations)
                    VoipPushTokenRegistration.configureForRole(context, AccountRole.SAFE_CONTACT)
                    assertTrue(VoipPushTokenRegistration.isEnabled(context))
                    assertEquals("old-token", preferences.getString("token", null))
                    val device = requireNotNull(preferences.getString("device_id", null))
                    mockConstruction(VoipManager::class.java).use { managers ->
                        VoipPushTokenRegistration.rotateCurrentToken(context)
                        val (remover, registrar) = managers.constructed()
                        verify(remover).unregisterPushToken(device)
                        verify(registrar).registerPushToken(device, "replacement-token")
                    }
                    assertEquals("replacement-token", preferences.getString("token", null))
                    assertEquals(device, preferences.getString("device_id", null))
                    verify(messaging, times(2)).register()
                    verify(messaging).unregister()
                    verify(installations).delete()
                }
            }
        } finally {
            WorkManagerImpl.setDelegate(null)
        }
    }

    @Test
    fun workerStopsWhenSignedOutAndRetriesOnlyTemporaryFailures() = withAuth {
        `when`(parameters.inputData).thenReturn(workDataOf(), workDataOf("token" to "token"))
        val worker = VoipPushTokenRegistrationWorker(context, parameters)
        assertEquals(Result.failure(), worker.doWork())
        assertEquals(Result.failure(), worker.doWork())
        `when`(parameters.inputData).thenReturn(workDataOf("token" to "token", "device_id" to "device"))
        assertEquals(Result.success(), worker.doWork())
        `when`(auth.currentUser).thenReturn(user)
        assertEquals(Result.success(), worker.doWork())
        assumeTrue(isVoipApiConfigured())
        preferences.edit().putBoolean("enabled_for_account", true).commit()
        val outcomes = listOf(
            null to Result.success(),
            VoipApiException(429, null, "Busy") to Result.retry(),
            VoipApiException(403, null, "Denied") to Result.failure(),
            IOException("Offline") to Result.retry(),
            IllegalArgumentException("Invalid token") to Result.failure(),
        )
        for ((failure, expected) in outcomes) {
            mockConstruction(VoipManager::class.java) { manager, _ ->
                if (failure != null) {
                    runBlocking { doAnswer { throw failure }.`when`(manager).registerPushToken("device", "token") }
                }
            }.use { managers ->
                assertEquals(expected, worker.doWork())
                verify(managers.constructed().single()).registerPushToken("device", "token")
            }
        }
    }

    @Test
    fun directTokenLifecycleAndWorkerBranchesWorkWhenEnabledInPreferences() = withAuth {
        val work = mock(WorkManagerImpl::class.java)
        val messaging = mock(FirebaseMessaging::class.java)
        val installations = mock(FirebaseInstallations::class.java)
        `when`(messaging.register()).thenReturn(Tasks.forResult(null))
        `when`(messaging.unregister()).thenReturn(Tasks.forResult(null))
        `when`(installations.id).thenReturn(Tasks.forResult("rotated-token"))
        `when`(installations.delete()).thenReturn(Tasks.forResult(null))
        WorkManagerImpl.setDelegate(work)
        try {
            `when`(auth.currentUser).thenReturn(null)
            assertTrue(runCatching { VoipPushTokenRegistration.registerCurrentToken(context) }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { VoipPushTokenRegistration.unregisterCurrentDevice(context) }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { VoipPushTokenRegistration.rotateCurrentToken(context) }.exceptionOrNull() is IllegalStateException)

            `when`(auth.currentUser).thenReturn(user)
            assertTrue(runCatching { VoipPushTokenRegistration.registerCurrentToken(context) }.exceptionOrNull() is IllegalStateException)
            assertTrue(runCatching { VoipPushTokenRegistration.onTokenRefreshed(context, " ") }.exceptionOrNull() is IllegalArgumentException)
            VoipPushTokenRegistration.onTokenRefreshed(context, "initial-token")
            assertEquals("initial-token", preferences.getString("token", null))

            preferences.edit().putBoolean("enabled_for_account", true).commit()
            mockStatic(FirebaseMessaging::class.java).use { firebaseMessaging ->
                firebaseMessaging.`when`<FirebaseMessaging> { FirebaseMessaging.getInstance() }.thenReturn(messaging)
                mockStatic(FirebaseInstallations::class.java).use { firebaseInstallations ->
                    firebaseInstallations.`when`<FirebaseInstallations> { FirebaseInstallations.getInstance() }.thenReturn(installations)
                    mockConstruction(VoipManager::class.java).use { managers ->
                        VoipPushTokenRegistration.rotateCurrentToken(context)
                        VoipPushTokenRegistration.configureForRole(context, AccountRole.ADMIN)
                        assertEquals("rotated-token", preferences.getString("token", null))
                        assertFalse(VoipPushTokenRegistration.isEnabled(context))
                        verify(work).cancelUniqueWork("voip-push-token-registration")
                        assertEquals(3, managers.constructed().size)
                    }
                }
            }

            var workerInput = workDataOf("token" to "token", "device_id" to "device")
            val workerParams = mock(WorkerParameters::class.java).apply {
                `when`(backgroundExecutor).thenReturn(Executor(Runnable::run))
                `when`(taskExecutor).thenReturn(mock(TaskExecutor::class.java))
                `when`(inputData).thenAnswer { workerInput }
            }
            val worker = VoipPushTokenRegistrationWorker(context, workerParams)
            assertEquals(Result.success(), worker.doWork())
            workerInput = workDataOf("token" to "token")
            assertEquals(Result.failure(), worker.doWork())
        } finally {
            preferences.edit().clear().commit()
            WorkManagerImpl.setDelegate(null)
        }
    }

    private fun withAuth(action: suspend () -> Unit) = runBlocking<Unit> {
        mockStatic(FirebaseAuth::class.java).use {
            it.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
            action()
        }
    }
}
