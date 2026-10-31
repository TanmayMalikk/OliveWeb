// Tests fractions, calculus, matrices, and statistics for calculator users.
package com.olive.app.ui.covers.calculator

import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedCalculatorEngineTest {
    private val radians = CalculatorAngleMode.RADIANS

    // Inputs: the values shown below. Expected: calculates fractions and calculus.
    @Test
    fun calculatesFractionsAndCalculus() {
        assertTrue(
            AdvancedCalculatorEngine.calculate(
                AdvancedTool.Fraction,
                "1.25",
                radians,
                com.olive.app.ui.testResourceText()
            ).startsWith("5/4")
        )
        assertTrue(
            AdvancedCalculatorEngine.calculate(
                AdvancedTool.Derivative,
                "x^3 ; 2",
                radians,
                com.olive.app.ui.testResourceText()
            ).contains("12")
        )
        assertTrue(
            AdvancedCalculatorEngine.calculate(
                AdvancedTool.Integral,
                "x ; 0 ; 2",
                radians,
                com.olive.app.ui.testResourceText()
            ).contains("2")
        )
        assertTrue(
            AdvancedCalculatorEngine.calculate(
                AdvancedTool.Zero,
                "x^2 - 4 ; 0 ; 5",
                radians,
                com.olive.app.ui.testResourceText()
            ).contains("x = 2")
        )
    }

    // Inputs: the values shown below. Expected: calculates complex numbers and matrices.
    @Test
    fun calculatesComplexNumbersAndMatrices() {
        assertTrue(
            AdvancedCalculatorEngine.calculate(
                AdvancedTool.ComplexMath,
                "(2,3) + (4,-1)",
                radians,
                com.olive.app.ui.testResourceText()
            ).startsWith("6 + 2i")
        )
        assertTrue(
            AdvancedCalculatorEngine.calculate(
                AdvancedTool.MatrixDeterminant,
                "1,2;3,4",
                radians,
                com.olive.app.ui.testResourceText()
            ).contains("-2")
        )
        val reducedRowEchelonForm = AdvancedCalculatorEngine.calculate(
            AdvancedTool.MatrixReducedRowEchelonForm,
            "1,2,5;3,4,11",
            radians,
            com.olive.app.ui.testResourceText()
        )
        assertTrue(reducedRowEchelonForm.contains("[ 1, 0, 1 ]"))
        assertTrue(reducedRowEchelonForm.contains("[ 0, 1, 2 ]"))
        val system = AdvancedCalculatorEngine.calculate(
            AdvancedTool.LinearSystem,
            "1,1,5;2,-1,1",
            radians,
            com.olive.app.ui.testResourceText()
        )
        assertTrue(system.contains("[ 1, 0, 2 ]"))
        assertTrue(system.contains("[ 0, 1, 3 ]"))
    }

    // Inputs: the values shown below. Expected: calculates statistics.
    @Test
    fun calculatesStatistics() {
        val stats = AdvancedCalculatorEngine.calculate(
            AdvancedTool.OneVariableStats,
            "1,2,2,4,8",
            radians,
            com.olive.app.ui.testResourceText()
        )
        assertTrue(stats.contains("mean = 3.4"))
        assertTrue(stats.contains("mode = 2"))
        val regression = AdvancedCalculatorEngine.calculate(
            AdvancedTool.TwoVariableStats,
            "1,2,3 | 2,4,6",
            radians,
            com.olive.app.ui.testResourceText()
        )
        assertTrue(regression.contains("correlation = 1"))
        assertTrue(
            AdvancedCalculatorEngine.calculate(
                AdvancedTool.Scientific,
                "1.2×10^5",
                radians,
                com.olive.app.ui.testResourceText()
            ).contains("120000")
        )
        assertTrue(
            AdvancedCalculatorEngine.calculate(
                AdvancedTool.Scientific,
                "E5",
                radians,
                com.olive.app.ui.testResourceText()
            ).startsWith("1E5")
        )
        assertTrue(
            AdvancedCalculatorEngine.calculate(
                AdvancedTool.OneVariableStats,
                "E5, 2e5, 3x10^5",
                radians,
                com.olive.app.ui.testResourceText()
            ).contains("mean = 200000")
        )
    }
}
