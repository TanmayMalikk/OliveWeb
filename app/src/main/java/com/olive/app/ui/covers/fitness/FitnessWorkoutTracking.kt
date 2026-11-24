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
import com.olive.app.data.location.DeviceCoordinates
import com.olive.app.data.location.DeviceLocation
import com.olive.app.data.location.DeviceLocationUpdates
import com.olive.app.data.location.supportDistanceMeters
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.time.LocalDate
import com.google.firebase.auth.FirebaseAuth
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import kotlin.math.roundToInt

internal enum class OutdoorWorkoutType(val storedName: String) {
    WALK("walk"),
    RUN("run"),
    CYCLE("cycle"),
    HIKE("hike");

    companion object {
        /**
         * Creates a value from stored for fitness cover.
         *
         * @param value Input value to store, parse, or format, or null when not provided.
         * @return The resulting from stored.
         */
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
    /**
     * Calculates active workout time without including paused time.
     *
     * @param now Current epoch timestamp in milliseconds.
     * @return The computed elapsed milliseconds numeric value.
     */
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
    /**
     * Adds the distance between each valid pair of workout route points.
     *
     * @param first First supplied to [distanceMeters].
     * @param second Second supplied to [distanceMeters].
     * @return The computed distance meters numeric value.
     */
    fun distanceMeters(first: WorkoutRoutePoint, second: WorkoutRoutePoint): Double =
        supportDistanceMeters(first.latitude, first.longitude, second.latitude, second.longitude)

    /**
     * Gives the fitness cover the pace minutes per kilometre it needs.
     *
     * @param distanceMeters Distance meters supplied to [paceMinutesPerKilometre].
     * @param elapsedMillis Duration or timestamp for elapsed, in milliseconds.
     * @return The computed pace minutes per kilometre number, or `null` if unavailable.
     */
    fun paceMinutesPerKilometre(distanceMeters: Double, elapsedMillis: Long): Double? {
        if (distanceMeters < 10.0 || elapsedMillis <= 0L) return null
        return elapsedMillis / 60_000.0 / (distanceMeters / 1_000.0)
    }

    /**
     * Keeps only the five newest completed outdoor workouts.
     *
     * @param existing Collection of existing values to process.
     * @param finished Finished supplied to [keepRecentWorkouts].
     * @return List of resulting keep recent workouts items.
     */
    fun keepRecentWorkouts(
        existing: List<OutdoorWorkoutSnapshot>,
        finished: OutdoorWorkoutSnapshot,
    ): List<OutdoorWorkoutSnapshot> = (listOf(finished) + existing)
        .distinctBy { it.finishedAt }
        .take(MAX_RECENT_WORKOUTS)

    private const val MAX_RECENT_WORKOUTS = 5
}

internal class FitnessWorkoutStore(context: Context, accountId: String) {
    private val preferences = fitnessPreferences(context, accountId, workout = true)

    /**
     * Loads the snapshot needed by the fitness cover.
     *
     * @return The resulting [OutdoorWorkoutSnapshot] instance.
     */
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

    /**
     * Loads the five most recent outdoor workouts.
     *
     * @return List of resulting history items.
     */
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

    /**
     * Starts a new outdoor workout and clears the previous live route.
     *
     * @param type Type supplied to [start].
     * @param now Current epoch timestamp in milliseconds.
     */
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

    /**
     * Pauses workout time and GPS recording.
     *
     * @param now Current epoch timestamp in milliseconds.
     */
    fun pause(now: Long = System.currentTimeMillis()) {
        val current = snapshot()
        if (!current.active || current.paused) return
        preferences.edit {
            putBoolean("paused", true)
            putLong("paused_at", now)
        }
    }

    /**
     * Resumes workout time and GPS recording.
     *
     * @param now Current epoch timestamp in milliseconds.
     */
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

    /**
     * Finishes the active workout and saves it to workout history.
     *
     * @param now Current epoch timestamp in milliseconds.
     */
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

    /**
     * Adds a valid GPS point and updates workout distance.
     *
     * @param location Location supplied to [addLocation].
     */
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

    /**
     * Converts the saved workout route between JSON and map points.
     *
     * @param points Collection of points values to process.
     * @return The resulting encode route.
     */
    private fun encodeRoute(points: List<WorkoutRoutePoint>) = JSONArray().apply {
        points.forEach { point ->
            put(
                JSONObject().put("lat", point.latitude).put("lon", point.longitude)
                    .put("at", point.recordedAt)
            )
        }
    }.toString()

    /**
     * Fitness cover: Converts route.
     *
     * @param value Input value to store, parse, or format, or null when not provided.
     * @return List of resulting decode route items.
     */
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

    /**
     * Converts completed workout history between JSON and app models.
     *
     * @param workouts Collection of workouts values to process.
     * @return The resulting encode history.
     */
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

    /**
     * Fitness cover: Converts history.
     *
     * @param value Input value to store, parse, or format, or null when not provided.
     * @return List of resulting decode history items.
     */
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
    /**
     * Sends the start command to the outdoor workout service.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param accountId Account ID supplied to [start].
     * @param type Type supplied to [start].
     */
    fun start(context: Context, accountId: String, type: OutdoorWorkoutType) {
        val intent = workoutIntent(context, FitnessWorkoutService.ACTION_START, accountId)
            .putExtra(FitnessWorkoutService.EXTRA_TYPE, type.storedName)
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Pauses workout time and GPS recording.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param accountId Account ID supplied to [pause].
     */
    fun pause(context: Context, accountId: String) {
        context.startService(workoutIntent(context, FitnessWorkoutService.ACTION_PAUSE, accountId))
    }

    /**
     * Resumes workout time and GPS recording.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param accountId Account ID supplied to [resume].
     */
    fun resume(context: Context, accountId: String) {
        context.startService(workoutIntent(context, FitnessWorkoutService.ACTION_RESUME, accountId))
    }

    /**
     * Finishes the active workout and saves it to workout history.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param accountId Account ID supplied to [finish].
     */
    fun finish(context: Context, accountId: String) {
        context.startService(workoutIntent(context, FitnessWorkoutService.ACTION_FINISH, accountId))
    }

    /**
     * Creates the service command for one workout action.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param action Action supplied to [workoutIntent].
     * @param accountId Account ID supplied to [workoutIntent].
     * @return The resulting workout intent.
     */
    private fun workoutIntent(context: Context, action: String, accountId: String) =
        Intent(context, FitnessWorkoutService::class.java).setAction(action)
            .putExtra(FitnessWorkoutService.EXTRA_ACCOUNT_ID, accountId)
}

internal class FitnessWorkoutService : Service() {
    private var store: FitnessWorkoutStore? = null
    private var locationUpdates: DeviceLocationUpdates? = null

    /**
     * Prepares GPS tracking and the foreground notification for outdoor workouts.
     */
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * Starts, pauses, resumes, or stops the outdoor workout from the service action.
     *
     * @param intent Android intent containing the action and optional extras for this component.
     * @param flags Flags supplied to [onStartCommand].
     * @param startId Start ID supplied to [onStartCommand].
     * @return The computed on start command numeric value.
     */
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
        // Runs the workout command sent by the fitness screen or notification.
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
                // Run this part only when workout store snapshot active.
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

    /**
     * Requests one-second GPS updates while an outdoor workout is active.
     */
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

    /**
     * Finishes and saves the workout before stopping the service.
     *
     * @param workoutStore Workout store supplied to [finishWorkout].
     */
    private fun finishWorkout(workoutStore: FitnessWorkoutStore) {
        stopLocationUpdates()
        workoutStore.finish()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Starts the required foreground notification while GPS workout tracking runs.
     */
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

    /**
     * Refreshes elapsed time and distance in the workout notification.
     */
    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, workoutNotification())
    }

    /**
     * Builds the workout notification shown while the fitness cover is running.
     *
     * @return The resulting [Notification] for workout notification.
     */
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

    /**
     * Creates the Android channel used by workout tracking notifications.
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL,
            getString(R.string.fitness_outdoor_workout),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.fitness_outdoor_workout_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * This workout service is command-based, so screens do not bind to it.
     *
     * @param intent Android intent containing the action and optional extras for this component.
     * @return The resulting [IBinder], or `null` if unavailable.
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Stops GPS updates when Android destroys the workout service.
     */
    override fun onDestroy() {
        stopLocationUpdates()
        super.onDestroy()
    }

    /**
     * Removes GPS callbacks when the workout pauses or ends.
     */
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

// Fitness cover users: receives the paired Watch6 daily step total for the active account.

internal class FitnessWatchListenerService : WearableListenerService() {
    /**
     * Saves fitness data received from the connected Wear OS watch.
     *
     * @param events Data event buffer delivered by the wearable data layer.
     */
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED ||
                event.dataItem.uri.path != FitnessWatchSync.WATCH_ACTIVITY_PATH
            ) return@forEach

            val accountId = getSharedPreferences(
                FitnessWatchSync.SYNC_PREFERENCES,
                MODE_PRIVATE,
            ).getString(FitnessWatchSync.ACTIVE_ACCOUNT_ID, null) ?: return@forEach
            val watchData = DataMapItem.fromDataItem(event.dataItem).dataMap
            val watchAccountId = watchData.getString("accountId").orEmpty()
            val signedInAccountId = runCatching {
                FirebaseAuth.getInstance().currentUser?.uid
            }.getOrNull()
            // Ignore delayed readings from a watch session owned by another account.
            if (watchAccountId != accountId || signedInAccountId != accountId || !FitnessAccountSync.ready.value) return@forEach
            val prefs = getSharedPreferences(FitnessWatchSync.SYNC_PREFERENCES, MODE_PRIVATE)
            if (watchData.getString(FitnessWatchSync.SESSION_ID) != prefs.getString(FitnessWatchSync.SESSION_ID, null) ||
                watchData.getString("date") != LocalDate.now().toString() ||
                watchData.getLong("updatedAt") < prefs.getLong("accountStartedAt", 0L)) return@forEach
            FitnessStore(applicationContext, accountId).recordWatchActivity(
                watchSteps = watchData.getInt("watchSteps"),
                heartRate = watchData.getInt("heartRate"),
                watchUpdatedAt = watchData.getLong("updatedAt"),
                heartRateUpdatedAt = watchData.getLong("heartRateAt"),
            )
            FitnessAccountSync.publish(applicationContext, accountId)
        }
    }
}

internal enum class FitnessPrimaryGoal {
    LOSE_WEIGHT,
    MAINTAIN,
    BUILD_MUSCLE,
    IMPROVE_FITNESS,
}

internal enum class FitnessActivityLevel {
    SEDENTARY,
    LIGHTLY_ACTIVE,
    MODERATELY_ACTIVE,
    VERY_ACTIVE,
}

internal enum class FitnessNutritionStyle {
    BALANCED,
    HIGH_PROTEIN,
    LOWER_CARB,
}

internal enum class FitnessMetabolicBasis {
    FEMALE,
    MALE,
    NEUTRAL,
}

internal data class FitnessGoalQuestionnaireInput(
    val primaryGoal: FitnessPrimaryGoal = FitnessPrimaryGoal.MAINTAIN,
    val activityLevel: FitnessActivityLevel = FitnessActivityLevel.LIGHTLY_ACTIVE,
    val nutritionStyle: FitnessNutritionStyle = FitnessNutritionStyle.BALANCED,
    val ageYears: Int = 30,
    val heightCm: Float = 170f,
    val weightKg: Float = 70f,
    val metabolicBasis: FitnessMetabolicBasis = FitnessMetabolicBasis.NEUTRAL,
)

internal data class FitnessGoalRecommendation(
    val profile: FitnessProfile,
    val estimatedMaintenanceCalories: Int,
)

internal object FitnessGoalCalculator {
    const val MIN_AGE = 13
    const val MAX_AGE = 100
    const val MIN_HEIGHT_CM = 100f
    const val MAX_HEIGHT_CM = 230f
    const val MIN_WEIGHT_KG = 30f
    const val MAX_WEIGHT_KG = 300f

    /**
     * Checks whether the questionnaire measurements fall inside supported physiological bounds.
     *
     * @param input Questionnaire answers supplied to [isValid].
     * @return `true` when age, height, and weight are finite and within bounds.
     */
    fun isValid(input: FitnessGoalQuestionnaireInput): Boolean =
        input.ageYears in MIN_AGE..MAX_AGE &&
            input.heightCm.isFinite() &&
            input.heightCm in MIN_HEIGHT_CM..MAX_HEIGHT_CM &&
            input.weightKg.isFinite() &&
            input.weightKg in MIN_WEIGHT_KG..MAX_WEIGHT_KG

    /**
     * Computes initial daily step, calorie, macronutrient, and water goals from questionnaire inputs.
     *
     * @param input Questionnaire answers supplied to [calculate].
     * @return The resulting [FitnessGoalRecommendation] with bounded targets and maintenance estimate.
     */
    fun calculate(input: FitnessGoalQuestionnaireInput): FitnessGoalRecommendation {
        val safeAge = input.ageYears.coerceIn(MIN_AGE, MAX_AGE)
        val safeHeight = (if (input.heightCm.isFinite()) input.heightCm else 170f)
            .coerceIn(MIN_HEIGHT_CM, MAX_HEIGHT_CM)
        val safeWeight = (if (input.weightKg.isFinite()) input.weightKg else 70f)
            .coerceIn(MIN_WEIGHT_KG, MAX_WEIGHT_KG)

        val sexOffset = when (input.metabolicBasis) {
            FitnessMetabolicBasis.FEMALE -> -161.0
            FitnessMetabolicBasis.MALE -> 5.0
            // Midpoint between the standard Mifflin-St Jeor offsets avoids requiring a binary selection.
            FitnessMetabolicBasis.NEUTRAL -> -78.0
        }
        val bmr = 10.0 * safeWeight + 6.25 * safeHeight - 5.0 * safeAge + sexOffset
        val activityMultiplier = when (input.activityLevel) {
            FitnessActivityLevel.SEDENTARY -> 1.2
            FitnessActivityLevel.LIGHTLY_ACTIVE -> 1.375
            FitnessActivityLevel.MODERATELY_ACTIVE -> 1.55
            FitnessActivityLevel.VERY_ACTIVE -> 1.725
        }
        val tdee = bmr * activityMultiplier
        val maintenanceCalories = roundToNearest(tdee, 50).coerceIn(1_200, 4_500)

        val goalDelta = when (input.primaryGoal) {
            FitnessPrimaryGoal.LOSE_WEIGHT -> -400.0
            FitnessPrimaryGoal.MAINTAIN -> 0.0
            FitnessPrimaryGoal.BUILD_MUSCLE -> 250.0
            FitnessPrimaryGoal.IMPROVE_FITNESS -> 100.0
        }
        val calorieFloor = when (input.metabolicBasis) {
            FitnessMetabolicBasis.FEMALE -> 1_200
            FitnessMetabolicBasis.MALE -> 1_500
            FitnessMetabolicBasis.NEUTRAL -> 1_350
        }
        val calorieGoal = roundToNearest(tdee + goalDelta, 50).coerceIn(calorieFloor, 4_500)

        val (baseProteinShare, fatShare) = when (input.nutritionStyle) {
            FitnessNutritionStyle.BALANCED -> 0.25 to 0.28
            FitnessNutritionStyle.HIGH_PROTEIN -> 0.32 to 0.26
            FitnessNutritionStyle.LOWER_CARB -> 0.30 to 0.38
        }
        val goalProteinBoost = if (
            input.primaryGoal == FitnessPrimaryGoal.LOSE_WEIGHT ||
            input.primaryGoal == FitnessPrimaryGoal.BUILD_MUSCLE
        ) 0.03 else 0.0
        val proteinShare = (baseProteinShare + goalProteinBoost).coerceAtMost(0.36)

        val maxProtein = minOf(safeWeight * 2.2, (calorieGoal * 0.40) / 4.0)
        val minProtein = minOf(safeWeight * 1.0, maxProtein)
        val proteinRaw = ((calorieGoal * proteinShare) / 4.0).coerceIn(minProtein, maxProtein)
        val proteinGoal = roundToNearest(proteinRaw, 5).coerceIn(40, 350)
        val fatGoal = roundToNearest((calorieGoal * fatShare) / 9.0, 5).coerceIn(30, 200)
        val remainingCalories = (calorieGoal - proteinGoal * 4 - fatGoal * 9).coerceAtLeast(200)
        val carbohydrateGoal = roundToNearest(remainingCalories / 4.0, 5).coerceIn(50, 700)

        val baseSteps = when (input.activityLevel) {
            FitnessActivityLevel.SEDENTARY -> 6_000
            FitnessActivityLevel.LIGHTLY_ACTIVE -> 8_000
            FitnessActivityLevel.MODERATELY_ACTIVE -> 10_000
            FitnessActivityLevel.VERY_ACTIVE -> 12_000
        }
        val stepBonus = when (input.primaryGoal) {
            FitnessPrimaryGoal.LOSE_WEIGHT -> 1_500
            FitnessPrimaryGoal.IMPROVE_FITNESS -> 2_000
            FitnessPrimaryGoal.MAINTAIN, FitnessPrimaryGoal.BUILD_MUSCLE -> 0
        }
        val stepGoal = (baseSteps + stepBonus).coerceIn(5_000, 15_000)

        val waterActivityBonus = when (input.activityLevel) {
            FitnessActivityLevel.SEDENTARY -> 0.0
            FitnessActivityLevel.LIGHTLY_ACTIVE -> 200.0
            FitnessActivityLevel.MODERATELY_ACTIVE -> 400.0
            FitnessActivityLevel.VERY_ACTIVE -> 600.0
        }
        val waterGoalMl = roundToNearest(33.0 * safeWeight + waterActivityBonus, 100)
            .coerceIn(1_500, 4_500)

        return FitnessGoalRecommendation(
            profile = FitnessProfile(
                weightKg = safeWeight,
                heightCm = safeHeight,
                stepGoal = stepGoal,
                calorieGoal = calorieGoal,
                proteinGoal = proteinGoal,
                carbohydrateGoal = carbohydrateGoal,
                fatGoal = fatGoal,
                waterGoalMl = waterGoalMl,
            ),
            estimatedMaintenanceCalories = maintenanceCalories,
        )
    }

    /**
     * Serializes the non-body-metric questionnaire selections for local restoration on this device.
     *
     * @param input Questionnaire answers supplied to [toJson].
     * @return JSON representation storing goal, activity, nutrition style, age, and formula choice.
     */
    fun toJson(input: FitnessGoalQuestionnaireInput): String = JSONObject().apply {
        put("primaryGoal", input.primaryGoal.name)
        put("activityLevel", input.activityLevel.name)
        put("nutritionStyle", input.nutritionStyle.name)
        put("ageYears", input.ageYears.coerceIn(MIN_AGE, MAX_AGE))
        put("metabolicBasis", input.metabolicBasis.name)
    }.toString()

    /**
     * Restores saved questionnaire selections paired with the current height and weight from [fallbackProfile].
     *
     * @param raw Saved JSON string, or `null` if not configured yet.
     * @param fallbackProfile Current [FitnessProfile] providing baseline weight and height.
     * @return The reconstructed [FitnessGoalQuestionnaireInput].
     */
    fun fromJson(
        raw: String?,
        fallbackProfile: FitnessProfile,
    ): FitnessGoalQuestionnaireInput {
        val safeWeight = (if (fallbackProfile.weightKg.isFinite()) fallbackProfile.weightKg else 70f)
            .coerceIn(MIN_WEIGHT_KG, MAX_WEIGHT_KG)
        val safeHeight = (if (fallbackProfile.heightCm.isFinite()) fallbackProfile.heightCm else 170f)
            .coerceIn(MIN_HEIGHT_CM, MAX_HEIGHT_CM)
        if (raw.isNullOrBlank()) {
            return FitnessGoalQuestionnaireInput(heightCm = safeHeight, weightKg = safeWeight)
        }
        val parsed = runCatching { JSONObject(raw) }.getOrNull()
            ?: return FitnessGoalQuestionnaireInput(heightCm = safeHeight, weightKg = safeWeight)
        return FitnessGoalQuestionnaireInput(
            primaryGoal = enumValueOrDefault(parsed.optString("primaryGoal"), FitnessPrimaryGoal.MAINTAIN),
            activityLevel = enumValueOrDefault(parsed.optString("activityLevel"), FitnessActivityLevel.LIGHTLY_ACTIVE),
            nutritionStyle = enumValueOrDefault(parsed.optString("nutritionStyle"), FitnessNutritionStyle.BALANCED),
            ageYears = parsed.optInt("ageYears", 30).coerceIn(MIN_AGE, MAX_AGE),
            heightCm = safeHeight,
            weightKg = safeWeight,
            metabolicBasis = enumValueOrDefault(parsed.optString("metabolicBasis"), FitnessMetabolicBasis.NEUTRAL),
        )
    }

    /**
     * Rounds [value] to the nearest multiple of [step].
     *
     * @param value The number to round.
     * @param step The interval used for rounding.
     * @return The closest integer multiple of the specified step.
     */
    private fun roundToNearest(value: Double, step: Int): Int =
        (value / step).roundToInt() * step

    /**
     * Parses enum entry [name] safely, falling back to [default] when unrecognized.
     *
     * @param T The enum type to search for a matching constant.
     * @param name The stored enum constant name to parse.
     * @param default The enum type to search for a matching constant.
     * @return The matching enum constant, or the supplied default when the name is unknown.
     */
    private inline fun <reified T : Enum<T>> enumValueOrDefault(name: String, default: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(default)
}
