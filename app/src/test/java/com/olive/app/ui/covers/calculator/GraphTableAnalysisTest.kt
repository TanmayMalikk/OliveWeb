// Tests graph value tables and invalid ranges for calculator users.
package com.olive.app.ui.covers.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GraphTableAnalysisTest {
    // Inputs: the values shown below. Expected: range builds an xcolumn and one column per equation.
    @Test
    fun rangeBuildsAnXColumnAndOneColumnPerEquation() {
        val table =
            buildGraphTable(listOf("x^2", "2x+1"), CalculatorAngleMode.RADIANS, -1.0, 1.0, 1.0)
        assertEquals(listOf("X", "Y1", "Y2"), table.headers)
        assertEquals(listOf("-1", "1", "-1"), table.rows.first())
        assertEquals(listOf("0", "0", "1"), table.rows[1])
        assertEquals(listOf("1", "1", "3"), table.rows.last())
    }

    // Inputs: the values shown below. Expected: unsafe table ranges are rejected.
    @Test
    fun unsafeTableRangesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            buildGraphTable(
                listOf("x"),
                CalculatorAngleMode.RADIANS,
                5.0,
                -5.0,
                1.0
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildGraphTable(
                listOf("x"),
                CalculatorAngleMode.RADIANS,
                0.0,
                10.0,
                0.0
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildGraphTable(
                listOf("x"),
                CalculatorAngleMode.RADIANS,
                0.0,
                1_000.0,
                1.0
            )
        }
    }
}
