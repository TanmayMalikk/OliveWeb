// Calculator cover users: calculates advanced calculator results.
package com.olive.app.ui.covers.calculator

import com.olive.app.R

import org.apache.commons.math3.linear.LUDecomposition
import org.apache.commons.math3.linear.MatrixUtils

internal object AdvancedCalculatorEngine {
    fun calculate(
        tool: AdvancedTool,
        rawInput: String,
        mode: CalculatorAngleMode,
        text: com.olive.app.ui.shared.ResourceText
    ): String {
        val input = normalizeCalculatorNotation(rawInput)
        return when (tool) {
            AdvancedTool.Expression -> number(GraphExpression.compile(input, mode).evaluate(0.0))
            AdvancedTool.FunctionValue -> semi(input, 2).let {
                text(
                    R.string.ui_value_value_0c813d,
                    number(GraphExpression.compile(it[0], mode).evaluate(value(it[1], mode)))
                )
            }

            AdvancedTool.LinearSystem -> matrix(reducedRowEchelonForm(matrix(input)))
            AdvancedTool.Fraction -> fraction(input)
            AdvancedTool.MixedFraction -> mixed(input)
            AdvancedTool.Scientific -> scientific(input)
            AdvancedTool.Derivative -> derivative(input, mode)
            AdvancedTool.Integral -> integral(input, mode)
            AdvancedTool.Zero -> optimize(input, mode, "zero")
            AdvancedTool.Minimum -> optimize(input, mode, "min")
            AdvancedTool.Maximum -> optimize(input, mode, "max")
            AdvancedTool.Intersection -> intersection(input, mode)
            AdvancedTool.Limit -> limit(input, mode, text)
            AdvancedTool.ComplexMath -> complexArithmetic(input)
            AdvancedTool.ComplexDetails -> complexDetails(input, text)
            AdvancedTool.ComplexPower -> complexPower(input, text)
            AdvancedTool.MatrixAdd -> matrixAdd(input)
            AdvancedTool.MatrixMultiply -> matrixBinary(input) { a, b -> a.multiply(b) }
            AdvancedTool.MatrixScalar -> matrixScalar(input)
            AdvancedTool.MatrixPower -> matrixPower(input)
            AdvancedTool.MatrixInverse -> matrix(LUDecomposition(matrix(input)).solver.inverse)
            AdvancedTool.MatrixDeterminant -> number(LUDecomposition(matrix(input)).determinant)
            AdvancedTool.MatrixTranspose -> matrix(matrix(input).transpose())
            AdvancedTool.MatrixIdentity -> matrix(
                MatrixUtils.createRealIdentityMatrix(
                    input.trim().toInt().also { require(it in 1..10) })
            )

            AdvancedTool.MatrixReducedRowEchelonForm -> matrix(reducedRowEchelonForm(matrix(input)))
            AdvancedTool.MatrixRowOperation -> matrixRowOperation(input)
            AdvancedTool.OneVariableStats -> oneVariable(input, text)
            AdvancedTool.TwoVariableStats -> twoVariable(input, text)
            AdvancedTool.PolynomialRegression -> polynomialRegression(input)
            AdvancedTool.Regression -> regression(input, text)
        }
    }


}
