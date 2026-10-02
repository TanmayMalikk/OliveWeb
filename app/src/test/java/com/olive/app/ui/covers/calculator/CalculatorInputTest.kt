package com.olive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalculatorInputTest {
    @Test fun enteredPinRequiresTwoConsecutiveEqualsTaps() {
        val input=CalculatorInput()
        "123456".forEach { assertNull(input.press(it.toString())) }
        assertNull(input.press("="))
        assertEquals("123456",input.press("="))
        assertEquals("0",input.display)
        assertNull(input.press("="))
        "0012".forEach { input.press(it.toString()) }
        assertEquals("0012",input.display)
        assertNull(input.press("="))
        assertEquals("0012",input.press("="))
    }

    @Test fun arithmeticAndInterveningKeysDoNotBecomePinTrigger() {
        val input=CalculatorInput()
        listOf("1",".","2","+","3",".","4","=").forEach { assertNull(input.press(it)) }
        assertEquals("4.6",input.display)
        assertNull(input.press("="))
        input.reset()
        "1234".forEach { input.press(it.toString()) }
        input.press("=")
        input.press("5")
        assertNull(input.press("="))
        input.reset()
        listOf("1","0","0","0","+","2","3","4","=","=").forEach { assertNull(input.press(it)) }
        assertEquals("1234",input.display)
        input.reset()
        "999999999999999999".forEach { input.press(it.toString()) }
        listOf("+","1","=").forEach { input.press(it) }
        assertEquals("1E+18",input.display)
    }

    @Test fun trigonometryUsesTheSelectedAngleModeAndDisarmsPinEntry() {
        val input=CalculatorInput()
        "90".forEach { input.press(it.toString()) }
        input.applyTrig("sin",CalculatorAngleMode.DEGREES)
        assertEquals("1",input.display)
        assertEquals("sin(90)",input.expression)
        assertNull(input.press("="))

        input.reset()
        "3.141592653589793".forEach { input.press(it.toString()) }
        input.applyTrig("cos",CalculatorAngleMode.RADIANS)
        assertEquals("-1",input.display)
    }
}
