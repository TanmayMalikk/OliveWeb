package com.olive.app.wear

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.*
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.*
import com.google.android.gms.wearable.*
import java.time.Instant
import kotlin.math.roundToInt

// Fast measurements while visible; the passive service remains registered after the UI stops.
internal class WatchFitnessRepository(private val context: Context, private val onStateChanged: (WatchFitnessState) -> Unit) :
    SensorEventListener, DataClient.OnDataChangedListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val client = Wearable.getDataClient(context)
    private val measure = HealthServices.getClient(context).measureClient
    private var running = false
    private val changes = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> onStateChanged(WatchFitnessData.snapshot(context)) }
    private val heart = object : MeasureCallback {
        /**
         * Handles availability status updates for the tracked health data type.
         *
         * @param dataType Data type supplied to [onAvailabilityChanged].
         * @param availability Availability supplied to [onAvailabilityChanged].
         */
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) = Unit
        /**
         * Processes incoming real-time health measurement data points.
         *
         * @param data Data supplied to [onDataReceived].
         */
        override fun onDataReceived(data: DataPointContainer) {
            val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
            data.getData(DataType.HEART_RATE_BPM).forEach { point ->
                if (point.value.isFinite()) WatchFitnessData.heart(context, point.value.roundToInt(), point.getTimeInstant(boot).toEpochMilli())
            }
            WatchFitnessData.publish(context)
        }
        /**
         * Handles a failed health sensor or callback registration.
         *
         * @param throwable Throwable error reported by the failing operation.
         */
        override fun onRegistrationFailed(throwable: Throwable) = Unit
    }
    /**
     * Starts watch fitness repository.
     */
    fun start() {
        if (running) return
        running = true
        WatchFitnessData.prefs(context).registerOnSharedPreferenceChangeListener(changes)
        client.addListener(this)
        client.dataItems.addOnSuccessListener { items ->
            try {
                items.firstOrNull { it.uri.path == WatchFitnessData.SESSION }?.let { WatchFitnessData.session(context, DataMapItem.fromDataItem(it).dataMap) }
                items.firstOrNull { it.uri.path == WatchFitnessData.DAILY }?.let { WatchFitnessData.daily(context, DataMapItem.fromDataItem(it).dataMap) }
            } finally { items.release() }
            WatchFitnessData.publish(context)
        }
        if (allowed(Manifest.permission.ACTIVITY_RECOGNITION)) sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        if (allowed(if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS))
            measure.registerMeasureCallback(DataType.HEART_RATE_BPM, heart)
        WatchPassiveRegistration.schedule(context)
        onStateChanged(WatchFitnessData.snapshot(context))
    }
    /**
     * Stops watch fitness repository.
     */
    fun stop() {
        if (!running) return
        running = false
        sensors.unregisterListener(this); client.removeListener(this)
        measure.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, heart)
        WatchFitnessData.prefs(context).unregisterOnSharedPreferenceChangeListener(changes)
    }
    /**
     * Handles updated sensor readings from the wearable or device hardware.
     *
     * @param event Sensor, UI, or lifecycle event being processed.
     */
    override fun onSensorChanged(event: SensorEvent) {
        val timestamp = System.currentTimeMillis() - SystemClock.elapsedRealtime() + event.timestamp / 1_000_000
        event.values.firstOrNull()?.takeIf(Float::isFinite)?.let {
            WatchFitnessData.steps(context, it.roundToInt(), timestamp, passive = false)
            WatchFitnessData.publish(context)
        }
    }
    /**
     * Responds to sensor accuracy level updates from the Android sensor framework.
     *
     * @param sensor Optional sensor supplied to [onAccuracyChanged].
     * @param accuracy Accuracy supplied to [onAccuracyChanged].
     */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    /**
     * Processes data layer events synced between the paired phone and watch.
     *
     * @param events Data event buffer delivered by the wearable data layer.
     */
    override fun onDataChanged(events: DataEventBuffer) { WatchPhoneListener.apply(context, events) }
    /**
     * Computes allowed.
     *
     * @param permission Permission supplied to [allowed].
     * @return The resulting allowed.
     */
    private fun allowed(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
