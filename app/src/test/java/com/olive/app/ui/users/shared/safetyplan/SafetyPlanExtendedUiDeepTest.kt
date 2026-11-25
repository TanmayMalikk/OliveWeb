package com.olive.app.ui.users.shared.safetyplan

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.Friend
import com.olive.app.data.safety.SharedSafetyPlan
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class SafetyPlanExtendedUiDeepTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun sharedSafetyPlanListRendersEmptyLoadingErrorAndPopulatedPlans() {
        var plans by mutableStateOf<List<SharedSafetyPlan>>(emptyList())
        var loading by mutableStateOf(true)
        var localMode by mutableStateOf(false)
        var error by mutableStateOf<Int?>(null)
        var backed = 0
        var opened: SharedSafetyPlan? = null

        compose.setContent {
            OliveUiTestHost {
                Column {
                    SharedSafetyPlanList(
                        plans = plans,
                        loading = loading,
                        localMode = localMode,
                        error = error,
                        onBack = { backed++ },
                        onOpen = { opened = it },
                    )
                }
            }
        }
        compose.waitForIdle()

        loading = false
        localMode = true
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.safe_contact_no_shared_plans)).assertExists()

        localMode = false
        error = R.string.cancel
        compose.waitForIdle()

        error = null
        plans = listOf(
            SharedSafetyPlan("owner-1", "Riley", 1000L),
            SharedSafetyPlan("owner-2", "", 2000L),
        )
        compose.waitForIdle()
        compose.onNodeWithTag("shared_plan_owner-1").performClick()
        compose.onNodeWithTag("safe_contact_safety_back").performClick()
        assertEquals("owner-1", opened?.ownerUid)
        assertTrue(backed == 1)
    }

    @Test
    fun safetyPlanScreenWithFriendsExercisesSharingConsultationAndQuestionnaire() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.SafetyPlan, localMode = true).use { handle ->
            val session = handle.session
            session.runtime.state.friends = listOf(Friend("fr-1", "sc-uid-1", "trusted_one"))
            compose.setContent {
                OliveUiTestHost {
                    SafetyPlanScreen(session)
                }
            }
            compose.waitUntil(timeoutMillis = 5_000) {
                session.safetyPlans.plan != null && !session.safetyPlans.loadingPlan
            }
            compose.waitForIdle()

            compose.onNodeWithTag("safety_plan_page").performScrollToNode(hasTestTag("safety_request_consultation"))
            compose.onNodeWithTag("safety_request_consultation").assertExists()

            compose.onNodeWithTag("safety_plan_page").performScrollToNode(hasTestTag("safety_share_contact_sc-uid-1"))
            compose.onNodeWithTag("safety_share_contact_sc-uid-1").performClick()
            compose.waitForIdle()

            compose.onNodeWithTag("safety_plan_page").performScrollToNode(hasTestTag("safety_documents"))
            compose.onNodeWithTag("safety_documents").performClick()
            compose.waitForIdle()

            compose.onNodeWithTag("safety_plan_page").performScrollToNode(hasTestTag("safety_tailor_questionnaire"))
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("safety_tailor_questionnaire").performClick()
            compose.mainClock.advanceTimeBy(250)
        }
    }
}
