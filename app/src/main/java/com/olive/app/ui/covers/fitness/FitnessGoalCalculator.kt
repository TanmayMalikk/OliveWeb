// Fitness cover users: calculates recommended fitness and nutrition goals from questionnaire answers.
package com.olive.app.ui.covers.fitness

import kotlin.math.roundToInt
import org.json.JSONObject

internal enum class FitnessPrimaryGoal {
    LOSE_WEIGHT,
    MAINTAIN,
    BUILD_MUSCLE,
    IMPROVE_FITNESS,
}

internal enum class FitnessActivityLevel {
    SEDENTARY,
    LIGHTLY_ACTIVE,
    MODERATELY_ACTIVE,
    VERY_ACTIVE,
}

internal enum class FitnessNutritionStyle {
    BALANCED,
    HIGH_PROTEIN,
    LOWER_CARB,
}

internal enum class FitnessMetabolicBasis {
    FEMALE,
    MALE,
    NEUTRAL,
}

internal data class FitnessGoalQuestionnaireInput(
    val primaryGoal: FitnessPrimaryGoal = FitnessPrimaryGoal.MAINTAIN,
    val activityLevel: FitnessActivityLevel = FitnessActivityLevel.LIGHTLY_ACTIVE,
    val nutritionStyle: FitnessNutritionStyle = FitnessNutritionStyle.BALANCED,
    val ageYears: Int = 30,
    val heightCm: Float = 170f,
    val weightKg: Float = 70f,
    val metabolicBasis: FitnessMetabolicBasis = FitnessMetabolicBasis.NEUTRAL,
)

internal data class FitnessGoalRecommendation(
    val profile: FitnessProfile,
    val estimatedMaintenanceCalories: Int,
)

internal object FitnessGoalCalculator {
    const val MIN_AGE = 13
    const val MAX_AGE = 100
    const val MIN_HEIGHT_CM = 100f
    const val MAX_HEIGHT_CM = 230f
    const val MIN_WEIGHT_KG = 30f
    const val MAX_WEIGHT_KG = 300f

    /**
     * Checks whether the questionnaire measurements fall inside supported physiological bounds.
     *
     * @param input Questionnaire answers supplied to [isValid].
     * @return `true` when age, height, and weight are finite and within bounds.
     */
    fun isValid(input: FitnessGoalQuestionnaireInput): Boolean =
        input.ageYears in MIN_AGE..MAX_AGE &&
            input.heightCm.isFinite() &&
            input.heightCm in MIN_HEIGHT_CM..MAX_HEIGHT_CM &&
            input.weightKg.isFinite() &&
            input.weightKg in MIN_WEIGHT_KG..MAX_WEIGHT_KG

    /**
     * Computes initial daily step, calorie, macronutrient, and water goals from questionnaire inputs.
     *
     * @param input Questionnaire answers supplied to [calculate].
     * @return The resulting [FitnessGoalRecommendation] with bounded targets and maintenance estimate.
     */
    fun calculate(input: FitnessGoalQuestionnaireInput): FitnessGoalRecommendation {
        val safeAge = input.ageYears.coerceIn(MIN_AGE, MAX_AGE)
        val safeHeight = (if (input.heightCm.isFinite()) input.heightCm else 170f)
            .coerceIn(MIN_HEIGHT_CM, MAX_HEIGHT_CM)
        val safeWeight = (if (input.weightKg.isFinite()) input.weightKg else 70f)
            .coerceIn(MIN_WEIGHT_KG, MAX_WEIGHT_KG)

        val sexOffset = when (input.metabolicBasis) {
            FitnessMetabolicBasis.FEMALE -> -161.0
            FitnessMetabolicBasis.MALE -> 5.0
            // Midpoint between the standard Mifflin-St Jeor offsets avoids requiring a binary selection.
            FitnessMetabolicBasis.NEUTRAL -> -78.0
        }
        val bmr = 10.0 * safeWeight + 6.25 * safeHeight - 5.0 * safeAge + sexOffset
        val activityMultiplier = when (input.activityLevel) {
            FitnessActivityLevel.SEDENTARY -> 1.2
            FitnessActivityLevel.LIGHTLY_ACTIVE -> 1.375
            FitnessActivityLevel.MODERATELY_ACTIVE -> 1.55
            FitnessActivityLevel.VERY_ACTIVE -> 1.725
        }
        val tdee = bmr * activityMultiplier
        val maintenanceCalories = roundToNearest(tdee, 50).coerceIn(1_200, 4_500)

        val goalDelta = when (input.primaryGoal) {
            FitnessPrimaryGoal.LOSE_WEIGHT -> -400.0
            FitnessPrimaryGoal.MAINTAIN -> 0.0
            FitnessPrimaryGoal.BUILD_MUSCLE -> 250.0
            FitnessPrimaryGoal.IMPROVE_FITNESS -> 100.0
        }
        val calorieFloor = when (input.metabolicBasis) {
            FitnessMetabolicBasis.FEMALE -> 1_200
            FitnessMetabolicBasis.MALE -> 1_500
            FitnessMetabolicBasis.NEUTRAL -> 1_350
        }
        val calorieGoal = roundToNearest(tdee + goalDelta, 50).coerceIn(calorieFloor, 4_500)

        val (baseProteinShare, fatShare) = when (input.nutritionStyle) {
            FitnessNutritionStyle.BALANCED -> 0.25 to 0.28
            FitnessNutritionStyle.HIGH_PROTEIN -> 0.32 to 0.26
            FitnessNutritionStyle.LOWER_CARB -> 0.30 to 0.38
        }
        val goalProteinBoost = if (
            input.primaryGoal == FitnessPrimaryGoal.LOSE_WEIGHT ||
            input.primaryGoal == FitnessPrimaryGoal.BUILD_MUSCLE
        ) 0.03 else 0.0
        val proteinShare = (baseProteinShare + goalProteinBoost).coerceAtMost(0.36)

        val maxProtein = minOf(safeWeight * 2.2, (calorieGoal * 0.40) / 4.0)
        val minProtein = minOf(safeWeight * 1.0, maxProtein)
        val proteinRaw = ((calorieGoal * proteinShare) / 4.0).coerceIn(minProtein, maxProtein)
        val proteinGoal = roundToNearest(proteinRaw, 5).coerceIn(40, 350)
        val fatGoal = roundToNearest((calorieGoal * fatShare) / 9.0, 5).coerceIn(30, 200)
        val remainingCalories = (calorieGoal - proteinGoal * 4 - fatGoal * 9).coerceAtLeast(200)
        val carbohydrateGoal = roundToNearest(remainingCalories / 4.0, 5).coerceIn(50, 700)

        val baseSteps = when (input.activityLevel) {
            FitnessActivityLevel.SEDENTARY -> 6_000
            FitnessActivityLevel.LIGHTLY_ACTIVE -> 8_000
            FitnessActivityLevel.MODERATELY_ACTIVE -> 10_000
            FitnessActivityLevel.VERY_ACTIVE -> 12_000
        }
        val stepBonus = when (input.primaryGoal) {
            FitnessPrimaryGoal.LOSE_WEIGHT -> 1_500
            FitnessPrimaryGoal.IMPROVE_FITNESS -> 2_000
            FitnessPrimaryGoal.MAINTAIN, FitnessPrimaryGoal.BUILD_MUSCLE -> 0
        }
        val stepGoal = (baseSteps + stepBonus).coerceIn(5_000, 15_000)

        val waterActivityBonus = when (input.activityLevel) {
            FitnessActivityLevel.SEDENTARY -> 0.0
            FitnessActivityLevel.LIGHTLY_ACTIVE -> 200.0
            FitnessActivityLevel.MODERATELY_ACTIVE -> 400.0
            FitnessActivityLevel.VERY_ACTIVE -> 600.0
        }
        val waterGoalMl = roundToNearest(33.0 * safeWeight + waterActivityBonus, 100)
            .coerceIn(1_500, 4_500)

        return FitnessGoalRecommendation(
            profile = FitnessProfile(
                weightKg = safeWeight,
                heightCm = safeHeight,
                stepGoal = stepGoal,
                calorieGoal = calorieGoal,
                proteinGoal = proteinGoal,
                carbohydrateGoal = carbohydrateGoal,
                fatGoal = fatGoal,
                waterGoalMl = waterGoalMl,
            ),
            estimatedMaintenanceCalories = maintenanceCalories,
        )
    }

    /**
     * Serializes the non-body-metric questionnaire selections for local restoration on this device.
     *
     * @param input Questionnaire answers supplied to [toJson].
     * @return JSON representation storing goal, activity, nutrition style, age, and formula choice.
     */
    fun toJson(input: FitnessGoalQuestionnaireInput): String = JSONObject().apply {
        put("primaryGoal", input.primaryGoal.name)
        put("activityLevel", input.activityLevel.name)
        put("nutritionStyle", input.nutritionStyle.name)
        put("ageYears", input.ageYears.coerceIn(MIN_AGE, MAX_AGE))
        put("metabolicBasis", input.metabolicBasis.name)
    }.toString()

    /**
     * Restores saved questionnaire selections paired with the current height and weight from [fallbackProfile].
     *
     * @param raw Saved JSON string, or `null` if not configured yet.
     * @param fallbackProfile Current [FitnessProfile] providing baseline weight and height.
     * @return The reconstructed [FitnessGoalQuestionnaireInput].
     */
    fun fromJson(
        raw: String?,
        fallbackProfile: FitnessProfile,
    ): FitnessGoalQuestionnaireInput {
        val safeWeight = (if (fallbackProfile.weightKg.isFinite()) fallbackProfile.weightKg else 70f)
            .coerceIn(MIN_WEIGHT_KG, MAX_WEIGHT_KG)
        val safeHeight = (if (fallbackProfile.heightCm.isFinite()) fallbackProfile.heightCm else 170f)
            .coerceIn(MIN_HEIGHT_CM, MAX_HEIGHT_CM)
        if (raw.isNullOrBlank()) {
            return FitnessGoalQuestionnaireInput(heightCm = safeHeight, weightKg = safeWeight)
        }
        val parsed = runCatching { JSONObject(raw) }.getOrNull()
            ?: return FitnessGoalQuestionnaireInput(heightCm = safeHeight, weightKg = safeWeight)
        return FitnessGoalQuestionnaireInput(
            primaryGoal = enumValueOrDefault(parsed.optString("primaryGoal"), FitnessPrimaryGoal.MAINTAIN),
            activityLevel = enumValueOrDefault(parsed.optString("activityLevel"), FitnessActivityLevel.LIGHTLY_ACTIVE),
            nutritionStyle = enumValueOrDefault(parsed.optString("nutritionStyle"), FitnessNutritionStyle.BALANCED),
            ageYears = parsed.optInt("ageYears", 30).coerceIn(MIN_AGE, MAX_AGE),
            heightCm = safeHeight,
            weightKg = safeWeight,
            metabolicBasis = enumValueOrDefault(parsed.optString("metabolicBasis"), FitnessMetabolicBasis.NEUTRAL),
        )
    }

    /** Rounds [value] to the nearest multiple of [step]. */
    private fun roundToNearest(value: Double, step: Int): Int =
        (value / step).roundToInt() * step

    /** Parses enum entry [name] safely, falling back to [default] when unrecognized. */
    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String, default: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(default)
}
