package com.olive.app.ui.users.shared.messaging

import com.olive.app.data.AccountRole
import com.olive.app.data.messaging.VoipCall
import com.olive.app.data.messaging.VoipParticipant
import com.olive.app.data.messaging.VoipParticipantStatus
import com.olive.app.data.messaging.otherPartyName
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.shared.navigation.shouldShowCallOverlay
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCallScreenTest {
    @Test
    fun callDurationUsesPhoneStyleClockFormatting() {
        assertEquals("00:00", formatCallDuration(0))
        assertEquals("09:05", formatCallDuration(545))
        assertEquals("1:02:03", formatCallDuration(3_723))
    }

    @Test
    fun eachPhoneDisplaysTheOtherPersonsName() {
        val call = VoipCall(
            callId = "call-1",
            name = "Tan",
            callerId = "lars-id",
            participants = listOf(
                VoipParticipant("lars-id", VoipParticipantStatus.ACCEPTED),
                VoipParticipant("tan-id", VoipParticipantStatus.PENDING),
            ),
            callerName = "Lars",
        )

        assertEquals("Lars", call.otherPartyName("tan-id"))
        assertEquals("Tan", call.otherPartyName("lars-id"))
    }

    @Test
    fun callsOverlayBothTheSafeAppAndUserCover() {
        assertEquals(true, shouldShowCallOverlay(SessionScreen.Safe, AccountRole.USER))
        assertEquals(true, shouldShowCallOverlay(SessionScreen.Cover, AccountRole.USER))
        assertEquals(true, shouldShowCallOverlay(SessionScreen.Safe, AccountRole.SAFE_CONTACT))
        assertEquals(false, shouldShowCallOverlay(SessionScreen.Cover, AccountRole.SAFE_CONTACT))
        assertEquals(false, shouldShowCallOverlay(SessionScreen.Welcome, AccountRole.USER))
    }
}
