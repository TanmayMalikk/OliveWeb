package com.olive.app.notification

import org.junit.Assert.assertEquals
import org.junit.Test

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
}
