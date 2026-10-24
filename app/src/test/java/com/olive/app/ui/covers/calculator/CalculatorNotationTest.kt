// Tests the calculator notation test behavior.
package com.olive.app.ui.covers.calculator

import com.olive.app.ui.shared.*

import org.junit.Assert.assertEquals
import org.junit.Test

// Groups the data and actions for calculator notation test.
class CalculatorNotationTest {
    @Test
    fun normalizesEverySupportedScientificNotationSpelling() {
        assertEquals(
            "1E5 + 2e-4 + 3e6 + 1e7",
            normalizeCalculatorNotation("1E5 + 2e-4 + 3x10^6 + E7")
        )
    }

    @Test
    fun usesScientificNotationForAnswersWithLongZeroRuns() {
        assertEquals("1.25E10", formatCalculatorNumber(12_500_000_000.0))
        assertEquals("2.5E-7", formatCalculatorNumber(0.00000025))
        assertEquals("125000", formatCalculatorNumber(125_000.0))
    }
}
