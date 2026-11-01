// Watch fitness users: holds Watch6 activity readings and nutrition synced from the phone.
package com.olive.app.wear

internal data class WatchHeartRateSummary(
    val peakBpm: Int = 0,
    val lowestBpm: Int = 0,
    val totalBpm: Long = 0L,
    val sampleCount: Int = 0,
    val restingTotalBpm: Long = 0L,
    val restingSampleCount: Int = 0,
) {
    val averageBpm: Int?
        get() = if (sampleCount == 0) null else (totalBpm / sampleCount).toInt()

    val restingBpm: Int?
        get() = if (restingSampleCount == 0) null else {
            (restingTotalBpm / restingSampleCount).toInt()
        }

    // Watch vitals: adds one safe sensor reading to today's summary.
    fun record(bpm: Int, resting: Boolean): WatchHeartRateSummary {
        if (bpm !in 30..240) return this
        if (sampleCount == Int.MAX_VALUE || totalBpm > Long.MAX_VALUE - bpm) return this
        if (resting && (restingSampleCount == Int.MAX_VALUE || restingTotalBpm > Long.MAX_VALUE - bpm)) {
            return this
        }
        return copy(
            peakBpm = maxOf(peakBpm, bpm),
            lowestBpm = if (lowestBpm == 0) bpm else minOf(lowestBpm, bpm),
            totalBpm = totalBpm + bpm,
            sampleCount = sampleCount + 1,
            restingTotalBpm = restingTotalBpm + if (resting) bpm else 0,
            restingSampleCount = restingSampleCount + if (resting) 1 else 0,
        )
    }
}

internal data class WatchFitnessState(
    val watchSteps: Int = 0,
    val phoneSteps: Int = 0,
    val stepGoal: Int = 8_000,
    val heartRate: Int? = null,
    val heartRateStatus: String = "Tap allow to read heart rate",
    val heartRateSummary: WatchHeartRateSummary = WatchHeartRateSummary(),
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
    val displayedSteps: Int get() = maxOf(watchSteps, phoneSteps).coerceAtLeast(0)
}
