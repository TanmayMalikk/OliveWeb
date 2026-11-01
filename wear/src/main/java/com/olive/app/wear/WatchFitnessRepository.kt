// Watch fitness users: reads Watch6 sensors and receives the phone's nutrition summary.
package com.olive.app.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.time.LocalDate
import kotlin.math.roundToInt

internal class WatchFitnessRepository(
    private val context: Context,
    private val onStateChanged: (WatchFitnessState) -> Unit,
) : SensorEventListener, DataClient.OnDataChangedListener {
    private val preferences = context.getSharedPreferences("watch_fitness", Context.MODE_PRIVATE)
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val dataClient = Wearable.getDataClient(context)
    private val measureClient = HealthServices.getClient(context).measureClient
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var state = WatchFitnessState(watchSteps = savedSteps())
    private var running = false
    private var lastPublishedAt = 0L
    private var lastPublishedSteps = -1
    private val publishHeartbeat = object : Runnable {
        override fun run() {
            if (!running) return
            publishWatchActivity(force = true)
            heartbeatHandler.postDelayed(this, WATCH_HEARTBEAT_MS)
        }
    }

    private val heartRateCallback = object : MeasureCallback {
        override fun onAvailabilityChanged(
            dataType: DeltaDataType<*, *>,
            availability: Availability,
        ) {
            update(state.copy(heartRateStatus = availability.toString()))
        }

        override fun onDataReceived(data: DataPointContainer) {
            val latest = data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value
            if (latest != null && latest.isFinite()) {
                update(state.copy(heartRate = latest.roundToInt(), heartRateStatus = "Live"))
            }
        }

        override fun onRegistrationFailed(throwable: Throwable) {
            update(state.copy(heartRateStatus = "Heart rate unavailable"))
        }
    }

    fun start() {
        if (running) return
        running = true
        dataClient.addListener(this)
        loadPhoneData()

        if (hasActivityPermission()) {
            sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
        if (hasHeartRatePermission()) {
            measureClient.registerMeasureCallback(DataType.HEART_RATE_BPM, heartRateCallback)
        }
        heartbeatHandler.removeCallbacks(publishHeartbeat)
        heartbeatHandler.postDelayed(publishHeartbeat, WATCH_HEARTBEAT_MS)
        onStateChanged(state)
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
        dataClient.removeListener(this)
        measureClient.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, heartRateCallback)
        heartbeatHandler.removeCallbacks(publishHeartbeat)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val totalSinceBoot = event.values.firstOrNull()?.roundToInt() ?: return
        val lastTotal = preferences.getInt("sensor_total", -1)
        val difference = if (lastTotal < 0 || totalSinceBoot < lastTotal) 0 else totalSinceBoot - lastTotal
        val today = LocalDate.now().toString()
        val savedDate = preferences.getString("step_date", null)
        val current = if (savedDate == today) preferences.getInt("steps_today", 0) else 0
        val newTotal = (current + difference.coerceAtLeast(0)).coerceAtMost(500_000)
        preferences.edit {
            putString("step_date", today)
            putInt("sensor_total", totalSinceBoot.coerceAtLeast(0))
            putInt("steps_today", newTotal)
        }
        update(state.copy(watchSteps = newTotal))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == DAILY_DATA_PATH) {
                applyPhoneData(DataMapItem.fromDataItem(event.dataItem).dataMap)
            }
        }
    }

    private fun loadPhoneData() {
        dataClient.dataItems.addOnSuccessListener { items ->
            try {
                items.firstOrNull { it.uri.path == DAILY_DATA_PATH }?.let {
                    applyPhoneData(DataMapItem.fromDataItem(it).dataMap)
                }
            } finally {
                items.release()
            }
        }
    }

    private fun applyPhoneData(data: DataMap) {
        update(
            state.copy(
                phoneSteps = data.getInt("phoneSteps").coerceAtLeast(0),
                stepGoal = data.getInt("stepGoal", 8_000).coerceAtLeast(1),
                calories = data.getFloat("calories").coerceAtLeast(0f),
                calorieGoal = data.getInt("calorieGoal", 2_000).coerceAtLeast(1),
                protein = data.getFloat("protein").coerceAtLeast(0f),
                proteinGoal = data.getInt("proteinGoal", 120).coerceAtLeast(1),
                carbohydrates = data.getFloat("carbohydrates").coerceAtLeast(0f),
                carbohydrateGoal = data.getInt("carbohydrateGoal", 250).coerceAtLeast(1),
                fat = data.getFloat("fat").coerceAtLeast(0f),
                fatGoal = data.getInt("fatGoal", 65).coerceAtLeast(1),
                fiber = data.getFloat("fiber").coerceAtLeast(0f),
                sugar = data.getFloat("sugar").coerceAtLeast(0f),
                sodium = data.getFloat("sodium").coerceAtLeast(0f),
                phoneConnected = true,
                updatedAt = data.getLong("updatedAt"),
            )
        )
    }

    private fun savedSteps(): Int = if (preferences.getString("step_date", null) == LocalDate.now().toString()) {
        preferences.getInt("steps_today", 0)
    } else {
        0
    }

    private fun hasActivityPermission() = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACTIVITY_RECOGNITION,
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasHeartRatePermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 36) {
            "android.permission.health.READ_HEART_RATE"
        } else {
            Manifest.permission.BODY_SENSORS
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun update(newState: WatchFitnessState) {
        state = newState
        onStateChanged(newState)
        publishWatchActivity(force = false)
    }

    // Watch fitness users: sends a heartbeat even when BPM and steps stay unchanged.
    private fun publishWatchActivity(force: Boolean) {
        val now = System.currentTimeMillis()
        val stepChanged = state.watchSteps != lastPublishedSteps
        if (!force && !stepChanged && now - lastPublishedAt < WATCH_HEARTBEAT_MS) return
        val request = PutDataMapRequest.create(WATCH_ACTIVITY_PATH).apply {
            dataMap.putInt("watchSteps", state.watchSteps.coerceAtLeast(0))
            dataMap.putInt("heartRate", state.heartRate ?: 0)
            dataMap.putLong("updatedAt", now)
        }.asPutDataRequest().setUrgent()
        dataClient.putDataItem(request)
        lastPublishedSteps = state.watchSteps
        lastPublishedAt = now
    }

    private companion object {
        const val DAILY_DATA_PATH = "/olive/fitness/daily"
        const val WATCH_ACTIVITY_PATH = "/olive/fitness/watch-activity"
        const val WATCH_HEARTBEAT_MS = 15_000L
    }
}
