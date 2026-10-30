// Tests graph equations, errors, and angle modes for calculator users.
package com.olive.app.ui.covers.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.PI

class GraphExpressionTest {
    // Inputs: the values shown below. Expected: evaluates common graph equations.
    @Test
    fun evaluatesCommonGraphEquations() {
        assertEquals(9.0, GraphExpression.compile("y = x^2").evaluate(3.0), 1e-9)
        assertEquals(7.0, GraphExpression.compile("2x + 1").evaluate(3.0), 1e-9)
        assertEquals(1.0, GraphExpression.compile("sin(pi / 2)").evaluate(0.0), 1e-9)
        assertEquals(-9.0, GraphExpression.compile("-x^2").evaluate(3.0), 1e-9)
        assertEquals(1.0, GraphExpression.compile("cos(2pi)").evaluate(PI), 1e-9)
    }

    // Inputs: the values shown below. Expected: reports invalid graph equations.
    @Test
    fun reportsInvalidGraphEquations() {
        assertThrows(IllegalArgumentException::class.java) { GraphExpression.compile("") }
        assertThrows(IllegalArgumentException::class.java) { GraphExpression.compile("sin x") }
        assertThrows(IllegalArgumentException::class.java) { GraphExpression.compile("x +") }
    }

    // Inputs: the values shown below. Expected: switches trigonometry between radians and degrees.
    @Test
    fun switchesTrigonometryBetweenRadiansAndDegrees() {
        assertEquals(
            1.0,
            GraphExpression.compile("sin(x)", CalculatorAngleMode.RADIANS).evaluate(PI / 2),
            1e-9
        )
        assertEquals(
            1.0,
            GraphExpression.compile("sin(x)", CalculatorAngleMode.DEGREES).evaluate(90.0),
            1e-9
        )
        assertEquals(
            -1.0,
            GraphExpression.compile("cos(x)", CalculatorAngleMode.DEGREES).evaluate(180.0),
            1e-9
        )
    }

    // Inputs: the values shown below. Expected: evaluates scientific and combinatoric functions.
    @Test
    fun evaluatesScientificAndCombinatoricFunctions() {
        assertEquals(120.0, GraphExpression.compile("5!").evaluate(0.0), 1e-9)
        assertEquals(120.0, GraphExpression.compile("nPr(6,3)").evaluate(0.0), 1e-9)
        assertEquals(20.0, GraphExpression.compile("nCr(6,3)").evaluate(0.0), 1e-9)
        assertEquals(3.0, GraphExpression.compile("root(27,3)").evaluate(0.0), 1e-9)
        assertEquals(1250.0, GraphExpression.compile("1.25e3").evaluate(0.0), 1e-9)
        assertEquals(
            30.0,
            GraphExpression.compile("asin(0.5)", CalculatorAngleMode.DEGREES).evaluate(0.0),
            1e-9
        )
        assertEquals(120000.0, GraphExpression.compile("1.2E5").evaluate(0.0), 1e-9)
        assertEquals(120000.0, GraphExpression.compile("1.2×10^5").evaluate(0.0), 1e-9)
        assertEquals(120000.0, GraphExpression.compile("1.2x10^5").evaluate(0.0), 1e-9)
        assertEquals(100000.0, GraphExpression.compile("E5").evaluate(0.0), 1e-9)
        assertEquals(100000.0, GraphExpression.compile("e5").evaluate(0.0), 1e-9)
    }
}
