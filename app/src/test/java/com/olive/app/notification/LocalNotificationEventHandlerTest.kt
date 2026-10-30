// Tests alerts for new incoming messages to signed-in users.
package com.olive.app.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNotificationEventHandlerTest {
    // Inputs: the values shown below. Expected: background work manager checks are disabled.
    @Test
    fun backgroundWorkManagerChecksAreDisabled() {
        assertFalse(LocalNotificationEventHandler.BACKGROUND_CHECKS_ENABLED)
        assertEquals(5L, LocalNotificationEventHandler.BACKGROUND_CHECK_DELAY_SECONDS)
    }

    // Inputs: the values shown below. Expected: only anew incoming message schedules anotification.
    @Test
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
