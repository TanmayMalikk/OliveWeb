// Contains the history code for this part of Olive.
package com.olive.app.ui.covers.calculator

import com.olive.app.ui.shared.*

import android.content.Context
import androidx.core.content.edit
import com.olive.app.R
import com.olive.app.ui.shared.ResourceFailure
import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

// Local-only calculator history. Each signed-in/cover account receives a separate saved list.
internal class CalculatorHistoryStore(context: Context, accountId: String) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val historyKey = "history_${accountId.ifBlank { "guest" }}"

    fun load(): List<CalculatorHistoryEntry> = runCatching {
        val stored = preferences.getString(historyKey, null) ?: return@runCatching emptyList()
        val array = JSONArray(stored)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val expression = item.optString(EXPRESSION).take(120)
                val result = item.optString(RESULT).take(120)
                if (expression.isNotBlank() && result.isNotBlank()) add(
                    CalculatorHistoryEntry(
                        expression,
                        result
                    )
                )
                if (size == CALCULATOR_HISTORY_LIMIT) break
            }
        }
    }.getOrDefault(emptyList())

    fun save(entries: List<CalculatorHistoryEntry>) {
        if (entries.isEmpty()) {
            preferences.edit { remove(historyKey) }
            return
        }
        val array = JSONArray()
        entries.take(CALCULATOR_HISTORY_LIMIT).forEach { entry ->
            array.put(JSONObject().put(EXPRESSION, entry.expression).put(RESULT, entry.result))
        }
        preferences.edit { putString(historyKey, array.toString()) }
    }

    private companion object {
        const val PREFERENCES_NAME = "better_calculator_history"
        const val EXPRESSION = "expression"
        const val RESULT = "result"
    }
}

private val TIMES_TEN_PATTERN =
    Regex("(?i)([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*[x×*]\\s*10\\s*\\^\\s*([+-]?\\d+)")
private val IMPLICIT_ONE_EXPONENT_PATTERN = Regex("(?i)(^|[\\s(,;|+\\-*/=])e\\s*([+-]?\\d+)")

// All calculator entry points share these spellings: 1E5, 1e5, 1x10^5, 1×10^5, and shorthand E5/e5.
internal fun normalizeCalculatorNotation(raw: String): String {
    var value = raw.replace('−', '-')
    value =
        TIMES_TEN_PATTERN.replace(value) { match -> "${match.groupValues[1]}e${match.groupValues[2]}" }
    value =
        IMPLICIT_ONE_EXPONENT_PATTERN.replace(value) { match -> "${match.groupValues[1]}1e${match.groupValues[2]}" }
    return value
}

// Reads calculator number from saved text.
internal fun parseCalculatorNumber(raw: String): Double =
    normalizeCalculatorNotation(raw).trim().toDouble()

// Large and tiny answers avoid long runs of zeroes while ordinary values stay easy to read.
internal fun formatCalculatorNumber(value: Double, significantDigits: Int = 10): String {
    requireText(value.isFinite()) { ResourceFailure(R.string.ui_the_result_is_not_a_finite_number) }
    if (value == 0.0) return "0"
    val magnitude = abs(value)
    if (magnitude !in 1e-6..<1e9) {
        return formatScientificCalculatorNumber(value, significantDigits)
    }
    return BigDecimal.valueOf(value).round(MathContext(significantDigits.coerceIn(2, 15)))
        .stripTrailingZeros().toPlainString()
}

// Formats scientific calculator number.
internal fun formatScientificCalculatorNumber(value: Double, significantDigits: Int = 10): String {
    requireText(value.isFinite()) { ResourceFailure(R.string.ui_the_result_is_not_a_finite_number) }
    if (value == 0.0) return "0E0"
    val parts =
        String.format(Locale.US, "%.${significantDigits.coerceIn(2, 15) - 1}E", value).split('E')
    val mantissa = parts[0].trimEnd('0').trimEnd('.')
    return "${mantissa}E${parts[1].toInt()}"
}
