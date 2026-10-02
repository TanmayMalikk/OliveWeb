package com.olive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.PI

class GraphExpressionTest {
    @Test fun evaluatesCommonGraphEquations() {
        assertEquals(9.0,GraphExpression.compile("y = x^2").evaluate(3.0),1e-9)
        assertEquals(7.0,GraphExpression.compile("2x + 1").evaluate(3.0),1e-9)
        assertEquals(1.0,GraphExpression.compile("sin(pi / 2)").evaluate(0.0),1e-9)
        assertEquals(-9.0,GraphExpression.compile("-x^2").evaluate(3.0),1e-9)
        assertEquals(1.0,GraphExpression.compile("cos(2pi)").evaluate(PI),1e-9)
    }

    @Test fun reportsInvalidGraphEquations() {
        assertThrows(IllegalArgumentException::class.java) { GraphExpression.compile("") }
        assertThrows(IllegalArgumentException::class.java) { GraphExpression.compile("sin x") }
        assertThrows(IllegalArgumentException::class.java) { GraphExpression.compile("x +") }
    }

    @Test fun switchesTrigonometryBetweenRadiansAndDegrees() {
        assertEquals(1.0,GraphExpression.compile("sin(x)",CalculatorAngleMode.RADIANS).evaluate(PI/2),1e-9)
        assertEquals(1.0,GraphExpression.compile("sin(x)",CalculatorAngleMode.DEGREES).evaluate(90.0),1e-9)
        assertEquals(-1.0,GraphExpression.compile("cos(x)",CalculatorAngleMode.DEGREES).evaluate(180.0),1e-9)
    }
}
