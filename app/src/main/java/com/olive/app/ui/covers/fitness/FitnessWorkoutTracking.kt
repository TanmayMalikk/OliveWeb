// Fitness cover users: records a private outdoor workout route while a workout is active.
package com.olive.app.ui.covers.fitness

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.olive.app.OliveLauncher
import com.olive.app.R
import com.olive.app.data.DeviceCoordinates
import com.olive.app.data.DeviceLocation
import com.olive.app.data.DeviceLocationUpdates
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal enum class OutdoorWorkoutType(val storedName: String) {
    WALK("walk"),
    RUN("run"),
    CYCLE("cycle"),
    HIKE("hike");

    companion object {
        // Creates a value from stored for fitness cover.
        fun fromStored(value: String?) = entries.firstOrNull { it.storedName == value } ?: WALK
    }
}

internal data class WorkoutRoutePoint(
    val latitude: Double,
    val longitude: Double,
    val recordedAt: Long,
)

internal data class OutdoorWorkoutSnapshot(
    val active: Boolean = false,
    val paused: Boolean = false,
    val type: OutdoorWorkoutType = OutdoorWorkoutType.WALK,
    val startedAt: Long = 0L,
    val pausedAt: Long = 0L,
    val pausedDuration: Long = 0L,
    val finishedAt: Long = 0L,
    val distanceMeters: Double = 0.0,
    val route: List<WorkoutRoutePoint> = emptyList(),
) {
    // Fitness cover: Returns the elapsed millis. It reads the current device time.
    fun elapsedMillis(now: Long = System.currentTimeMillis()): Long {
        if (startedAt <= 0L) return 0L
        val end = when {
            active && paused -> pausedAt
            active -> now
            finishedAt > 0L -> finishedAt
            else -> now
        }
        return (end - startedAt - pausedDuration).coerceAtLeast(0L)
    }
}

internal object FitnessWorkoutMath {
    // returns the distance meters for fitness cover. It uses the saved health data to update the fitness screen.
    fun distanceMeters(first: WorkoutRoutePoint, second: WorkoutRoutePoint): Double {
        val earthRadius = 6_371_000.0
        val latitudeDelta = Math.toRadians(second.latitude - first.latitude)
        val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
        val firstLatitude = Math.toRadians(first.latitude)
        val secondLatitude = Math.toRadians(second.latitude)
        val haversine = sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
            cos(firstLatitude) * cos(secondLatitude) *
            sin(longitudeDelta / 2.0) * sin(longitudeDelta / 2.0)
        return earthRadius * 2.0 * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }

    // Fitness cover: returns the pace minutes per kilometre.
    fun paceMinutesPerKilometre(distanceMeters: Double, elapsedMillis: Long): Double? {
        if (distanceMeters < 10.0 || elapsedMillis <= 0L) return null
        return elapsedMillis / 60_000.0 / (distanceMeters / 1_000.0)
    }

    // returns the keep recent workouts for fitness cover. It uses the saved health data to update the fitness screen.
    fun keepRecentWorkouts(
        existing: List<OutdoorWorkoutSnapshot>,
        finished: OutdoorWorkoutSnapshot,
    ): List<OutdoorWorkoutSnapshot> = (listOf(finished) + existing)
        .distinctBy { it.finishedAt }
        .take(MAX_RECENT_WORKOUTS)

    private const val MAX_RECENT_WORKOUTS = 5
}

internal class FitnessWorkoutStore(context: Context, accountId: String) {
    private val preferences = context.getSharedPreferences(
        "fitness_workout_${accountId.hashCode().toUInt().toString(16)}",
        Context.MODE_PRIVATE,
    )

    // returns the snapshot for fitness cover. It reads the value saved on this device.
    fun snapshot(): OutdoorWorkoutSnapshot = OutdoorWorkoutSnapshot(
        active = preferences.getBoolean("active", false),
        paused = preferences.getBoolean("paused", false),
        type = OutdoorWorkoutType.fromStored(preferences.getString("type", null)),
        startedAt = preferences.getLong("started_at", 0L),
        pausedAt = preferences.getLong("paused_at", 0L),
        pausedDuration = preferences.getLong("paused_duration", 0L),
        finishedAt = preferences.getLong("finished_at", 0L),
        distanceMeters = Double.fromBits(preferences.getLong("distance_bits", 0L)),
        route = decodeRoute(preferences.getString("route", null)),
    )

    // Fitness cover: returns the history. It uses the saved health data to update the fitness screen.
    fun history(): List<OutdoorWorkoutSnapshot> {
        val savedHistory = decodeHistory(preferences.getString("workout_history", null))
        if (savedHistory.isNotEmpty()) return savedHistory
        val legacyWorkout = snapshot()
        return if (!legacyWorkout.active && legacyWorkout.finishedAt > 0L) {
            listOf(legacyWorkout.copy(route = emptyList()))
        } else {
            emptyList()
        }
    }

    // Fitness cover: Starts the feature. It reads the value saved on this device.
    fun start(type: OutdoorWorkoutType, now: Long = System.currentTimeMillis()) {
        val preservedHistory = history()
        preferences.edit {
            remove("active")
            remove("paused")
            remove("type")
            remove("started_at")
            remove("paused_at")
            remove("paused_duration")
            remove("finished_at")
            remove("distance_bits")
            remove("route")
            if (preservedHistory.isNotEmpty()) {
                putString("workout_history", encodeHistory(preservedHistory))
            }
            putBoolean("active", true)
            putString("type", type.storedName)
            putLong("started_at", now)
        }
    }

    // returns the pause for fitness cover. It reads the value saved on this device.
    fun pause(now: Long = System.currentTimeMillis()) {
        val current = snapshot()
        if (!current.active || current.paused) return
        preferences.edit {
            putBoolean("paused", true)
            putLong("paused_at", now)
        }
    }

    // Fitness cover: returns the resume. It reads the value saved on this device.
    fun resume(now: Long = System.currentTimeMillis()) {
        val current = snapshot()
        if (!current.active || !current.paused) return
        preferences.edit {
            putBoolean("paused", false)
            putLong("paused_at", 0L)
            putLong(
                "paused_duration",
                current.pausedDuration + (now - current.pausedAt).coerceAtLeast(0L),
            )
        }
    }

    // returns the finish for fitness cover. It reads the value saved on this device.
    fun finish(now: Long = System.currentTimeMillis()) {
        val current = snapshot()
        if (!current.active) return
        val finalPause = if (current.paused) {
            (now - current.pausedAt).coerceAtLeast(0L)
        } else {
            0L
        }
        val finishedWorkout = current.copy(
            active = false,
            paused = false,
            pausedAt = 0L,
            pausedDuration = current.pausedDuration + finalPause,
            finishedAt = now,
            route = emptyList(),
        )
        val updatedHistory = FitnessWorkoutMath.keepRecentWorkouts(history(), finishedWorkout)
        preferences.edit {
            putBoolean("active", false)
            putBoolean("paused", false)
            putLong("paused_at", 0L)
            putLong("paused_duration", current.pausedDuration + finalPause)
            putLong("finished_at", now)
            putString("workout_history", encodeHistory(updatedHistory))
        }
    }

    // Adds location for fitness cover. It uses the saved health data to update the fitness screen.
    fun addLocation(location: DeviceCoordinates) {
        val current = snapshot()
        if (!current.active || current.paused ||
            (location.accuracy != null && location.accuracy > MAX_ACCURACY_METRES)
        ) return
        val point = WorkoutRoutePoint(location.latitude, location.longitude, location.timestamp)
        val previous = current.route.lastOrNull()
        val segment = previous?.let { FitnessWorkoutMath.distanceMeters(it, point) } ?: 0.0
        val seconds = previous?.let { (point.recordedAt - it.recordedAt) / 1_000.0 } ?: 0.0
        val maximumReasonableSegment = maxOf(100.0, seconds * MAX_SPEED_METRES_PER_SECOND)
        if (previous != null && (seconds <= 0.0 || segment > maximumReasonableSegment)) return

        val route = if (current.route.size < MAX_ROUTE_POINTS) {
            current.route + point
        } else {
            current.route.filterIndexed { index, _ -> index % 2 == 0 } + point
        }
        preferences.edit {
            putLong("distance_bits", (current.distanceMeters + segment).toBits())
            putString("route", encodeRoute(route))
        }
    }

    // Converts route for fitness cover.
    private fun encodeRoute(points: List<WorkoutRoutePoint>) = JSONArray().apply {
        points.forEach { point ->
            put(
                JSONObject().put("lat", point.latitude).put("lon", point.longitude)
                    .put("at", point.recordedAt)
            )
        }
    }.toString()

    // Fitness cover: Converts route.
    private fun decodeRoute(value: String?): List<WorkoutRoutePoint> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(value)
            buildList {
                for (index in 0 until array.length()) {
                    val point = array.getJSONObject(index)
                    add(
                        WorkoutRoutePoint(
                            latitude = point.getDouble("lat"),
                            longitude = point.getDouble("lon"),
                            recordedAt = point.getLong("at"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    // Converts history for fitness cover.
    private fun encodeHistory(workouts: List<OutdoorWorkoutSnapshot>) = JSONArray().apply {
        workouts.forEach { workout ->
            put(
                JSONObject()
                    .put("type", workout.type.storedName)
                    .put("startedAt", workout.startedAt)
                    .put("pausedDuration", workout.pausedDuration)
                    .put("finishedAt", workout.finishedAt)
                    .put("distance", workout.distanceMeters)
            )
        }
    }.toString()

    // Fitness cover: Converts history.
    private fun decodeHistory(value: String?): List<OutdoorWorkoutSnapshot> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(value)
            buildList {
                for (index in 0 until array.length()) {
                    val workout = array.getJSONObject(index)
                    add(
                        OutdoorWorkoutSnapshot(
                            type = OutdoorWorkoutType.fromStored(workout.optString("type")),
                            startedAt = workout.optLong("startedAt"),
                            pausedDuration = workout.optLong("pausedDuration"),
                            finishedAt = workout.optLong("finishedAt"),
                            distanceMeters = workout.optDouble("distance"),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val MAX_ACCURACY_METRES = 60f
        const val MAX_SPEED_METRES_PER_SECOND = 25.0
        const val MAX_ROUTE_POINTS = 1_500
    }
}

internal object FitnessWorkoutController {
    // Fitness cover: Starts the feature. It uses the saved health data to update the fitness screen.
    fun start(context: Context, accountId: String, type: OutdoorWorkoutType) {
        val intent = workoutIntent(context, FitnessWorkoutService.ACTION_START, accountId)
            .putExtra(FitnessWorkoutService.EXTRA_TYPE, type.storedName)
        ContextCompat.startForegroundService(context, intent)
    }

    // returns the pause for fitness cover. It uses the saved health data to update the fitness screen.
    fun pause(context: Context, accountId: String) {
        context.startService(workoutIntent(context, FitnessWorkoutService.ACTION_PAUSE, accountId))
    }

    // Fitness cover: returns the resume. It uses the saved health data to update the fitness screen.
    fun resume(context: Context, accountId: String) {
        context.startService(workoutIntent(context, FitnessWorkoutService.ACTION_RESUME, accountId))
    }

    // Fitness cover: returns the finish. It uses the saved health data to update the fitness screen.
    fun finish(context: Context, accountId: String) {
        context.startService(workoutIntent(context, FitnessWorkoutService.ACTION_FINISH, accountId))
    }

    // returns the workout intent for fitness cover.
    private fun workoutIntent(context: Context, action: String, accountId: String) =
        Intent(context, FitnessWorkoutService::class.java).setAction(action)
            .putExtra(FitnessWorkoutService.EXTRA_ACCOUNT_ID, accountId)
}

internal class FitnessWorkoutService : Service() {
    private var store: FitnessWorkoutStore? = null
    private var locationUpdates: DeviceLocationUpdates? = null

    // Fitness cover: Responds when create. It uses the saved health data to update the fitness screen.
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    // Responds when start command for fitness cover. It reads the value saved on this device.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val accountId = intent?.getStringExtra(EXTRA_ACCOUNT_ID)
            ?: getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE)
                .getString("account_id", null)
        if (accountId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }
        getSharedPreferences(SERVICE_PREFERENCES, MODE_PRIVATE).edit {
            putString("account_id", accountId)
        }
        val workoutStore = FitnessWorkoutStore(this, accountId)
        store = workoutStore
        // Choose what on start command should do for the current value.
        when (intent?.action) {
            ACTION_START -> {
                workoutStore.start(
                    OutdoorWorkoutType.fromStored(intent.getStringExtra(EXTRA_TYPE))
                )
                showForegroundNotification()
                requestLocations()
            }
            ACTION_PAUSE -> {
                workoutStore.pause()
                stopLocationUpdates()
                updateNotification()
            }
            ACTION_RESUME -> {
                workoutStore.resume()
                showForegroundNotification()
                requestLocations()
            }
            ACTION_FINISH -> finishWorkout(workoutStore)
            else -> {
                // Only continue with on start command when workout store snapshot active.
                if (workoutStore.snapshot().active) {
                    showForegroundNotification()
                    if (!workoutStore.snapshot().paused) requestLocations()
                } else {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    // Fitness cover: Requests locations. It uses the saved health data to update the fitness screen.
    private fun requestLocations() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            store?.finish()
            stopSelf()
            return
        }
        stopLocationUpdates()
        locationUpdates = DeviceLocation.startUpdates(
            context = this,
            intervalMillis = 1_000L,
            minimumDistanceMeters = 1f,
        ) { location ->
            store?.addLocation(location)
            updateNotification()
        }
        if (locationUpdates == null) {
            store?.finish()
            stopSelf()
        }
    }

    // Fitness cover: Stops workout. It uses the saved health data to update the fitness screen.
    private fun finishWorkout(workoutStore: FitnessWorkoutStore) {
        stopLocationUpdates()
        workoutStore.finish()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // Fitness cover: Starts foreground notification. It uses the saved health data to update the fitness screen.
    private fun showForegroundNotification() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            workoutNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            },
        )
    }

    // Updates notification for fitness cover. It uses the saved health data to update the fitness screen.
    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, workoutNotification())
    }

    // Fitness cover: returns the workout notification.
    private fun workoutNotification(): android.app.Notification {
        val snapshot = store?.snapshot() ?: OutdoorWorkoutSnapshot()
        val distance = String.format(Locale.ROOT, "%.2f km", snapshot.distanceMeters / 1_000.0)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, OliveLauncher::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_app_info_blank)
            .setContentTitle(
                "${getString(R.string.fitness_outdoor_workout)} · " + getString(
                    if (snapshot.paused) R.string.fitness_pause else R.string.activity
                )
            )
            .setContentText(distance)
            .setContentIntent(openApp)
            .setOngoing(snapshot.active)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()
    }

    // Builds notification channel for fitness cover. It uses the saved health data to update the fitness screen.
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            getString(R.string.fitness_outdoor_workout),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.fitness_outdoor_workout_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // Fitness cover: Responds when bind. It uses the saved health data to update the fitness screen.
    override fun onBind(intent: Intent?): IBinder? = null

    // Responds when destroy for fitness cover. It uses the saved health data to update the fitness screen.
    override fun onDestroy() {
        stopLocationUpdates()
        super.onDestroy()
    }

    // Fitness cover: Stops location updates. It uses the saved health data to update the fitness screen.
    private fun stopLocationUpdates() {
        locationUpdates?.stop()
        locationUpdates = null
    }

    companion object {
        const val ACTION_START = "com.olive.app.fitness.START_WORKOUT"
        const val ACTION_PAUSE = "com.olive.app.fitness.PAUSE_WORKOUT"
        const val ACTION_RESUME = "com.olive.app.fitness.RESUME_WORKOUT"
        const val ACTION_FINISH = "com.olive.app.fitness.FINISH_WORKOUT"
        const val EXTRA_ACCOUNT_ID = "account_id"
        const val EXTRA_TYPE = "workout_type"
        private const val SERVICE_PREFERENCES = "fitness_workout_service"
        private const val NOTIFICATION_CHANNEL = "fitness_outdoor_workout"
        private const val NOTIFICATION_ID = 4_120
    }
}
