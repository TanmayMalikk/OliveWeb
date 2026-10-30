// Tests equation roots and expression parsing for calculator users.
package com.olive.app.ui.covers.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EquationSolverTest {
    // Inputs: the values shown below. Expected: solves quadratic with real and complex roots.
    @Test
    fun solvesQuadraticWithRealAndComplexRoots() {
        assertEquals("x = 2, 3", EquationSolver.quadratic("x^2 - 5x + 6 = 0"))
        assertEquals("x = 0 − 1i, 0 + 1i", EquationSolver.quadratic("x² + 1 = 0"))
    }

    // Inputs: the values shown below. Expected: solves polynomial and moves right side to the left.
    @Test
    fun solvesPolynomialAndMovesRightSideToTheLeft() {
        assertEquals("x = 1, 2, 3", EquationSolver.polynomial("x^3 - 6x^2 + 11x - 6 = 0"))
        assertEquals("x = 2, 3", EquationSolver.polynomial("x^2 = 5x - 6"))
    }

    // Inputs: the values shown below. Expected: accepts parentheses and implicit multiplication.
    @Test
    fun acceptsParenthesesAndImplicitMultiplication() {
        assertEquals("x = 2, 3", EquationSolver.quadratic("(x - 2)(x - 3) = 0"))
        assertEquals("x = -1, -1", EquationSolver.quadratic("2(x + 1)^2 = 0"))
        assertEquals("x = 1, 2, 3", EquationSolver.polynomial("(x - 1)(x - 2)(x - 3) = 0"))
        assertEquals("x = -2, -1, 2", EquationSolver.polynomial("(x + 1)(x^2 - 4) = 0"))
    }

    // Inputs: the values shown below. Expected: accepts scientific notation in solvers and polynomial calculations.
    @Test
    fun acceptsScientificNotationInSolversAndPolynomialCalculations() {
        assertEquals("x = 1, 2", EquationSolver.quadratic("E5x^2 - 3e5x + 2x10^5 = 0"))
        assertEquals("3E9x", EquationSolver.calculatePolynomial("E9x + 2x10^9x"))
    }

    // Inputs: the values shown below. Expected: calculates polynomial arithmetic and returns polynomials or numbers.
    @Test
    fun calculatesPolynomialArithmeticAndReturnsPolynomialsOrNumbers() {
        assertEquals("2x", EquationSolver.calculatePolynomial("(x + 1) + (x - 1)"))
        assertEquals("2", EquationSolver.calculatePolynomial("(x + 1) - (x - 1)"))
        assertEquals("x^2 − 1", EquationSolver.calculatePolynomial("(x + 1)(x - 1)"))
        assertEquals("x + 1", EquationSolver.calculatePolynomial("(x^2 - 1) / (x - 1)"))
        assertEquals("12", EquationSolver.calculatePolynomial("(2 + 4) * 2"))
    }

    // Inputs: the values shown below. Expected: rejects polynomial division with aremainder.
    @Test
    fun rejectsPolynomialDivisionWithARemainder() {
        assertThrows(IllegalArgumentException::class.java) { EquationSolver.calculatePolynomial("x / (x + 1)") }
    }

    // Inputs: the values shown below. Expected: rejects unsupported or malformed equations.
    @Test
    fun rejectsUnsupportedOrMalformedEquations() {
        assertThrows(IllegalArgumentException::class.java) { EquationSolver.polynomial("x^9 - 1 = 0") }
        assertThrows(IllegalArgumentException::class.java) { EquationSolver.quadratic("x + 2 = 0") }
    }
}
