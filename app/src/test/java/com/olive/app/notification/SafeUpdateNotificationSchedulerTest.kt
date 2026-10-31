// Tests the notification delay used by discreet cover updates.
package com.olive.app.notification

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SafeUpdateNotificationSchedulerTest {
    // Inputs: the values shown below. Expected: random delay always stays inside configured window.
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
