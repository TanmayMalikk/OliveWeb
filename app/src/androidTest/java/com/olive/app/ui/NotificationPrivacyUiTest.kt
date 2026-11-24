package com.olive.app.ui

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.R
import com.olive.app.notification.OliveNotificationManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@SdkSuppress(minSdkVersion = 33)
class NotificationPrivacyUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val call = "coverage-fixture-call"
    private val tag = "coverage-fixture-safe-update"

    @Before
    fun grantNotificationPermission() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun removeFixtureAlerts() {
        manager.cancel(call.hashCode())
        manager.cancel(tag, 0)
    }

    @Test
    fun incomingCallAlertDoesNotShowTheCallersName() {
        OliveNotificationManager.showIncomingCall(context, call, "test-caller", "Private caller")
        val alert = manager.activeNotifications.single { it.id == call.hashCode() }.notification
        assertEquals(OliveNotificationManager.CHANNEL_CALL_INVITES, alert.channelId)
        assertEquals(Notification.CATEGORY_CALL, alert.category)
        assertEquals(20_000L, alert.timeoutAfter)
        assertEquals(context.getString(R.string.ui_incoming_call), alert.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertFalse(alert.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Private caller"))
        assertEquals(context.packageName, alert.contentIntent.creatorPackage)
    }

    @Test
    fun repeatedSafeActivityWithTheSameTagReplacesThePreviousBanner() {
        OliveNotificationManager.showSafeUpdate(context, tag = tag)
        OliveNotificationManager.showSafeUpdate(context, body = R.string.notification_new_app_update, tag = tag)
        val alert = manager.activeNotifications.single { it.tag == tag }.notification
        assertEquals(OliveNotificationManager.CHANNEL_SAFE_UPDATES, alert.channelId)
        assertEquals(context.getString(R.string.notification_new_app_update), alert.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(context.packageName, alert.contentIntent.creatorPackage)
    }
}
