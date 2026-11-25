package com.olive.app.ui.covers.fitness

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.olive.app.support.OliveUiTestHost
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class FitnessWorkoutAndChartsUiDeepTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun fitnessHistoryChartsOutdoorWorkoutCardAndManualFoodEntryRender() {
        compose.mainClock.autoAdvance = false
        val today = LocalDate.now()
        val days = (0..6).map { offset ->
            FitnessHistoryDay(
                date = today.minusDays(offset.toLong()),
                steps = 3500 + offset * 500,
                distanceKm = 2.5f + offset * 0.4f,
                caloriesBurned = 220f + offset * 30f,
                caloriesEaten = 1850f + offset * 50f,
                proteinGrams = 95f + offset * 5f,
                carbohydrateGrams = 210f + offset * 10f,
                fatGrams = 55f + offset * 2f,
            )
        }
        var stage by mutableIntStateOf(0)

        compose.setContent {
            OliveUiTestHost(privateDesign = false) {
                when (stage) {
                    0 -> Column {
                        FitnessHistoryCharts(days)
                        FitnessOutdoorWorkoutCard(accountId = "owner-1", enabled = true)
                    }
                    else -> ManualFoodEntryDialog(onDismiss = {}, onContinue = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(250)
        stage = 1
        compose.mainClock.advanceTimeBy(250)
    }
}
