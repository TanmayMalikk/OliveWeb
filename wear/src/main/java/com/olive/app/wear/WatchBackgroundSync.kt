package com.olive.app.wear

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.*
import androidx.work.*
import com.google.android.gms.wearable.*
import java.time.Instant
import kotlin.math.roundToInt

class WatchPhoneListener : WearableListenerService() {
    /**
     * Processes data layer events synced between the paired phone and watch.
     *
     * @param events Data event buffer delivered by the wearable data layer.
     */
    override fun onDataChanged(events: DataEventBuffer) = apply(applicationContext, events)
    companion object {
        /**
         * Applies the watch phone listener.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @param events Data event buffer delivered by the wearable data layer.
         */
        fun apply(context: Context, events: DataEventBuffer) {
            var changed = false
            // Session first even when one buffer contains both account and summary updates.
            for (path in listOf(WatchFitnessData.SESSION, WatchFitnessData.DAILY)) events.forEach { event ->
                if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == path) {
                    val data = DataMapItem.fromDataItem(event.dataItem).dataMap
                    if (path == WatchFitnessData.SESSION) changed = WatchFitnessData.session(context, data) || changed
                    else WatchFitnessData.daily(context, data)
                }
            }
            if (changed) WatchFitnessData.publish(context)
        }
    }
}

class WatchPassiveFitnessService : PassiveListenerService() {
    /**
     * Handles the new data points received event.
     *
     * @param dataPoints Data points supplied to [onNewDataPointsReceived].
     */
    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val boot = Instant.ofEpochMilli(System.currentTimeMillis() - SystemClock.elapsedRealtime())
        dataPoints.getData(DataType.STEPS_DAILY).forEach { point ->
            WatchFitnessData.steps(this, point.value.coerceIn(0, 500_000).toInt(), point.getEndInstant(boot).toEpochMilli(), passive = true)
        }
        dataPoints.getData(DataType.HEART_RATE_BPM).forEach { point ->
            if (point.value.isFinite()) WatchFitnessData.heart(this, point.value.roundToInt(), point.getTimeInstant(boot).toEpochMilli())
        }
        WatchFitnessData.publish(this)
    }
}

internal object WatchPassiveRegistration {
    /**
     * Schedules the watch passive registration.
     *
     * @param context Android context used to access system services, resources, or storage.
     */
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("watch-passive-registration", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WatchPassiveRegistrationWorker>().build())
    }
}

class WatchPassiveRegistrationWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    /**
     * Executes the scheduled WorkManager background task and returns the resulting work status.
     *
     * @return WorkManager [Result] indicating success, retry, or failure.
     */
    override fun doWork(): Result = try {
        val client = HealthServices.getClient(applicationContext).passiveMonitoringClient
        val supported = client.getCapabilitiesAsync().get().supportedDataTypesPassiveMonitoring
        val types = mutableSetOf<DataType<*, *>>()
        /**
         * Computes allowed.
         *
         * @param value Input value to store, parse, or format.
         * @return The resulting allowed.
         */
        fun allowed(value: String) = ContextCompat.checkSelfPermission(applicationContext, value) == PackageManager.PERMISSION_GRANTED
        if (allowed(Manifest.permission.ACTIVITY_RECOGNITION) && DataType.STEPS_DAILY in supported) types += DataType.STEPS_DAILY
        val heart = if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS
        val background = if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND" else "android.permission.BODY_SENSORS_BACKGROUND"
        if (allowed(heart) && (Build.VERSION.SDK_INT < 33 || allowed(background)) && DataType.HEART_RATE_BPM in supported) types += DataType.HEART_RATE_BPM
        if (types.isNotEmpty()) client.setPassiveListenerServiceAsync(WatchPassiveFitnessService::class.java,
            PassiveListenerConfig.builder().setDataTypes(types).build()).get()
        Result.success()
    } catch (_: SecurityException) { Result.success() }
      catch (_: Exception) { Result.retry() }
}

class WatchFitnessBootReceiver : BroadcastReceiver() {
    /**
     * Handles the receive event.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param intent Android intent containing the action and optional extras for this component.
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
            WatchPassiveRegistration.schedule(context)
    }
}
