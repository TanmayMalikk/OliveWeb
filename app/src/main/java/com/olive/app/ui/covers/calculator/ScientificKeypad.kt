package com.olive.app.ui.covers.calculator

// Key layout and scientific dispatch are independent of responsive screen rendering.
internal class ScientificKeypad(private val input:CalculatorInput) {
    val baseRows=listOf(listOf("AC","()","%","÷"),listOf("7","8","9","×"),listOf("4","5","6","−"),listOf("1","2","3","+"),listOf(".","0","⌫","="))
    fun scientificRows(inverse:Boolean)=if(inverse) listOf(
            listOf("√","π","^","!"),listOf("INV","sin⁻¹","cos⁻¹","tan⁻¹"),listOf("x²","e","eˣ","10ˣ"),
        ) else listOf(
            listOf("√","π","^","!"),listOf("INV","sin","cos","tan"),listOf("x²","e","ln","log"),
        )

    fun press(key:String,angleMode:CalculatorAngleMode,onInverse:()->Unit,onPin:(String)->Unit) {
        when(key) {
            "INV" -> onInverse()
            "sin","cos","tan" -> input.beginTrig(key,false,angleMode)
            "sin⁻¹","cos⁻¹","tan⁻¹" -> input.beginTrig(key.substringBefore('⁻'),true,angleMode)
            "√","ln","log" -> input.beginFunction(key,angleMode)
            else -> input.press(key)?.let(onPin)
        }
    }
}
