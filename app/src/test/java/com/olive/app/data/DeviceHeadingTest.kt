package com.olive.app.data

import android.view.Surface
import com.olive.app.data.location.adjustAzimuthForDisplayRotation
import com.olive.app.data.location.normalizeHeadingDegrees
import com.olive.app.data.location.selectPreferredHeading
import com.olive.app.data.location.shortestAngleDelta
import com.olive.app.data.location.smoothHeading
import org.junit.Assert.*
import org.junit.Test

class DeviceHeadingTest {
    @Test
    fun normalizesAnglesIntoZeroTo360Range() {
        assertEquals(0f, normalizeHeadingDegrees(0f), 0.001f)
        assertEquals(0f, normalizeHeadingDegrees(360f), 0.001f)
        assertEquals(350f, normalizeHeadingDegrees(-10f), 0.001f)
        assertEquals(90f, normalizeHeadingDegrees(450f), 0.001f)
        assertEquals(0f, normalizeHeadingDegrees(Float.NaN), 0.001f)
    }

    @Test
    fun shortestDeltaAndSmoothingCrossZeroWithoutJumping() {
        assertEquals(20f, shortestAngleDelta(350f, 10f), 0.001f)
        assertEquals(-20f, shortestAngleDelta(10f, 350f), 0.001f)
        assertEquals(180f, shortestAngleDelta(0f, 180f), 0.001f)

        val smoothedForward = smoothHeading(355f, 5f, alpha = 0.5f)
        assertEquals(0f, normalizeHeadingDegrees(smoothedForward), 0.001f)

        val smoothedBackward = smoothHeading(5f, 355f, alpha = 0.5f)
        assertEquals(0f, normalizeHeadingDegrees(smoothedBackward), 0.001f)
    }

    @Test
    fun prefersGpsBearingWhenMovingAndCompassWhenStationary() {
        assertEquals(125f, selectPreferredHeading(125f, 1.5f, 20f)!!, 0.001f)
        assertEquals(20f, selectPreferredHeading(125f, 0.3f, 20f)!!, 0.001f)
        assertEquals(20f, selectPreferredHeading(null, null, 20f)!!, 0.001f)
        assertEquals(90f, selectPreferredHeading(90f, null, null)!!, 0.001f)
        assertNull(selectPreferredHeading(null, null, null))
    }

    @Test
    fun adjustsCompassAzimuthForDisplayRotation() {
        assertEquals(45f, adjustAzimuthForDisplayRotation(45f, Surface.ROTATION_0), 0.001f)
        assertEquals(135f, adjustAzimuthForDisplayRotation(45f, Surface.ROTATION_90), 0.001f)
        assertEquals(225f, adjustAzimuthForDisplayRotation(45f, Surface.ROTATION_180), 0.001f)
        assertEquals(315f, adjustAzimuthForDisplayRotation(45f, Surface.ROTATION_270), 0.001f)
    }
}
