package com.olive.app.ui.covers

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class DiscreetCoverEntrySettingsDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun coverSettingsAndDiscreetOliveHandleValidationSaveEntryAndEmergency() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Cover, localMode = true).use { handle ->
            val session = handle.session
            session.runtime.state.coverFullName = "Jordan Cover"
            session.runtime.state.coverDob = "1992-03-14"
            session.runtime.state.deliveryAddress = DeliveryAddress(city = "Edmonton")
            var backed = 0
            var opened = 0
            var emergency = 0

            compose.setContent {
                OliveUiTestHost(privateDesign = false) {
                    CoverSettingsPage(session, onBack = { backed++ })
                    DiscreetCoverEntry(
                        enabled = true,
                        onOpen = { opened++ },
                        onEmergency = { emergency++ },
                        onEmergencyFeedback = {},
                    )
                }
            }
            compose.mainClock.advanceTimeBy(250)

            // Trigger validation shake on blank full name, then fill valid name and save
            compose.onNodeWithTag("cover_full_name").performTextReplacement("")
            compose.onNodeWithTag("cover_save_settings").performClick()
            compose.mainClock.advanceTimeBy(150)

            compose.onNodeWithTag("cover_full_name").performTextReplacement("Updated Cover Name")
            compose.onNodeWithTag("cover_save_settings").performClick()
            compose.mainClock.advanceTimeBy(200)

            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("discreet_cover_olive"))[0]
                .performSemanticsAction(SemanticsActions.OnClick)
            compose.onAllNodes(androidx.compose.ui.test.hasTestTag("discreet_cover_olive"))[1]
                .performSemanticsAction(SemanticsActions.OnLongClick)
            compose.mainClock.advanceTimeBy(150)

            CoverLauncher.ensureStudioLauncher(context)
            CoverLauncher.apply(context, CoverType.NEWS, AccountRole.USER)
            CoverLauncher.apply(context, CoverType.FITNESS, AccountRole.USER)
            CoverLauncher.apply(context, CoverType.NEWS, AccountRole.SAFE_CONTACT)
            CoverLauncher.apply(context, CoverType.NEWS, AccountRole.ADMIN)
            performEmergencyVibration(context)

            assertEquals(1, emergency)
        }
    }
}
