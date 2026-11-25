package com.olive.app.ui.covers.fitness

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.CoverType
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SessionScreen
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class FitnessScreensUiDeepTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun fitnessHomeAndVitalsAndCoverPagesRenderAndSwitchCleanly() {
        compose.mainClock.autoAdvance = false
        val today = LocalDate.now()
        val vitals = HeartRateVitals(
            date = today,
            peakBpm = 142,
            lowestBpm = 58,
            restingBpm = 62,
            averageBpm = 78,
            sampleCount = 12,
            watchConnected = true,
            lastUpdatedAt = System.currentTimeMillis(),
            samples = listOf(HeartRateSample(System.currentTimeMillis(), 78)),
        )
        val food = NutritionFood(
            id = "oats",
            name = "Oatmeal",
            brand = "Quaker",
            servingDescription = "1 bowl",
            calories = 150f,
            proteinGrams = 5f,
            carbohydrateGrams = 27f,
            fatGrams = 3f,
            source = FoodDataSource.MANUAL,
        )
        val populatedSnapshot = FitnessSnapshot(
            profile = FitnessProfile(),
            stepsToday = 4200,
            foodsToday = listOf(FoodLogEntry("l-1", food, 1f, MealType.BREAKFAST)),
            heartRateVitals = vitals,
            heartRateHistory = listOf(vitals.copy(date = today.minusDays(1)), vitals),
            watchConnected = true,
            watchSeenWithinFourteenDays = true,
            waterTodayMl = 750,
        )
        val emptyConnected = FitnessSnapshot(FitnessProfile(), 0, emptyList(), watchConnected = true)
        val emptyRecent = FitnessSnapshot(FitnessProfile(), 0, emptyList(), watchSeenWithinFourteenDays = true)
        val emptyDisconnected = FitnessSnapshot(FitnessProfile(), 0, emptyList())

        var stage by mutableIntStateOf(0)
        var starts = 0
        var goals = 0
        var dismissals = 0

        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Cover, localMode = true).use { handle ->
            handle.session.runtime.state.coverType = CoverType.FITNESS
            compose.setContent {
                OliveUiTestHost(privateDesign = false) {
                    when (stage) {
                        0 -> FitnessHomePage(
                            snapshot = populatedSnapshot,
                            onGoals = { goals++ },
                            showGoalQuestionnairePrompt = true,
                            onStartQuestionnaire = { starts++ },
                            onDismissQuestionnaire = { dismissals++ },
                        )
                        1 -> FitnessHomePage(snapshot = populatedSnapshot, onGoals = { goals++ }, showGoalQuestionnairePrompt = false)
                        2 -> FitnessVitalsPage(snapshot = populatedSnapshot)
                        3 -> FitnessVitalsPage(snapshot = emptyConnected)
                        4 -> FitnessVitalsPage(snapshot = emptyRecent)
                        5 -> FitnessVitalsPage(snapshot = emptyDisconnected)
                        else -> FitnessCover(handle.session, interactive = true)
                    }
                }
            }
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithTag("fitness_home").assertExists()
            compose.onNodeWithTag("fitness_start_questionnaire").performClick()
            compose.onNodeWithTag("fitness_edit_goals_manual").performClick()
            compose.onNodeWithTag("fitness_dismiss_questionnaire").performClick()
            assertTrue(starts == 1 && goals == 1 && dismissals == 1)

            for (nextStage in 1..5) {
                stage = nextStage
                compose.mainClock.advanceTimeBy(250)
            }
        }
    }

    @Test
    fun fitnessCoverSwitchesTabsAndOpensSettings() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Cover, localMode = true).use { handle ->
            handle.session.runtime.state.coverType = CoverType.FITNESS
            compose.setContent {
                OliveUiTestHost(privateDesign = false) {
                    FitnessCover(handle.session, interactive = true)
                }
            }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("fitness_account_settings").assertExists()

            listOf(
                R.string.activity,
                R.string.fitness_vitals,
                R.string.nutrition,
                R.string.fitness_coach_tab,
                R.string.home,
            ).forEach { tabRes ->
                compose.onNodeWithContentDescription(context.getString(tabRes)).performClick()
                compose.mainClock.advanceTimeBy(250)
            }
            compose.onNodeWithTag("fitness_account_settings").performClick()
            compose.mainClock.advanceTimeBy(250)
        }
    }
}
