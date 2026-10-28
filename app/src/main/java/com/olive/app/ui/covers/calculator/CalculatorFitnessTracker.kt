// Calculator cover users: shows steps, calories, walk tracking, and history.
package com.olive.app.ui.covers.calculator

import android.Manifest
import android.os.Build
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.olive.app.R
import com.olive.app.ui.shared.*
import com.olive.app.ui.users.user.dashboard.LocalLocationPermissionRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FitnessTrackerScreen(accountId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context, accountId) { FitnessStore(context.applicationContext, accountId) }
    var snapshot by remember(store) { mutableStateOf(store.snapshot()) }
    var walk by remember(store) { mutableStateOf(store.walk()) }
    var message by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val activityPermissionNeeded = stringResource(R.string.activity_permission_needed)
    val fitnessSettingsSaved = stringResource(R.string.fitness_settings_saved)
    val locationNeededForWalk = stringResource(R.string.location_needed_for_walk)
    val requestLocation = LocalLocationPermissionRequest.current
    val scope = rememberCoroutineScope()
    val sensorManager = remember(context) { context.getSystemService(SensorManager::class.java) }
    val stepSensor = remember(sensorManager) { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }
    var activityAllowed by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    val activityPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        activityAllowed = it
        if (!it) message = activityPermissionNeeded
    }

    // Listen while this page is open; the hardware counter supplies the accumulated total.
    DisposableEffect(stepSensor, activityAllowed, store) {
        if (stepSensor == null || !activityAllowed) return@DisposableEffect onDispose { }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val total = event.values.firstOrNull()?.roundToInt() ?: return
                snapshot = store.recordSensorValue(total)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sensorManager.unregisterListener(listener) }
    }

    // Refresh the walk card whenever the background tracker saves a new point.
    DisposableEffect(store) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                walk = store.walk()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(FitnessWalkService.ACTION_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    LaunchedEffect(walk.active) {
        while (walk.active) {
            delay(1_000L)
            tick++
            walk = store.walk()
        }
    }

    BackHandler(onBack = onBack)
    FitnessScaffold(onBack) { padding ->
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).oliveScrollbar(listState)
                .testTag("fitness_tracker"),
            state = listState,
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { FitnessSummary(snapshot) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !activityAllowed) item {
                PermissionCard(
                    text = stringResource(R.string.activity_permission_needed),
                    button = stringResource(R.string.grant_activity_access),
                ) { activityPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION) }
            } else if (stepSensor == null) item {
                Text(stringResource(R.string.step_sensor_unavailable), color = Muted)
            }
            item {
                FitnessProfileCard(snapshot.profile) { updated ->
                    store.saveProfile(updated)
                    snapshot = store.snapshot()
                    message = fitnessSettingsSaved
                }
            }
            item {
                WalkTrackerCard(
                    walk = walk,
                    now = remember(tick) { System.currentTimeMillis() },
                    onStart = {
                        scope.launch {
                            if (requestLocation()) {
                                FitnessWalkService.start(context, accountId)
                                message = null
                            } else {
                                message = locationNeededForWalk
                            }
                        }
                    },
                    onStop = {
                        FitnessWalkService.stop(context, accountId)
                        walk = walk.copy(active = false)
                    },
                )
            }
            item {
                Text(
                    stringResource(R.string.seven_day_history),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(snapshot.history, key = { it.date.toString() }) { day ->
                FitnessHistoryRow(day, snapshot.profile.stepGoal)
            }
            item {
                Text(stringResource(R.string.calories_estimate_notice), color = Muted)
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FitnessScaffold(onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        containerColor = Cream,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.fitness_calculator)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Cream),
            )
        },
        content = content,
    )
}

@Composable
private fun FitnessSummary(snapshot: FitnessSnapshot) {
    val locale = LocalConfiguration.current.locales[0]
    val numberFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val distance = FitnessMath.distanceKm(snapshot.stepsToday, snapshot.profile.heightCm)
    val calories = FitnessMath.calories(snapshot.stepsToday, snapshot.profile)
    Card(colors = CardDefaults.cardColors(containerColor = Paper)) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Outlined.DirectionsWalk, null, tint = OliveGreen)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.steps_today), color = Muted)
                    Text(
                        numberFormat.format(snapshot.stepsToday),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            val progress = (snapshot.stepsToday.toFloat() / snapshot.profile.stepGoal)
                .coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                },
            )
            Text(stringResource(R.string.steps_goal_value, snapshot.profile.stepGoal))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FitnessMetric(
                    stringResource(R.string.distance_value_km, distance),
                    stringResource(R.string.distance),
                )
                FitnessMetric(
                    stringResource(R.string.calories_value, calories),
                    stringResource(R.string.calories),
                )
                FitnessMetric(
                    FitnessMath.activeMinutes(snapshot.stepsToday).toString(),
                    stringResource(R.string.active_minutes),
                )
            }
        }
    }
}

@Composable
private fun FitnessMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
    }
}

@Composable
private fun FitnessProfileCard(profile: FitnessProfile, onSave: (FitnessProfile) -> Unit) {
    var weight by remember(profile) { mutableStateOf(profile.weightKg.roundToInt().toString()) }
    var height by remember(profile) { mutableStateOf(profile.heightCm.roundToInt().toString()) }
    var goal by remember(profile) { mutableStateOf(profile.stepGoal.toString()) }
    Card(colors = CardDefaults.cardColors(containerColor = Paper)) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.fitness_settings), style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FitnessNumberField(weight, { weight = it }, R.string.weight_kg, Modifier.weight(1f))
                FitnessNumberField(height, { height = it }, R.string.height_cm, Modifier.weight(1f))
            }
            FitnessNumberField(goal, { goal = it }, R.string.daily_step_goal, Modifier.fillMaxWidth())
            Button(
                onClick = {
                    onSave(
                        FitnessProfile(
                            weight.toFloatOrNull() ?: profile.weightKg,
                            height.toFloatOrNull() ?: profile.heightCm,
                            goal.toIntOrNull() ?: profile.stepGoal,
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().testTag("save_fitness_settings"),
            ) { Text(stringResource(R.string.save)) }
        }
    }
}

@Composable
private fun FitnessNumberField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(6)) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun WalkTrackerCard(
    walk: FitnessWalkState,
    now: Long,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val elapsed = if (walk.active) (now - walk.startedAt).coerceAtLeast(0L) else 0L
    val speed = FitnessMath.speedKmh(walk.distanceMeters, elapsed)
    val pace = FitnessMath.paceMinutesPerKm(walk.distanceMeters, elapsed)
    Card(colors = CardDefaults.cardColors(containerColor = Paper)) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Route, null, tint = OliveGreen)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.walk_tracker), style = MaterialTheme.typography.titleLarge)
            }
            if (walk.active) {
                Text(stringResource(R.string.walk_tracking_active), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.walk_distance_value, walk.distanceMeters / 1_000f))
                Text(stringResource(R.string.walk_time_value, formatElapsed(elapsed)))
                Text(stringResource(R.string.walk_speed_value, speed))
                Text(stringResource(R.string.walk_pace_value, pace))
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth().testTag("stop_walk")) {
                    Text(stringResource(R.string.stop_walk))
                }
            } else {
                Text(stringResource(R.string.walk_tracker_description), color = Muted)
                Button(onClick = onStart, modifier = Modifier.fillMaxWidth().testTag("start_walk")) {
                    Text(stringResource(R.string.start_walk))
                }
            }
        }
    }
}

@Composable
private fun FitnessHistoryRow(day: FitnessDay, goal: Int) {
    val locale = LocalConfiguration.current.locales[0]
    val numberFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val date = remember(day.date) {
        day.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(date, modifier = Modifier.width(120.dp))
        LinearProgressIndicator(
            progress = { (day.steps.toFloat() / goal).coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Text(numberFormat.format(day.steps))
    }
}

@Composable
private fun PermissionCard(text: String, button: String, request: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text)
            Button(onClick = request) { Text(button) }
        }
    }
}

private fun formatElapsed(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    return "%02d:%02d:%02d".format(
        Locale.US,
        totalSeconds / 3_600L,
        (totalSeconds % 3_600L) / 60L,
        totalSeconds % 60L,
    )
}
