package com.olive.app.ui

import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlin.math.abs

private val TIMES_TEN_PATTERN=Regex("(?i)([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*(?:x|×|\\*)\\s*10\\s*\\^\\s*([+-]?\\d+)")
private val IMPLICIT_ONE_EXPONENT_PATTERN=Regex("(?i)(^|[\\s(,;|+\\-*/=])e\\s*([+-]?\\d+)")

// All calculator entry points share these spellings: 1E5, 1e5, 1x10^5, 1×10^5, and shorthand E5/e5.
internal fun normalizeCalculatorNotation(raw:String):String {
    var value=raw.replace('−','-')
    value=TIMES_TEN_PATTERN.replace(value) { match -> "${match.groupValues[1]}e${match.groupValues[2]}" }
    value=IMPLICIT_ONE_EXPONENT_PATTERN.replace(value) { match -> "${match.groupValues[1]}1e${match.groupValues[2]}" }
    return value
}

internal fun parseCalculatorNumber(raw:String):Double = normalizeCalculatorNotation(raw).trim().toDouble()

// Large and tiny answers avoid long runs of zeroes while ordinary values stay easy to read.
internal fun formatCalculatorNumber(value:Double,significantDigits:Int=10):String {
    require(value.isFinite()) { "The result is not a finite number." }
    if(value==0.0) return "0"
    val magnitude=abs(value)
    if(magnitude>=1e9 || magnitude<1e-6) {
        return formatScientificCalculatorNumber(value,significantDigits)
    }
    return BigDecimal.valueOf(value).round(MathContext(significantDigits.coerceIn(2,15)))
        .stripTrailingZeros().toPlainString()
}

internal fun formatScientificCalculatorNumber(value:Double,significantDigits:Int=10):String {
    require(value.isFinite()) { "The result is not a finite number." }
    if(value==0.0) return "0E0"
    val parts=String.format(Locale.US,"%.${significantDigits.coerceIn(2,15)-1}E",value).split('E')
    val mantissa=parts[0].trimEnd('0').trimEnd('.')
    return "$mantissa"+"E"+parts[1].toInt()
}
