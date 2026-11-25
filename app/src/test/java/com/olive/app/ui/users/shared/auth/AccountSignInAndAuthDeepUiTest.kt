package com.olive.app.ui.users.shared.auth

import android.app.Application
import android.app.KeyguardManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.UserProfile
import com.olive.app.security.SafeEntryMethod
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class AccountSignInAndAuthDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun appPinSetupAndPinPromptCoverValidationDeviceLockAndSubmission() {
        val km = context.getSystemService(KeyguardManager::class.java)
        shadowOf(km).setIsDeviceSecure(true)
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Pin, localMode = true).use { handle ->
            val session = handle.session
            val profile = UserProfile(username = "alex", fullName = "Alex", dob = "1995-05-05", role = AccountRole.USER)
            val identity = AccountIdentity("owner-pin", "alex")
            session.runtime.pendingIdentity = identity
            session.runtime.pendingProfile = profile

            var showSetup by mutableStateOf(true)
            compose.setContent {
                OliveUiTestHost {
                    if (showSetup) {
                        AppPinSetup(session, onBack = {})
                    } else {
                        PinPrompt(session)
                    }
                }
            }
            compose.waitForIdle()

            // Trigger validation shake, mismatch shake, then valid PIN
            compose.onNodeWithTag("pin_setup_submit").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("new_pin").performScrollTo().performTextReplacement("2468")
            compose.onNodeWithTag("confirm_pin").performScrollTo().performTextReplacement("1357")
            compose.onNodeWithTag("pin_setup_submit").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("confirm_pin").performScrollTo().performTextReplacement("2468")
            compose.onNodeWithTag("pin_setup_submit").performScrollTo().performClick()
            compose.waitForIdle()

            // Switch to PinPrompt (DeviceLock -> switch to backup PIN -> short error -> valid submit)
            val enrolled = session.runtime.store.enroll(identity, profile, "2468")
            session.runtime.enrollment = session.runtime.store.updateSafeEntry(
                enrolled,
                SafeEntryMethod.DEVICE_LOCK,
                null,
                org.json.JSONObject().put("granted", true),
            )
            session.runtime.state.screen = SessionScreen.Pin
            showSetup = false
            compose.waitForIdle()
            compose.onNodeWithTag("device_lock_unlock").performClick()
            compose.onNodeWithTag("use_backup_pin").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("unlock_pin").performScrollTo().performTextInput("12")
            compose.onNodeWithTag("unlock_submit").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("unlock_pin").performScrollTo().performTextInput("34")
            compose.onNodeWithTag("unlock_submit").performScrollTo().performClick()
            compose.waitForIdle()
        }
    }
}
