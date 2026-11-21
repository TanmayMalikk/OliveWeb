// Fitness cover users: tracks meals, macros, calories, and daily steps.
package com.olive.app.ui.covers.fitness

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveIconButton as IconButton
import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.ripple
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.olive.app.R
import com.olive.app.ui.covers.CoverSettingsPage
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import android.content.Context
import androidx.core.content.edit
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlin.time.Duration.Companion.milliseconds
import java.time.LocalDate


private enum class FitnessPage { HOME, ACTIVITY, VITALS, NUTRITION, COACH, FOOD_SEARCH, GOALS, ACCOUNT }

/**
 * Shows the fitness cover and keeps its selected page and saved health data in sync.
 *
 * @param session Shared session view model owning application state and actions.
 * @param interactive Whether to apply interactive.
 */
@Composable
internal fun FitnessCover(session: SessionViewModel, interactive: Boolean = true) {
    val context = LocalContext.current
    val accountId = session.coverAccountId ?: "guest"
    val store = remember(context, accountId) { FitnessStore(context, accountId) }
    val foodLibrary = remember(store) { FitnessFoodLibrary(store.preferences) }
    var loaded by remember(store) { mutableStateOf<Pair<FitnessSnapshot, List<FitnessHistoryDay>>?>(null) }
    var pendingSnapshot by remember(store) { mutableStateOf<FitnessSnapshot?>(null) }
    val snapshot = pendingSnapshot ?: loaded?.first
    val history = loaded?.second.orEmpty()
    var page by rememberSaveable(accountId) { mutableStateOf(FitnessPage.HOME) }
    var foodSection by rememberSaveable(accountId) { mutableStateOf(FoodLibrarySection.Search) }
    var foodMeal by rememberSaveable(accountId) { mutableStateOf<MealType?>(null) }
    val snackbars = remember(store) { SnackbarHostState() }
    var removedFood by remember(store) { mutableStateOf<Pair<LocalDate, FoodLogEntry>?>(null) }
    val removedMessage = stringResource(R.string.fitness_diary_food_removed)
    val undoLabel = stringResource(R.string.fitness_diary_undo)
    LaunchedEffect(store, removedFood, interactive, page) {
        val removal = removedFood ?: return@LaunchedEffect
        if (interactive && page == FitnessPage.NUTRITION && snackbars.showSnackbar(
                removedMessage, actionLabel = undoLabel, withDismissAction = true,
                duration = SnackbarDuration.Long,
            ) == SnackbarResult.ActionPerformed
        ) {
            store.restoreFood(removal.second, removal.first)
            pendingSnapshot = store.snapshot()
        }
        removedFood = null
    }
    val pageFocus = remember { FocusRequester() }
    val pageLabel = when (page) {
        FitnessPage.HOME -> stringResource(R.string.home)
        FitnessPage.ACTIVITY -> stringResource(R.string.activity)
        FitnessPage.VITALS -> stringResource(R.string.fitness_vitals)
        FitnessPage.NUTRITION -> stringResource(R.string.nutrition)
        FitnessPage.COACH -> stringResource(R.string.fitness_coach_tab)
        FitnessPage.FOOD_SEARCH -> stringResource(R.string.find_food)
        FitnessPage.GOALS -> stringResource(R.string.fitness_goals)
        FitnessPage.ACCOUNT -> stringResource(R.string.settings)
    }

    var sensorPermissionSettled by remember(accountId) { mutableStateOf(false) }
    val ready by FitnessAccountSync.ready.collectAsState()
    val cloudFailed by FitnessAccountSync.failed.collectAsState()
    if (session.localMode || accountId == "guest" || ready) FitnessStepSensor(
        store, interactive, onPermissionSettled = { sensorPermissionSettled = true },
    ) { pendingSnapshot = it }
    DisposableEffect(store) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            pendingSnapshot = store.snapshot()
            loaded = pendingSnapshot!! to store.history(days = 14)
        }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { store.preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    LaunchedEffect(store) {
        var date = java.time.LocalDate.now()
        while (true) {
            if (java.time.LocalDate.now() != date) {
                pendingSnapshot = null
                date = java.time.LocalDate.now()
            }
            loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.snapshot() to store.history(days = 14) }
            val nextDay = java.time.LocalDate.now().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()
            delay(java.time.Duration.between(java.time.Instant.now(), nextDay).toMillis().coerceAtLeast(1000L).milliseconds)
        }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(accountId, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            while (true) {
                if (accountId != "guest") try {
                    FitnessHealthConnect.read(context, accountId)
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                  catch (_: Exception) { /* Permission/provider changes are retried on the next foreground read. */ }
                delay(15_000.milliseconds)
            }
        }
    }
    LaunchedEffect(snapshot, ready) {
        snapshot ?: return@LaunchedEffect
        if (!session.localMode && accountId != "guest" && !ready) return@LaunchedEffect
        FitnessWatchSync.publish(context, accountId, snapshot)
        if (!session.localMode) {
            FitnessCloudSync.upload(accountId, snapshot, history)
        }
    }
    // Back returns from a fitness submenu or section before it is allowed to leave the cover app.
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = interactive) { progress ->
        val currentPage = page
        val destination = when (currentPage) {
            FitnessPage.FOOD_SEARCH -> FitnessPage.NUTRITION
            FitnessPage.HOME -> FitnessPage.HOME
            else -> FitnessPage.HOME
        }
        try {
            progress.collect { if (destination != currentPage) backProgress = it.progress }
            if (page == currentPage) page = destination
        } finally {
            backProgress = 0f
        }
    }
    // Announce the new fitness page from its heading after a navigation tab is selected.
    // coverReset changes whenever Quick Exit brings the already-composed cover back on screen.
    // Include it so HOME receives focus even when its page enum was already HOME and therefore
    // would not restart a page-only effect.
    LaunchedEffect(page, session.coverReset) {
        if (page !in setOf(FitnessPage.ACCOUNT, FitnessPage.GOALS, FitnessPage.FOOD_SEARCH)) {
            withFrameNanos { }
            pageFocus.requestFocus()
        }
    }

    if (page == FitnessPage.ACCOUNT) {
        CoverSettingsPage(session) { page = FitnessPage.HOME }
        return
    }

    OliveTheme {
        Box(
            Modifier.fillMaxSize().background(FitnessBackground)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Column(
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = 1f - backProgress * 0.045f
                    scaleY = scaleX
                }.padding(top = 8.dp),
            ) {
                FitnessHeader(
                    page = page,
                    pageLabel = pageLabel,
                    pageFocus = pageFocus,
                    enabled = interactive,
                    onPage = { page = it },
                    onSettings = { page = FitnessPage.ACCOUNT },
                )
                // Opens the selected fitness overview, activity, nutrition, or vitals page.
                FitnessHealthConnection(accountId, interactive && sensorPermissionSettled)
                if (cloudFailed) Text(stringResource(R.string.fitness_sync_pending), Modifier.padding(horizontal = 16.dp))
                val dataLoading = snapshot == null || loaded == null ||
                    (!session.localMode && accountId != "guest" && !ready)
                if (page == FitnessPage.COACH) {
                    if (interactive) androidx.compose.runtime.key(accountId) {
                        FitnessCoachPage(
                            accountId, !session.localMode && accountId != "guest", store, dataLoading,
                        ) {
                            pendingSnapshot = it
                        }
                    }
                } else if (dataLoading) {
                    val skeleton = when (page) {
                        FitnessPage.HOME -> SkeletonLayout.FitnessSummary
                        FitnessPage.ACTIVITY -> SkeletonLayout.FitnessActivity
                        FitnessPage.VITALS -> SkeletonLayout.FitnessVitals
                        FitnessPage.NUTRITION -> SkeletonLayout.FitnessNutrition
                        FitnessPage.FOOD_SEARCH -> SkeletonLayout.FoodResults
                        FitnessPage.GOALS, FitnessPage.ACCOUNT, FitnessPage.COACH -> SkeletonLayout.Form
                    }
                    LoadingSkeleton(skeleton, Modifier.fillMaxWidth().padding(16.dp))
                } else Crossfade(
                    targetState = page, animationSpec = tween(180), label = "Fitness view",
                ) { visiblePage ->
                    val pageEnabled = interactive && page == visiblePage
                    when (visiblePage) {
                        FitnessPage.HOME -> FitnessHomePage(snapshot, onGoals = { page = FitnessPage.GOALS })
                        FitnessPage.ACTIVITY -> FitnessActivityPage(
                            snapshot = snapshot,
                            history = history,
                            accountId = accountId,
                            enabled = pageEnabled,
                        )
                        FitnessPage.VITALS -> FitnessVitalsPage(snapshot)
                        FitnessPage.NUTRITION -> TodayPage(
                            snapshot = snapshot,
                            enabled = pageEnabled,
                            padding = PaddingValues(0.dp),
                            onAddFood = {
                                foodMeal = null
                                foodSection = FoodLibrarySection.Search
                                page = FitnessPage.FOOD_SEARCH
                            },
                            onRemoveFood = { logId ->
                                val date = LocalDate.now()
                                val entry = store.snapshot(date).foodsToday.firstOrNull { it.logId == logId }
                                if (entry != null) {
                                    pendingSnapshot = store.removeFood(logId, date)
                                    removedFood = date to entry
                                }
                            },
                            onAddWater = { amount, goal -> pendingSnapshot = store.addWater(amount, goal) },
                            onUpdateFood = { pendingSnapshot = store.updateFood(it) },
                            library = foodLibrary,
                            onOpenLibrary = { foodMeal = null; foodSection = it; page = FitnessPage.FOOD_SEARCH },
                            onLogMeal = {
                                foodMeal = it
                                foodSection = FoodLibrarySection.Search
                                page = FitnessPage.FOOD_SEARCH
                            },
                        )
                        FitnessPage.FOOD_SEARCH -> FoodSearchPage(
                            enabled = pageEnabled,
                            padding = PaddingValues(0.dp),
                            library = foodLibrary,
                            initialSection = foodSection,
                            initialMeal = foodMeal,
                            recentFoods = store.recentFoods(),
                            onFoodAdded = { food, servings, meal, logId ->
                                pendingSnapshot = store.addFood(food, servings, meal, logId = logId)
                                page = FitnessPage.NUTRITION
                            },
                        )
                        FitnessPage.GOALS -> FitnessGoalsPage(
                            profile = snapshot.profile,
                            enabled = pageEnabled,
                            padding = PaddingValues(0.dp),
                            onSave = {
                                store.saveProfile(it)
                                pendingSnapshot = store.snapshot()
                                page = FitnessPage.HOME
                            },
                        )
                        FitnessPage.ACCOUNT, FitnessPage.COACH -> Unit
                    }
                }
            }
            if (interactive && page == FitnessPage.NUTRITION) {
                SnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).padding(12.dp))
            }
        }
    }
}
private val FitnessTabBarShape = RoundedCornerShape(28.dp)
private val FitnessTabShape = RoundedCornerShape(24.dp)

/**
 * Draws the fitness title and top navigation.
 *
 * @param page Page supplied to [FitnessHeader].
 * @param pageLabel Page label supplied to [FitnessHeader].
 * @param pageFocus Page focus supplied to [FitnessHeader].
 * @param enabled Whether this feature or control is currently enabled.
 * @param onPage Callback invoked when page changes or occurs.
 * @param onSettings Callback invoked when settings changes or occurs.
 */
@Composable
private fun FitnessHeader(
    page: FitnessPage,
    pageLabel: String,
    pageFocus: FocusRequester,
    enabled: Boolean,
    onPage: (FitnessPage) -> Unit,
    onSettings: () -> Unit,
) {
    val trackerLabel = stringResource(R.string.fitness_tracker)
    Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                trackerLabel,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("fitness_page_title").focusRequester(pageFocus).focusable().semantics {
                    heading()
                    paneTitle = pageLabel
                    contentDescription = "$pageLabel, $trackerLabel"
                },
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
            Modifier.fillMaxWidth().clip(FitnessTabBarShape)
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
            FitnessTab(
                Modifier.weight(1f),
                stringResource(R.string.fitness_coach_tab),
                page == FitnessPage.COACH,
                enabled,
                { onPage(FitnessPage.COACH) },
            ) { Icon(Icons.Outlined.AutoAwesome, null) }
        }
    }
}

/**
 * Draws one fitness navigation tab and marks it selected when active.
 *
 * @param modifier Compose modifier applied to the root layout.
 * @param label Accessible label or display caption.
 * @param selected Whether to apply selected.
 * @param enabled Whether this feature or control is currently enabled.
 * @param onClick Callback invoked when the control is activated.
 * @param icon Action or callback executed for icon.
 */
@Composable
private fun FitnessTab(
    modifier: Modifier,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val source = rememberOliveInteraction()
    Column(
        modifier.olivePress(source, enabled)
            .shadow(if (selected && enabled) 2.dp else 0.dp, FitnessTabShape)
            .clip(FitnessTabShape)
            .background(if (selected) Paper else Color.Transparent)
            .selectable(
                selected = selected,
                interactionSource = source,
                indication = ripple(),
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick,
            ).clearAndSetSemantics {
                contentDescription = label
                role = Role.Tab
                this.selected = selected
                onClick(label) {
                    onClick()
                    true
                }
            }.padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides if (selected) DarkOlive else Muted
        ) {
            icon()
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center)
        }
    }
}

/**
 * Starts and stops the phone step sensor with the screen lifecycle.
 *
 * @param store Store supplied to [FitnessStepSensor].
 * @param enabled Whether this feature or control is currently enabled.
 * @param onPermissionSettled Callback invoked when permission settled changes or occurs.
 * @param onUpdate Callback invoked when updated data is available.
 */
@Composable
private fun FitnessStepSensor(store: FitnessStore, enabled: Boolean, onPermissionSettled: () -> Unit, onUpdate: (FitnessSnapshot) -> Unit) {
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
    ) { permissionGranted = it; onPermissionSettled() }

    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        if (!permissionGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else onPermissionSettled()
    }
    // Start or stop the phone step sensor as permission and lifecycle change.
    DisposableEffect(stepSensor, permissionGranted, store) {
        // Stop listening when the phone has no step sensor or activity permission.
        if (stepSensor == null || !permissionGranted) return@DisposableEffect onDispose { }
        val listener = object : SensorEventListener {
            /**
             * Adds new step-counter readings to today's fitness total.
             *
             * @param event Sensor, UI, or lifecycle event being processed.
             */
            override fun onSensorChanged(event: SensorEvent) {
                event.values.firstOrNull()?.roundToInt()?.let {
                    onUpdate(store.recordSensorValue(it))
                }
            }

            /**
             * The step counter does not need accuracy changes, so this callback stays empty.
             *
             * @param sensor Optional sensor supplied to [onAccuracyChanged].
             * @param accuracy Accuracy supplied to [onAccuracyChanged].
             */
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sensorManager.unregisterListener(listener) }
    }
}

/**
 * Draws the fitness activity page for the fitness cover.
 *
 * @param snapshot Firestore or data snapshot containing the latest state.
 * @param history Collection of history values to process.
 * @param accountId Account ID supplied to [FitnessActivityPage].
 * @param enabled Whether this feature or control is currently enabled.
 */
@Composable
private fun FitnessActivityPage(
    snapshot: FitnessSnapshot,
    history: List<FitnessHistoryDay>,
    accountId: String,
    enabled: Boolean,
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
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(R.string.activity_message),
                    color = FitnessSubtle,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item { FitnessActivityDashboard(snapshot) }
        item { FitnessOutdoorWorkoutCard(accountId, enabled) }
        item { FitnessHistoryCharts(history) }
    }
}

/**
 * Fitness cover shows this fitness goals page view so the user can view its details and use its actions.
 *
 * @param profile Account profile details.
 * @param enabled Whether this feature or control is currently enabled.
 * @param padding Padding supplied to [FitnessGoalsPage].
 * @param onSave Callback invoked when save changes or occurs.
 */
@Composable
private fun FitnessGoalsPage(
    profile: FitnessProfile,
    enabled: Boolean,
    padding: PaddingValues,
    onSave: (FitnessProfile) -> Unit,
) {
    val headingFocus = remember { FocusRequester() }
    var weight by remember(profile) { mutableStateOf(profile.weightKg.roundToInt().toString()) }
    var height by remember(profile) { mutableStateOf(profile.heightCm.roundToInt().toString()) }
    var steps by remember(profile) { mutableStateOf(profile.stepGoal.toString()) }
    var calories by remember(profile) { mutableStateOf(profile.calorieGoal.toString()) }
    var protein by remember(profile) { mutableStateOf(profile.proteinGoal.toString()) }
    var carbohydrates by remember(profile) { mutableStateOf(profile.carbohydrateGoal.toString()) }
    var fat by remember(profile) { mutableStateOf(profile.fatGoal.toString()) }
    // Move TalkBack to the goals heading after the submenu opens.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        headingFocus.requestFocus()
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().padding(padding).oliveScrollbar(listState),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                stringResource(R.string.fitness_goals),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.focusRequester(headingFocus).focusable().semantics { heading() },
            )
        }
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
                            waterGoalMl = profile.waterGoalMl,
                        )
                    )
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().testTag("save_fitness_goals"),
            ) { Text(stringResource(R.string.save)) }
        }
    }
}

/**
 * Draws the goal field for the fitness cover.
 *
 * @param value Input value to store, parse, or format.
 * @param onChange Callback invoked when the value or observed state changes.
 * @param label Accessible label or display caption.
 * @param enabled Whether this feature or control is currently enabled.
 */
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

// Fitness cover users: sends today's goals and nutrition totals to a paired Wear OS watch.

internal object FitnessWatchSync {
    private var lastPublished = ""
    const val DAILY_DATA_PATH = "/olive/fitness/daily"
    const val WATCH_ACTIVITY_PATH = "/olive/fitness/watch-activity"
    const val ACCOUNT_SESSION_PATH = "/olive/account/session"
    const val SYNC_PREFERENCES = "fitness_watch_sync"
    const val ACTIVE_ACCOUNT_ID = "active_account_id"
    const val SESSION_ID = "sessionId"

    /**
     * Tells the paired watch which phone account owns future fitness readings.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param accountId Optional account ID supplied to [bindAccount].
     */
    fun bindAccount(context: Context, accountId: String?) {
        val safeAccountId = accountId.orEmpty().trim()
        val prefs = context.getSharedPreferences(SYNC_PREFERENCES, Context.MODE_PRIVATE)
        if (prefs.getString(ACTIVE_ACCOUNT_ID, "") != safeAccountId || !prefs.contains(SESSION_ID)) {
            prefs.edit(commit = true) {
                if (safeAccountId.isBlank()) remove(ACTIVE_ACCOUNT_ID) else putString(ACTIVE_ACCOUNT_ID, safeAccountId)
                putString(SESSION_ID, java.util.UUID.randomUUID().toString())
                putLong("accountStartedAt", System.currentTimeMillis())
            }
        }
        val request = PutDataMapRequest.create(ACCOUNT_SESSION_PATH).apply {
            dataMap.putString(SESSION_ID, prefs.getString(SESSION_ID, "").orEmpty())
            dataMap.putLong("sessionStartedAt", prefs.getLong("accountStartedAt", 0L))
            dataMap.putBoolean("signedIn", safeAccountId.isNotBlank())
            dataMap.putString("accountId", safeAccountId)
            dataMap.putLong("updatedAt", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request)
    }

    /**
     * Sends the latest phone fitness summary to the paired Wear OS app.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param accountId Account ID supplied to [publish].
     * @param snapshot Firestore or data snapshot containing the latest state.
     */
    @Synchronized fun publish(context: Context, accountId: String, snapshot: FitnessSnapshot) {
        if (accountId.isBlank() || accountId == "guest") return
        val prefs = context.getSharedPreferences(SYNC_PREFERENCES, Context.MODE_PRIVATE)
        if (prefs.getString(ACTIVE_ACCOUNT_ID, null) != accountId ||
            fitnessAuthenticatedUid() != accountId) return
        val nutrition = snapshot.nutrition
        val signature = "$accountId|${prefs.getString(SESSION_ID, "")}|${java.time.LocalDate.now()}|${snapshot.profile}|${snapshot.stepsToday}|$nutrition"
        if (signature == lastPublished) return
        lastPublished = signature
        val request = PutDataMapRequest.create(DAILY_DATA_PATH).apply {
            dataMap.putString("accountId", accountId)
            dataMap.putString(SESSION_ID, prefs.getString(SESSION_ID, "").orEmpty())
            dataMap.putString("date", java.time.LocalDate.now().toString())
            dataMap.putLong("updatedAt", System.currentTimeMillis())
            dataMap.putInt("phoneSteps", snapshot.stepsToday.coerceAtLeast(0))
            dataMap.putInt("stepGoal", snapshot.profile.stepGoal)
            dataMap.putInt("calorieGoal", snapshot.profile.calorieGoal)
            dataMap.putInt("proteinGoal", snapshot.profile.proteinGoal)
            dataMap.putInt("carbohydrateGoal", snapshot.profile.carbohydrateGoal)
            dataMap.putInt("fatGoal", snapshot.profile.fatGoal)
            dataMap.putFloat("calories", nutrition.calories.coerceAtLeast(0f))
            dataMap.putFloat("protein", nutrition.proteinGrams.coerceAtLeast(0f))
            dataMap.putFloat("carbohydrates", nutrition.carbohydrateGrams.coerceAtLeast(0f))
            dataMap.putFloat("fat", nutrition.fatGrams.coerceAtLeast(0f))
            dataMap.putFloat("fiber", nutrition.fiberGrams.coerceAtLeast(0f))
            dataMap.putFloat("sugar", nutrition.sugarGrams.coerceAtLeast(0f))
            dataMap.putFloat("sodium", nutrition.sodiumMilligrams.coerceAtLeast(0f))
        }.asPutDataRequest().setUrgent()

        Wearable.getDataClient(context).putDataItem(request).addOnFailureListener {
            synchronized(this) { if (lastPublished == signature) lastPublished = "" }
        }
    }
}
