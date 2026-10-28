// Calculator cover users: calculates complex-number operations.
package com.olive.app.ui.covers.calculator

import com.olive.app.ui.shared.*

import com.olive.app.ui.shared.ResourceFailure
import com.olive.app.R

import kotlin.math.*


internal fun fraction(raw: String): String {
    val value = if ('/' in raw) raw.split('/').let {
        require(it.size == 2)
        it[0].trim().toDouble() / it[1].trim().toDouble()
    } else raw.trim().toDouble()
    val (n, d) = approximateFraction(value)
    return "$n/$d = ${number(value)}"
}

internal fun mixed(raw: String): String {
    val text = raw.trim()
    if (' ' in text) {
        val parts = text.split(Regex("\\s+"))
        require(parts.size == 2)
        val whole = parts[0].toLong()
        val fraction = parts[1].split('/')
        require(fraction.size == 2)
        val denominator = fraction[1].toLong()
        val numerator = abs(whole) * denominator + fraction[0].toLong()
        return "${if (whole < 0) -numerator else numerator}/$denominator"
    }
    val pieces = text.split('/')
    require(pieces.size == 2)
    val numerator = pieces[0].toLong()
    val denominator = pieces[1].toLong().also { require(it != 0L) }
    return "${numerator / denominator} ${abs(numerator % denominator)}/$denominator"
}

internal fun scientific(raw: String): String {
    val value = value(raw, CalculatorAngleMode.RADIANS)
    return formatScientificCalculatorNumber(value) + " = " + number(value)
}

internal fun derivative(raw: String, mode: CalculatorAngleMode): String {
    val p = semi(raw, 2)
    val f = GraphExpression.compile(p[0], mode)
    val x = value(p[1], mode)
    val h = max(1e-6, abs(x) * 1e-6)
    return "f′(${number(x)}) = ${number((f.evaluate(x + h) - f.evaluate(x - h)) / (2 * h))}"
}

internal fun integral(raw: String, mode: CalculatorAngleMode): String {
    val p = semi(raw, 3)
    val f = GraphExpression.compile(p[0], mode)
    val a = value(p[1], mode)
    val b = value(p[2], mode)
    val steps = 4000
    val h = (b - a) / steps
    var total = f.evaluate(a) + f.evaluate(b)
    for (i in 1 until steps) total += (if (i % 2 == 0) 2 else 4) * f.evaluate(a + i * h)
    return "∫ = ${number(total * h / 3)}"
}

internal fun optimize(raw: String, mode: CalculatorAngleMode, kind: String): String {
    val p = semi(raw, 3)
    val f = GraphExpression.compile(p[0], mode)
    var left = value(p[1], mode)
    var right = value(p[2], mode)
    require(right > left)
    if (kind == "zero") {
        var fl = f.evaluate(left)
        var bracket = false
        for (i in 1..1000) {
            val x = left + (right - left) * i / 1000
            val fx = f.evaluate(x)
            if (fl * fx <= 0) {
                right = x
                left = x - (right - left) / i
                bracket = true
                break
            }
            fl = fx
        }
        requireText(bracket) { ResourceFailure(R.string.ui_no_zero_was_found_in_that_interval) }
        repeat(80) {
            val mid = (left + right) / 2
            if (f.evaluate(left) * f.evaluate(mid) <= 0) right =
                mid else left = mid
        }
        val x = (left + right) / 2
        return "x = ${number(x)}, y = ${number(f.evaluate(x))}"
    }
    val maximize = kind == "max"
    repeat(100) {
        val m1 = left + (right - left) / 3
        val m2 = right - (right - left) / 3
        val chooseLeft =
            if (maximize) f.evaluate(m1) < f.evaluate(m2) else f.evaluate(m1) > f.evaluate(m2)
        if (chooseLeft) left =
            m1 else right = m2
    }
    val x = (left + right) / 2
    return "x = ${number(x)}, y = ${number(f.evaluate(x))}"
}

internal fun intersection(raw: String, mode: CalculatorAngleMode): String {
    val p = semi(raw, 4)
    return optimize("(${p[0]})-(${p[1]});${p[2]};${p[3]}", mode, "zero")
}

internal fun limit(
    raw: String,
    mode: CalculatorAngleMode,
    text: com.olive.app.ui.shared.ResourceText
): String {
    val p = semi(raw, 2)
    val f = GraphExpression.compile(p[0], mode)
    val x = value(p[1], mode)
    val rows = (1..6).map { 10.0.pow(-it) }.joinToString("\n") { h ->
        text(
            R.string.ui_h_value_left_value_right_value,
            number(h),
            number(f.evaluate(x - h)),
            number(f.evaluate(x + h))
        )
    }
    return rows
}
