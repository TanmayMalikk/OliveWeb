package com.olive.app.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModelProvider
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.UserProfile
import com.olive.app.R
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.messaging.FriendInvite
import com.olive.app.security.DeviceEnrollment
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.users.shared.messaging.MessagesScreen
import com.olive.app.ui.users.shared.messaging.FriendInviteDialog
import com.olive.app.ui.users.shared.navigation.LocalSafeSystemLauncher
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

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

    @Test
    fun inviteDialogCopiesSharesAndRefreshesAfterClaimOrNetworkFailure() {
        val targets = mutableMapOf<String, () -> Unit>()
        val stopped = mutableListOf<String>()
        val attempts = mutableListOf<Boolean>()
        val expires = System.currentTimeMillis() + 48 * 60 * 60 * 1000L
        val first = FriendInvite("Fixture1", "https://example.test/get/?invite=Fixture1", expires)
        val renewed = FriendInvite("Fixture2", "https://example.test/get/?invite=Fixture2", expires)
        var dismissed = false
        var share: Intent? = null
        compose.setContent {
            OliveTheme {
                CompositionLocalProvider(LocalSafeSystemLauncher provides { share = it; true }) {
                    FriendInviteDialog("synthetic-owner", createInvite = { refresh ->
                        attempts += refresh
                        when (attempts.size) {
                            1 -> first
                            2 -> throw IllegalStateException("Synthetic offline error")
                            else -> renewed
                        }
                    }, observeUnavailable = { code, unavailable ->
                        targets[code] = unavailable
                        ChatSubscription { stopped += code }
                    }, onDismiss = { dismissed = true })
                }
            }
        }
        fun label(id: Int) = compose.activity.getString(id)
        compose.onNodeWithText(first.code).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.friend_invite_copy_code)).performScrollTo().performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(first.code, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        compose.onNodeWithText(label(R.string.friend_invite_copy_link)).performScrollTo().performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(first.link, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        }
        compose.onNodeWithText(label(R.string.friend_invite_share)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(Intent.ACTION_CHOOSER, share?.action) }
        compose.runOnIdle { targets.getValue(first.code)() }
        compose.onNodeWithText(label(R.string.friend_invite_unavailable)).assertIsDisplayed()
        compose.onNodeWithText(first.code).assertDoesNotExist()
        val refresh = compose.onNodeWithText(label(R.string.friend_invite_refresh))
        refresh.performScrollTo().performClick()
        compose.onNodeWithText("Synthetic offline error").assertIsDisplayed()
        refresh.performClick()
        compose.onNodeWithText(renewed.code).assertIsDisplayed()
        compose.runOnIdle {
            targets.getValue(first.code)()
            assertTrue(first.code in stopped)
            assertEquals(listOf(false, true, true), attempts)
        }
        compose.onNodeWithText(renewed.code).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.friend_invite_done)).performClick()
        compose.runOnIdle { assertTrue(dismissed) }
    }
}
