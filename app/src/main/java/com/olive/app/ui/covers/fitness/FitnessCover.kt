// Fitness cover users: tracks meals, macros, calories, and daily steps.
package com.olive.app.ui.covers.fitness

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.olive.app.R
import com.olive.app.ui.covers.CoverSettingsPage
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.shared.Cream
import com.olive.app.ui.shared.Ink
import com.olive.app.ui.shared.LightSage
import com.olive.app.ui.shared.Muted
import com.olive.app.ui.shared.OliveGreen
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.shared.PaleOlive
import com.olive.app.ui.shared.Paper
import com.olive.app.ui.shared.oliveScrollbar
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val FitnessBackground = Cream
private val FitnessSurface = Paper
private val FitnessAccent = OliveGreen
private val FitnessText = Ink
private val FitnessSubtle = Muted
private val FitnessHeroEnd = Color(0xFF6F8459)
private val FitnessWarmCard = Color(0xFFF1F3E9)

private enum class FitnessPage { HOME, ACTIVITY, VITALS, NUTRITION, FOOD_SEARCH, GOALS, ACCOUNT }

@Composable
internal fun FitnessCover(session: SessionViewModel, interactive: Boolean = true) {
    val context = LocalContext.current
    val accountId = session.coverAccountId ?: "guest"
    val store = remember(context, accountId) { FitnessStore(context, accountId) }
    var snapshot by remember(store) { mutableStateOf(store.snapshot()) }
    val history = remember(store, snapshot) { store.history(days = 14) }
    var page by remember { mutableStateOf(FitnessPage.HOME) }

    FitnessStepSensor(store) { snapshot = it }
    LaunchedEffect(store) {
        while (true) {
            delay(2_000L)
            snapshot = store.snapshot()
        }
    }
    LaunchedEffect(snapshot) {
        FitnessWatchSync.publish(context, accountId, snapshot)
    }
    LaunchedEffect(session.coverReset) {
        snapshot = store.snapshot()
        page = FitnessPage.HOME
    }

    if (page == FitnessPage.ACCOUNT) {
        CoverSettingsPage(session) { page = FitnessPage.HOME }
        return
    }

    OliveTheme {
        Column(
            Modifier.fillMaxSize().background(FitnessBackground)
                .windowInsetsPadding(WindowInsets.statusBars).padding(top = 8.dp),
        ) {
            FitnessHeader(
                page = page,
                enabled = interactive,
                onPage = { page = it },
                onSettings = { page = FitnessPage.ACCOUNT },
            )
            when (page) {
                FitnessPage.HOME -> FitnessHomePage(
                    snapshot = snapshot,
                    onGoals = { page = FitnessPage.GOALS },
                )
                FitnessPage.ACTIVITY -> FitnessActivityPage(snapshot, history)
                FitnessPage.VITALS -> FitnessVitalsPage(snapshot)
                FitnessPage.NUTRITION -> TodayPage(
                    snapshot = snapshot,
                    enabled = interactive,
                    padding = PaddingValues(0.dp),
                    onAddFood = { page = FitnessPage.FOOD_SEARCH },
                    onRemoveFood = { snapshot = store.removeFood(it) },
                )
                FitnessPage.FOOD_SEARCH -> FoodSearchPage(
                    enabled = interactive,
                    padding = PaddingValues(0.dp),
                    onFoodAdded = { food, servings, meal ->
                        snapshot = store.addFood(food, servings, meal)
                        page = FitnessPage.NUTRITION
                    },
                )
                FitnessPage.GOALS -> FitnessGoalsPage(
                    profile = snapshot.profile,
                    enabled = interactive,
                    padding = PaddingValues(0.dp),
                    onSave = {
                        store.saveProfile(it)
                        snapshot = store.snapshot()
                        page = FitnessPage.HOME
                    },
                )
                FitnessPage.ACCOUNT -> Unit
            }
        }
    }
}

@Composable
private fun FitnessHeader(
    page: FitnessPage,
    enabled: Boolean,
    onPage: (FitnessPage) -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.fitness_tracker),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(PaleOlive),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(
                    onClick = onSettings,
                    enabled = enabled,
                    modifier = Modifier.testTag("fitness_account_settings"),
                ) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings)) }
            }
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(LightSage).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FitnessTab(
                Modifier.weight(1f),
                stringResource(R.string.home),
                page == FitnessPage.HOME,
                enabled,
                { onPage(FitnessPage.HOME) },
            ) { Icon(Icons.Outlined.Home, null) }
            FitnessTab(
                Modifier.weight(1f),
                stringResource(R.string.activity),
                page == FitnessPage.ACTIVITY,
                enabled,
                { onPage(FitnessPage.ACTIVITY) },
            ) { Icon(Icons.AutoMirrored.Outlined.DirectionsWalk, null) }
            FitnessTab(
                Modifier.weight(1f),
                stringResource(R.string.fitness_vitals),
                page == FitnessPage.VITALS,
                enabled,
                { onPage(FitnessPage.VITALS) },
            ) { Icon(Icons.Outlined.FavoriteBorder, null) }
            FitnessTab(
                Modifier.weight(1f),
                stringResource(R.string.nutrition),
                page == FitnessPage.NUTRITION || page == FitnessPage.FOOD_SEARCH,
                enabled,
                { onPage(FitnessPage.NUTRITION) },
            ) { Icon(Icons.Outlined.Restaurant, null) }
        }
    }
}

@Composable
private fun FitnessTab(
    modifier: Modifier,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Column(
        modifier.clip(RoundedCornerShape(24.dp))
            .background(if (selected) OliveGreen else Color.Transparent)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick,
            ).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (selected) Paper else OliveGreen
        ) {
            icon()
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun FitnessStepSensor(store: FitnessStore, onUpdate: (FitnessSnapshot) -> Unit) {
    val context = LocalContext.current
    val sensorManager = remember(context) { context.getSystemService(SensorManager::class.java) }
    val stepSensor = remember(sensorManager) { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }
    var permissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACTIVITY_RECOGNITION,
                    ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissionGranted = it }

    LaunchedEffect(Unit) {
        if (!permissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }
    DisposableEffect(stepSensor, permissionGranted, store) {
        if (stepSensor == null || !permissionGranted) return@DisposableEffect onDispose { }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                event.values.firstOrNull()?.roundToInt()?.let {
                    onUpdate(store.recordSensorValue(it))
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sensorManager.unregisterListener(listener) }
    }
}

@Composable
private fun FitnessHomePage(snapshot: FitnessSnapshot, onGoals: () -> Unit) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().oliveScrollbar(listState).testTag("fitness_home"),
        state = listState,
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                stringResource(R.string.fitness_home_message),
                color = FitnessSubtle,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(FitnessAccent, FitnessHeroEnd),
                        )
                    ).padding(22.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        stringResource(R.string.today_summary),
                        style = MaterialTheme.typography.titleMedium,
                        color = Paper.copy(alpha = 0.82f),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        HeroMetric(snapshot.stepsToday.toString(), stringResource(R.string.steps_today))
                        HeroMetric(snapshot.nutrition.calories.displayAmount(), stringResource(R.string.calories))
                        HeroMetric("${snapshot.nutrition.proteinGrams.displayAmount()} g", stringResource(R.string.protein))
                    }
                }
            }
        }
        item { StepSummaryCard(snapshot) }
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = FitnessWarmCard,
                    contentColor = FitnessText,
                ),
                modifier = Modifier.fillMaxWidth().clickable(onClick = onGoals),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.goals), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.edit_fitness_goals), color = FitnessSubtle)
                    }
                    Icon(Icons.Outlined.Settings, null, tint = FitnessAccent)
                }
            }
        }
    }
}

@Composable
private fun RowScope.HeroMetric(value: String, label: String) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
            .background(Paper.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(value, color = Paper, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, color = Paper.copy(alpha = 0.78f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun FitnessActivityPage(
    snapshot: FitnessSnapshot,
    history: List<FitnessHistoryDay>,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().oliveScrollbar(listState).testTag("fitness_activity"),
        state = listState,
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    stringResource(R.string.activity),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.activity_message),
                    color = FitnessSubtle,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item { FitnessActivityDashboard(snapshot) }
        item { FitnessHistoryCharts(history) }
    }
}

@Composable
private fun TodayPage(
    snapshot: FitnessSnapshot,
    enabled: Boolean,
    padding: PaddingValues,
    onAddFood: () -> Unit,
    onRemoveFood: (String) -> Unit,
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).oliveScrollbar(listState)
            .testTag("fitness_today"),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.nutrition),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.daily_nutrition),
                    color = FitnessSubtle,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item { DailyNutritionCard(snapshot) }
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.meals), style = MaterialTheme.typography.titleLarge)
                Button(onClick = onAddFood, enabled = enabled) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.add_food))
                }
            }
        }
        MealType.entries.forEach { meal ->
            val entries = snapshot.foodsToday.filter { it.meal == meal }
            item { Text(meal.displayName, fontWeight = FontWeight.SemiBold) }
            if (entries.isEmpty()) {
                item { Text(stringResource(R.string.no_food_logged), color = FitnessSubtle) }
            } else {
                items(entries, key = FoodLogEntry::logId) { entry ->
                    LoggedFoodRow(entry, enabled) { onRemoveFood(entry.logId) }
                }
            }
        }
        item { ProviderAttribution() }
    }
}

@Composable
private fun DailyNutritionCard(snapshot: FitnessSnapshot) {
    val totals = snapshot.nutrition
    val profile = snapshot.profile
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = FitnessWarmCard),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        totals.calories.displayAmount(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(stringResource(R.string.calories), color = FitnessSubtle)
                }
                Text(
                    "${profile.calorieGoal} kcal · ${stringResource(R.string.calorie_goal)}",
                    color = FitnessSubtle,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            val calorieProgress = (
                totals.calories / profile.calorieGoal.coerceAtLeast(1).toFloat()
            ).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { calorieProgress },
                modifier = Modifier.fillMaxWidth().height(9.dp).clip(CircleShape).semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(calorieProgress, 0f..1f)
                },
            )
            NutritionProgress(
                stringResource(R.string.protein),
                totals.proteinGrams,
                profile.proteinGoal.toFloat(),
                "g",
            )
            NutritionProgress(
                stringResource(R.string.carbohydrates),
                totals.carbohydrateGrams,
                profile.carbohydrateGoal.toFloat(),
                "g",
            )
            NutritionProgress(
                stringResource(R.string.fat),
                totals.fatGrams,
                profile.fatGoal.toFloat(),
                "g",
            )
            HorizontalDivider()
            NutritionAmountRow(stringResource(R.string.saturated_fat), totals.saturatedFatGrams, "g")
            NutritionAmountRow(stringResource(R.string.trans_fat), totals.transFatGrams, "g")
            NutritionAmountRow(stringResource(R.string.fiber), totals.fiberGrams, "g")
            NutritionAmountRow(stringResource(R.string.sugar), totals.sugarGrams, "g")
            NutritionAmountRow(stringResource(R.string.sodium), totals.sodiumMilligrams, "mg")
            NutritionAmountRow(
                stringResource(R.string.cholesterol),
                totals.cholesterolMilligrams,
                "mg",
            )
            NutritionAmountRow(stringResource(R.string.potassium), totals.potassiumMilligrams, "mg")
        }
    }
}

@Composable
private fun NutritionAmountRow(label: String, value: Float, unit: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text("${value.displayAmount()} $unit")
    }
}

@Composable
private fun NutritionProgress(label: String, value: Float, goal: Float, unit: String) {
    val progress = (value / goal.coerceAtLeast(1f)).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text("${value.displayAmount()} / ${goal.displayAmount()} $unit")
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
            },
        )
    }
}

@Composable
private fun StepSummaryCard(snapshot: FitnessSnapshot) {
    val progress = (snapshot.stepsToday.toFloat() / snapshot.profile.stepGoal).coerceIn(0f, 1f)
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = FitnessSurface),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(PaleOlive),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.DirectionsWalk, null, tint = FitnessAccent)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.steps_today), color = FitnessSubtle)
                        Text(
                            snapshot.stepsToday.toString(),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${(progress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(stringResource(R.string.goals), color = FitnessSubtle)
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActivityMiniMetric(
                    value = snapshot.profile.stepGoal.toString(),
                    label = stringResource(R.string.daily_step_goal),
                )
                ActivityMiniMetric(
                    value = "${FitnessMath.distanceKm(snapshot.stepsToday, snapshot.profile.heightCm).displayAmount()} km",
                    label = stringResource(R.string.distance),
                )
                ActivityMiniMetric(
                    value = "${FitnessMath.walkingCalories(snapshot.stepsToday, snapshot.profile).displayAmount()} kcal",
                    label = stringResource(R.string.active_calories),
                )
            }
        }
    }
}

@Composable
private fun RowScope.ActivityMiniMetric(value: String, label: String) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(FitnessWarmCard)
            .semantics(mergeDescendants = true) {
                contentDescription = "$label, $value"
            }.padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, color = FitnessText, fontWeight = FontWeight.Bold)
        Text(label, color = FitnessSubtle, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun LoggedFoodRow(entry: FoodLogEntry, enabled: Boolean, onRemove: () -> Unit) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = FitnessSurface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.food.name, fontWeight = FontWeight.SemiBold)
                Text(
                    "${entry.servings.displayAmount()} × ${entry.food.servingDescription} · " +
                            "${(entry.food.calories * entry.servings).displayAmount()} kcal",
                    color = FitnessSubtle,
                )
            }
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(Icons.Outlined.Delete, stringResource(R.string.remove))
            }
        }
    }
}

@Composable
private fun FoodSearchPage(
    enabled: Boolean,
    padding: PaddingValues,
    onFoodAdded: (NutritionFood, Float, MealType) -> Unit,
) {
    val repository = remember { FoodNutritionRepository() }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf(FoodSearchResult()) }
    var searched by remember { mutableStateOf(false) }
    var selectedFood by remember { mutableStateOf<NutritionFood?>(null) }

    Column(
        Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.find_food), style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(80) },
                label = { Text(stringResource(R.string.search_foods)) },
                singleLine = true,
                enabled = enabled && !loading,
                modifier = Modifier.weight(1f).testTag("fitness_food_search"),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    loading = true
                    searched = true
                    scope.launch {
                        result = repository.search(query)
                        loading = false
                    }
                },
                enabled = enabled && !loading && query.trim().length >= 2,
            ) {
                Icon(Icons.Outlined.Search, stringResource(R.string.search))
            }
        }
        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (searched && result.foods.isEmpty()) {
            val message = if (result.providerFailures >= 2) {
                R.string.food_search_unavailable
            } else {
                R.string.no_food_results
            }
            Text(stringResource(message))
        }
        if (result.providerFailures == 1) {
            Text(stringResource(R.string.some_food_sources_unavailable), color = FitnessSubtle)
        }
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyColumn(
            modifier = Modifier.weight(1f).oliveScrollbar(listState),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(result.foods, key = NutritionFood::id) { food ->
                FoodSearchResultCard(food, enabled) { selectedFood = food }
            }
        }
        ProviderAttribution()
    }

    selectedFood?.let { food ->
        AddFoodDialog(
            food = food,
            onDismiss = { selectedFood = null },
            onAdd = { servings, meal ->
                selectedFood = null
                onFoodAdded(food, servings, meal)
            },
        )
    }
}

@Composable
private fun FoodSearchResultCard(food: NutritionFood, enabled: Boolean, onAdd: () -> Unit) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = FitnessSurface),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(food.name, fontWeight = FontWeight.SemiBold)
            if (food.brand.isNotBlank()) Text(food.brand, color = FitnessSubtle)
            Text(
                "${food.servingDescription} · ${food.calories.displayAmount()} kcal · " +
                        "P ${food.proteinGrams.displayAmount()} g · " +
                        "C ${food.carbohydrateGrams.displayAmount()} g · " +
                        "F ${food.fatGrams.displayAmount()} g",
            )
            Text(
                "${stringResource(R.string.fiber)} ${food.fiberGrams.displayAmount()} g · " +
                        "${stringResource(R.string.sugar)} ${food.sugarGrams.displayAmount()} g · " +
                        "${stringResource(R.string.saturated_fat)} " +
                        "${food.saturatedFatGrams.displayAmount()} g",
                style = MaterialTheme.typography.bodySmall,
                color = FitnessSubtle,
            )
            Text(
                "${stringResource(R.string.sodium)} ${food.sodiumMilligrams.displayAmount()} mg · " +
                        "${stringResource(R.string.cholesterol)} " +
                        "${food.cholesterolMilligrams.displayAmount()} mg · " +
                        "${stringResource(R.string.potassium)} " +
                        "${food.potassiumMilligrams.displayAmount()} mg",
                style = MaterialTheme.typography.bodySmall,
                color = FitnessSubtle,
            )
            Text(
                food.source.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = FitnessSubtle,
            )
            OutlinedButton(onClick = onAdd, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.add_to_diary))
            }
        }
    }
}

@Composable
private fun AddFoodDialog(
    food: NutritionFood,
    onDismiss: () -> Unit,
    onAdd: (Float, MealType) -> Unit,
) {
    var servings by remember { mutableStateOf("1") }
    var meal by remember { mutableStateOf(MealType.BREAKFAST) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(food.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${food.servingDescription} · ${food.calories.displayAmount()} kcal · " +
                            "P ${food.proteinGrams.displayAmount()} g · " +
                            "C ${food.carbohydrateGrams.displayAmount()} g · " +
                            "F ${food.fatGrams.displayAmount()} g",
                )
                OutlinedTextField(
                    value = servings,
                    onValueChange = { value ->
                        servings = value.filter { it.isDigit() || it == '.' }.take(6)
                    },
                    label = { Text(stringResource(R.string.servings)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                MealType.entries.forEach { option ->
                    OutlinedButton(
                        onClick = { meal = option },
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = if (meal == option) {
                                "${option.displayName}, selected"
                            } else {
                                option.displayName
                            }
                        },
                    ) { Text(option.displayName) }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(servings.toFloatOrNull() ?: 1f, meal) },
                enabled = (servings.toFloatOrNull() ?: 0f) > 0f,
            ) { Text(stringResource(R.string.add_food)) }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun FitnessGoalsPage(
    profile: FitnessProfile,
    enabled: Boolean,
    padding: PaddingValues,
    onSave: (FitnessProfile) -> Unit,
) {
    var weight by remember(profile) { mutableStateOf(profile.weightKg.roundToInt().toString()) }
    var height by remember(profile) { mutableStateOf(profile.heightCm.roundToInt().toString()) }
    var steps by remember(profile) { mutableStateOf(profile.stepGoal.toString()) }
    var calories by remember(profile) { mutableStateOf(profile.calorieGoal.toString()) }
    var protein by remember(profile) { mutableStateOf(profile.proteinGoal.toString()) }
    var carbohydrates by remember(profile) { mutableStateOf(profile.carbohydrateGoal.toString()) }
    var fat by remember(profile) { mutableStateOf(profile.fatGoal.toString()) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().padding(padding).oliveScrollbar(listState),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text(stringResource(R.string.fitness_goals), style = MaterialTheme.typography.headlineSmall) }
        item { GoalField(weight, { weight = it }, stringResource(R.string.weight_kg), enabled) }
        item { GoalField(height, { height = it }, stringResource(R.string.height_cm), enabled) }
        item { GoalField(steps, { steps = it }, stringResource(R.string.daily_step_goal), enabled) }
        item { HorizontalDivider() }
        item { GoalField(calories, { calories = it }, stringResource(R.string.calorie_goal), enabled) }
        item { GoalField(protein, { protein = it }, stringResource(R.string.protein_goal), enabled) }
        item {
            GoalField(
                carbohydrates,
                { carbohydrates = it },
                stringResource(R.string.carbohydrate_goal),
                enabled,
            )
        }
        item { GoalField(fat, { fat = it }, stringResource(R.string.fat_goal), enabled) }
        item {
            Button(
                onClick = {
                    onSave(
                        FitnessProfile(
                            weightKg = weight.toFloatOrNull() ?: profile.weightKg,
                            heightCm = height.toFloatOrNull() ?: profile.heightCm,
                            stepGoal = steps.toIntOrNull() ?: profile.stepGoal,
                            calorieGoal = calories.toIntOrNull() ?: profile.calorieGoal,
                            proteinGoal = protein.toIntOrNull() ?: profile.proteinGoal,
                            carbohydrateGoal = carbohydrates.toIntOrNull()
                                ?: profile.carbohydrateGoal,
                            fatGoal = fat.toIntOrNull() ?: profile.fatGoal,
                        )
                    )
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("save_fitness_goals"),
            ) { Text(stringResource(R.string.save)) }
        }
    }
}

@Composable
private fun GoalField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter(Char::isDigit).take(6)) },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProviderAttribution() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.fatsecret_attribution),
            style = MaterialTheme.typography.labelSmall,
            color = FitnessSubtle,
        )
        Text(
            stringResource(R.string.open_food_facts_attribution),
            style = MaterialTheme.typography.labelSmall,
            color = FitnessSubtle,
        )
    }
}
