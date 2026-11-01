// Tests readable plot axes for changing and constant data.
package com.olive.app.ui.covers.calculator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatPlotAxisTest {
    // Inputs: the values shown below. Expected: increments scale with the provided data.
    @Test
    fun incrementsScaleWithTheProvidedData() {
        assertEquals(1.0, statAxis(1.0, 5.0).increment, 0.0)
        assertEquals(25.0, statAxis(0.0, 100.0).increment, 0.0)
        assertEquals(50.0, statAxis(0.0, 250.0).increment, 0.0)
    }

    // Inputs: the values shown below. Expected: constant data still gets avisible axis.
    @Test
    fun constantDataStillGetsAVisibleAxis() {
        val axis = statAxis(5.0, 5.0)
        assertTrue(axis.minimum < 5.0)
        assertTrue(axis.maximum > 5.0)
    }
}
