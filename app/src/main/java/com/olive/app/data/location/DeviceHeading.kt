// Safe-app users: computes circular compass/GPS heading normalization and smoothing.
package com.olive.app.data.location

import android.view.Surface

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
