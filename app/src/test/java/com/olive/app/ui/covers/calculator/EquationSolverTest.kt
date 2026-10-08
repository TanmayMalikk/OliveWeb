package com.olive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EquationSolverTest {
    @Test fun solvesQuadraticWithRealAndComplexRoots() {
        assertEquals("x = 2, 3",EquationSolver.quadratic("x^2 - 5x + 6 = 0"))
        assertEquals("x = 0 − 1i, 0 + 1i",EquationSolver.quadratic("x² + 1 = 0"))
    }

    @Test fun solvesPolynomialAndMovesRightSideToTheLeft() {
        assertEquals("x = 1, 2, 3",EquationSolver.polynomial("x^3 - 6x^2 + 11x - 6 = 0"))
        assertEquals("x = 2, 3",EquationSolver.polynomial("x^2 = 5x - 6"))
    }

    @Test fun acceptsParenthesesAndImplicitMultiplication() {
        assertEquals("x = 2, 3",EquationSolver.quadratic("(x - 2)(x - 3) = 0"))
        assertEquals("x = -1, -1",EquationSolver.quadratic("2(x + 1)^2 = 0"))
        assertEquals("x = 1, 2, 3",EquationSolver.polynomial("(x - 1)(x - 2)(x - 3) = 0"))
        assertEquals("x = -2, -1, 2",EquationSolver.polynomial("(x + 1)(x^2 - 4) = 0"))
    }

    @Test fun acceptsScientificNotationInSolversAndPolynomialCalculations() {
        assertEquals("x = 1, 2",EquationSolver.quadratic("E5x^2 - 3e5x + 2x10^5 = 0"))
        assertEquals("3E9x",EquationSolver.calculatePolynomial("E9x + 2x10^9x"))
    }

    @Test fun calculatesPolynomialArithmeticAndReturnsPolynomialsOrNumbers() {
        assertEquals("2x",EquationSolver.calculatePolynomial("(x + 1) + (x - 1)"))
        assertEquals("2",EquationSolver.calculatePolynomial("(x + 1) - (x - 1)"))
        assertEquals("x^2 − 1",EquationSolver.calculatePolynomial("(x + 1)(x - 1)"))
        assertEquals("x + 1",EquationSolver.calculatePolynomial("(x^2 - 1) / (x - 1)"))
        assertEquals("12",EquationSolver.calculatePolynomial("(2 + 4) * 2"))
    }

    @Test fun rejectsPolynomialDivisionWithARemainder() {
        assertThrows(IllegalArgumentException::class.java) { EquationSolver.calculatePolynomial("x / (x + 1)") }
    }

    @Test fun rejectsUnsupportedOrMalformedEquations() {
        assertThrows(IllegalArgumentException::class.java) { EquationSolver.polynomial("x^9 - 1 = 0") }
        assertThrows(IllegalArgumentException::class.java) { EquationSolver.quadratic("x + 2 = 0") }
    }
}
