// Watch fitness users: holds Watch6 activity readings and nutrition synced from the phone.
package com.olive.app.wear

internal data class WatchFitnessState(
    val watchSteps: Int = 0,
    val phoneSteps: Int = 0,
    val stepGoal: Int = 8_000,
    val heartRate: Int? = null,
    val heartRateStatus: String = "Tap allow to read heart rate",
    val calories: Float = 0f,
    val calorieGoal: Int = 2_000,
    val protein: Float = 0f,
    val proteinGoal: Int = 120,
    val carbohydrates: Float = 0f,
    val carbohydrateGoal: Int = 250,
    val fat: Float = 0f,
    val fatGoal: Int = 65,
    val fiber: Float = 0f,
    val sugar: Float = 0f,
    val sodium: Float = 0f,
    val phoneConnected: Boolean = false,
    val updatedAt: Long = 0L,
) {
    // The larger total avoids adding the same steps reported by both the watch and phone.
    val displayedSteps: Int get() = maxOf(watchSteps, phoneSteps)
}
