// Safe-app users: tracks tilt-invariant true-north compass + GPS heading for map rotation & heading cone.
package com.olive.app.ui.users.user.location

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.olive.app.data.location.applyMagneticDeclination
import com.olive.app.data.location.computeTiltInvariantAzimuthDegrees
import com.olive.app.data.location.selectPreferredHeading
import com.olive.app.data.location.shortestAngleDelta
import com.olive.app.data.location.smoothHeading
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Observes tilt-invariant true-north compass orientation and GPS movement bearing while [enabled] is true,
 * returning a circularly smoothed heading in `[0f, 360f)`.
 */
@Composable
internal fun rememberDeviceHeading(
    enabled: Boolean,
    latitude: Double? = null,
    longitude: Double? = null,
    gpsBearing: Float? = null,
    gpsSpeedMetersPerSecond: Float? = null,
): Float? {
    val context = LocalContext.current
    var heading by remember { mutableStateOf<Float?>(null) }
    var compassAzimuth by remember { mutableStateOf<Float?>(null) }
    val latestGpsBearing by rememberUpdatedState(gpsBearing)
    val latestGpsSpeed by rememberUpdatedState(gpsSpeedMetersPerSecond)

    // Cache magnetic declination per ~55 km grid cell so GeomagneticField runs only when location shifts significantly.
    val declinationDegrees = remember(
        latitude?.let { (it * 2.0).roundToInt() },
        longitude?.let { (it * 2.0).roundToInt() },
    ) {
        if (latitude != null && longitude != null &&
            latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
        ) {
            runCatching {
                GeomagneticField(latitude.toFloat(), longitude.toFloat(), 0f, System.currentTimeMillis()).declination
            }.getOrDefault(0f)
        } else 0f
    }
    val latestDeclination by rememberUpdatedState(declinationDegrees)

    fun publishIfChanged(rawCompass: Float?) {
        val candidate = selectPreferredHeading(latestGpsBearing, latestGpsSpeed, rawCompass) ?: return
        val next = smoothHeading(heading, candidate, alpha = 0.28f)
        if (heading == null || abs(shortestAngleDelta(heading!!, next)) >= 1.5f) {
            heading = next
        }
    }

    LaunchedEffect(enabled, gpsBearing, gpsSpeedMetersPerSecond, declinationDegrees) {
        if (!enabled) {
            heading = null
            compassAzimuth = null
        } else {
            publishIfChanged(compassAzimuth)
        }
    }

    DisposableEffect(context, enabled) {
        if (!enabled) {
            onDispose {}
        } else {
            val sensorManager = context.getSystemService(SensorManager::class.java)
            if (sensorManager == null) {
                onDispose {}
            } else {
                val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                    ?: sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
                val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
                val gravity = FloatArray(3)
                val geomagnetic = FloatArray(3)
                var hasGravity = false
                var hasMagnetic = false
                val rawRotationMatrix = FloatArray(9)
                val remappedMatrix = FloatArray(9)
                var lastEmitElapsedMs = 0L

                fun currentDisplayRotation(): Int = runCatching {
                    if (Build.VERSION.SDK_INT >= 30) {
                        context.display.rotation
                    } else {
                        @Suppress("DEPRECATION")
                        context.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation
                    }
                }.getOrNull() ?: Surface.ROTATION_0

                fun remapForDisplay(input: FloatArray): FloatArray = when (currentDisplayRotation()) {
                    Surface.ROTATION_90 -> {
                        SensorManager.remapCoordinateSystem(input, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remappedMatrix)
                        remappedMatrix
                    }
                    Surface.ROTATION_180 -> {
                        SensorManager.remapCoordinateSystem(input, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remappedMatrix)
                        remappedMatrix
                    }
                    Surface.ROTATION_270 -> {
                        SensorManager.remapCoordinateSystem(input, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remappedMatrix)
                        remappedMatrix
                    }
                    else -> input
                }

                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val now = SystemClock.elapsedRealtime()
                        when (event.sensor.type) {
                            Sensor.TYPE_ROTATION_VECTOR,
                            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                                SensorManager.getRotationMatrixFromVector(rawRotationMatrix, event.values)
                            }
                            Sensor.TYPE_ACCELEROMETER -> {
                                System.arraycopy(event.values, 0, gravity, 0, minOf(event.values.size, 3))
                                hasGravity = true
                                if (!hasMagnetic || !SensorManager.getRotationMatrix(rawRotationMatrix, null, gravity, geomagnetic)) {
                                    return
                                }
                            }
                            Sensor.TYPE_MAGNETIC_FIELD -> {
                                System.arraycopy(event.values, 0, geomagnetic, 0, minOf(event.values.size, 3))
                                hasMagnetic = true
                                if (!hasGravity || !SensorManager.getRotationMatrix(rawRotationMatrix, null, gravity, geomagnetic)) {
                                    return
                                }
                            }
                            else -> return
                        }
                        if (now - lastEmitElapsedMs < 50L) return
                        lastEmitElapsedMs = now
                        val matrix = remapForDisplay(rawRotationMatrix)
                        val magneticAzimuth = computeTiltInvariantAzimuthDegrees(matrix) ?: return
                        val trueAzimuth = applyMagneticDeclination(magneticAzimuth, latestDeclination)
                        compassAzimuth = trueAzimuth
                        publishIfChanged(trueAzimuth)
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }

                if (rotationSensor != null) {
                    sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
                } else {
                    accelerometer?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
                    magnetometer?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
                }

                onDispose {
                    sensorManager.unregisterListener(listener)
                }
            }
        }
    }

    return if (enabled) heading else null
}
