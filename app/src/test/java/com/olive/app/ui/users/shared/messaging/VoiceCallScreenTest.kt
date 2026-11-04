package com.olive.app.ui.users.shared.messaging

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCallScreenTest {
    @Test
    fun callDurationUsesPhoneStyleClockFormatting() {
        assertEquals("00:00", formatCallDuration(0))
        assertEquals("09:05", formatCallDuration(545))
        assertEquals("1:02:03", formatCallDuration(3_723))
    }
}
