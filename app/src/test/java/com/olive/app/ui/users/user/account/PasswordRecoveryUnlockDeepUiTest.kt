package com.olive.app.ui.users.user.account

import android.app.Application
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.olive.app.data.account.AccountRole
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.session.PasswordRecoveryUnlockRequest
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
@OptIn(ExperimentalCoroutinesApi::class)
class PasswordRecoveryUnlockDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun passwordRecoveryUnlockDialogComposesAndCompletesCleanly() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, localMode = true).use { handle ->
            val session = handle.session
            session.runtime.state.passwordRecoveryUnlockRequest = null

            compose.setContent {
                OliveUiTestHost {
                    PasswordRecoveryUnlockDialog(session)
                }
            }
            compose.mainClock.advanceTimeBy(150)

            val req1 = PasswordRecoveryUnlockRequest()
            compose.runOnUiThread {
                session.runtime.state.passwordRecoveryUnlockRequest = req1
                Snapshot.sendApplyNotifications()
            }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(250)
            compose.runOnUiThread {
                session.runtime.state.passwordRecoveryUnlockRequest = null
                Snapshot.sendApplyNotifications()
            }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(150)
            req1.complete("Recovery#2026")
            assertEquals("Recovery#2026", req1.result.getCompleted())

            val req2 = PasswordRecoveryUnlockRequest()
            req2.complete(null)
            assertNull(req2.result.getCompleted())
            compose.mainClock.autoAdvance = true
        }
    }
}
