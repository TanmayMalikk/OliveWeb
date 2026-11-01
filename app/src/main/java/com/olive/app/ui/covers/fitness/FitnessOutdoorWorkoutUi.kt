// Fitness cover users: starts and controls private GPS-tracked outdoor workouts.
package com.olive.app.ui.covers.fitness

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Hiking
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.olive.app.R
import com.olive.app.data.LocationRoute
import com.olive.app.data.MapLocationPoint
import com.olive.app.data.RouteCoordinate
import com.olive.app.ui.shared.Ink
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.PaleOlive
import com.olive.app.ui.shared.Paper
import com.olive.app.ui.users.user.location.InteractiveLocationMap
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun FitnessOutdoorWorkoutCard(accountId: String, enabled: Boolean) {
    val context = LocalContext.current
    val store = remember(context, accountId) { FitnessWorkoutStore(context, accountId) }
    var workout by remember(store) { mutableStateOf(store.snapshot()) }
    var recentWorkouts by remember(store) { mutableStateOf(store.history()) }
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var selectedType by remember { mutableStateOf(OutdoorWorkoutType.WALK) }
    var pendingType by remember { mutableStateOf<OutdoorWorkoutType?>(null) }
    var permissionDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val type = pendingType
        pendingType = null
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true && type != null) {
            permissionDenied = false
            FitnessWorkoutController.start(context, accountId, type)
        } else {
            permissionDenied = true
        }
    }

    LaunchedEffect(store) {
        while (true) {
            workout = store.snapshot()
            recentWorkouts = store.history()
            currentTime = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    fun startWorkout(type: OutdoorWorkoutType) {
        val preciseLocationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (preciseLocationGranted) {
            FitnessWorkoutController.start(context, accountId, type)
        } else {
            pendingType = type
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                )
            )
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Paper),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        stringResource(R.string.fitness_outdoor_workout),
                        color = Ink,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        stringResource(R.string.fitness_outdoor_workout_description),
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(PaleOlive),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Terrain, null, tint = OliveGreen)
                }
            }

            if (workout.active) {
                ActiveWorkoutContent(
                    workout = workout,
                    currentTime = currentTime,
                    enabled = enabled,
                    onPause = { FitnessWorkoutController.pause(context, accountId) },
                    onResume = { FitnessWorkoutController.resume(context, accountId) },
                    onFinish = { FitnessWorkoutController.finish(context, accountId) },
                )
            } else {
                WorkoutTypeSelector(selectedType) { selectedType = it }
                Button(
                    onClick = { startWorkout(selectedType) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OliveGreen),
                ) {
                    Icon(Icons.Outlined.PlayArrow, null)
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.fitness_start_outdoor_workout))
                }
                if (permissionDenied) {
                    Text(
                        stringResource(R.string.fitness_precise_location_required),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (recentWorkouts.isNotEmpty()) {
                    RecentWorkoutHistory(recentWorkouts)
                }
            }
        }
    }
}

@Composable
private fun WorkoutTypeSelector(
    selected: OutdoorWorkoutType,
    onSelected: (OutdoorWorkoutType) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkoutTypeChip(Modifier.weight(1f), OutdoorWorkoutType.WALK, selected, onSelected)
            WorkoutTypeChip(Modifier.weight(1f), OutdoorWorkoutType.RUN, selected, onSelected)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkoutTypeChip(Modifier.weight(1f), OutdoorWorkoutType.CYCLE, selected, onSelected)
            WorkoutTypeChip(Modifier.weight(1f), OutdoorWorkoutType.HIKE, selected, onSelected)
        }
    }
}

@Composable
private fun WorkoutTypeChip(
    modifier: Modifier,
    type: OutdoorWorkoutType,
    selected: OutdoorWorkoutType,
    onSelected: (OutdoorWorkoutType) -> Unit,
) {
    val label = workoutTypeLabel(type)
    val icon = when (type) {
        OutdoorWorkoutType.WALK -> Icons.AutoMirrored.Outlined.DirectionsWalk
        OutdoorWorkoutType.RUN -> Icons.AutoMirrored.Outlined.DirectionsRun
        OutdoorWorkoutType.CYCLE -> Icons.AutoMirrored.Outlined.DirectionsBike
        OutdoorWorkoutType.HIKE -> Icons.Outlined.Hiking
    }
    FilterChip(
        selected = selected == type,
        onClick = { onSelected(type) },
        label = { Text(label) },
        leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
        modifier = modifier,
    )
}

@Composable
private fun ActiveWorkoutContent(
    workout: OutdoorWorkoutSnapshot,
    currentTime: Long,
    enabled: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onFinish: () -> Unit,
) {
    val elapsed = workout.elapsedMillis(currentTime)
    val pace = FitnessWorkoutMath.paceMinutesPerKilometre(workout.distanceMeters, elapsed)
    Text(
        text = workoutTypeLabel(workout.type),
        color = OliveGreen,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
    WorkoutLocationMap(workout, elapsed)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        WorkoutMetric(formatDistance(workout.distanceMeters), stringResource(R.string.distance))
        WorkoutMetric(formatDuration(elapsed), stringResource(R.string.fitness_duration))
        WorkoutMetric(formatPace(pace), stringResource(R.string.fitness_pace))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = if (workout.paused) onResume else onPause,
            enabled = enabled,
            modifier = Modifier.weight(1f).height(48.dp),
        ) {
            Icon(if (workout.paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, null)
            Spacer(Modifier.size(4.dp))
            Text(
                stringResource(
                    if (workout.paused) R.string.fitness_resume else R.string.fitness_pause
                )
            )
        }
        Button(
            onClick = onFinish,
            enabled = enabled,
            modifier = Modifier.weight(1f).height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A453E)),
        ) {
            Icon(Icons.Outlined.Stop, null)
            Spacer(Modifier.size(4.dp))
            Text(stringResource(R.string.fitness_finish))
        }
    }
}

@Composable
private fun WorkoutLocationMap(workout: OutdoorWorkoutSnapshot, elapsedMillis: Long) {
    val points = workout.route
    Box(
        modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (points.isEmpty()) {
            Text(
                stringResource(R.string.fitness_waiting_for_gps),
                color = Muted,
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            val latest = points.last()
            val marker = MapLocationPoint(
                uid = FITNESS_WORKOUT_MAP_UID,
                name = workoutTypeLabel(workout.type),
                latitude = latest.latitude,
                longitude = latest.longitude,
                updatedAt = latest.recordedAt,
                live = workout.active && !workout.paused,
            )
            val route = points.takeIf { it.size >= 2 }?.let { routePoints ->
                LocationRoute(
                    coordinates = routePoints.map {
                        RouteCoordinate(it.latitude, it.longitude)
                    },
                    distanceMeters = workout.distanceMeters,
                    durationSeconds = elapsedMillis / 1_000.0,
                    directions = emptyList(),
                )
            }
            InteractiveLocationMap(
                points = listOf(marker),
                ownPointUid = FITNESS_WORKOUT_MAP_UID,
                showOwnPointInBlue = true,
                route = route,
                navigationActive = workout.active && !workout.paused,
                modifier = Modifier.fillMaxWidth().height(220.dp),
            )
        }
    }
}

@Composable
private fun RecentWorkoutHistory(workouts: List<OutdoorWorkoutSnapshot>) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.fitness_recent_outdoor_workouts),
            color = Ink,
            fontWeight = FontWeight.Bold,
        )
        workouts.take(5).forEach { workout ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(PaleOlive)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        workoutTypeLabel(workout.type),
                        color = Ink,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        formatWorkoutDate(workout.finishedAt),
                        color = Muted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    WorkoutMetric(
                        formatDistance(workout.distanceMeters),
                        stringResource(R.string.distance),
                    )
                    WorkoutMetric(
                        formatDuration(workout.elapsedMillis()),
                        stringResource(R.string.fitness_duration),
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkoutMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Ink, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun workoutTypeLabel(type: OutdoorWorkoutType) = stringResource(
    when (type) {
        OutdoorWorkoutType.WALK -> R.string.fitness_walking
        OutdoorWorkoutType.RUN -> R.string.fitness_running
        OutdoorWorkoutType.CYCLE -> R.string.fitness_cycling
        OutdoorWorkoutType.HIKE -> R.string.fitness_hiking
    }
)

private fun formatDistance(distanceMeters: Double) =
    String.format(Locale.ROOT, "%.2f km", distanceMeters / 1_000.0)

private fun formatDuration(elapsedMillis: Long): String {
    val totalSeconds = elapsedMillis / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

private fun formatPace(pace: Double?) = pace?.let {
    val minutes = it.toInt()
    val seconds = ((it - minutes) * 60.0).toInt().coerceIn(0, 59)
    String.format(Locale.ROOT, "%d:%02d /km", minutes, seconds)
} ?: "--"

private fun formatWorkoutDate(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

private const val FITNESS_WORKOUT_MAP_UID = "private-fitness-workout"
