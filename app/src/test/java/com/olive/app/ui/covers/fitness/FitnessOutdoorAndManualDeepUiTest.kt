package com.olive.app.ui.covers.fitness

import android.Manifest
import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.location.DeviceCoordinates
import com.olive.app.support.OliveUiTestHost
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class FitnessOutdoorAndManualDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun fitnessOutdoorWorkoutCardRendersHistoryRouteMapAndControls() {
        compose.mainClock.autoAdvance = false
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        val accountId = "fit-outdoor-u1"
        val store = FitnessWorkoutStore(context, accountId)
        val t0 = System.currentTimeMillis() - 60_000L
        store.start(OutdoorWorkoutType.RUN, now = t0)
        store.addLocation(DeviceCoordinates(53.5444, -113.4909, 8f, t0 + 1_000L))
        store.addLocation(DeviceCoordinates(53.5461, -113.4938, 8f, t0 + 11_000L))
        store.finish(now = t0 + 20_000L)

        // Start a second live active workout with two GPS points so map + controls render
        val t1 = t0 + 30_000L
        store.start(OutdoorWorkoutType.CYCLE, now = t1)
        store.addLocation(DeviceCoordinates(53.5444, -113.4909, 6f, t1 + 1_000L))
        store.addLocation(DeviceCoordinates(53.5455, -113.4922, 6f, t1 + 11_000L))

        compose.setContent {
            OliveUiTestHost(locationGranted = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    FitnessOutdoorWorkoutCard(accountId = accountId, enabled = true)
                }
            }
        }
        compose.mainClock.advanceTimeBy(350)

        // Pause -> advance -> Resume -> advance -> Finish
        compose.onNodeWithText(context.getString(R.string.fitness_pause)).performClick()
        store.pause(t1 + 15_000L)
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithText(context.getString(R.string.fitness_resume)).performClick()
        store.resume(t1 + 18_000L)
        compose.mainClock.advanceTimeBy(1_100)
        compose.onNodeWithText(context.getString(R.string.fitness_finish)).performClick()
        compose.mainClock.advanceTimeBy(250)

        // Exercise manualDraftFromFood helper
        val food = NutritionFood(
            id = "f-manual",
            name = "Granola",
            brand = "House",
            servingDescription = "45g",
            calories = 190f,
            proteinGrams = 5f,
            carbohydrateGrams = 30f,
            fatGrams = 6f,
            saturatedFatGrams = 1f,
            transFatGrams = 0f,
            fiberGrams = 4f,
            sugarGrams = 8f,
            sodiumMilligrams = 95f,
            cholesterolMilligrams = 0f,
            potassiumMilligrams = 140f,
            source = FoodDataSource.MANUAL,
        )
        val draft = manualDraftFromFood(food)
        assertEquals("Granola", draft.name)
        assertEquals("45g", draft.serving)
    }
}
