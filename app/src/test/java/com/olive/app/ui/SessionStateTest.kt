package com.olive.app.ui

import com.olive.app.data.messaging.ChatMessage
import com.olive.app.ui.session.SessionState
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateTest {
    @Test
    fun privateNavigationStateRequiresAnAuthorizedSession() {
        var authorized = false
        val state = SessionState { authorized }
        state.screen = SessionScreen.Safe
        assertEquals(SessionScreen.Cover, state.navigationState.value.screen)

        authorized = true
        state.screen = SessionScreen.Safe
        assertEquals(SessionScreen.Safe, state.navigationState.value.screen)
    }

    @Test
    fun closingMessagingPublishesClearedPrivateState() {
        val state = SessionState { true }
        state.chatMessages = listOf(ChatMessage("message", "sender", "name", "body", 1L))
        state.messagingBusy = true
        val before = state.messagingState.value

        state.clearMessaging(clearRows = true)

        assertEquals(1, before.chatMessages.size)
        assertTrue(state.messagingState.value.chatMessages.isEmpty())
        assertEquals(false, state.messagingState.value.messagingBusy)
    }
}
