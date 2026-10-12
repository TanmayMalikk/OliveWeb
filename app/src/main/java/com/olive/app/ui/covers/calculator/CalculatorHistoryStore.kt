package com.olive.app.ui.covers.calculator

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

// Local-only calculator history. Each signed-in/cover account receives a separate saved list.
internal class CalculatorHistoryStore(context:Context,accountId:String) {
    private val preferences=context.getSharedPreferences(PREFERENCES_NAME,Context.MODE_PRIVATE)
    private val historyKey="history_${accountId.ifBlank { "guest" }}"

    fun load():List<CalculatorHistoryEntry> = runCatching {
        val stored=preferences.getString(historyKey,null) ?: return@runCatching emptyList()
        val array=JSONArray(stored)
        buildList {
            for(index in 0 until array.length()) {
                val item=array.optJSONObject(index) ?: continue
                val expression=item.optString(EXPRESSION).take(120)
                val result=item.optString(RESULT).take(120)
                if(expression.isNotBlank() && result.isNotBlank()) add(CalculatorHistoryEntry(expression,result))
            }
        }
    }.getOrDefault(emptyList())

    fun save(entries:List<CalculatorHistoryEntry>) {
        if(entries.isEmpty()) {
            preferences.edit { remove(historyKey) }
            return
        }
        val array=JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().put(EXPRESSION,entry.expression).put(RESULT,entry.result))
        }
        preferences.edit { putString(historyKey,array.toString()) }
    }

    private companion object {
        const val PREFERENCES_NAME="better_calculator_history"
        const val EXPRESSION="expression"
        const val RESULT="result"
    }
}
