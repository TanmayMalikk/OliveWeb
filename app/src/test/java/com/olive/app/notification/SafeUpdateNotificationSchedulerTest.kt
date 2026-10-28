// Tests the notification delay used by discreet cover updates.
package com.olive.app.notification

import com.olive.app.ui.shared.*

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SafeUpdateNotificationSchedulerTest {
    @Test
    fun randomDelayAlwaysStaysInsideConfiguredWindow() {
        val random = Random(395)
        repeat(1_000) {
            assertTrue(
                SafeUpdateNotificationScheduler.randomDelaySeconds(random) in
                        SafeUpdateNotificationScheduler.MIN_DELAY_SECONDS..SafeUpdateNotificationScheduler.MAX_DELAY_SECONDS
            )
        }
    }
}
