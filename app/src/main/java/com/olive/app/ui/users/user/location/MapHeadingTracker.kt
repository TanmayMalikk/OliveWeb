// Safe-app users: tracks compass + GPS heading when map rotation mode is active.
package com.olive.app.ui.users.user.location

import android.content.Context
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
import com.olive.app.data.location.adjustAzimuthForDisplayRotation
import com.olive.app.data.location.selectPreferredHeading
import com.olive.app.data.location.shortestAngleDelta
import com.olive.app.data.location.smoothHeading
import kotlin.math.abs

/**
 * Observes compass orientation and GPS movement bearing while [enabled] is true,
 * returning a circularly smoothed heading in `[0f, 360f)`.
 */
@Composable
internal fun rememberDeviceHeading(
    enabled: Boolean,
    gpsBearing: Float?,
    gpsSpeedMetersPerSecond: Float?,
): Float? {
    val context = LocalContext.current
    var heading by remember { mutableStateOf<Float?>(null) }
    var compassAzimuth by remember { mutableStateOf<Float?>(null) }
    val latestGpsBearing by rememberUpdatedState(gpsBearing)
    val latestGpsSpeed by rememberUpdatedState(gpsSpeedMetersPerSecond)

    fun publishIfChanged(rawCompass: Float?) {
        val candidate = selectPreferredHeading(latestGpsBearing, latestGpsSpeed, rawCompass) ?: return
        val next = smoothHeading(heading, candidate)
        if (heading == null || abs(shortestAngleDelta(heading!!, next)) >= 2f) {
            heading = next
        }
    }

    LaunchedEffect(enabled, gpsBearing, gpsSpeedMetersPerSecond) {
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
                val rotationMatrix = FloatArray(9)
                val orientationAngles = FloatArray(3)
                var lastEmitElapsedMs = 0L

                fun currentDisplayRotation(): Int = runCatching {
                    if (Build.VERSION.SDK_INT >= 30) {
                        context.display.rotation
                    } else {
                        @Suppress("DEPRECATION")
                        context.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation
                    }
                }.getOrNull() ?: Surface.ROTATION_0

                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val now = SystemClock.elapsedRealtime()
                        val rawAzimuth = when (event.sensor.type) {
                            Sensor.TYPE_ROTATION_VECTOR,
                            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                                Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                            }

                            Sensor.TYPE_ACCELEROMETER -> {
                                System.arraycopy(event.values, 0, gravity, 0, minOf(event.values.size, 3))
                                hasGravity = true
                                if (!hasMagnetic || !SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
                                    return
                                }
                                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                                Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                            }

                            Sensor.TYPE_MAGNETIC_FIELD -> {
                                System.arraycopy(event.values, 0, geomagnetic, 0, minOf(event.values.size, 3))
                                hasMagnetic = true
                                if (!hasGravity || !SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)) {
                                    return
                                }
                                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                                Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                            }

                            else -> return
                        }
                        if (now - lastEmitElapsedMs < 60L) return
                        lastEmitElapsedMs = now
                        val adjusted = adjustAzimuthForDisplayRotation(rawAzimuth, currentDisplayRotation())
                        compassAzimuth = adjusted
                        publishIfChanged(adjusted)
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
