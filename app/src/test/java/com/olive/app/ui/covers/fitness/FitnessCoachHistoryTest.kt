package com.olive.app.ui.covers.fitness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class FitnessCoachHistoryTest {
    @Test fun reopeningKeepsOlderMessagesInOrderAndSeparatesAccounts() {
        val context = RuntimeEnvironment.getApplication()
        val first = FitnessCoachHistory(context, "coach-first")
        val second = FitnessCoachHistory(context, "coach-second")
        assertTrue(first.load().isEmpty())
        repeat(12) { index -> first.append("Question $index", "Reply $index") }
        second.append("Different question", "Different reply")

        val expected = (0 until 12).flatMap { index ->
            listOf(CoachTurn("user", "Question $index"), CoachTurn("model", "Reply $index"))
        }
        assertEquals(expected, FitnessCoachHistory(context, "coach-first").load())
        assertEquals(
            listOf(CoachTurn("user", "Different question"), CoachTurn("model", "Different reply")),
            FitnessCoachHistory(context, "coach-second").load(),
        )
    }

    @Test fun clearDeletesOnlyOwnerHistory() {
        val context = RuntimeEnvironment.getApplication()
        val first = FitnessCoachHistory(context, "coach-first")
        val second = FitnessCoachHistory(context, "coach-second")
        first.append("Q1", "R1")
        second.append("Q2", "R2")
        first.clear()
        assertTrue(first.load().isEmpty())
        assertEquals(listOf(CoachTurn("user", "Q2"), CoachTurn("model", "R2")), second.load())
    }
}
