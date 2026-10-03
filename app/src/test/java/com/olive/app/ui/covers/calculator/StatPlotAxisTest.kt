package com.olive.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatPlotAxisTest {
    @Test fun incrementsScaleWithTheProvidedData() {
        assertEquals(1.0,statAxis(1.0,5.0).increment,0.0)
        assertEquals(25.0,statAxis(0.0,100.0).increment,0.0)
        assertEquals(50.0,statAxis(0.0,250.0).increment,0.0)
    }

    @Test fun constantDataStillGetsAVisibleAxis() {
        val axis=statAxis(5.0,5.0)
        assertTrue(axis.minimum<5.0)
        assertTrue(axis.maximum>5.0)
    }
}
