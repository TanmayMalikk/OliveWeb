// Calculator cover users: calculates matrix operations.
package com.olive.app.ui.covers.calculator

import com.olive.app.R

import org.apache.commons.math3.complex.Complex
import org.apache.commons.math3.linear.Array2DRowRealMatrix
import kotlin.math.*


internal fun complexArithmetic(raw: String): String {
    val match =
        Regex("\\(?\\s*([^,()]+)\\s*,\\s*([^,()]+)\\s*\\)?\\s*([+\\-*/^])\\s*\\(?\\s*([^,()]+)\\s*,\\s*([^,()]+)\\s*\\)?").matchEntire(
            raw.trim()
        ) ?: error("Use (real,imaginary) operator (real,imaginary).")
    val a = Complex(match.groupValues[1].toDouble(), match.groupValues[2].toDouble())
    val b = Complex(match.groupValues[4].toDouble(), match.groupValues[5].toDouble())
    val result = when (match.groupValues[3]) {
        "+" -> a.add(b)
        "-" -> a.subtract(b)
        "*" -> a.multiply(b)
        "/" -> a.divide(b)
        else -> a.pow(
            b
        )
    }
    return complex(result)
}

internal fun complexDetails(raw: String, text: com.olive.app.ui.shared.ResourceText): String {
    val z = complexValue(raw)
    return text(
        R.string.ui_z_value_real_value_imaginary_value_conjugate_value_magnitude_valu,
        complex(z),
        number(z.real),
        number(z.imaginary),
        complex(z.conjugate()),
        number(z.abs()),
        number(z.argument),
        number(z.abs()),
        number(z.argument)
    )
}

internal fun complexPower(raw: String, text: com.olive.app.ui.shared.ResourceText): String {
    val p = raw.split(';')
    require(p.size == 2)
    val z = complexValue(p[0])
    val n = p[1].trim().toInt()
    require(n != 0)
    val powered = z.pow(n.toDouble())
    val roots = (0 until abs(n)).joinToString("\n") { k ->
        complex(
            Complex(
                z.abs().pow(1.0 / abs(n)),
                (z.argument + 2 * PI * k) / abs(n)
            )
        )
    }
    return text(R.string.ui_power_value_roots_value, complex(powered), roots)
}

private fun complexValue(raw: String) =
    raw.trim().removePrefix("(").removeSuffix(")").split(',').let {
        require(it.size == 2)
        Complex(
            it[0].trim().toDouble(),
            it[1].trim().toDouble()
        )
    }

internal fun complex(z: Complex) =
    "${number(z.real)} ${if (z.imaginary < 0) "−" else "+"} ${number(abs(z.imaginary))}i"

internal fun matrixAdd(raw: String): String {
    val p = raw.split('|')
    require(p.size == 3)
    val a = matrix(p[0])
    val b = matrix(p[1])
    return matrix(if (p[2].trim() == "-") a.subtract(b) else a.add(b))
}

internal fun matrixBinary(
    raw: String,
    op: (Array2DRowRealMatrix, Array2DRowRealMatrix) -> Array2DRowRealMatrix
): String {
    val p = raw.split('|')
    require(p.size == 2)
    return matrix(op(matrix(p[0]), matrix(p[1])))
}

internal fun matrixScalar(raw: String): String {
    val p = raw.split('|')
    require(p.size == 2)
    return matrix(
        matrix(p[0]).scalarMultiply(
            p[1].trim().toDouble()
        )
    )
}

internal fun matrixPower(raw: String): String {
    val p = raw.split('|')
    require(p.size == 2)
    val exponent =
        p[1].trim().toInt()
    require(exponent >= 0)
    return matrix(matrix(p[0]).power(exponent))
}

internal fun matrixRowOperation(raw: String): String {
    val p = raw.split('|')
    require(p.size == 2)
    val source = matrix(p[0])
    val data = source.data.map { it.copyOf() }.toTypedArray()
    val command = p[1].trim().split(Regex("\\s+"))
    when (command.firstOrNull()?.lowercase()) {
        "swap" -> {
            require(command.size == 3)
            val a = command[1].toInt() - 1
            val b = command[2].toInt() - 1
            val temp = data[a]
            data[a] = data[b]
            data[b] = temp
        }

        "scale" -> {
            require(command.size == 3)
            val row = command[1].toInt() - 1
            val factor =
                command[2].toDouble()
            data[row].indices.forEach { data[row][it] *= factor }
        }

        "add" -> {
            require(command.size == 4)
            val sourceRow = command[1].toInt() - 1
            val targetRow = command[2].toInt() - 1
            val factor =
                command[3].toDouble()
            data[targetRow].indices.forEach { data[targetRow][it] += factor * data[sourceRow][it] }
        }

        else -> error("Use swap row row, scale row factor, or add source target factor.")
    }
    return matrix(Array2DRowRealMatrix(data))
}

internal fun matrix(raw: String): Array2DRowRealMatrix {
    val rows = raw.trim().split(';')
        .map { row -> row.split(',').map { it.trim().toDouble() }.toDoubleArray() }
    require(
        rows.isNotEmpty() && rows.all { it.size == rows[0].size })
    return Array2DRowRealMatrix(
        rows.toTypedArray()
    )
}

internal fun matrix(value: org.apache.commons.math3.linear.RealMatrix) =
    value.data.joinToString("\n") { row ->
        row.joinToString(
            prefix = "[ ",
            postfix = " ]"
        ) { number(it) }
    }

internal fun reducedRowEchelonForm(source: Array2DRowRealMatrix): Array2DRowRealMatrix {
    val a = source.data.map { it.copyOf() }.toTypedArray()
    var lead = 0
    for (r in a.indices) {
        if (lead >= a[0].size) break
        var i = r
        while (abs(a[i][lead]) < 1e-10) {
            i++
            if (i == a.size) {
                i = r
                lead++
                if (lead == a[0].size) return Array2DRowRealMatrix(a)
            }
        }
        val temp = a[i]
        a[i] = a[r]
        a[r] = temp
        val divisor =
            a[r][lead]
        for (j in a[r].indices) a[r][j] /= divisor
        for (k in a.indices) if (k != r) {
            val factor = a[k][lead]
            for (j in a[k].indices) a[k][j] -= factor * a[r][j]
        }
        lead++
    }
    return Array2DRowRealMatrix(a)
}
