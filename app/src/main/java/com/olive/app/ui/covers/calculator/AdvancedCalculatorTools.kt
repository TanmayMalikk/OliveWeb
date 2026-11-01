// Calculator cover users: lists advanced calculator operations.
package com.olive.app.ui.covers.calculator

import com.olive.app.R


// These are the advanced calculator choices. Add a choice here and handle it in calculate() below.
internal enum class AdvancedTool(val category: Int, val title: Int, val example: Int) {
    Expression(
        R.string.ui_basic,
        R.string.ui_evaluate_expression,
        R.string.ui_sqrt_81_5_or_ncr_10_3
    ),
    FunctionValue(R.string.ui_algebra, R.string.ui_function_value, R.string.ui_x_2_2x_3),
    LinearSystem(R.string.ui_algebra, R.string.ui_system_of_equations, R.string.ui_1_1_5_2_1_1),
    Fraction(R.string.ui_basic, R.string.ui_fraction_decimal, R.string.ui_1_25_or_7_4),
    MixedFraction(R.string.ui_basic, R.string.ui_mixed_improper_fraction, R.string.ui_2_1_3_or_7_3),
    Scientific(R.string.ui_basic, R.string.ui_scientific_notation, R.string.ui_1_2e5_or_1_2_10_5),
    Derivative(R.string.ui_calculus, R.string.ui_numerical_derivative, R.string.ui_x_3_2x_2),
    Integral(R.string.ui_calculus, R.string.ui_definite_integral, R.string.ui_sin_x_0_pi),
    Zero(R.string.ui_calculus, R.string.ui_find_zero, R.string.ui_x_2_4_0_5),
    Minimum(R.string.ui_calculus, R.string.ui_find_minimum, R.string.ui_x_2_4x_10_10),
    Maximum(R.string.ui_calculus, R.string.ui_find_maximum, R.string.ui_x_2_4x_10_10_be0ef3),
    Intersection(R.string.ui_calculus, R.string.ui_find_intersection, R.string.ui_x_2_2x_3_10_10),
    Limit(R.string.ui_calculus, R.string.ui_investigate_limit, R.string.ui_sin_x_x_0),
    ComplexMath(R.string.ui_complex, R.string.ui_complex_arithmetic, R.string.ui_2_3_4_1),
    ComplexDetails(R.string.ui_complex, R.string.ui_complex_details, R.string.ui_3_4),
    ComplexPower(R.string.ui_complex, R.string.ui_complex_power_roots, R.string.ui_3_4_2),
    MatrixAdd(R.string.ui_matrices, R.string.ui_matrix_add_subtract, R.string.ui_1_2_3_4_5_6_7_8),
    MatrixMultiply(
        R.string.ui_matrices,
        R.string.ui_matrix_multiply,
        R.string.ui_1_2_3_4_5_6_7_8_9ad55f
    ),
    MatrixScalar(R.string.ui_matrices, R.string.ui_scalar_multiplication, R.string.ui_1_2_3_4_3),
    MatrixPower(R.string.ui_matrices, R.string.ui_matrix_power, R.string.ui_1_2_3_4_3),
    MatrixInverse(R.string.ui_matrices, R.string.ui_inverse, R.string.ui_1_2_3_4),
    MatrixDeterminant(R.string.ui_matrices, R.string.ui_determinant, R.string.ui_1_2_3_4),
    MatrixTranspose(R.string.ui_matrices, R.string.ui_transpose, R.string.ui_1_2_3_4_5_6),
    MatrixIdentity(R.string.ui_matrices, R.string.ui_identity_matrix, R.string.ui_3),
    MatrixReducedRowEchelonForm(
        R.string.ui_matrices,
        R.string.ui_rref_augmented_matrix,
        R.string.ui_1_2_5_3_4_11
    ),
    MatrixRowOperation(
        R.string.ui_matrices,
        R.string.ui_row_operation,
        R.string.ui_1_2_3_4_swap_1_2
    ),
    OneVariableStats(
        R.string.ui_statistics,
        R.string.ui_one_variable_statistics,
        R.string.ui_1_2_2_4_8
    ),
    TwoVariableStats(
        R.string.ui_statistics,
        R.string.ui_two_variable_statistics,
        R.string.ui_1_2_3_2_4_5
    ),
    PolynomialRegression(
        R.string.ui_statistics,
        R.string.ui_polynomial_regression,
        R.string.ui_degree_2_1_2_3_2_5_10
    ),
    Regression(
        R.string.ui_statistics,
        R.string.ui_other_regression,
        R.string.ui_exponential_1_2_3_2_4_8
    )
}

// All advanced calculations stay local on the phone. Semicolons separate expression arguments and pipes separate data sets.
