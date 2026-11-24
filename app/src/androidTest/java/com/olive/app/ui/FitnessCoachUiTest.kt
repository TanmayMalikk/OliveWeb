package com.olive.app.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.olive.app.R
import com.olive.app.ui.covers.fitness.*
import com.olive.app.ui.shared.OliveTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class FitnessCoachUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun label(id: Int) = compose.activity.getString(id)
    private val account = "synthetic-coach-${UUID.randomUUID()}"

    private class Coach : FitnessCoachService {
        var status = CoachMembership(active = true, canManage = true)
        var unavailable = false
        var reply = CompletableDeferred<CoachReply>()
        var lastPrompt = ""
        override suspend fun membership(): CoachMembership {
            check(!unavailable)
            return status
        }
        override suspend fun billingPortal(): Uri = error("Synthetic unavailable portal")
        override suspend fun paymentSheet(): CoachPayment = error("Synthetic unavailable checkout")
        override suspend fun send(
            text: String,
            turns: List<CoachTurn>,
            profile: FitnessProfile,
            history: List<FitnessCoachDay>,
        ): CoachReply {
            lastPrompt = text
            return reply.await()
        }
    }

    private fun mount(coach: Coach): FitnessStore {
        val store = FitnessStore(compose.activity, account)
        compose.setContent { OliveTheme {
            FitnessCoachPage(account, true, store, false, coach) {}
        } }
        return store
    }

    private fun waitForLabel(id: Int) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label(id)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun coachLabelsItsWorkoutPlanAndKeepsTheComposerVisible() {
        val coach = Coach()
        mount(coach)
        waitForLabel(R.string.fitness_coach_question)
        compose.onNodeWithText(label(R.string.fitness_coach_prompt_workout)).performClick()
        compose.onNodeWithContentDescription(label(R.string.fitness_coach_send)).performClick()
        waitForLabel(R.string.fitness_coach_thinking)
        val plan = "Try this three-day starter plan.\n\n" +
            "Monday · 15-minute easy walk\n" +
            "Wednesday · 2 sets of chair squats and wall push-ups\n" +
            "Friday · 20-minute walk at a comfortable pace\n\n" +
            "Rest between sessions. Add a few minutes next week if it feels easy."
        coach.reply.complete(CoachReply(plan, emptyMap()))
        val expectedReply = "${label(R.string.fitness_coach_disclaimer)}\n\n$plan"
        compose.waitUntil(10_000) { compose.onAllNodesWithText(expectedReply).fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.onActivity {
            WindowInsetsControllerCompat(it.window, it.window.decorView).hide(WindowInsetsCompat.Type.ime())
        }
        compose.onNode(hasText(expectedReply) and hasText(label(R.string.fitness_coach_tab))).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        File(compose.activity.getExternalFilesDir(null), "fitness-coach-test.png").outputStream().use { output ->
            compose.onNodeWithTag("fitness_coach_screen").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    @Test fun subscribedCoachSavesManualAndAutomaticStepTargetsAndKeepsFailedDraft() {
        val coach = Coach()
        val store = mount(coach)
        waitForLabel(R.string.fitness_coach_question)
        val send = compose.onNodeWithContentDescription(label(R.string.fitness_coach_send))
        val composer = compose.onNode(hasSetTextAction())
        send.assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.fitness_coach_prompt_workout)).performClick()
        composer.assertTextContains(label(R.string.fitness_coach_prompt_workout))
        assertEquals("", coach.lastPrompt)
        composer.performTextClearance()
        composer.performTextInput("Set my step goal to 2000")
        send.performClick()
        waitForLabel(R.string.fitness_coach_thinking)
        send.assertIsNotEnabled()
        coach.reply.complete(CoachReply("Try 2000 steps.", mapOf("steps" to 2000)))
        waitForLabel(R.string.fitness_coach_apply)
        assertEquals(8000, store.profile().stepGoal)
        compose.onNodeWithText(label(R.string.fitness_coach_apply)).performClick()
        waitForLabel(R.string.fitness_coach_goals_saved)
        assertEquals(2000, store.profile().stepGoal)

        compose.onNodeWithContentDescription(label(R.string.fitness_coach_settings)).performClick()
        compose.onNodeWithText(label(R.string.fitness_coach_auto_goals)).performClick()
        compose.onNodeWithText(label(R.string.close)).performClick()
        coach.reply = CompletableDeferred()
        composer.performTextInput("Set my step goal to 3000")
        send.performClick()
        waitForLabel(R.string.fitness_coach_thinking)
        coach.reply.complete(CoachReply("Try 3000 steps.", mapOf("steps" to 3000)))
        waitForLabel(R.string.fitness_coach_goals_saved)
        assertEquals(3000, store.profile().stepGoal)
        assertEquals("Set my step goal to 3000", coach.lastPrompt)

        coach.reply = CompletableDeferred()
        composer.performTextInput("Keep this draft")
        send.performClick()
        waitForLabel(R.string.fitness_coach_thinking)
        coach.reply.completeExceptionally(IllegalStateException("Synthetic offline response"))
        waitForLabel(R.string.fitness_coach_reply_error)
        composer.assertTextContains("Keep this draft")
        send.assertIsEnabled()
    }

    @Test fun checkoutFailureKeepsChatLockedAndSubscriptionRefreshCanUnlockIt() {
        val coach = Coach().apply { status = CoachMembership(active = false, canManage = true) }
        mount(coach)
        waitForLabel(R.string.fitness_coach_subscribe)
        compose.onNodeWithText(label(R.string.fitness_coach_subscribe)).performClick()
        waitForLabel(R.string.fitness_coach_billing_error)
        compose.onNodeWithContentDescription(label(R.string.fitness_coach_send)).assertDoesNotExist()
        coach.status = CoachMembership(active = true, canManage = true)
        compose.onNodeWithText(label(R.string.fitness_coach_refresh)).performClick()
        waitForLabel(R.string.fitness_coach_question)
        compose.onNodeWithContentDescription(label(R.string.fitness_coach_settings)).performClick()
        compose.onNodeWithText(label(R.string.fitness_coach_manage)).performClick()
        waitForLabel(R.string.fitness_coach_billing_error)
    }
}
