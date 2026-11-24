// Tests initial fitness & nutrition goal calculations and account-scoped questionnaire state.
package com.olive.app.ui.covers.fitness

import android.content.Context
import java.util.UUID
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class FitnessGoalQuestionnaireTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun store(account: String = "questionnaire-${UUID.randomUUID()}") = FitnessStore(context, account)

    @Test
    fun primaryGoalsAdjustCaloriesAndStepsInExpectedDirections() {
        val base = FitnessGoalQuestionnaireInput(
            activityLevel = FitnessActivityLevel.MODERATELY_ACTIVE,
            ageYears = 29,
            heightCm = 175f,
            weightKg = 75f,
            metabolicBasis = FitnessMetabolicBasis.MALE,
        )
        val lose = FitnessGoalCalculator.calculate(base.copy(primaryGoal = FitnessPrimaryGoal.LOSE_WEIGHT))
        val maintain = FitnessGoalCalculator.calculate(base.copy(primaryGoal = FitnessPrimaryGoal.MAINTAIN))
        val muscle = FitnessGoalCalculator.calculate(base.copy(primaryGoal = FitnessPrimaryGoal.BUILD_MUSCLE))
        val fitness = FitnessGoalCalculator.calculate(base.copy(primaryGoal = FitnessPrimaryGoal.IMPROVE_FITNESS))

        assertTrue(lose.profile.calorieGoal < maintain.profile.calorieGoal)
        assertTrue(maintain.profile.calorieGoal < muscle.profile.calorieGoal)
        assertTrue(fitness.profile.stepGoal > maintain.profile.stepGoal)
        assertTrue(lose.profile.stepGoal > maintain.profile.stepGoal)
    }

    @Test
    fun higherActivityStrictlyIncreasesMaintenanceCaloriesStepsAndWater() {
        val levels = listOf(
            FitnessActivityLevel.SEDENTARY,
            FitnessActivityLevel.LIGHTLY_ACTIVE,
            FitnessActivityLevel.MODERATELY_ACTIVE,
            FitnessActivityLevel.VERY_ACTIVE,
        ).map { level ->
            FitnessGoalCalculator.calculate(
                FitnessGoalQuestionnaireInput(
                    activityLevel = level,
                    ageYears = 30,
                    heightCm = 170f,
                    weightKg = 70f,
                ),
            )
        }
        levels.zipWithNext().forEach { (lower, higher) ->
            assertTrue(higher.estimatedMaintenanceCalories > lower.estimatedMaintenanceCalories)
            assertTrue(higher.profile.stepGoal > lower.profile.stepGoal)
            assertTrue(higher.profile.waterGoalMl > lower.profile.waterGoalMl)
        }
    }

    @Test
    fun neutralBasisSitsBetweenFemaleAndMaleAndNutritionStylesShiftMacros() {
        val common = FitnessGoalQuestionnaireInput(
            primaryGoal = FitnessPrimaryGoal.MAINTAIN,
            activityLevel = FitnessActivityLevel.MODERATELY_ACTIVE,
            ageYears = 32,
            heightCm = 172f,
            weightKg = 72f,
        )
        val female = FitnessGoalCalculator.calculate(common.copy(metabolicBasis = FitnessMetabolicBasis.FEMALE))
        val neutral = FitnessGoalCalculator.calculate(common.copy(metabolicBasis = FitnessMetabolicBasis.NEUTRAL))
        val male = FitnessGoalCalculator.calculate(common.copy(metabolicBasis = FitnessMetabolicBasis.MALE))

        assertTrue(female.profile.calorieGoal < neutral.profile.calorieGoal)
        assertTrue(neutral.profile.calorieGoal < male.profile.calorieGoal)

        val balanced = FitnessGoalCalculator.calculate(common.copy(nutritionStyle = FitnessNutritionStyle.BALANCED))
        val highProtein = FitnessGoalCalculator.calculate(common.copy(nutritionStyle = FitnessNutritionStyle.HIGH_PROTEIN))
        val lowerCarb = FitnessGoalCalculator.calculate(common.copy(nutritionStyle = FitnessNutritionStyle.LOWER_CARB))

        assertTrue(highProtein.profile.proteinGoal >= balanced.profile.proteinGoal)
        assertTrue(lowerCarb.profile.carbohydrateGoal < balanced.profile.carbohydrateGoal)
        assertTrue(lowerCarb.profile.fatGoal > balanced.profile.fatGoal)
    }

    @Test
    fun extremeInputsStayWithinSafeCalorieAndStorageBounds() {
        val low = FitnessGoalCalculator.calculate(
            FitnessGoalQuestionnaireInput(
                primaryGoal = FitnessPrimaryGoal.LOSE_WEIGHT,
                activityLevel = FitnessActivityLevel.SEDENTARY,
                ageYears = 120,
                heightCm = 80f,
                weightKg = 20f,
                metabolicBasis = FitnessMetabolicBasis.FEMALE,
            ),
        ).profile
        val high = FitnessGoalCalculator.calculate(
            FitnessGoalQuestionnaireInput(
                primaryGoal = FitnessPrimaryGoal.BUILD_MUSCLE,
                activityLevel = FitnessActivityLevel.VERY_ACTIVE,
                ageYears = 18,
                heightCm = 240f,
                weightKg = 350f,
                metabolicBasis = FitnessMetabolicBasis.MALE,
            ),
        ).profile
        val nonFinite = FitnessGoalCalculator.calculate(
            FitnessGoalQuestionnaireInput(
                ageYears = 25,
                heightCm = Float.NaN,
                weightKg = Float.POSITIVE_INFINITY,
            ),
        ).profile

        assertTrue(low.calorieGoal >= 1_200)
        assertTrue(high.calorieGoal <= 4_500)
        assertTrue(nonFinite.heightCm == 170f && nonFinite.weightKg == 70f)
        listOf(low, high, nonFinite).forEach { profile ->
            assertTrue(profile.weightKg in 30f..300f)
            assertTrue(profile.heightCm in 100f..230f)
            assertTrue(profile.stepGoal in 500..100_000)
            assertTrue(profile.calorieGoal in 500..10_000)
            assertTrue(profile.proteinGoal in 10..1_000)
            assertTrue(profile.carbohydrateGoal in 10..2_000)
            assertTrue(profile.fatGoal in 10..1_000)
            assertTrue(profile.waterGoalMl in 250..10_000)
            val macroCalories = profile.proteinGoal * 4 + profile.carbohydrateGoal * 4 + profile.fatGoal * 9
            assertTrue(abs(macroCalories - profile.calorieGoal) <= 150)
        }
    }

    @Test
    fun validationAndJsonSerializationHandleBoundariesAndProfileSync() {
        val valid = FitnessGoalQuestionnaireInput(
            primaryGoal = FitnessPrimaryGoal.BUILD_MUSCLE,
            activityLevel = FitnessActivityLevel.VERY_ACTIVE,
            nutritionStyle = FitnessNutritionStyle.HIGH_PROTEIN,
            ageYears = 26,
            heightCm = 178f,
            weightKg = 74f,
            metabolicBasis = FitnessMetabolicBasis.FEMALE,
        )
        assertTrue(FitnessGoalCalculator.isValid(valid))
        assertFalse(FitnessGoalCalculator.isValid(valid.copy(ageYears = 12)))
        assertFalse(FitnessGoalCalculator.isValid(valid.copy(ageYears = 101)))
        assertFalse(FitnessGoalCalculator.isValid(valid.copy(heightCm = 99f)))
        assertFalse(FitnessGoalCalculator.isValid(valid.copy(heightCm = Float.NaN)))
        assertFalse(FitnessGoalCalculator.isValid(valid.copy(weightKg = 301f)))
        assertFalse(FitnessGoalCalculator.isValid(valid.copy(weightKg = Float.POSITIVE_INFINITY)))

        val json = FitnessGoalCalculator.toJson(valid)
        val restored = FitnessGoalCalculator.fromJson(
            raw = json,
            fallbackProfile = FitnessProfile(weightKg = 68f, heightCm = 180f),
        )
        assertEquals(FitnessPrimaryGoal.BUILD_MUSCLE, restored.primaryGoal)
        assertEquals(FitnessActivityLevel.VERY_ACTIVE, restored.activityLevel)
        assertEquals(FitnessNutritionStyle.HIGH_PROTEIN, restored.nutritionStyle)
        assertEquals(26, restored.ageYears)
        assertEquals(FitnessMetabolicBasis.FEMALE, restored.metabolicBasis)
        assertEquals(68f, restored.weightKg, 0f)
        assertEquals(180f, restored.heightCm, 0f)
    }

    @Test
    fun storeTracksQuestionnaireCompletionDismissalAndAccountIsolation() {
        val first = store()
        val other = store()
        assertFalse(first.hasConfiguredGoals())
        assertTrue(first.shouldPromptGoalQuestionnaire())

        // Logging water sets only water_goal_ml and does not hide initial fitness/nutrition setup.
        first.addWater(250, 2000)
        assertFalse(first.hasConfiguredGoals())
        assertTrue(first.shouldPromptGoalQuestionnaire())

        val dismissable = store()
        dismissable.dismissGoalQuestionnaire()
        assertFalse(dismissable.shouldPromptGoalQuestionnaire())
        assertEquals(FitnessProfile(), dismissable.profile())

        val answers = FitnessGoalQuestionnaireInput(
            primaryGoal = FitnessPrimaryGoal.LOSE_WEIGHT,
            activityLevel = FitnessActivityLevel.MODERATELY_ACTIVE,
            nutritionStyle = FitnessNutritionStyle.HIGH_PROTEIN,
            ageYears = 34,
            heightCm = 166f,
            weightKg = 72f,
            metabolicBasis = FitnessMetabolicBasis.FEMALE,
        )
        val recommendation = FitnessGoalCalculator.calculate(answers)
        first.saveQuestionnaireGoals(answers, recommendation.profile)

        assertTrue(first.hasConfiguredGoals())
        assertFalse(first.shouldPromptGoalQuestionnaire())
        assertEquals(recommendation.profile, first.profile())
        assertEquals(answers, first.savedQuestionnaireInput())

        assertFalse(other.hasConfiguredGoals())
        assertTrue(other.shouldPromptGoalQuestionnaire())

        val hydrated = store()
        hydrated.preferences.edit().putInt("step_goal", 9500).commit()
        assertTrue(hydrated.hasConfiguredGoals())
        assertFalse(hydrated.shouldPromptGoalQuestionnaire())

        assertFalse(cloudFitnessKey("goals_questionnaire_completed"))
        assertFalse(cloudFitnessKey("goals_questionnaire_dismissed"))
        assertFalse(cloudFitnessKey("goals_questionnaire_answers"))
    }
}
