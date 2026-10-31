// Tests shared calculator parsing and fraction helpers at numeric boundaries.
package com.olive.app.ui.covers.calculator

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class CalculatorSupportBoundaryTest {
    // Input: zero, integers, and negative integers. Expected: every exact integer uses denominator one.
    @Test
    fun fractionApproximationKeepsExactIntegers() {
        assertEquals(0L to 1L, approximateFraction(0.0))
        assertEquals(7L to 1L, approximateFraction(7.0))
        assertEquals(-12L to 1L, approximateFraction(-12.0))
    }

    // Input: positive and negative repeating fractions. Expected: signs and reduced denominators are preserved.
    @Test
    fun fractionApproximationReducesRepeatingValues() {
        assertEquals(1L to 3L, approximateFraction(1.0 / 3.0))
        assertEquals(-2L to 3L, approximateFraction(-2.0 / 3.0))
        assertEquals(7L to 8L, approximateFraction(0.875))
    }

    // Input: irrational pi. Expected: the bounded approximation is close and never exceeds denominator 10,000.
    @Test
    fun irrationalApproximationStaysInsideItsSearchLimit() {
        val (numerator, denominator) = approximateFraction(PI)
        assertTrue(denominator in 1..10_000)
        assertTrue(abs(PI - numerator.toDouble() / denominator) < 0.000001)
    }

    // Input: common factors, coprimes, and zero. Expected: greatest-common-divisor boundaries remain safe.
    @Test
    fun greatestCommonDivisorHandlesBoundaryInputs() {
        assertEquals(6L, gcd(54, 24))
        assertEquals(1L, gcd(17, 13))
        assertEquals(5L, gcd(0, 5))
        assertEquals(1L, gcd(0, 0))
    }

    // Input: scientific notation, Unicode multiplication, and signed numbers. Expected: list parsing shares calculator notation rules.
    @Test
    fun numericListsAcceptEverySupportedScientificNotation() {
        assertEquals(
            listOf(1000.0, -0.02, 300.0, 10.0, 10.0, 10.0, 10.0),
            list("1E3, -2e-2, 3×10^2, E1 E1 E1 E1"),
        )
    }

    // Input: whitespace-only and non-finite list values. Expected: invalid statistical data is rejected immediately.
    @Test
    fun numericListsRejectMissingAndNonFiniteValues() {
        assertThrows(IllegalArgumentException::class.java) { list(" \n , ") }
        assertThrows(IllegalArgumentException::class.java) { list("NaN") }
        assertThrows(IllegalArgumentException::class.java) { list("Infinity") }
        assertThrows(IllegalArgumentException::class.java) { approximateFraction(Double.NaN) }
    }

    // Input: arithmetic and variable expressions in both angle modes. Expected: shared evaluation returns finite answers.
    @Test
    fun sharedExpressionEvaluationUsesCalculatorGrammar() {
        assertEquals(14.0, value("2+3*4", CalculatorAngleMode.RADIANS), 0.000001)
        assertEquals(1.0, value("sin(90)", CalculatorAngleMode.DEGREES), 0.000001)
        assertEquals(-8.0, value("-2^3", CalculatorAngleMode.RADIANS), 0.000001)
    }

    // Input: ordinary, tiny, huge, and non-finite output values. Expected: formatting is stable and unsafe results fail.
    @Test
    fun sharedNumberFormattingHandlesMagnitudeBoundaries() {
        assertEquals("12.5", number(12.5))
        assertTrue(number(0.0000001).contains("E"))
        assertTrue(number(1.0e12).contains("E"))
        assertThrows(IllegalArgumentException::class.java) { number(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { number(Double.POSITIVE_INFINITY) }
    }
}
