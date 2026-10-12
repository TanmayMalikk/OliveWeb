package com.olive.app.ui.covers.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals("1E18",input.display)
    }

    @Test fun trigonometryUsesTheSelectedAngleModeAndDisarmsPinEntry() {
        val input=CalculatorInput()
        input.beginTrig("sin",false,CalculatorAngleMode.DEGREES)
        "90".forEach { input.press(it.toString()) }
        assertEquals("sin(90",input.display)
        input.press("=")
        assertEquals("1",input.display)
        assertEquals("sin(90) =",input.expression)
        assertNull(input.press("="))

        input.reset()
        input.beginTrig("cos",false,CalculatorAngleMode.RADIANS)
        "3.141592653589793".forEach { input.press(it.toString()) }
        input.press("=")
        assertEquals("-1",input.display)

        input.reset()
        input.beginTrig("sin",true,CalculatorAngleMode.DEGREES)
        listOf("0",".","5","=").forEach { input.press(it) }
        assertEquals("30",input.display)
        assertEquals("sin⁻¹(0.5) =",input.expression)
    }

    @Test fun parenthesesAndPercentStayVisibleUntilEquals() {
        val input=CalculatorInput()
        listOf("(","2","+","3",")","×","4").forEach { input.press(it) }
        assertEquals("(2+3)×4",input.display)
        input.press("=")
        assertEquals("20",input.display)

        input.reset()
        listOf("5","%").forEach { input.press(it) }
        assertEquals("5%",input.display)
        assertEquals("5%",input.expression)
        input.press("=")
        assertEquals("0.05",input.display)
    }

    @Test fun smartParenthesesAndScientificKeysBuildVisibleExpressions() {
        val input=CalculatorInput()
        listOf("()","2","+","3","()","×","4","=").forEach { input.press(it) }
        assertEquals("20",input.display)

        input.reset()
        input.beginFunction("√",CalculatorAngleMode.RADIANS)
        listOf("8","1","=").forEach { input.press(it) }
        assertEquals("9",input.display)

        input.reset()
        listOf("5","!","=").forEach { input.press(it) }
        assertEquals("120",input.display)
    }

    @Test fun formatsLargeAndSmallAnswersAndReusesHistory() {
        val input=CalculatorInput()
        listOf("1","e","1","0","=").forEach { input.press(it) }
        assertEquals("1E10",input.display)
        assertEquals(CalculatorHistoryEntry("1e10","1E10"),input.history.first())

        input.reset()
        listOf("2","+","3","=").forEach { input.press(it) }
        val prior=input.history.first()
        input.press("AC")
        input.reuse(prior)
        assertEquals("2+3",input.expression)
        assertEquals("2+3",input.display)
    }

    @Test fun historyChangesArePersistedAndCanBeCleared() {
        var persisted=emptyList<CalculatorHistoryEntry>()
        val input=CalculatorInput(onHistoryChanged={ persisted=it })
        listOf("2","+","2","=").forEach { input.press(it) }
        assertEquals(input.history,persisted)
        assertEquals(CalculatorHistoryEntry("2+2","4"),persisted.first())

        input.clearHistory()
        assertTrue(input.history.isEmpty())
        assertTrue(persisted.isEmpty())
    }
}
