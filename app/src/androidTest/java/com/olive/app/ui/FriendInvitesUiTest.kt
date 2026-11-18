package com.olive.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.UserProfile
import com.olive.app.security.DeviceEnrollment
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.users.shared.messaging.MessagesScreen
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import org.junit.Rule
import org.junit.Test

class FriendInvitesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun showMessages(role: AccountRole) {
        lateinit var session: SessionViewModel
        compose.runOnUiThread { session = ViewModelProvider(compose.activity)[SessionViewModel::class.java] }
        check(session.localMode)
        compose.waitUntil(30_000) { !session.busy }
        compose.runOnIdle {
            session.runtime.enrollment = DeviceEnrollment("invite-ui-example", "", role)
            session.runtime.pinAuthorizedUid = "invite-ui-example"
            session.runtime.pinAuthorizedEpoch = session.runtime.epoch
            session.runtime.state.accountRole = role
            session.runtime.state.profile = UserProfile("invite_example", "Example Person", "", "", "1990-01-01", "English", "", "Alberta", "", role=role)
            session.runtime.state.page = SafePage.Messages
            session.runtime.state.screen = SessionScreen.Safe
        }
        compose.setContent { OliveTheme { MessagesScreen(session) } }
        compose.onNodeWithTag("friend_invite").assertIsDisplayed()
        compose.onNodeWithTag("friend_invite_code").assertExists()
        compose.onNodeWithTag("friend_invite_claim").assertIsNotEnabled()
        compose.onNodeWithTag("message_username").assertDoesNotExist()
        compose.onNodeWithTag("search_users").assertDoesNotExist()
    }

    @Test fun userMessagesOfferInvitesWithoutUsernameDiscovery() = showMessages(AccountRole.USER)
    @Test fun safeContactMessagesOfferInvitesWithoutUsernameDiscovery() = showMessages(AccountRole.SAFE_CONTACT)
}
