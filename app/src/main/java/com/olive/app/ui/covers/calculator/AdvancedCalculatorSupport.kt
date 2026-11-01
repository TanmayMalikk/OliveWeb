// Calculator cover users: formats advanced calculator results and errors.
package com.olive.app.ui.covers.calculator

import com.olive.app.ui.shared.*

import com.olive.app.ui.shared.ResourceFailure
import com.olive.app.R

import kotlin.math.*


internal fun semi(raw: String, size: Int) = raw.split(';')
    .also { requireText(it.size == size) { ResourceFailure(R.string.ui_use_semicolons_exactly_like_the_example) } }
    .map { it.trim() }

internal fun list(raw: String) =
    raw.trim().split(Regex("[,\\s]+")).filter { it.isNotBlank() }.map(::parseCalculatorNumber)
        .also { values -> require(values.isNotEmpty() && values.all(Double::isFinite)) }

internal fun value(raw: String, mode: CalculatorAngleMode) =
    GraphExpression.compile(raw, mode).evaluate(0.0)

internal fun number(value: Double): String = formatCalculatorNumber(value)
internal fun approximateFraction(value: Double): Pair<Long, Long> {
    require(value.isFinite())
    var bestN = 0L
    var bestD = 1L
    var bestError = Double.MAX_VALUE
    for (d in 1L..10000L) {
        val n = round(value * d).toLong()
        val error = abs(value - n.toDouble() / d)
        if (error < bestError) {
            bestN = n
            bestD = d
            bestError = error
        }
        if (error < 1e-10) break
    }
    val gcd = gcd(abs(bestN), bestD)
    return bestN / gcd to bestD / gcd
}

internal tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) max(1, a) else gcd(b, a % b)
