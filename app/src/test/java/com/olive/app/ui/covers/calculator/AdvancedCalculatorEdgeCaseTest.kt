// Tests malformed text, impossible operations, boundaries, and mixed input formats in calculator tools.
package com.olive.app.ui.covers.calculator

import org.junit.Assert.*
import org.junit.Test

class AdvancedCalculatorEdgeCaseTest {
    private val radians = CalculatorAngleMode.RADIANS
    private val text = com.olive.app.ui.testResourceText()

    // Inputs: the values shown below. Expected: semicolon and number lists accept whitespace but reject wrong shapes.
    @Test
    fun semicolonAndNumberListsAcceptWhitespaceButRejectWrongShapes() {
        assertEquals(listOf("x + 1", "2"), semi(" x + 1 ; 2 ", 2))
        assertEquals(listOf(1.0, 2.0, 3.0), list("1, 2\n3"))

        assertThrows(IllegalArgumentException::class.java) { semi("x + 1", 2) }
        assertThrows(IllegalArgumentException::class.java) { list("  ,  \n") }
        assertThrows(NumberFormatException::class.java) { list("one, two") }
    }

    // Inputs: the values shown below. Expected: fraction and mixed number tools reject malformed values and zero divisors.
    @Test
    fun fractionAndMixedNumberToolsRejectMalformedValuesAndZeroDivisors() {
        assertTrue(fraction("-1.25").startsWith("-5/4"))
        assertEquals("-7/3", mixed("-2 1/3"))
        assertEquals("2 1/3", mixed("7/3"))

        assertThrows(Exception::class.java) { fraction("not a number") }
        assertThrows(Exception::class.java) { mixed("1/0") }
        assertThrows(Exception::class.java) { mixed("one half") }
    }

    // Inputs: the values shown below. Expected: matrix tools reject ragged rows invalid commands and impossible powers.
    @Test
    fun matrixToolsRejectRaggedRowsInvalidCommandsAndImpossiblePowers() {
        assertThrows(IllegalArgumentException::class.java) { matrix("1,2;3") }
        assertThrows(Exception::class.java) { matrix("one,two") }
        assertThrows(IllegalArgumentException::class.java) { matrixPower("1,0;0,1|-1") }
        assertThrows(Exception::class.java) { matrixRowOperation("1,2;3,4|swap 1") }
        assertThrows(Exception::class.java) { matrixRowOperation("1,2;3,4|rotate 1 2") }
    }

    // Inputs: the values shown below. Expected: statistics reject empty mismatched and unsupported data.
    @Test
    fun statisticsRejectEmptyMismatchedAndUnsupportedData() {
        assertThrows(IllegalArgumentException::class.java) { oneVariable("", text) }
        assertThrows(IllegalArgumentException::class.java) { twoVariable("1,2|3", text) }
        assertThrows(IllegalArgumentException::class.java) {
            polynomialRegression("degree 4|1,2,3|1,4,9")
        }
        assertThrows(IllegalStateException::class.java) {
            regression("imaginary|1,2,3|4,5,6", text)
        }
    }

    // Inputs: the values shown below. Expected: optimization rejects backward intervals and missing zeros.
    @Test
    fun optimizationRejectsBackwardIntervalsAndMissingZeros() {
        assertThrows(IllegalArgumentException::class.java) { optimize("x;5;1", radians, "min") }
        assertThrows(IllegalArgumentException::class.java) { optimize("x^2+1;-2;2", radians, "zero") }
    }

    // Inputs: the values shown below. Expected: calculator input ignores unknown keys and bounds hostile input length.
    @Test
    fun calculatorInputIgnoresUnknownKeysAndBoundsHostileInputLength() {
        val input = CalculatorInput()
        input.press("DROP TABLE")
        assertEquals("0", input.display)
        assertEquals("", input.expression)

        repeat(200) { input.press("9") }
        assertEquals(120, input.expression.length)
        input.press("=")
        assertTrue(input.display == "Error" || input.display.isNotBlank())
    }
}
