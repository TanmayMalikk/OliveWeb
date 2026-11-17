package com.olive.app.ui.covers.fitness

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FitnessHealthConnectTest {
    @Test
    fun backgroundReadAggregatesStepsAndUsesAllHeartRatePagesWithoutDuplicates() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val date = LocalDate.now()
        val started = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val owner = "health-test-owner"
        seedSession(context, owner, date, started)
        val client = mock(HealthConnectClient::class.java)
        val aggregateRequest = mock(AggregateRequest::class.java)
        val controller = mock(PermissionController::class.java)
        `when`(client.permissionController).thenReturn(controller)
        `when`(controller.getGrantedPermissions()).thenReturn(
            FitnessHealthConnect.permissions + HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
        )
        val results = mock(AggregationResult::class.java)
        `when`(results[StepsRecord.COUNT_TOTAL]).thenReturn(320L)
        `when`(client.aggregate(any<AggregateRequest>() ?: aggregateRequest)).thenReturn(results)
        val valid = System.currentTimeMillis() - 5
        val first = heartRate(
            HeartRateRecord.Sample(Instant.ofEpochMilli(started - 5_000), 85),
            HeartRateRecord.Sample(Instant.ofEpochMilli(valid), 72),
        )
        val second = heartRate(
            HeartRateRecord.Sample(Instant.ofEpochMilli(valid + 1), 95),
            HeartRateRecord.Sample(Instant.ofEpochMilli(valid + 2), 250),
        )
        @Suppress("UNCHECKED_CAST")
        val readRequest = mock(ReadRecordsRequest::class.java) as ReadRecordsRequest<HeartRateRecord>
        val pages = mutableListOf<String?>()
        doAnswer { invocation ->
            val request = invocation.getArgument<ReadRecordsRequest<HeartRateRecord>>(0)
            pages += request.pageToken
            when {
                pages.size > 2 -> ReadRecordsResponse(emptyList<HeartRateRecord>(), null)
                request.pageToken == null -> ReadRecordsResponse(listOf(first), "next")
                else -> ReadRecordsResponse(listOf(first, second), null)
            }
        }.`when`(client).readRecords(any<ReadRecordsRequest<HeartRateRecord>>() ?: readRequest)
        val ready = FitnessAccountSync.ready.value
        FitnessAccountSync.ready.value = true
        try {
            FitnessHealthConnect.read(context, owner, background = true, authenticatedUid = { owner }, healthProvider = { client })
            val fitness = FitnessStore(context, owner).snapshot(date)
            assertEquals(listOf(null, "next"), pages)
            assertEquals(420, fitness.stepsToday)
            val vitals = fitness.heartRateHistory.single()
            assertEquals(2, vitals.sampleCount)
            assertEquals(72, vitals.lowestBpm)
            assertEquals(95, vitals.peakBpm)
            assertEquals(listOf(95), vitals.samples.map { it.bpm })
            assertEquals(valid + 1, fitnessPreferences(context, owner).getLong("_hc_read_$date", 0))
        } finally {
            FitnessAccountSync.ready.value = ready
        }
    }

    @Test
    fun backgroundAccessRequiresPermissionAndAccountSwitchDiscardsTheInFlightResult() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val date = LocalDate.now()
        val started = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val owner = "health-old-session"
        val session = seedSession(context, owner, date, started)
        val client = mock(HealthConnectClient::class.java)
        val aggregateRequest = mock(AggregateRequest::class.java)
        val controller = mock(PermissionController::class.java)
        `when`(client.permissionController).thenReturn(controller)
        `when`(controller.getGrantedPermissions()).thenReturn(setOf(HealthPermission.getReadPermission(StepsRecord::class)))
        val result = mock(AggregationResult::class.java)
        `when`(result[StepsRecord.COUNT_TOTAL]).thenReturn(1000L)
        doAnswer {
            session.edit().putString(FitnessWatchSync.SESSION_ID, "new-login-session").commit()
            result
        }.`when`(client).aggregate(any<AggregateRequest>() ?: aggregateRequest)
        val ready = FitnessAccountSync.ready.value
        FitnessAccountSync.ready.value = true
        try {
            FitnessHealthConnect.read(context, owner, background = true, authenticatedUid = { owner }, healthProvider = { client })
            verify(client, never()).aggregate(any<AggregateRequest>() ?: aggregateRequest)
            FitnessHealthConnect.read(context, owner, authenticatedUid = { owner }, healthProvider = { client })
            verify(client).aggregate(any<AggregateRequest>() ?: aggregateRequest)
            assertEquals(0, FitnessStore(context, owner).snapshot(date).stepsToday)
        } finally {
            FitnessAccountSync.ready.value = ready
        }
    }

    private fun seedSession(context: Context, uid: String, date: LocalDate, started: Long) =
        context.getSharedPreferences(FitnessWatchSync.SYNC_PREFERENCES, 0).also { session ->
            session.edit().putLong("accountStartedAt", started).putString(FitnessWatchSync.SESSION_ID, "fixture-session").commit()
            fitnessPreferences(context, uid).edit().putInt("_hc_base_${date}_$started", 100)
                .putLong("_hc_window_${date}_$started", started).commit()
        }

    private fun heartRate(vararg samples: HeartRateRecord.Sample): HeartRateRecord =
        mock(HeartRateRecord::class.java).also { record -> `when`(record.samples).thenReturn(samples.toList()) }
}
