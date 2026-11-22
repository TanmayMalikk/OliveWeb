// Safe-app users: computes circular compass/GPS heading normalization and smoothing.
package com.olive.app.data.location

import android.view.Surface
import kotlin.math.atan2

/**
 * Normalizes any heading angle in degrees into `[0f, 360f)`.
 */
internal fun normalizeHeadingDegrees(degrees: Float): Float {
    if (!degrees.isFinite()) return 0f
    val mod = degrees % 360f
    return if (mod < 0f) mod + 360f else if (mod >= 360f) mod - 360f else mod
}

/**
 * Returns the shortest signed angular difference from [fromDegrees] to [toDegrees] in `(-180f, 180f]`.
 */
internal fun shortestAngleDelta(fromDegrees: Float, toDegrees: Float): Float {
    val diff = (normalizeHeadingDegrees(toDegrees) - normalizeHeadingDegrees(fromDegrees)) % 360f
    return when {
        diff > 180f -> diff - 360f
        diff <= -180f -> diff + 360f
        else -> diff
    }
}

/**
 * Applies circular exponential smoothing across the 0°/360° boundary.
 */
internal fun smoothHeading(current: Float?, target: Float, alpha: Float = 0.22f): Float {
    val normalizedTarget = normalizeHeadingDegrees(target)
    if (current == null || !current.isFinite()) return normalizedTarget
    val clampedAlpha = alpha.coerceIn(0f, 1f)
    val delta = shortestAngleDelta(current, normalizedTarget)
    return normalizeHeadingDegrees(current + clampedAlpha * delta)
}

/**
 * Prefers GPS movement bearing once moving at walking speed (>= 0.8 m/s), falling back to compass azimuth.
 */
internal fun selectPreferredHeading(
    gpsBearing: Float?,
    gpsSpeedMetersPerSecond: Float?,
    compassAzimuth: Float?,
): Float? {
    val movingWithBearing = gpsBearing != null && gpsBearing.isFinite() &&
        gpsSpeedMetersPerSecond != null && gpsSpeedMetersPerSecond.isFinite() &&
        gpsSpeedMetersPerSecond >= 0.8f
    return when {
        movingWithBearing -> normalizeHeadingDegrees(gpsBearing)
        compassAzimuth != null && compassAzimuth.isFinite() -> normalizeHeadingDegrees(compassAzimuth)
        gpsBearing != null && gpsBearing.isFinite() -> normalizeHeadingDegrees(gpsBearing)
        else -> null
    }
}

/**
 * Adjusts magnetic azimuth for the current Android display rotation.
 */
internal fun adjustAzimuthForDisplayRotation(azimuthDegrees: Float, surfaceRotation: Int): Float {
    val offset = when (surfaceRotation) {
        Surface.ROTATION_90 -> 90f
        Surface.ROTATION_180 -> 180f
        Surface.ROTATION_270 -> 270f
        else -> 0f
    }
    return normalizeHeadingDegrees(azimuthDegrees + offset)
}

/**
 * Computes a tilt-invariant compass azimuth from a screen-remapped 3x3 rotation matrix
 * (east = col/row world X, north = world Y). Smoothly blends device +Y (when held flat)
 * and back-of-phone -Z (when held upright while walking) to eliminate gimbal lock at 45°–90° tilt.
 */
internal fun computeTiltInvariantAzimuthDegrees(rotationMatrix: FloatArray): Float? {
    if (rotationMatrix.size < 9) return null
    val flatWeight = rotationMatrix[8].coerceAtLeast(0f)
    val uprightWeight = rotationMatrix[7]
    var east = rotationMatrix[1] * flatWeight - rotationMatrix[2] * uprightWeight
    var north = rotationMatrix[4] * flatWeight - rotationMatrix[5] * uprightWeight
    if (east * east + north * north < 1e-4f) {
        east = rotationMatrix[1]
        north = rotationMatrix[4]
    }
    if (!east.isFinite() || !north.isFinite() || (east == 0f && north == 0f)) return null
    val degrees = Math.toDegrees(atan2(east.toDouble(), north.toDouble())).toFloat()
    return normalizeHeadingDegrees(degrees)
}

/**
 * Converts magnetic compass heading to true north heading using magnetic declination.
 */
internal fun applyMagneticDeclination(magneticHeadingDegrees: Float, declinationDegrees: Float): Float {
    val normalized = normalizeHeadingDegrees(magneticHeadingDegrees)
    if (!declinationDegrees.isFinite()) return normalized
    return normalizeHeadingDegrees(normalized + declinationDegrees)
}
