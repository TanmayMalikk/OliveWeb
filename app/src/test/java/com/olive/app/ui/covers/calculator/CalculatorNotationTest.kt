// Tests scientific notation input and output in the calculator cover.
package com.olive.app.ui.covers.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CalculatorNotationTest {
    // Inputs: the values shown below. Expected: normalizes every supported scientific notation spelling.
    @Test
    fun normalizesEverySupportedScientificNotationSpelling() {
        assertEquals(
            "1E5 + 2e-4 + 3e6 + 1e7",
            normalizeCalculatorNotation("1E5 + 2e-4 + 3x10^6 + E7")
        )
    }

    // Inputs: the values shown below. Expected: uses scientific notation for answers with long zero runs.
    @Test
    fun usesScientificNotationForAnswersWithLongZeroRuns() {
        assertEquals("1.25E10", formatCalculatorNumber(12_500_000_000.0))
        assertEquals("2.5E-7", formatCalculatorNumber(0.00000025))
        assertEquals("125000", formatCalculatorNumber(125_000.0))
    }

    // Inputs: positive infinity, negative infinity, and NaN. Expected: calculator formatting rejects non-finite overflow results.
    @Test
    fun nonFiniteOverflowResultsAreRejected() {
        for (value in listOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NaN)) {
            assertThrows(Exception::class.java) { formatCalculatorNumber(value) }
            assertThrows(Exception::class.java) { formatScientificCalculatorNumber(value) }
        }
    }
}
