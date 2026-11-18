package com.olive.app.data.messaging

import android.Manifest
import android.app.NotificationManager
import android.content.ContextWrapper
import android.content.Intent
import com.olive.app.OliveActivity
import androidx.work.impl.WorkManagerImpl
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.messaging.RemoteMessage
import com.olive.app.BuildConfig
import com.olive.app.notification.OliveNotificationManager
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
class OliveFirebaseMessagingServiceTest {
    @Test
    fun onlyNewerReleasePushesShowAnUpdateAndScheduleTheDownloadCheck() = withService { service, _, work ->
        val notifications = shadowOf(service.getSystemService(NotificationManager::class.java))
        for (version in listOf(BuildConfig.VERSION_CODE - 1, BuildConfig.VERSION_CODE)) {
            service.onMessageReceived(message("app_update", "versionCode" to version.toString()))
        }
        assertTrue(notifications.allNotifications.isEmpty())
        assertTrue(mockingDetails(work).invocations.isEmpty())
        service.onMessageReceived(message("app_update",
            "versionCode" to (BuildConfig.VERSION_CODE + 1).toString(), "versionName" to "fixture-update"))
        val notification = notifications.allNotifications.single()
        assertEquals(OliveNotificationManager.CHANNEL_APP_UPDATES, notification.channelId)
        assertTrue(mockingDetails(work).invocations.any {
            it.method.name == "enqueueUniqueWork" && it.arguments[0] == "olive-update-fcm"
        })
    }

    @Test
    fun registrationPersistsTheInstallationAndOrdinaryNotificationsRespectTheCurrentAccount() =
        withService { service, auth, work ->
            service.onRegistered("fixture-installation")
            assertEquals("fixture-installation", service.getSharedPreferences("voip_push_token", 0).getString("token", null))
            assertTrue(mockingDetails(work).invocations.any {
                it.method.name == "enqueueUniqueWork" && it.arguments[0] == "olive-update-registration"
            })
            val notifications = shadowOf(service.getSystemService(NotificationManager::class.java))
            service.onMessageReceived(message("emergency", "recipientId" to "another", "emergencyId" to "foreign"))
            assertTrue(notifications.allNotifications.isEmpty())
            service.onMessageReceived(message("emergency", "recipientId" to "fixture-owner", "emergencyId" to "owned"))
            assertEquals(1, notifications.allNotifications.size)
            `when`(auth.currentUser).thenReturn(null)
            service.onMessageReceived(message("sync_invalidation", "revision" to "12", "urgent" to "1"))
            assertEquals(1, notifications.allNotifications.size)
        }

    private fun message(type: String, vararg data: Pair<String, String>) =
        RemoteMessage.Builder("fixture").setData(mapOf("type" to type, *data)).build()

    private fun withService(test: (OliveFirebaseMessagingService, FirebaseAuth, WorkManagerImpl) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val auth = mock(FirebaseAuth::class.java)
        val user = mock(FirebaseUser::class.java)
        `when`(user.uid).thenReturn("fixture-owner")
        `when`(auth.currentUser).thenReturn(user)
        val work = mock(WorkManagerImpl::class.java)
        val service = Robolectric.buildService(OliveFirebaseMessagingService::class.java).get()
        val resources = spy(service.resources)
        doReturn("Synthetic notification").`when`(resources).getString(anyInt())
        val packages = spy(service.packageManager)
        doReturn(Intent(app, OliveActivity::class.java)).`when`(packages).getLaunchIntentForPackage(app.packageName)
        ReflectionHelpers.setField(service, "mBase", object : ContextWrapper(service.baseContext) {
            override fun getResources() = resources
            override fun getPackageManager() = packages
        })
        WorkManagerImpl.setDelegate(work)
        try {
            mockStatic(FirebaseAuth::class.java).use { firebase ->
                firebase.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
                test(service, auth, work)
            }
        } finally {
            WorkManagerImpl.setDelegate(null)
        }
    }
}
