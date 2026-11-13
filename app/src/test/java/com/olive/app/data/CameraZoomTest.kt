package com.olive.app.data

import com.olive.app.data.safety.nextCameraZoom
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraZoomTest {
    // A normal zoom-in step increases the current ratio by the requested amount.
    @Test
    fun zoomInUsesRequestedStep() {
        assertEquals(2.5f, nextCameraZoom(2f, 1f, 6f, 1.25f), 0.001f)
    }

    // Zoom controls stop at the limits reported by the phone camera.
    @Test
    fun zoomStepsStayInsideCameraLimits() {
        assertEquals(6f, nextCameraZoom(5.5f, 1f, 6f, 1.25f), 0.001f)
        assertEquals(1f, nextCameraZoom(1f, 1f, 6f, 0.8f), 0.001f)
    }

    // Invalid sensor values fall back to a safe normal zoom instead of crashing the camera screen.
    @Test
    fun invalidZoomValuesUseSafeFallback() {
        assertEquals(1f, nextCameraZoom(Float.NaN, 1f, 6f, 1.25f), 0.001f)
    }
}
