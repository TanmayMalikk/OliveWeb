package com.olive.app.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedCalculatorEngineTest {
    private val radians=CalculatorAngleMode.RADIANS

    @Test fun calculatesFractionsAndCalculus() {
        assertTrue(AdvancedCalculatorEngine.calculate(AdvancedTool.Fraction,"1.25",radians).startsWith("5/4"))
        assertTrue(AdvancedCalculatorEngine.calculate(AdvancedTool.Derivative,"x^3 ; 2",radians).contains("12"))
        assertTrue(AdvancedCalculatorEngine.calculate(AdvancedTool.Integral,"x ; 0 ; 2",radians).contains("2"))
        assertTrue(AdvancedCalculatorEngine.calculate(AdvancedTool.Zero,"x^2 - 4 ; 0 ; 5",radians).contains("x = 2"))
    }

    @Test fun calculatesComplexNumbersAndMatrices() {
        assertTrue(AdvancedCalculatorEngine.calculate(AdvancedTool.ComplexMath,"(2,3) + (4,-1)",radians).startsWith("6 + 2i"))
        assertTrue(AdvancedCalculatorEngine.calculate(AdvancedTool.MatrixDeterminant,"1,2;3,4",radians).contains("-2"))
        val rref=AdvancedCalculatorEngine.calculate(AdvancedTool.MatrixRref,"1,2,5;3,4,11",radians)
        assertTrue(rref.contains("[ 1, 0, 1 ]"))
        assertTrue(rref.contains("[ 0, 1, 2 ]"))
        val system=AdvancedCalculatorEngine.calculate(AdvancedTool.LinearSystem,"1,1,5;2,-1,1",radians)
        assertTrue(system.contains("[ 1, 0, 2 ]"))
        assertTrue(system.contains("[ 0, 1, 3 ]"))
    }

    @Test fun calculatesStatistics() {
        val stats=AdvancedCalculatorEngine.calculate(AdvancedTool.OneVariableStats,"1,2,2,4,8",radians)
        assertTrue(stats.contains("mean = 3.4"))
        assertTrue(stats.contains("mode = 2"))
        val regression=AdvancedCalculatorEngine.calculate(AdvancedTool.TwoVariableStats,"1,2,3 | 2,4,6",radians)
        assertTrue(regression.contains("correlation = 1"))
        assertTrue(AdvancedCalculatorEngine.calculate(AdvancedTool.Scientific,"1.2×10^5",radians).contains("120000"))
    }
}
