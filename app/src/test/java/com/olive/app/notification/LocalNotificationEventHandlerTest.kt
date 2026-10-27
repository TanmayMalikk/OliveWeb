// Tests the local notification event handler test behavior.
package com.olive.app.notification

import com.olive.app.ui.shared.*

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Groups the data and actions for local notification event handler test.
class LocalNotificationEventHandlerTest {
    @Test
    fun backgroundWorkManagerChecksAreDisabled() {
        assertFalse(LocalNotificationEventHandler.BACKGROUND_CHECKS_ENABLED)
        assertEquals(5L, LocalNotificationEventHandler.BACKGROUND_CHECK_DELAY_SECONDS)
    }

    @Test
    // Runs when ly anew incoming message schedules anotification happens.
    fun onlyANewIncomingMessageSchedulesANotification() {
        val shouldNotify = LocalSafeUpdateCheckWorker::shouldScheduleNotification
        assertFalse(shouldNotify(null, "message-1", "friend", "current", false))
        assertTrue(shouldNotify(null, "message-1", "friend", "current", true))
        assertFalse(shouldNotify("message-1", "message-1", "friend", "current", true))
        assertFalse(shouldNotify("message-1", "message-2", "current", "current", true))
        assertFalse(shouldNotify("message-1", "", "friend", "current", true))
        assertTrue(shouldNotify("message-1", "message-2", "friend", "current", true))
    }
}
