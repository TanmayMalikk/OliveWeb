package com.olive.app.ui.covers.calculator

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
data class CalculatorHistoryEntry(val expression:String,val result:String)

// Build the visible equation here and wait for equals before calculating it locally.
class CalculatorInput(
    initialHistory:List<CalculatorHistoryEntry> = emptyList(),
    private val onHistoryChanged:(List<CalculatorHistoryEntry>)->Unit = {},
) {
    var display by mutableStateOf("0"); private set
    var expression by mutableStateOf(""); private set
    var history by mutableStateOf(initialHistory); private set
    private var evaluated=false
    private var armedPin:String?=null
    private var angleMode=CalculatorAngleMode.RADIANS

    fun reset() {
        display="0";expression="";evaluated=false;armedPin=null
    }

    fun press(key:String):String? {
        if(key!="=") armedPin=null
        when(key) {
            "AC" -> reset()
            "⌫" -> if(!evaluated) updateEntry(expression.dropLast(1))
            "±" -> {
                beginFreshIfNeeded()
                val changed=when {
                    expression.isBlank() -> "-"
                    expression.startsWith("-(") && expression.endsWith(')') -> expression.substring(2,expression.length-1)
                    else -> "-($expression)"
                }
                updateEntry(changed)
            }
            "%" -> if(canEndValue() && expression.lastOrNull()!='%') updateEntry("$expression%")
            "+","−","×","÷","^" -> {
                if(evaluated) { expression=display;evaluated=false }
                val unaryMinus=key=="−" && (expression.isBlank() || expression.lastOrNull()=='(' || expression.lastOrNull() in operatorCharacters)
                val next=when {
                    unaryMinus -> expression+key
                    expression.lastOrNull() in operatorCharacters -> expression.dropLast(1)+key
                    else -> expression+key
                }
                if(expression.isNotBlank() || unaryMinus) updateEntry(next)
            }
            "!" -> if(canEndValue() && expression.lastOrNull()!='!') updateEntry("$expression!")
            "x²" -> if(canEndValue()) updateEntry("$expression^2")
            "π","e" -> { beginFreshIfNeeded();updateEntry((expression+key).take(MAX_EXPRESSION_LENGTH)) }
            "eˣ" -> { beginFreshIfNeeded();updateEntry((expression+"e^(").take(MAX_EXPRESSION_LENGTH)) }
            "10ˣ" -> { beginFreshIfNeeded();updateEntry((expression+"10^(").take(MAX_EXPRESSION_LENGTH)) }
            "(" -> {
                beginFreshIfNeeded()
                updateEntry("$expression(")
            }
            ")" -> if(canEndValue() && expression.count { it=='(' }>expression.count { it==')' }) updateEntry("$expression)")
            "()" -> {
                beginFreshIfNeeded()
                val close=canEndValue() && expression.count { it=='(' }>expression.count { it==')' }
                updateEntry(expression+if(close) ")" else "(")
            }
            "=" -> return evaluateOrUnlock()
            "." -> {
                beginFreshIfNeeded()
                if(!currentNumber().contains('.')) updateEntry(expression+if(needsLeadingZero()) "0." else ".")
            }
            else -> if(key.length==1 && key[0].isDigit()) {
                beginFreshIfNeeded()
                updateEntry((expression+key).take(MAX_EXPRESSION_LENGTH))
            }
        }
        return null
    }

    fun beginTrig(function:String,inverse:Boolean,angleMode:CalculatorAngleMode) {
        val label=if(inverse) when(function) { "sin"->"sin⁻¹";"cos"->"cos⁻¹";else->"tan⁻¹" } else function
        beginFunction(label,angleMode)
    }

    fun beginFunction(label:String,angleMode:CalculatorAngleMode) {
        beginFreshIfNeeded()
        this.angleMode=angleMode
        updateEntry(("$expression$label(").take(MAX_EXPRESSION_LENGTH))
        armedPin=null
    }

    private fun evaluateOrUnlock():String? {
        armedPin?.let { pin -> reset();return pin }
        if(evaluated) return null
        val missingClosers=(expression.count { it=='(' }-expression.count { it==')' }).coerceAtLeast(0)
        val source=expression+")".repeat(missingClosers)
        if(source.isBlank()) return null
        if(source.matches(Regex("[0-9]{4,6}"))) armedPin=source
        val normalized=source.replace('−','-')
            .replace("√","sqrt").replace("π","pi")
            .replace("sin⁻¹","asin").replace("cos⁻¹","acos").replace("tan⁻¹","atan")
        display=runCatching { formatCalculatorNumber(GraphExpression.compile(normalized,angleMode).evaluate(0.0)) }.getOrDefault("Error")
        if(display!="Error" && armedPin==null)
            updateHistory(listOf(CalculatorHistoryEntry(source,display))+history)
        expression="$source ="
        evaluated=true
        return null
    }

    private fun beginFreshIfNeeded() {
        if(evaluated || display=="Error") { expression="";display="0";evaluated=false }
    }

    private fun updateEntry(value:String) {
        expression=value.take(MAX_EXPRESSION_LENGTH)
        display=expression.ifBlank { "0" }
        evaluated=false
    }

    fun reuse(entry:CalculatorHistoryEntry) {
        expression=entry.expression.take(MAX_EXPRESSION_LENGTH)
        display=expression.ifBlank { "0" }
        evaluated=false
        armedPin=null
    }

    fun clearHistory()=updateHistory(emptyList())

    // Disk history arrives after the keypad is already interactive.
    fun restoreHistory(value:List<CalculatorHistoryEntry>) {
        history=if(history.isEmpty()) value else (history+value).distinct()
    }

    private fun updateHistory(value:List<CalculatorHistoryEntry>) {
        history=value
        onHistoryChanged(value)
    }

    private fun canEndValue()=expression.lastOrNull()?.let { it.isDigit() || it==')' || it=='%' }==true
    private fun needsLeadingZero()=expression.isBlank() || expression.lastOrNull() in operatorCharacters || expression.lastOrNull()=='('
    private fun currentNumber()=expression.takeLastWhile { it.isDigit() || it=='.' }

    private companion object {
        const val MAX_EXPRESSION_LENGTH=120
        val operatorCharacters=setOf('+','−','×','÷','^')
    }
}
