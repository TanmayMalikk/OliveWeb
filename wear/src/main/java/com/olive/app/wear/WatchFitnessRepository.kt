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
import androidx.wear.tiles.TileService
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
    private var state = WatchFitnessState(
        watchSteps = savedSteps(),
        heartRateSummary = savedHeartRateSummary(),
    )
    private var stepsAtLastHeartRate = state.watchSteps
    private var running = false
    private var lastPublishedAt = 0L
    private var lastPublishedSteps = -1
    private var lastTileUpdateAt = 0L
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
                val bpm = latest.roundToInt()
                val today = LocalDate.now().toString()
                val currentSummary = if (preferences.getString(HEART_RATE_DATE_KEY, null) == today) {
                    state.heartRateSummary
                } else {
                    WatchHeartRateSummary()
                }
                val summary = currentSummary.record(
                    bpm = bpm,
                    resting = state.watchSteps == stepsAtLastHeartRate,
                )
                stepsAtLastHeartRate = state.watchSteps
                saveHeartRateSummary(today, summary)
                preferences.edit { putInt("heart_rate_latest", bpm) }
                update(
                    state.copy(
                        heartRate = bpm.takeIf { it in 30..240 },
                        heartRateStatus = "Live",
                        heartRateSummary = summary,
                    )
                )
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
        preferences.edit {
            putInt("phone_steps", data.getInt("phoneSteps").coerceAtLeast(0))
            putInt("step_goal", data.getInt("stepGoal", 8_000).coerceAtLeast(1))
            putFloat("calories", data.getFloat("calories").coerceAtLeast(0f))
            putInt("calorie_goal", data.getInt("calorieGoal", 2_000).coerceAtLeast(1))
        }
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

    private fun savedHeartRateSummary(): WatchHeartRateSummary {
        val today = LocalDate.now().toString()
        if (preferences.getString(HEART_RATE_DATE_KEY, null) != today) {
            return WatchHeartRateSummary()
        }
        return WatchHeartRateSummary(
            peakBpm = preferences.getInt("heart_rate_peak", 0),
            lowestBpm = preferences.getInt("heart_rate_lowest", 0),
            totalBpm = preferences.getLong("heart_rate_total", 0L),
            sampleCount = preferences.getInt("heart_rate_samples", 0),
            restingTotalBpm = preferences.getLong("heart_rate_resting_total", 0L),
            restingSampleCount = preferences.getInt("heart_rate_resting_samples", 0),
        )
    }

    private fun saveHeartRateSummary(date: String, summary: WatchHeartRateSummary) {
        preferences.edit {
            putString(HEART_RATE_DATE_KEY, date)
            putInt("heart_rate_peak", summary.peakBpm)
            putInt("heart_rate_lowest", summary.lowestBpm)
            putLong("heart_rate_total", summary.totalBpm)
            putInt("heart_rate_samples", summary.sampleCount)
            putLong("heart_rate_resting_total", summary.restingTotalBpm)
            putInt("heart_rate_resting_samples", summary.restingSampleCount)
        }
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
        requestTileUpdates()
    }

    private fun requestTileUpdates() {
        val now = System.currentTimeMillis()
        if (now - lastTileUpdateAt < TILE_UPDATE_INTERVAL_MS) return
        val updater = TileService.getUpdater(context)
        updater.requestUpdate(OliveActivityTileService::class.java)
        updater.requestUpdate(OliveVitalsTileService::class.java)
        lastTileUpdateAt = now
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
        const val HEART_RATE_DATE_KEY = "heart_rate_date"
        const val TILE_UPDATE_INTERVAL_MS = 15_000L
    }
}
