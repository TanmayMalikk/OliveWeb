package com.olive.app.ui.users.shared.navigation

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.support.createUiTestSession
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class AppScreenNavigationDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun oliveRootRendersStorageErrorIdleCountdownPrivacyBlackoutAndPortals() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.StorageError, localMode = true).use { handle ->
            val session = handle.session
            compose.setContent {
                OliveRoot(session, locationPermissionVersion = 0, closeApp = {})
            }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText(context.getString(R.string.unable_to_open_local_storage)).assertExists()
            compose.onNodeWithText(context.getString(R.string.retry)).assertExists()

            // Switch to Safe with idle countdown warning dialog and stay-here dismiss
            session.runtime.state.screen = SessionScreen.Safe
            session.runtime.state.idleCountdownSeconds = 18
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("idle_stay_here").performClick()
            compose.mainClock.advanceTimeBy(200)

            // Switch to privacy blackout
            session.runtime.state.privacyBlackout = true
            compose.mainClock.advanceTimeBy(200)
            session.runtime.state.privacyBlackout = false
            compose.mainClock.advanceTimeBy(200)

            // Switch to Staff portal
            session.runtime.state.accountRole = AccountRole.STAFF
            session.runtime.state.screen = SessionScreen.Portal
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithText(context.getString(R.string.staff_panel)).assertExists()

            // Switch to Admin portal
            session.runtime.state.accountRole = AccountRole.ADMIN
            session.runtime.state.coverReset += 1
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("sign_out").assertExists()
        }
    }
}
