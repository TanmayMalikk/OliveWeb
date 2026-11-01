// Tests calculator input, history, angle mode, and PIN trigger behavior.
package com.olive.app.ui.covers.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorInputTest {
    // Inputs: the values shown below. Expected: history retains only five newest calculations including restored entries.
    @Test
    fun historyRetainsOnlyFiveNewestCalculationsIncludingRestoredEntries() {
        var saved = emptyList<CalculatorHistoryEntry>()
        val old = (1..9).map { CalculatorHistoryEntry("$it+1", "${it + 1}") }
        val input = CalculatorInput(old) { saved = it }
        assertEquals(old.take(5), input.history)
        input.restoreHistory(old)
        assertEquals(old.take(5), saved)
        listOf("9", "+", "9", "=").forEach { input.press(it) }
        assertEquals(5, input.history.size)
        assertEquals(CalculatorHistoryEntry("9+9", "18"), input.history.first())
        assertEquals(old.take(4), input.history.drop(1))
        assertEquals(input.history, saved)
    }

    // Inputs: the values shown below. Expected: entered pin requires two consecutive equals taps.
    @Test
    fun enteredPinRequiresTwoConsecutiveEqualsTaps() {
        val input = CalculatorInput()
        "123456".forEach { assertNull(input.press(it.toString())) }
        assertNull(input.press("="))
        assertEquals("123456", input.press("="))
        assertEquals("0", input.display)
        assertNull(input.press("="))
        "0012".forEach { input.press(it.toString()) }
        assertEquals("0012", input.display)
        assertNull(input.press("="))
        assertEquals("0012", input.press("="))
    }

    // Inputs: the values shown below. Expected: arithmetic and intervening keys do not become pin trigger.
    @Test
    fun arithmeticAndInterveningKeysDoNotBecomePinTrigger() {
        val input = CalculatorInput()
        listOf("1", ".", "2", "+", "3", ".", "4", "=").forEach { assertNull(input.press(it)) }
        assertEquals("4.6", input.display)
        assertNull(input.press("="))
        input.reset()
        "1234".forEach { input.press(it.toString()) }
        input.press("=")
        input.press("5")
        assertNull(input.press("="))
        input.reset()
        listOf(
            "1",
            "0",
            "0",
            "0",
            "+",
            "2",
            "3",
            "4",
            "=",
            "="
        ).forEach { assertNull(input.press(it)) }
        assertEquals("1234", input.display)
        input.reset()
        "999999999999999999".forEach { input.press(it.toString()) }
        listOf("+", "1", "=").forEach { input.press(it) }
        assertEquals("1E18", input.display)
    }

    // Inputs: the values shown below. Expected: trigonometry uses the selected angle mode and disarms pin entry.
    @Test
    fun trigonometryUsesTheSelectedAngleModeAndDisarmsPinEntry() {
        val input = CalculatorInput()
        input.beginTrig("sin", false, CalculatorAngleMode.DEGREES)
        "90".forEach { input.press(it.toString()) }
        assertEquals("sin(90", input.display)
        input.press("=")
        assertEquals("1", input.display)
        assertEquals("sin(90) =", input.expression)
        assertNull(input.press("="))

        input.reset()
        input.beginTrig("cos", false, CalculatorAngleMode.RADIANS)
        "3.141592653589793".forEach { input.press(it.toString()) }
        input.press("=")
        assertEquals("-1", input.display)

        input.reset()
        input.beginTrig("sin", true, CalculatorAngleMode.DEGREES)
        listOf("0", ".", "5", "=").forEach { input.press(it) }
        assertEquals("30", input.display)
        assertEquals("sin⁻¹(0.5) =", input.expression)
    }

    // Inputs: the values shown below. Expected: parentheses and percent stay visible until equals.
    @Test
    fun parenthesesAndPercentStayVisibleUntilEquals() {
        val input = CalculatorInput()
        listOf("(", "2", "+", "3", ")", "×", "4").forEach { input.press(it) }
        assertEquals("(2+3)×4", input.display)
        input.press("=")
        assertEquals("20", input.display)

        input.reset()
        listOf("5", "%").forEach { input.press(it) }
        assertEquals("5%", input.display)
        assertEquals("5%", input.expression)
        input.press("=")
        assertEquals("0.05", input.display)
    }

    // Inputs: the values shown below. Expected: smart parentheses and scientific keys build visible expressions.
    @Test
    fun smartParenthesesAndScientificKeysBuildVisibleExpressions() {
        val input = CalculatorInput()
        listOf("()", "2", "+", "3", "()", "×", "4", "=").forEach { input.press(it) }
        assertEquals("20", input.display)

        input.reset()
        input.beginFunction("√", CalculatorAngleMode.RADIANS)
        listOf("8", "1", "=").forEach { input.press(it) }
        assertEquals("9", input.display)

        input.reset()
        listOf("5", "!", "=").forEach { input.press(it) }
        assertEquals("120", input.display)
    }

    // Inputs: the values shown below. Expected: formats large and small answers and reuses history.
    @Test
    fun formatsLargeAndSmallAnswersAndReusesHistory() {
        val input = CalculatorInput()
        listOf("1", "e", "1", "0", "=").forEach { input.press(it) }
        assertEquals("1E10", input.display)
        assertEquals(CalculatorHistoryEntry("1e10", "1E10"), input.history.first())

        input.reset()
        listOf("2", "+", "3", "=").forEach { input.press(it) }
        val prior = input.history.first()
        input.press("AC")
        input.reuse(prior)
        assertEquals("2+3", input.expression)
        assertEquals("2+3", input.display)
    }

    // Inputs: the values shown below. Expected: history changes are persisted and can be cleared.
    @Test
    fun historyChangesArePersistedAndCanBeCleared() {
        var persisted = emptyList<CalculatorHistoryEntry>()
        val input = CalculatorInput(onHistoryChanged = { persisted = it })
        listOf("2", "+", "2", "=").forEach { input.press(it) }
        assertEquals(input.history, persisted)
        assertEquals(CalculatorHistoryEntry("2+2", "4"), persisted.first())

        input.clearHistory()
        assertTrue(input.history.isEmpty())
        assertTrue(persisted.isEmpty())
    }

    // Inputs: boundary, middle, and leading-zero PINs followed by equals twice. Expected: one equals only calculates; the second returns the exact PIN.
    @Test
    fun representativeCalculatorPinsRequireTwoEqualsPresses() {
        val input = CalculatorInput()
        val representativePins = listOf(
            "0000", "0001", "0123", "4999", "5000", "9998", "9999",
            "00000", "50000", "99999", "000000", "500000", "999999",
        )
        for (pin in representativePins) {
            input.reset()
            pin.forEach { assertNull("PIN returned before equals: $pin", input.press(it.toString())) }
            assertNull("PIN returned after only one equals: $pin", input.press("="))
            assertEquals("Wrong PIN returned for input=$pin", pin, input.press("="))
        }
    }

    // Inputs: 1,000 seeded random key sequences including operators, functions, equals, and unknown keys. Expected: any returned PIN is always 4-6 ASCII digits.
    @Test
    fun randomCalculatorKeySequencesNeverReturnMalformedPins() {
        val seed = 0x51A7E
        val random = kotlin.random.Random(seed)
        val keys = listOf(
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
            "+", "−", "×", "÷", "^", ".", "(", ")", "%", "!", "=", "AC", "⌫", "DROP TABLE",
        )
        val input = CalculatorInput()
        repeat(1_000) { case ->
            if (random.nextInt(20) == 0) input.reset()
            val key = keys[random.nextInt(keys.size)]
            val returned = input.press(key)
            if (returned != null) {
                assertTrue(
                    "seed=$seed case=$case key=$key returned=$returned expression=${input.expression}",
                    returned.matches(Regex("[0-9]{4,6}")),
                )
            }
            assertTrue(
                "seed=$seed case=$case expression exceeded limit: ${input.expression.length}",
                input.expression.length <= 122,
            )
        }
    }
}
