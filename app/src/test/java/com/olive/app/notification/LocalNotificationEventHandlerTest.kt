// Tests alerts for new incoming messages and FCM push updates to signed-in users.
package com.olive.app.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNotificationEventHandlerTest {
    // Inputs: the values shown below. Expected: background work manager checks are disabled because FCM is used.
    @Test
    fun backgroundWorkManagerChecksAreDisabled() {
        assertFalse(LocalNotificationEventHandler.BACKGROUND_CHECKS_ENABLED)
        assertEquals(5L, LocalNotificationEventHandler.BACKGROUND_CHECK_DELAY_SECONDS)
    }

    // Inputs: the values shown below. Expected: only a new incoming message schedules a notification.
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

    // Inputs: FCM data payloads. Expected: only valid recipient updates that have not been seen trigger an alert.
    @Test
    fun fcmNotificationFilterAcceptsNewRecipientUpdatesAndDeduplicates() {
        val payload = mapOf(
            "type" to "safe_update",
            "recipientId" to "user-1",
            "conversationId" to "conv-1",
            "messageId" to "msg-2",
            "senderUid" to "friend-1",
        )
        assertTrue(LocalNotificationEventHandler.shouldHandleFcmNotification(payload, "user-1", "msg-1"))
        assertFalse(LocalNotificationEventHandler.shouldHandleFcmNotification(payload, "user-1", "msg-2"))
        assertFalse(LocalNotificationEventHandler.shouldHandleFcmNotification(payload, "other-user", "msg-1"))
        assertFalse(LocalNotificationEventHandler.shouldHandleFcmNotification(payload, null, "msg-1"))
        assertFalse(
            LocalNotificationEventHandler.shouldHandleFcmNotification(
                payload + ("senderUid" to "user-1"),
                "user-1",
                "msg-1",
            )
        )
        assertFalse(
            LocalNotificationEventHandler.shouldHandleFcmNotification(
                mapOf("type" to "call_invitation", "recipientId" to "user-1"),
                "user-1",
                null,
            )
        )
    }
}
