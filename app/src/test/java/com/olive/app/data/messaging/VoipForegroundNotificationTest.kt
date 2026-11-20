package com.olive.app.data.messaging

import android.app.Notification
import android.app.Service
import android.content.ContextWrapper
import android.content.Intent
import androidx.work.impl.WorkManagerImpl
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoipForegroundNotificationTest {
    @Test
    fun foregroundActionsAddressTheRightCallAndQuickExitIsOptional() {
        val app = RuntimeEnvironment.getApplication()
        val service = Robolectric.buildService(VoipCallForegroundService::class.java).get()
        val pm = spy(service.packageManager)
        doReturn(Intent(app, VoipCallForegroundService::class.java)).`when`(pm).getLaunchIntentForPackage(app.packageName)
        val resources = spy(service.resources)
        doReturn("Synthetic call").`when`(resources).getString(anyInt())
        ReflectionHelpers.setField(service, "mBase", object : ContextWrapper(service.baseContext) {
            override fun getResources() = resources
            override fun getPackageManager() = pm
        })
        service.onCreate()
        val system = shadowOf(service)
        VoipCallForegroundService.start(app, "call-one", true)
        val command = shadowOf(app).nextStartedService
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(command, 0, 1))
        val notification = system.lastForegroundNotification
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(
            listOf(VoipCallForegroundService.ACTION_END_CALL, VoipCallForegroundService.ACTION_QUICK_EXIT),
            notification.actions.map { shadowOf(it.actionIntent).savedIntent.action },
        )
        notification.actions.forEach { action ->
            val intent = shadowOf(action.actionIntent).savedIntent
            assertEquals("call-one", intent.data?.lastPathSegment)
            assertEquals(app.packageName, intent.`package`)
        }

        VoipCallForegroundService.start(app, "call-two", false)
        service.onStartCommand(shadowOf(app).nextStartedService, 0, 2)
        val only = system.lastForegroundNotification.actions.single()
        assertEquals("call-two", shadowOf(only.actionIntent).savedIntent.data?.lastPathSegment)
        assertNull(service.onBind(null))
        VoipCallForegroundService.stop(app)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(), 0, 3))
        service.onDestroy()
    }

    @Test
    fun registerForTokenUpdatesSavesInstallationTokenRegardlessOfEnabledFlag() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val auth = mock(FirebaseAuth::class.java)
        val user = mock(FirebaseUser::class.java)
        val messaging = mock(FirebaseMessaging::class.java)
        val installations = mock(FirebaseInstallations::class.java)
        val work = mock(WorkManagerImpl::class.java)
        `when`(auth.currentUser).thenReturn(user)
        `when`(messaging.register()).thenReturn(Tasks.forResult(null))
        `when`(installations.id).thenReturn(Tasks.forResult("refreshed-installation-token"))
        WorkManagerImpl.setDelegate(work)
        mockStatic(FirebaseAuth::class.java).use { authStatic ->
            authStatic.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
            mockStatic(FirebaseMessaging::class.java).use { msgStatic ->
                msgStatic.`when`<FirebaseMessaging> { FirebaseMessaging.getInstance() }.thenReturn(messaging)
                mockStatic(FirebaseInstallations::class.java).use { instStatic ->
                    instStatic.`when`<FirebaseInstallations> { FirebaseInstallations.getInstance() }.thenReturn(installations)
                    try {
                        VoipPushTokenRegistration.registerForTokenUpdates(app)
                        val prefs = app.getSharedPreferences("voip_push_token", android.content.Context.MODE_PRIVATE)
                        assertEquals("refreshed-installation-token", prefs.getString("token", null))
                    } finally {
                        WorkManagerImpl.setDelegate(null)
                    }
                }
            }
        }
    }
}
