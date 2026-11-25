package com.olive.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.olive.app.data.account.AccountRole
import com.olive.app.security.IdlePolicy
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.IdleLockWarningDialog
import com.olive.app.ui.users.shared.navigation.OliveRoot
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class OliveActivityAndAppUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun idleLockWarningDialogHandlesExtendStayAndExit() {
        var stayed = 0
        var exited = 0
        var extendedTo = 0
        val extendOptions = IdlePolicy.extendOptions(60)

        compose.setContent {
            OliveUiTestHost {
                IdleLockWarningDialog(
                    seconds = 18,
                    usesCover = true,
                    currentTimeoutSeconds = 60,
                    onStayHere = { stayed++ },
                    onExtendTimeout = { extendedTo = it },
                    onQuickExit = { exited++ },
                )
            }
        }
        compose.waitForIdle()
        if (extendOptions.isNotEmpty()) {
            compose.onNodeWithTag("idle_extend_${extendOptions.first()}").performClick()
            assertEquals(extendOptions.first(), extendedTo)
        }
        compose.onNodeWithTag("idle_stay_here").performClick()
        compose.onNodeWithTag("idle_quick_exit").performClick()
        assertEquals(1, stayed)
        assertEquals(1, exited)
    }

    @Test
    fun safeAreaCoversHomeContactsHotlinesActivityProfileAndCoverSettings() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Home, localMode = true).use { handle ->
            compose.setContent {
                OliveUiTestHost {
                    OliveRoot(handle.session, locationPermissionVersion = 0, closeApp = {})
                }
            }
            compose.mainClock.advanceTimeBy(200)
            listOf(
                SafePage.Home,
                SafePage.Contacts,
                SafePage.Hotlines,
                SafePage.Activity,
                SafePage.Profile,
                SafePage.Settings,
                SafePage.StaffHelp,
                SafePage.Documents,
                SafePage.Notes,
                SafePage.SafetyPlan,
                SafePage.Messages,
                SafePage.Location,
                SafePage.Walkthrough,
            ).forEach { targetPage ->
                compose.runOnUiThread { handle.session.runtime.state.page = targetPage }
                compose.mainClock.advanceTimeBy(220)
            }
        }
    }
}
