package com.olive.app.notification

import android.Manifest
import android.app.NotificationManager
import android.content.ContextWrapper
import androidx.work.ListenableWorker.Result as WorkResult
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.olive.app.R
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OliveNotificationManagerTest {
    @Test
    fun notificationCategoriesHaveSeparateChannelIds() {
        val channels = with(OliveNotificationManager) {
            setOf(
                CHANNEL_CALL_INVITES,
                CHANNEL_ACTIVE_CALLS,
                CHANNEL_SAFE_UPDATES,
                CHANNEL_LOCATION,
                CHANNEL_APP_UPDATES,
            )
        }
        assertEquals(5, channels.size)
    }

    @Test
    fun incomingCallsSafeUpdatesAndScheduledWorkerNotifyWithPermissionOnly() {
        val baseApp = RuntimeEnvironment.getApplication()
        val resources = spy(baseApp.resources)
        doReturn("Synthetic").`when`(resources).getString(anyInt())
        val app = object : ContextWrapper(baseApp) {
            override fun getResources() = resources
            override fun getApplicationContext() = this
        }
        val manager = baseApp.getSystemService(NotificationManager::class.java)
        val notifications = shadowOf(manager)

        OliveNotificationManager.showIncomingCall(app, "call-no-perm", "caller-1", "Caller")
        OliveNotificationManager.showSafeUpdate(app)
        assertTrue(notifications.allNotifications.isEmpty())

        shadowOf(baseApp).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        OliveNotificationManager.showIncomingCall(app, "call-perm", "caller-1", "Caller")
        OliveNotificationManager.showSafeUpdate(app, R.string.notification_safe_app_activity, "custom-tag")
        assertEquals(2, notifications.allNotifications.size)

        val auth = mock(FirebaseAuth::class.java)
        val user = mock(FirebaseUser::class.java)
        `when`(user.uid).thenReturn("owner")
        `when`(auth.currentUser).thenReturn(user)
        val params = mock(WorkerParameters::class.java).apply {
            `when`(backgroundExecutor).thenReturn(Executor(Runnable::run))
            `when`(taskExecutor).thenReturn(mock(TaskExecutor::class.java))
            `when`(inputData).thenReturn(workDataOf(), workDataOf("uid" to "other"), workDataOf("uid" to "owner"))
        }
        val worker = SafeUpdateNotificationWorker(app, params)
        val work = mock(WorkManagerImpl::class.java)
        WorkManagerImpl.setDelegate(work)
        mockStatic(FirebaseAuth::class.java).use { authStatic ->
            authStatic.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
            try {
                assertEquals(WorkResult.success(), worker.doWork())
                assertEquals(WorkResult.success(), worker.doWork())
                assertEquals(WorkResult.success(), worker.doWork())
                assertEquals(3, notifications.allNotifications.size)
                SafeUpdateNotificationScheduler.schedule(app)
                SafeUpdateNotificationScheduler.cancel(app)
                assertTrue(SafeUpdateNotificationScheduler.randomDelaySeconds() in SafeUpdateNotificationScheduler.MIN_DELAY_SECONDS..SafeUpdateNotificationScheduler.MAX_DELAY_SECONDS)
                assertEquals(1, mockingDetails(work).invocations.count { it.method.name == "enqueueUniqueWork" })
                verify(work).cancelUniqueWork("olive_pending_safe_update_notification")
            } finally {
                WorkManagerImpl.setDelegate(null)
            }
        }
    }
}
