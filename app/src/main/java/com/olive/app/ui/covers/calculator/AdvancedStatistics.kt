// Contains the advanced statistics code for this part of Olive.
package com.olive.app.ui.covers.calculator

import com.olive.app.ui.shared.*

import com.olive.app.ui.shared.ResourceFailure
import com.olive.app.R

import org.apache.commons.math3.complex.Complex
import org.apache.commons.math3.linear.Array2DRowRealMatrix
import org.apache.commons.math3.linear.LUDecomposition
import org.apache.commons.math3.linear.MatrixUtils
import org.apache.commons.math3.stat.StatUtils
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics
import org.apache.commons.math3.stat.regression.SimpleRegression
import org.apache.commons.math3.fitting.SimpleCurveFitter
import org.apache.commons.math3.fitting.WeightedObservedPoint
import kotlin.math.*


// Runs when e variable happens.
internal fun oneVariable(raw: String, text: com.olive.app.ui.shared.ResourceText): String {
    val v = list(raw)
    val d = DescriptiveStatistics(v.toDoubleArray())
    val modes = StatUtils.mode(v.toDoubleArray()).joinToString { number(it) }
    return text(
        R.string.ui_n_value_sum_value_sum_of_squares_value_mean_value_median_value_mo,
        v.size,
        number(v.sum()),
        number(v.sumOf { it * it }),
        number(d.mean),
        number(d.getPercentile(50.0)),
        modes.ifBlank { text(R.string.ui_none) },
        number(d.min),
        number(d.getPercentile(25.0)),
        number(d.getPercentile(75.0)),
        number(d.max),
        number(d.standardDeviation),
        number(d.variance)
    )
}

internal fun twoVariable(raw: String, text: com.olive.app.ui.shared.ResourceText): String {
    val p = raw.split('|')
    require(p.size == 2)
    val x = list(p[0])
    val y = list(p[1])
    require(x.size == y.size)
    val regression = SimpleRegression(true)
    x.indices.forEach {
        regression.addData(
            x[it],
            y[it]
        )
    }
    return text(
        R.string.ui_correlation_value_y_value_valuex_r_value,
        number(PearsonsCorrelation().correlation(x.toDoubleArray(), y.toDoubleArray())),
        number(regression.intercept),
        number(regression.slope),
        number(regression.rSquare)
    )
}

internal fun polynomialRegression(raw: String): String {
    val p = raw.split('|')
    require(p.size == 3)
    val degree = p[0].filter { it.isDigit() }.toInt()
    require(degree in 1..4)
    val x = list(p[1])
    val y = list(p[2])
    require(x.size == y.size && x.size > degree)
    val design = Array(x.size) { row -> DoubleArray(degree + 1) { col -> x[row].pow(col) } }
    val coefficients =
        org.apache.commons.math3.linear.QRDecomposition(Array2DRowRealMatrix(design)).solver.solve(
            org.apache.commons.math3.linear.ArrayRealVector(y.toDoubleArray())
        ).toArray()
    return coefficients.indices.joinToString(" + ") { index ->
        "${
            number(
                coefficients[index]
            )
        }${if (index == 0) "" else "x^$index"}"
    }
}

internal fun regression(raw: String, text: com.olive.app.ui.shared.ResourceText): String {
    val p = raw.split('|')
    require(p.size == 3)
    val kind = p[0].trim().lowercase()
    val x = list(p[1])
    val y = list(p[2])
    require(x.size == y.size)
    return when (kind) {
        "linear" -> twoVariable("${p[1]}|${p[2]}", text)
        "median-median" -> medianMedian(
            x,
            y
        )

        "quadratic" -> polynomialRegression("degree 2|${p[1]}|${p[2]}")
        "cubic" -> polynomialRegression(
            "degree 3|${p[1]}|${p[2]}"
        )

        "quartic" -> polynomialRegression("degree 4|${p[1]}|${p[2]}")
        "exponential" -> transformedRegression(
            x,
            y.map { ln(it) },
            "y = %s e^(%s x)",
            true
        )

        "logarithmic" -> transformedRegression(
            x.map { ln(it) },
            y,
            "y = %s + %s ln(x)",
            false
        )

        "power" -> transformedRegression(
            x.map { ln(it) },
            y.map { ln(it) },
            "y = %s x^(%s)",
            true
        )

        "logistic" -> nonlinearRegression(
            x,
            y,
            doubleArrayOf(y.max(), 1.0, 1.0, x.average())
        ) { t, a -> a[0] / (1 + a[1] * exp(-a[2] * (t - a[3]))) }

        "sinusoidal" -> nonlinearRegression(
            x,
            y,
            doubleArrayOf((y.max() - y.min()) / 2, 1.0, 0.0, y.average())
        ) { t, a -> a[0] * sin(a[1] * t + a[2]) + a[3] }

        else -> error("Use linear, quadratic, cubic, quartic, exponential, logarithmic, power, logistic, sinusoidal, or median-median.")
    }
}

private fun transformedRegression(
    x: List<Double>,
    y: List<Double>,
    format: String,
    exponentiateIntercept: Boolean
): String {
    val r = SimpleRegression(true)
    x.indices.forEach { r.addData(x[it], y[it]) }
    val intercept =
        if (exponentiateIntercept) exp(r.intercept) else r.intercept
    return format.format(
        number(intercept),
        number(r.slope)
    ) + "\nR² = ${number(r.rSquare)}"
}

private fun nonlinearRegression(
    x: List<Double>,
    y: List<Double>,
    guess: DoubleArray,
    function: (Double, DoubleArray) -> Double
): String {
    val model = object : org.apache.commons.math3.analysis.ParametricUnivariateFunction {
        override fun value(t: Double, parameters: DoubleArray) = function(t, parameters)
        override fun gradient(t: Double, parameters: DoubleArray): DoubleArray {
            val h = 1e-6
            return parameters.indices.map { index ->
                val upper = parameters.copyOf()
                val lower =
                    parameters.copyOf()
                upper[index] += h
                lower[index] -= h
                (function(
                    t,
                    upper
                ) - function(t, lower)) / (2 * h)
            }.toDoubleArray()
        }
    }
    val points = x.indices.map { WeightedObservedPoint(1.0, x[it], y[it]) }
    val fitted = SimpleCurveFitter.create(model, guess).withMaxIterations(2000).fit(points)
    return fitted.mapIndexed { index, value -> "p${index + 1} = ${number(value)}" }
        .joinToString("\n")
}

private fun medianMedian(x: List<Double>, y: List<Double>): String {
    val pairs = x.zip(y).sortedBy { it.first }
    val groups = pairs.chunked(ceil(pairs.size / 3.0).toInt())
    require(groups.size >= 2)
    val medians = groups.map { group ->
        group.map { it.first }.sorted().let { it[it.size / 2] } to group.map { it.second }
            .sorted().let { it[it.size / 2] }
    }
    val slope =
        (medians.last().second - medians.first().second) / (medians.last().first - medians.first().first)
    val intercept = medians.map { it.second - slope * it.first }.average()
    return "y = ${number(intercept)} + ${number(slope)}x"
}
