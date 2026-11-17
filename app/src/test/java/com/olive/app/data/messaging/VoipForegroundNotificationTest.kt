package com.olive.app.data.messaging

import android.app.Notification
import android.app.Service
import android.content.ContextWrapper
import android.content.Intent
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
        val resources = spy(service.resources)
        doReturn("Synthetic call").`when`(resources).getString(anyInt())
        ReflectionHelpers.setField(service, "mBase", object : ContextWrapper(service.baseContext) {
            override fun getResources() = resources
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
}
