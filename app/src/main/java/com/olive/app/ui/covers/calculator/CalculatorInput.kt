package com.olive.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

// Handle the normal maths here; two equals taps after 4–6 entered digits hand a PIN to the safe gate.
class CalculatorInput {
    var display by mutableStateOf("0"); private set
    var expression by mutableStateOf(""); private set
    private var first: String? = null
    private var operator: String? = null
    private var fresh = false
    private var pinEligible = false
    private var armedPin: String? = null
    private var typedDigits: String? = null

    fun reset() {
        display="0"; expression=""; first=null; operator=null
        fresh=false; pinEligible=false; armedPin=null; typedDigits=null
    }

    fun press(key: String): String? {
        if(key != "=") armedPin=null
        when(key) {
            "AC" -> reset()
            "⌫" -> {
                typedDigits=typedDigits?.dropLast(1)
                display=typedDigits?.ifEmpty { "0" } ?: display.dropLast(1).ifEmpty { "0" }
                pinEligible=!typedDigits.isNullOrEmpty() && operator==null
            }
            "±" -> {
                if(display!="0" && display!="Error") display=if(display.startsWith("-")) display.drop(1) else "-$display"
                pinEligible=false;typedDigits=null
            }
            "%" -> {
                display=runCatching { (BigDecimal(display).divide(BigDecimal(100))).format() }.getOrDefault("Error")
                pinEligible=false;typedDigits=null
            }
            "+","−","×","÷" -> {
                if(display=="Error") return null
                first=display; operator=key; expression="$display $key"; fresh=true; pinEligible=false;typedDigits=null
            }
            "=" -> {
                val prior=first; val op=operator
                if(prior!=null && op!=null) {
                    expression="$prior $op $display ="
                    display=runCatching {
                        val left=BigDecimal(prior); val right=BigDecimal(display)
                        when(op) {
                            "+" -> left.add(right)
                            "−" -> left.subtract(right)
                            "×" -> left.multiply(right)
                            else -> left.divide(right,8,RoundingMode.HALF_UP)
                        }.format()
                    }.getOrDefault("Error")
                    first=null; operator=null; fresh=true; pinEligible=false;typedDigits=null
                } else if(armedPin!=null) {
                    val pin=armedPin
                    reset()
                    return pin
                } else if(pinEligible && display.matches(Regex("[0-9]{4,6}"))) {
                    armedPin=display
                } else {
                    fresh=true
                }
            }
            "." -> {
                if(fresh || display=="Error") { display="0.";fresh=false }
                else if(!display.contains('.')) display+="."
                pinEligible=false;typedDigits=null
            }
            else -> if(key.length==1 && key[0] in '0'..'9') {
                when {
                    fresh || display=="Error" || (typedDigits==null && display=="0") -> { typedDigits=key;display=key }
                    typedDigits!=null -> { typedDigits=(typedDigits+key).take(18);display=typedDigits!! }
                    else -> display=(display+key).take(18)
                }
                if(fresh || expression.endsWith("=")) expression=""
                fresh=false; pinEligible=operator==null && typedDigits!=null
            }
        }
        return null
    }

    fun applyTrig(function: String, angleMode: CalculatorAngleMode) {
        if(display=="Error") return
        val original=display
        val input=original.toDoubleOrNull() ?: run { display="Error";return }
        val angle=if(angleMode==CalculatorAngleMode.DEGREES) Math.toRadians(input) else input
        val answer=when(function) {
            "sin" -> sin(angle)
            "cos" -> cos(angle)
            "tan" -> tan(angle)
            else -> return
        }
        display=if(answer.isFinite()) BigDecimal.valueOf(if(abs(answer)<1e-12) 0.0 else answer).format() else "Error"
        expression=if(operator==null) "$function($original)" else "$first $operator $function($original)"
        fresh=true;pinEligible=false;armedPin=null;typedDigits=null
    }

    private fun BigDecimal.format(): String {
        val number=stripTrailingZeros()
        val plain=number.toPlainString()
        return if(plain.length<=18) plain else number.round(MathContext(10)).stripTrailingZeros().toString()
    }
}
