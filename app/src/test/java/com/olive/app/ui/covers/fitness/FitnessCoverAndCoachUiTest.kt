package com.olive.app.ui.covers.fitness

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class FitnessCoverAndCoachUiTest {
    @get:Rule
    val compose = createComposeRule()

    private class FakeCoachService(
        var membershipState: CoachMembership = CoachMembership(active = true, canManage = true),
    ) : FitnessCoachService {
        var throwOnStatus = false
        var throwOnSend = false
        var reply = CoachReply(
            text = "Great job!\n\nMonday\n* 15-min walk\n\nTargets updated.",
            goals = mapOf("steps" to 9500, "calories" to 2150),
        )
        var pending: CompletableDeferred<CoachReply>? = null
        override suspend fun membership(): CoachMembership {
            check(!throwOnStatus) { "status failed" }
            return membershipState
        }
        override suspend fun billingPortal(): Uri = error("portal unavailable")
        override suspend fun paymentSheet(): CoachPayment = error("checkout unavailable")
        override suspend fun send(
            text: String,
            turns: List<CoachTurn>,
            profile: FitnessProfile,
            history: List<FitnessCoachDay>,
        ): CoachReply {
            pending?.let { return it.await() }
            check(!throwOnSend) { "send failed" }
            return reply
        }
    }

    private fun appContext(): Context = RuntimeEnvironment.getApplication()

    @Test
    fun fitnessCoachHandlesSignedOutOfferSubscribedChatAndSettings() {
        val context = appContext()
        val storeSignedOut = FitnessStore(context, "coach-signedout")
        val fakeSignedOut = FakeCoachService(CoachMembership(active = false, canManage = false))

        compose.setContent {
            OliveUiTestHost(privateDesign = false) {
                FitnessCoachPage("coach-signedout", cloudAvailable = false, store = storeSignedOut, dataLoading = false, client = fakeSignedOut) {}
            }
        }
        compose.onNodeWithText(context.getString(R.string.fitness_coach_sign_in)).assertIsDisplayed()
    }

    @Test
    fun fitnessCoachSubscribedSendApplyGoalsAndSettingsFlow() {
        val context = appContext()
        val store = FitnessStore(context, "coach-active-test")
        val coach = FakeCoachService(CoachMembership(active = true, canManage = true))
        var goalsSavedCount = 0

        compose.setContent {
            OliveUiTestHost(privateDesign = false) {
                FitnessCoachPage("coach-active-test", cloudAvailable = true, store = store, dataLoading = false, client = coach) {
                    goalsSavedCount++
                }
            }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.getString(R.string.fitness_coach_question)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.fitness_coach_prompt_workout)).performClick()
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput("Suggest a workout routine for me")
        compose.onNodeWithContentDescription(context.getString(R.string.fitness_coach_send)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(context.getString(R.string.fitness_coach_apply)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(context.getString(R.string.fitness_coach_apply)).performClick()
        compose.waitForIdle()
        assertEquals(1, goalsSavedCount)

        // Open coach settings, toggle auto-goals, clear history.
        compose.onNodeWithContentDescription(context.getString(R.string.fitness_coach_settings)).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(context.getString(R.string.fitness_coach_auto_goals)).performClick()
        compose.onNodeWithText(context.getString(R.string.fitness_coach_clear_history)).performClick()
        compose.waitForIdle()
    }

    @Test
    fun fitnessCoverNavigatesTabsAndOpensSettings() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Cover, localMode = true).use { handle ->
            val context = appContext()
            compose.setContent {
                OliveUiTestHost(privateDesign = false) {
                    FitnessCover(handle.session, interactive = true)
                }
            }
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()

            for (tabRes in listOf(R.string.activity, R.string.fitness_vitals, R.string.nutrition, R.string.fitness_coach_tab, R.string.home)) {
                compose.onNodeWithContentDescription(context.getString(tabRes)).performClick()
                compose.mainClock.advanceTimeBy(300)
            }

            compose.onNodeWithTag("fitness_account_settings").performClick()
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithTag("fitness_page_title").assertExists()
        }
    }

    @Test
    fun fitnessGoalsAndQuestionnairePagesSaveAndNavigateAllSteps() {
        val context = appContext()
        var savedProfile: FitnessProfile? = null
        var openedQuestionnaire = false

        compose.setContent {
            OliveUiTestHost(privateDesign = false) {
                FitnessGoalsPage(
                    profile = FitnessProfile(),
                    enabled = true,
                    padding = PaddingValues(0.dp),
                    onSave = { savedProfile = it },
                    onOpenQuestionnaire = { openedQuestionnaire = true },
                )
            }
        }
        compose.onNodeWithTag("open_goal_questionnaire").performClick()
        assertTrue(openedQuestionnaire)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("save_fitness_goals"))
        compose.onNodeWithTag("save_fitness_goals").performClick()
        assertEquals(8000, savedProfile?.stepGoal)
    }

    @Test
    fun fitnessGoalQuestionnaireWalksThroughAllFourStepsAndSaves() {
        val context = appContext()
        var finalizedProfile: FitnessProfile? = null
        compose.setContent {
            OliveUiTestHost(privateDesign = false) {
                FitnessGoalQuestionnairePage(
                    initialInput = FitnessGoalQuestionnaireInput(),
                    enabled = true,
                    padding = PaddingValues(0.dp),
                    onSaveGoals = { _, profile -> finalizedProfile = profile },
                    onCustomizeGoals = { _, _ -> },
                    onCancel = {},
                )
            }
        }
        // Step 0 -> Step 1 -> Step 2 -> Step 3 -> Confirm.
        compose.onNodeWithTag("questionnaire_goal_LOSE_WEIGHT").performClick()
        compose.onNodeWithTag("questionnaire_next").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("questionnaire_next").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("questionnaire_basis_NEUTRAL").performScrollTo().performClick()
        compose.onNodeWithTag("questionnaire_next").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("questionnaire_save_goals").performClick()
        assertTrue(finalizedProfile != null)
    }
}
