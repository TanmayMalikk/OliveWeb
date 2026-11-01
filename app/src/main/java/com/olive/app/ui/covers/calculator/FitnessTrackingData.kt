// Calculator cover users: stores steps and calories during optional walk tracking.
package com.olive.app.ui.covers.calculator

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.olive.app.OliveActivity
import com.olive.app.R
import java.time.LocalDate
import kotlin.math.roundToInt

internal data class FitnessProfile(
    val weightKg: Float = 70f,
    val heightCm: Float = 170f,
    val stepGoal: Int = 8_000,
)

internal data class FitnessDay(val date: LocalDate, val steps: Int)

internal data class FitnessSnapshot(
    val profile: FitnessProfile,
    val stepsToday: Int,
    val history: List<FitnessDay>,
)

internal data class FitnessWalkState(
    val active: Boolean = false,
    val startedAt: Long = 0L,
    val distanceMeters: Float = 0f,
)

// Keeps calculations together so the UI and tests use the same estimates.
internal object FitnessMath {
    fun strideMeters(heightCm: Float) = (heightCm.coerceIn(100f, 230f) * 0.413f) / 100f
    fun distanceKm(steps: Int, heightCm: Float) =
        steps.coerceAtLeast(0) * strideMeters(heightCm) / 1_000f

    fun calories(steps: Int, profile: FitnessProfile) =
        distanceKm(steps, profile.heightCm) *
                profile.weightKg.coerceIn(30f, 300f) * 0.75f

    fun activeMinutes(steps: Int) = (steps.coerceAtLeast(0) / 100f).roundToInt()

    fun speedKmh(distanceMeters: Float, elapsedMillis: Long) =
        if (elapsedMillis <= 0L) 0f
        else (distanceMeters / 1_000f) / (elapsedMillis / 3_600_000f)

    fun paceMinutesPerKm(distanceMeters: Float, elapsedMillis: Long) =
        if (distanceMeters < 1f || elapsedMillis <= 0L) 0f
        else (elapsedMillis / 60_000f) / (distanceMeters / 1_000f)
}

// Stores only local fitness totals for this cover account.
internal class FitnessStore(context: Context, accountId: String) {
    private val preferences = context.getSharedPreferences(
        "calculator_fitness_${accountId.hashCode()}",
        Context.MODE_PRIVATE,
    )

    fun profile() = FitnessProfile(
        preferences.getFloat("weight_kg", 70f),
        preferences.getFloat("height_cm", 170f),
        preferences.getInt("step_goal", 8_000),
    )

    fun saveProfile(value: FitnessProfile) = preferences.edit {
        putFloat("weight_kg", value.weightKg.coerceIn(30f, 300f))
        putFloat("height_cm", value.heightCm.coerceIn(100f, 230f))
        putInt("step_goal", value.stepGoal.coerceIn(500, 100_000))
    }

    fun snapshot(today: LocalDate = LocalDate.now()) = FitnessSnapshot(
        profile = profile(),
        stepsToday = steps(today),
        history = (0L..6L).map { today.minusDays(it) }
            .map { FitnessDay(it, steps(it)) }.reversed(),
    )

    // Add the difference from Android's since-reboot counter to today's local total.
    fun recordSensorValue(totalSinceBoot: Int, today: LocalDate = LocalDate.now()): FitnessSnapshot {
        val previous = preferences.getInt("last_sensor_value", -1)
        val delta = if (previous < 0 || totalSinceBoot < previous) 0 else totalSinceBoot - previous
        preferences.edit {
            putInt("last_sensor_value", totalSinceBoot)
            if (delta > 0) {
                putInt(stepKey(today), (steps(today) + delta).coerceAtMost(500_000))
            }
        }
        return snapshot(today)
    }

    fun walk() = FitnessWalkState(
        preferences.getBoolean("walk_active", false),
        preferences.getLong("walk_started_at", 0L),
        preferences.getFloat("walk_distance_meters", 0f),
    )

    fun startWalk(now: Long = System.currentTimeMillis()) = preferences.edit {
        putBoolean("walk_active", true)
        putLong("walk_started_at", now)
        putFloat("walk_distance_meters", 0f)
        remove("walk_last_latitude")
        remove("walk_last_longitude")
    }

    fun stopWalk() = preferences.edit { putBoolean("walk_active", false) }

    fun recordLocation(location: Location) {
        if (!walk().active || (location.hasAccuracy() && location.accuracy > 60f)) return
        val previous = if (preferences.contains("walk_last_latitude")) Location(
            "previous"
        ).apply {
            latitude = java.lang.Double.longBitsToDouble(
                preferences.getLong("walk_last_latitude", 0L)
            )
            longitude = java.lang.Double.longBitsToDouble(
                preferences.getLong("walk_last_longitude", 0L)
            )
        } else null
        val addition = previous?.distanceTo(location)?.takeIf { it in 1.5f..150f } ?: 0f
        preferences.edit {
            putFloat(
                "walk_distance_meters",
                (walk().distanceMeters + addition).coerceAtMost(1_000_000f),
            )
            putLong(
                "walk_last_latitude",
                java.lang.Double.doubleToRawLongBits(location.latitude),
            )
            putLong(
                "walk_last_longitude",
                java.lang.Double.doubleToRawLongBits(location.longitude),
            )
        }
    }

    private fun steps(date: LocalDate) = preferences.getInt(stepKey(date), 0)
    private fun stepKey(date: LocalDate) = "steps_$date"
}

// Tracks a user-started walk while the screen is off and keeps Android's location notice visible.
internal class FitnessWalkService : Service() {
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var store: FitnessStore? = null
    private var activeAccountId: String? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                store?.recordLocation(location)
                announceUpdate()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_ACCOUNT_ID)
            ?.takeIf(String::isNotBlank)
            ?.let { accountId ->
                activeAccountId = accountId
                store = FitnessStore(applicationContext, accountId)
            }

        if (intent?.action == ACTION_STOP) {
            stopWalk()
            return START_NOT_STICKY
        }

        if (store == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (store?.walk()?.active != true) {
            store?.startWalk()
        }
        startForeground(NOTIFICATION_ID, notification())
        requestUpdates()
        announceUpdate()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        super.onDestroy()
    }

    private fun requestUpdates() {
        val allowed = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ).any { permission ->
            ContextCompat.checkSelfPermission(this, permission) ==
                    PackageManager.PERMISSION_GRANTED
        }

        if (!allowed) {
            stopWalk()
            return
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5_000L,
        ).setMinUpdateIntervalMillis(2_500L).build()

        runCatching {
            fused.requestLocationUpdates(request, callback, mainLooper)
        }.onFailure {
            stopWalk()
        }
    }

    private fun notification(): android.app.Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.fitness_tracking),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)

        val openIntent = Intent(this, OliveActivity::class.java)
        val open = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, FitnessWalkService::class.java)
            .setAction(ACTION_STOP)
            .putExtra(EXTRA_ACCOUNT_ID, activeAccountId)
        val stop = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_calculator)
            .setContentTitle(getString(R.string.walk_tracking_active))
            .setContentText(getString(R.string.walk_tracking_notification))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, getString(R.string.stop_walk), stop)
            .build()
    }

    private fun announceUpdate() {
        sendBroadcast(Intent(ACTION_UPDATED).setPackage(packageName))
    }

    private fun stopWalk() {
        fused.removeLocationUpdates(callback)
        store?.stopWalk()
        announceUpdate()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        internal const val ACTION_UPDATED = "com.olive.app.FITNESS_WALK_UPDATED"
        private const val ACTION_STOP = "com.olive.app.STOP_FITNESS_WALK"
        private const val EXTRA_ACCOUNT_ID = "account_id"
        private const val CHANNEL_ID = "fitness_tracking"
        private const val NOTIFICATION_ID = 4107

        fun start(context: Context, accountId: String) {
            val intent = Intent(context, FitnessWalkService::class.java)
                .putExtra(EXTRA_ACCOUNT_ID, accountId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context, accountId: String) {
            val intent = Intent(context, FitnessWalkService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_ACCOUNT_ID, accountId)
            context.startService(intent)
        }
    }
}
