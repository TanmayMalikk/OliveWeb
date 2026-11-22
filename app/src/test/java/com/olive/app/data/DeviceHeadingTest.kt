package com.olive.app.data

import android.view.Surface
import com.olive.app.data.location.adjustAzimuthForDisplayRotation
import com.olive.app.data.location.applyMagneticDeclination
import com.olive.app.data.location.computeTiltInvariantAzimuthDegrees
import com.olive.app.data.location.normalizeHeadingDegrees
import com.olive.app.data.location.selectPreferredHeading
import com.olive.app.data.location.shortestAngleDelta
import com.olive.app.data.location.smoothHeading
import kotlin.math.cos
import kotlin.math.sin
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

    /**
     * Constructs a world-from-device 3x3 rotation matrix for azimuth [yawDeg] and pitch-up [tiltDeg]
     * (0° = flat facing sky, 45° = comfortable walking angle, 90° = vertical portrait facing user).
     */
    private fun rotationMatrix(yawDeg: Double, tiltDeg: Double): FloatArray {
        val y = Math.toRadians(yawDeg)
        val t = Math.toRadians(tiltDeg)
        val cy = cos(y).toFloat()
        val sy = sin(y).toFloat()
        val ct = cos(t).toFloat()
        val st = sin(t).toFloat()
        // Device +X points right: (cy, -sy, 0) in (East, North, Up).
        // Device +Y points up-screen: (sy*ct, cy*ct, st).
        // Device +Z points out of screen toward user: (-sy*st, -cy*st, ct).
        return floatArrayOf(
            cy, sy * ct, -sy * st,
            -sy, cy * ct, -cy * st,
            0f, st, ct,
        )
    }

    @Test
    fun tiltInvariantAzimuthRemainsStableAtFlat45And90DegreesWithoutGimbalFlip() {
        for (tilt in listOf(0.0, 45.0, 88.0, 90.0, 100.0)) {
            for (expectedYaw in listOf(0.0, 45.0, 90.0, 135.0, 180.0, 270.0, 355.0)) {
                val matrix = rotationMatrix(expectedYaw, tilt)
                val computed = computeTiltInvariantAzimuthDegrees(matrix)!!
                val error = shortestAngleDelta(expectedYaw.toFloat(), computed)
                assertTrue(
                    "Expected $expectedYaw° at tilt $tilt°, got $computed° (error=$error°)",
                    kotlin.math.abs(error) < 0.1f,
                )
            }
        }
    }

    @Test
    fun appliesMagneticDeclinationCorrectlyAcrossZeroBoundary() {
        assertEquals(15f, applyMagneticDeclination(0f, 15f), 0.001f)
        assertEquals(355f, applyMagneticDeclination(10f, -15f), 0.001f)
        assertEquals(5f, applyMagneticDeclination(350f, 15f), 0.001f)
        assertEquals(120f, applyMagneticDeclination(120f, Float.NaN), 0.001f)
    }
}
