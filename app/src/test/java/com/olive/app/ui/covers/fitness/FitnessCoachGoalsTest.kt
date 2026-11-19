package com.olive.app.ui.covers.fitness

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FitnessCoachGoalsTest {
    @Test fun oneAutomaticUpdateCanApplyAllSixGoals() {
        val today = LocalDate.of(2026, 11, 19)
        val original = FitnessProfile()
        val suggested = mapOf("steps" to 8800, "calories" to 2100, "protein" to 130,
            "carbohydrates" to 245, "fat" to 60, "waterMl" to 2150)
        assertEquals(suggested, automaticCoachGoals(original, original, suggested, "", today))
        assertEquals(mapOf("steps" to 8800), automaticCoachGoals(original, original, suggested, today.toString(), today))
    }

    @Test fun automaticUpdatesSkipLargeInvalidOrStaleChanges() {
        val today = LocalDate.of(2026, 11, 19)
        val original = FitnessProfile(stepGoal = 20000)
        val current = original.copy(proteinGoal = 125)
        val suggested = mapOf("steps" to 100001, "calories" to 1799, "protein" to 130,
            "carbohydrates" to 250, "fat" to 5, "waterMl" to 2150, "heightCm" to 150)
        assertEquals(mapOf("waterMl" to 2150), automaticCoachGoals(original, current, suggested, "", today))
    }

    @Test fun optedInStepsCanGoFromEightThousandToTwoThousandAfterAnotherGoalChangedToday() {
        val today = LocalDate.of(2026, 11, 19)
        val original = FitnessProfile(stepGoal = 8000)
        val suggestion = mapOf("steps" to 2000, "waterMl" to 2150)
        assertEquals(mapOf("steps" to 2000),
            automaticCoachGoals(original, original, suggestion, today.toString(), today))
        assertTrue(automaticCoachGoals(original, original.copy(stepGoal = 6000), suggestion, today.toString(), today).isEmpty())
    }

    @Test fun applyingAProposalOnlyChangesValidSpecifiedTargets() {
        val original = FitnessProfile(weightKg = 65f, heightCm = 160f)
        val updated = original.withCoachGoals(mapOf("steps" to 8200, "waterMl" to -1, "weightKg" to 100))
        assertEquals(original.copy(stepGoal = 8200), updated)
    }
}
