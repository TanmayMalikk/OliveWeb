package com.olive.app.wear

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.health.services.client.HealthServices
import androidx.health.services.client.HealthServicesClient
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.PassiveMonitoringClient
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.IntervalDataPoint
import androidx.health.services.client.data.PassiveMonitoringCapabilities
import androidx.health.services.client.data.SampleDataPoint
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataItemBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class WatchComponentsAndSyncTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun <T> anyArg(fallback: T): T {
        Mockito.any<T>()
        return fallback
    }

    @Before
    fun initWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor { /* Prevent auto-running workers outside TestWorkerBuilder */ }.build(),
        )
    }

    @Test
    fun watchFitnessDataHandlesInvalidDatesPublishAndTileRefresh() {
        val now = System.currentTimeMillis() - 120_000L
        WatchFitnessData.prefs(context).edit().clear().commit()
        // Signed-out snapshot & publish early exit.
        assertEquals("Open Olive on your phone", WatchFitnessData.snapshot(context).heartRateStatus)
        WatchFitnessData.publish(context)
        assertFalse(WatchFitnessData.session(context, DataMap().apply { putLong("updatedAt", now) }))

        WatchFitnessData.session(context, DataMap().apply {
            putBoolean("signedIn", true); putString("accountId", "u1"); putString("sessionId", "s1")
            putLong("updatedAt", now); putLong("sessionStartedAt", now)
        })
        // Wrong date and NaN floats are ignored / sanitized.
        WatchFitnessData.daily(context, DataMap().apply {
            putString("accountId", "u1"); putString("sessionId", "s1"); putString("date", "2020-01-01")
            putLong("updatedAt", now + 1); putFloat("calories", 99f)
        })
        WatchFitnessData.daily(context, DataMap().apply {
            putString("accountId", "u1"); putString("sessionId", "s1"); putString("date", LocalDate.now().toString())
            putLong("updatedAt", now + 2); putFloat("calories", Float.NaN); putFloat("protein", -4f)
            putInt("stepGoal", 0)
        })
        WatchFitnessData.steps(context, -5, now + 3, passive = false)
        WatchFitnessData.heart(context, 72, now + 3)
        val snap = WatchFitnessData.snapshot(context)
        assertEquals(0f, snap.calories, 0f)
        assertEquals(1, snap.stepGoal)
        assertEquals("Last reading", snap.heartRateStatus)

        WatchFitnessData.prefs(context).edit().putString("step_date", "2020-01-01").commit()
        assertEquals(0, WatchFitnessData.snapshot(context).watchSteps)

        val dataClient = mock(DataClient::class.java)
        val putTask = Tasks.forResult(mock(DataItem::class.java))
        `when`(dataClient.putDataItem(Mockito.any())).thenReturn(putTask)
        Mockito.mockStatic(Wearable::class.java).use { wearable ->
            wearable.`when`<DataClient> { Wearable.getDataClient(context) }.thenReturn(dataClient)
            WatchFitnessData.publish(context)
            verify(dataClient).putDataItem(Mockito.any())
        }
    }

    @Test
    fun backgroundServicesBootReceiverAndRegistrationWorkerHandleHealthData() {
        val now = System.currentTimeMillis() - 10_000L
        WatchFitnessData.prefs(context).edit().clear().commit()

        val dataClient = mock(DataClient::class.java)
        `when`(dataClient.putDataItem(Mockito.any())).thenReturn(Tasks.forResult(mock(DataItem::class.java)))

        // WatchPhoneListener.apply & service onDataChanged
        val sessionItem = mock(DataItem::class.java).apply { `when`(uri).thenReturn(Uri.parse("wear://phone${WatchFitnessData.SESSION}")) }
        val dailyItem = mock(DataItem::class.java).apply { `when`(uri).thenReturn(Uri.parse("wear://phone${WatchFitnessData.DAILY}")) }
        val sessionEvent = mock(DataEvent::class.java).apply {
            `when`(type).thenReturn(DataEvent.TYPE_CHANGED); `when`(dataItem).thenReturn(sessionItem)
        }
        val dailyEvent = mock(DataEvent::class.java).apply {
            `when`(type).thenReturn(DataEvent.TYPE_CHANGED); `when`(dataItem).thenReturn(dailyItem)
        }
        val buffer = mock(DataEventBuffer::class.java)
        `when`(buffer.iterator()).thenAnswer { listOf(sessionEvent, dailyEvent).iterator() }
        val sessionMapItem = mock(DataMapItem::class.java).apply {
            `when`(dataMap).thenReturn(DataMap().apply {
                putBoolean("signedIn", true); putString("accountId", "watch-acct"); putString("sessionId", "tok1")
                putLong("updatedAt", now); putLong("sessionStartedAt", now)
            })
        }
        val dailyMapItem = mock(DataMapItem::class.java).apply {
            `when`(dataMap).thenReturn(DataMap().apply {
                putString("accountId", "watch-acct"); putString("sessionId", "tok1"); putString("date", LocalDate.now().toString())
                putLong("updatedAt", now + 1); putInt("phoneSteps", 222); putFloat("calories", 333f)
            })
        }
        Mockito.mockStatic(Wearable::class.java).use { wearable ->
            wearable.`when`<DataClient> { Wearable.getDataClient(anyArg(context)) }.thenReturn(dataClient)
            Mockito.mockStatic(DataMapItem::class.java).use { dmi ->
                dmi.`when`<DataMapItem> { DataMapItem.fromDataItem(sessionItem) }.thenReturn(sessionMapItem)
                dmi.`when`<DataMapItem> { DataMapItem.fromDataItem(dailyItem) }.thenReturn(dailyMapItem)
                val service = Robolectric.buildService(WatchPhoneListener::class.java).create().get()
                service.onDataChanged(buffer)
            }
            assertEquals(222, WatchFitnessData.snapshot(context).phoneSteps)

            // WatchPassiveFitnessService
            val passive = Robolectric.buildService(WatchPassiveFitnessService::class.java).create().get()
            val container = mock(DataPointContainer::class.java)
            val stepPoint = mock(IntervalDataPoint::class.java).apply {
                `when`(value).thenReturn(15L)
                `when`(getEndInstant(anyArg(Instant.EPOCH))).thenReturn(Instant.ofEpochMilli(now + 20))
            }
            val hrPoint = mock(SampleDataPoint::class.java).apply {
                `when`(value).thenReturn(84.2)
                `when`(getTimeInstant(anyArg(Instant.EPOCH))).thenReturn(Instant.ofEpochMilli(now + 30))
            }
            @Suppress("UNCHECKED_CAST")
            `when`(container.getData(DataType.STEPS_DAILY)).thenReturn(listOf(stepPoint as IntervalDataPoint<Long>))
            @Suppress("UNCHECKED_CAST")
            `when`(container.getData(DataType.HEART_RATE_BPM)).thenReturn(listOf(hrPoint as SampleDataPoint<Double>))
            passive.onNewDataPointsReceived(container)
            assertEquals(84, WatchFitnessData.snapshot(context).heartRate)
        }

        // BootReceiver
        val receiver = WatchFitnessBootReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_BATTERY_LOW))
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork("watch-passive-registration").get().isNotEmpty())

        // WatchPassiveRegistrationWorker
        val healthClient = mock(HealthServicesClient::class.java)
        val passiveClient = mock(PassiveMonitoringClient::class.java)
        `when`(healthClient.passiveMonitoringClient).thenReturn(passiveClient)
        @Suppress("UNCHECKED_CAST")
        val capsFuture = mock(ListenableFuture::class.java) as ListenableFuture<PassiveMonitoringCapabilities>
        @Suppress("UNCHECKED_CAST")
        val regFuture = mock(ListenableFuture::class.java) as ListenableFuture<Void>
        val caps = PassiveMonitoringCapabilities(
            supportedDataTypesPassiveMonitoring = setOf(DataType.STEPS_DAILY, DataType.HEART_RATE_BPM),
            supportedDataTypesPassiveGoals = emptySet(),
            supportedHealthEventTypes = emptySet(),
            supportedUserActivityStates = emptySet(),
        )
        `when`(capsFuture.get()).thenReturn(caps)
        `when`(passiveClient.getCapabilitiesAsync())
            .thenReturn(capsFuture)
            .thenThrow(SecurityException("denied"))
            .thenThrow(RuntimeException("transient"))
        val dummyConfig = androidx.health.services.client.data.PassiveListenerConfig.builder()
            .setDataTypes(setOf(DataType.STEPS_DAILY)).build()
        `when`(
            passiveClient.setPassiveListenerServiceAsync(
                anyArg(WatchPassiveFitnessService::class.java),
                anyArg(dummyConfig),
            )
        ).thenReturn(regFuture)

        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.BODY_SENSORS,
            "android.permission.BODY_SENSORS_BACKGROUND",
        )
        Mockito.mockStatic(HealthServices::class.java).use { hs ->
            hs.`when`<HealthServicesClient> { HealthServices.getClient(anyArg(context)) }.thenReturn(healthClient)
            val workerExecutor = Executors.newSingleThreadExecutor()
            try {
                val worker = TestWorkerBuilder<WatchPassiveRegistrationWorker>(context, workerExecutor).build()
                assertEquals(ListenableWorker.Result.success(), worker.doWork())
                assertEquals(ListenableWorker.Result.success(), worker.doWork())
                assertEquals(ListenableWorker.Result.retry(), worker.doWork())
            } finally {
                workerExecutor.shutdownNow()
            }
        }
    }

    @Test
    fun repositoryCallbacksAndActivityLifecycleDriveSensorsAndPermissions() {
        val now = System.currentTimeMillis() - 5_000L
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.BODY_SENSORS)
        val sensorManager = context.getSystemService(SensorManager::class.java)
        Shadows.shadowOf(sensorManager).addSensor(Shadows.shadowOf(mock(Sensor::class.java)).let {
            Sensor::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        })

        val dataClient = mock(DataClient::class.java)
        val itemBuffer = mock(DataItemBuffer::class.java)
        `when`(itemBuffer.iterator()).thenAnswer { emptyList<DataItem>().iterator() }
        `when`(dataClient.dataItems).thenReturn(Tasks.forResult(itemBuffer))
        `when`(dataClient.putDataItem(Mockito.any())).thenReturn(Tasks.forResult(mock(DataItem::class.java)))

        val healthClient = mock(HealthServicesClient::class.java)
        val measureClient = mock(MeasureClient::class.java)
        `when`(healthClient.measureClient).thenReturn(measureClient)

        var lastState: WatchFitnessState? = null
        Mockito.mockStatic(Wearable::class.java).use { wearable ->
            wearable.`when`<DataClient> { Wearable.getDataClient(anyArg(context)) }.thenReturn(dataClient)
            Mockito.mockStatic(HealthServices::class.java).use { hs ->
                hs.`when`<HealthServicesClient> { HealthServices.getClient(anyArg(context)) }.thenReturn(healthClient)
                WatchFitnessData.session(context, DataMap().apply {
                    putBoolean("signedIn", true); putString("accountId", "repo-user"); putString("sessionId", "s1")
                    putLong("updatedAt", now); putLong("sessionStartedAt", now)
                })
                val repo = WatchFitnessRepository(context) { lastState = it }
                repo.start()
                repo.start() // idempotent when already running

                val callback = Mockito.mockingDetails(measureClient).invocations
                    .first { it.method.name == "registerMeasureCallback" }
                    .let { inv -> inv.arguments.filterIsInstance<MeasureCallback>().first() }
                val hrPoint = mock(SampleDataPoint::class.java).apply {
                    `when`(value).thenReturn(71.0)
                    `when`(getTimeInstant(anyArg(Instant.EPOCH))).thenReturn(Instant.ofEpochMilli(now + 100))
                }
                val container = mock(DataPointContainer::class.java)
                @Suppress("UNCHECKED_CAST")
                `when`(container.getData(DataType.HEART_RATE_BPM)).thenReturn(listOf(hrPoint as SampleDataPoint<Double>))
                callback.onAvailabilityChanged(DataType.HEART_RATE_BPM, androidx.health.services.client.data.DataTypeAvailability.AVAILABLE)
                callback.onDataReceived(container)
                callback.onRegistrationFailed(IllegalStateException("fail"))

                val sensorEvent = SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.newInstance(1)
                sensorEvent.values[0] = 42f
                sensorEvent.timestamp = SystemClock.elapsedRealtimeNanos()
                repo.onSensorChanged(sensorEvent)
                repo.onAccuracyChanged(null, 0)
                val eventBuf = mock(DataEventBuffer::class.java)
                `when`(eventBuf.iterator()).thenAnswer { emptyList<DataEvent>().iterator() }
                repo.onDataChanged(eventBuf)
                assertEquals(71, lastState?.heartRate)

                repo.stop()
                repo.stop()

                // Exercise Activity lifecycle and ViewModel cleanup with permissions granted, then denied.
                val controller = Robolectric.buildActivity(WatchFitnessActivity::class.java).create().start().resume().pause().stop().destroy()
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertTrue(controller.get().isDestroyed)

                Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.BODY_SENSORS)
                val deniedController = Robolectric.buildActivity(WatchFitnessActivity::class.java).create().start().resume().pause().destroy()
                assertTrue(deniedController.get().isDestroyed)
            }
        }
    }
}
