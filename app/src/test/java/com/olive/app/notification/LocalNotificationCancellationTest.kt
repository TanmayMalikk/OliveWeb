package com.olive.app.notification

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class LocalNotificationCancellationTest {
    @Test fun unconfiguredFirebaseDoesNotBlockSignOutCleanup() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("guest-signout-fixture", Context.MODE_PRIVATE)
        assertNull(LocalNotificationEventHandler.cancellationAccountId(preferences))
    }
    @Test fun cleanupRetainsTheRecordedAccountWhenFirebaseIsUnavailable() {
        val preferences = RuntimeEnvironment.getApplication().getSharedPreferences("account-signout-fixture", Context.MODE_PRIVATE)
        preferences.edit().putString("fcm_active_uid", "fixture-user").commit()
        assertEquals("fixture-user", LocalNotificationEventHandler.cancellationAccountId(preferences))
    }
}
