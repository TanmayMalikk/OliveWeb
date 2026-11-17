package com.olive.app.ui.covers.fitness

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.*
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.*
import com.olive.app.R
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

internal object FitnessHealthConnect {
    private val readLock = Mutex()
    val permissions = setOf(HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class))

    fun available(context: Context) = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    fun schedule(context: Context, owner: String) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("fitness-health-${fitnessAccountKey(owner)}",
            ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<FitnessHealthWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf("uid" to owner)).build())
    }

    suspend fun read(context: Context, owner: String, background: Boolean = false) = readLock.withLock { withContext(Dispatchers.IO) {
        if (!FitnessAccountSync.ready.value || fitnessAuthenticatedUid() != owner || !available(context)) return@withContext
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        if (background && HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND !in granted) return@withContext
        if (permissions.none { it in granted }) return@withContext
        val session = context.getSharedPreferences(FitnessWatchSync.SYNC_PREFERENCES, Context.MODE_PRIVATE)
        val startedAt = session.getLong("accountStartedAt", System.currentTimeMillis())
        val token = session.getString("sessionId", null)
        val today = LocalDate.now()
        val local = fitnessPreferences(context, owner)
        val dayStart = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val windowKey = "_hc_window_${today}_$startedAt"
        val baseKey = "_hc_base_${today}_$startedAt"
        if (!local.contains(baseKey)) local.edit(commit = true) {
            putLong(windowKey, System.currentTimeMillis())
            putInt(baseKey, FitnessStore(context, owner).snapshot().stepsToday)
        }
        val start = maxOf(dayStart, startedAt, local.getLong(windowKey, startedAt))
        if (start >= System.currentTimeMillis()) return@withContext
        val time = TimeRangeFilter.between(Instant.ofEpochMilli(start), Instant.now())
        val steps = if (HealthPermission.getReadPermission(StepsRecord::class) in granted) {
            val result = client.aggregate(AggregateRequest(metrics = setOf(StepsRecord.COUNT_TOTAL), timeRangeFilter = time))
            ((result[StepsRecord.COUNT_TOTAL] ?: 0L) + local.getInt(baseKey, 0)).coerceIn(0, 500_000).toInt()
        } else 0
        val samples = mutableListOf<HeartRateSample>()
        if (HealthPermission.getReadPermission(HeartRateRecord::class) in granted) {
            var page: String? = null
            val lastRead = local.getLong("_hc_read_$today", start)
            do {
                val result = client.readRecords(ReadRecordsRequest(HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(maxOf(start, lastRead)), Instant.now()),
                    pageToken = page))
                result.records.forEach { record -> record.samples.forEach { sample ->
                    if (sample.time.toEpochMilli() > lastRead && sample.beatsPerMinute in 30..240)
                        samples += HeartRateSample(sample.time.toEpochMilli(), sample.beatsPerMinute.toInt())
                } }
                page = result.pageToken?.takeIf(String::isNotBlank)
            } while (page != null)
        }
        // Reads begun in an old login session cannot be applied to a new session, including A -> B -> A.
        if (fitnessAuthenticatedUid() != owner || session.getString("sessionId", null) != token) return@withContext
        FitnessStore(context, owner).recordHealthConnect(steps, samples, today)
        samples.maxOfOrNull { it.recordedAt }?.let { last -> local.edit { putLong("_hc_read_$today", last) } }
    } }
}

internal class FitnessHealthWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val owner = inputData.getString("uid") ?: return Result.failure()
        if (fitnessAuthenticatedUid() != owner) return Result.success()
        return try { FitnessHealthConnect.read(applicationContext, owner, background = true); Result.success() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: SecurityException) { Result.success() }
        catch (_: Exception) { Result.retry() }
    }
}

@Composable
internal fun FitnessHealthConnection(accountId: String, enabled: Boolean) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var checked by androidx.compose.runtime.saveable.rememberSaveable(accountId) { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        FitnessHealthConnect.schedule(context, accountId)
    }
    LaunchedEffect(accountId, enabled, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            // Check once per cover visit, before launching: dismissal or permission-screen return must not loop.
            if (checked || !enabled || accountId == "guest" ||
                fitnessAuthenticatedUid() != accountId || !FitnessHealthConnect.available(context)) return@repeatOnLifecycle
            try {
                val client = HealthConnectClient.getOrCreate(context)
                val wanted = FitnessHealthConnect.permissions.toMutableSet()
                if (client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
                    HealthConnectFeatures.FEATURE_STATUS_AVAILABLE) wanted += HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
                val missing = wanted - client.permissionController.getGrantedPermissions()
                checked = true
                if (missing.isNotEmpty()) launcher.launch(missing)
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (_: Exception) { /* An unavailable provider must not prevent the cover from opening. */ }
        }
    }
}

internal class FitnessHealthPrivacyActivity : androidx.activity.ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(android.widget.TextView(this).apply {
            text = getString(R.string.fitness_health_privacy)
            textSize = 18f
            setPadding(32, 48, 32, 32)
        })
    }
}
