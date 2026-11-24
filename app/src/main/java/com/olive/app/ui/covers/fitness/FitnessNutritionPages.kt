// Fitness cover users: shows nutrition totals, logged meals, and food search.
package com.olive.app.ui.covers.fitness

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveCard as Card
import com.olive.app.ui.shared.OliveFilledTonalButton as FilledTonalButton
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveTextButton as TextButton
import kotlin.time.Duration.Companion.milliseconds

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.BakeryDining
import androidx.compose.material.icons.outlined.FreeBreakfast
import androidx.compose.material.icons.outlined.LunchDining
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.LocalizedIcon as Icon
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*

/**
 * Shows today's calorie and macro totals with meal logging.
 *
 * @param snapshot Firestore or data snapshot containing the latest state.
 * @param enabled Whether this feature or control is currently enabled.
 * @param padding Padding supplied to [TodayPage].
 * @param onAddFood Callback invoked when add food changes or occurs.
 * @param onRemoveFood Callback invoked when remove food changes or occurs.
 * @param onAddWater Callback invoked when add water changes or occurs.
 * @param onUpdateFood Callback invoked when update food changes or occurs.
 * @param library Optional library supplied to [TodayPage].
 * @param onOpenLibrary Callback invoked when open library changes or occurs.
 * @param onLogMeal Callback invoked when log meal changes or occurs.
 */
@Composable
internal fun TodayPage(
    snapshot: FitnessSnapshot,
    enabled: Boolean,
    padding: PaddingValues,
    onAddFood: () -> Unit,
    onRemoveFood: (String) -> Unit,
    onAddWater: (Int, Int) -> Unit,
    onUpdateFood: (FoodLogEntry) -> Unit = {},
    library: FitnessFoodLibrary? = null,
    onOpenLibrary: (FoodLibrarySection) -> Unit = {},
    onLogMeal: (MealType) -> Unit = { onAddFood() },
) {
    val savedFoods = rememberSavedFoods(library)
    var editingEntry by remember { mutableStateOf<FoodLogEntry?>(null) }
    var editDetails by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).oliveScrollbar(listState)
            .testTag("fitness_today"),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.nutrition),
                    style = MaterialTheme.typography.headlineSmall.balancedHeadline,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.today), color = FitnessSubtle)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onAddFood, enabled = enabled) {
                    Icon(Icons.Outlined.Add, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.add_food))
                }
                WaterTracker(snapshot.waterTodayMl, snapshot.profile.waterGoalMl, enabled, onAddWater)
            }
        }
        if (library != null) item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(FoodLibrarySection.Recent, FoodLibrarySection.Saved).forEach { section ->
                    OutlinedButton(onClick = { onOpenLibrary(section) }, enabled = enabled) {
                        Text(stringResource(section.label))
                    }
                }
            }
        }
        item { DailyNutritionCard(snapshot) }
        item { Text(stringResource(R.string.meals), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() }) }
        MealType.entries.forEach { meal ->
            val entries = snapshot.foodsToday.filter { it.meal == meal }
            item {
                MealHeader(meal, entries, enabled) { onLogMeal(meal) }
            }
            items(entries, key = FoodLogEntry::logId) { entry ->
                LoggedFoodRow(entry, enabled, onEdit = { editingEntry = entry; editDetails = false }, onRemove = { onRemoveFood(entry.logId) })
            }
        }
        item { OtherNutrients(snapshot.nutrition) }
        item { ProviderAttribution() }
    }
    editingEntry?.let { entry ->
        if (editDetails) ManualFoodEntryDialog(onDismiss = { editDetails = false },
            onContinue = { editingEntry = entry.copy(food = it); editDetails = false }, initialFood = entry.food)
        else AddFoodDialog(food = entry.food, onDismiss = { editingEntry = null },
            onAdd = { food, servings, meal ->
                onUpdateFood(entry.copy(food = food.copy(servingOptions = emptyList()), servings = servings, meal = meal))
                editingEntry = null
            }, initialServings = entry.servings, initialMeal = entry.meal,
            editing = true, onEditFood = { editDetails = true },
            isSaved = savedFoods.any { it.id == entry.food.id },
            onToggleSaved = library?.let { { saved: Boolean -> it.setSaved(entry.food, saved) } })
    }

}

/**
 * Renders the meal header composable.
 *
 * @param meal Meal supplied to [MealHeader].
 * @param entries Collection of entries values to process.
 * @param enabled Whether this feature or control is currently enabled.
 * @param onLog Callback invoked when log changes or occurs.
 */
@Composable
private fun MealHeader(meal: MealType, entries: List<FoodLogEntry>, enabled: Boolean, onLog: () -> Unit) {
    val label = meal.localizedName()
    val icon = when (meal) {
        MealType.BREAKFAST -> Icons.Outlined.FreeBreakfast
        MealType.LUNCH -> Icons.Outlined.LunchDining
        MealType.DINNER -> Icons.Outlined.Restaurant
        MealType.SNACK -> Icons.Outlined.BakeryDining
    }
    val calories = entries.sumOf { (it.food.calories * it.servings).toDouble() }.toFloat()
    Card(colors = CardDefaults.cardColors(containerColor = FitnessSurface), shape = RoundedCornerShape(18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(icon, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                if (entries.isNotEmpty()) Text(
                    stringResource(R.string.fitness_diary_energy, calories.displayAmount()),
                    style = MaterialTheme.typography.bodySmall, color = FitnessSubtle,
                )
            }
            val action = stringResource(R.string.fitness_diary_log_meal, label)
            FilledTonalButton(onClick = onLog, enabled = enabled, modifier = Modifier
                .testTag("fitness_log_${meal.name}").semantics { contentDescription = action }) {
                Text(stringResource(R.string.fitness_diary_log), Modifier.clearAndSetSemantics { })
            }
        }
    }
}

/**
 * Looks up the localized display name for a meal type.
 *
 * @receiver The meal category to localize.
 * @return The resulting localized name string.
 */
@Composable
internal fun MealType.localizedName(): String = stringResource(when (this) {
    MealType.BREAKFAST -> R.string.fitness_diary_breakfast
    MealType.LUNCH -> R.string.fitness_diary_lunch
    MealType.DINNER -> R.string.fitness_diary_dinner
    MealType.SNACK -> R.string.fitness_diary_snacks
})

/**
 * Renders the daily nutrition card composable.
 *
 * @param snapshot Firestore or data snapshot containing the latest state.
 */
@Composable
private fun DailyNutritionCard(snapshot: FitnessSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CaloriesOverview(snapshot)
        MacroOverview(snapshot)
    }
}

/**
 * Shows calories logged and progress toward the daily calorie goal.
 *
 * @param snapshot Firestore or data snapshot containing the latest state.
 */
@Composable
private fun CaloriesOverview(snapshot: FitnessSnapshot) {
    val totals = snapshot.nutrition
    val profile = snapshot.profile
    val caloriesLabel = stringResource(R.string.calories)
    val remaining = (profile.calorieGoal - totals.calories).coerceAtLeast(0f)
    val remainingLabel = stringResource(R.string.fitness_diary_remaining, remaining.displayAmount())
    Card(
        shape = FitnessContinuousCorners,
        colors = CardDefaults.cardColors(containerColor = FitnessWarmCard),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().clearAndSetSemantics {
                    contentDescription = "$caloriesLabel, " +
                        "${totals.calories.displayAmount()} / ${profile.calorieGoal} kcal, $remainingLabel"
                },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.calories), color = FitnessSubtle)
                    Text(totals.calories.displayAmount(), style = MaterialTheme.typography.headlineMedium.tabularDigits, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.calorie_goal) + " · " +
                            stringResource(R.string.fitness_diary_energy, profile.calorieGoal.toString()),
                        style = MaterialTheme.typography.bodySmall.tabularDigits,
                        color = FitnessSubtle,
                    )
                }
                Text(
                    remainingLabel, Modifier.weight(1f).padding(start = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge.tabularDigits,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            val calorieProgress = (totals.calories / profile.calorieGoal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { calorieProgress },
                modifier = Modifier.fillMaxWidth().height(9.dp).clip(CircleShape)
                    .clearAndSetSemantics { },
            )
        }
    }
}

/**
 * Shows carbohydrates, fat, and protein against their daily goals.
 *
 * @param snapshot Firestore or data snapshot containing the latest state.
 */
@Composable
private fun MacroOverview(snapshot: FitnessSnapshot) {
    val totals = snapshot.nutrition
    val profile = snapshot.profile
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = FitnessSurface)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
            val stacked = maxWidth < 280.dp || androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.25f
            val metrics = listOf(
                Triple(R.string.carbohydrates, totals.carbohydrateGrams, profile.carbohydrateGoal),
                Triple(R.string.fat, totals.fatGrams, profile.fatGoal),
                Triple(R.string.protein, totals.proteinGrams, profile.proteinGoal),
            )
            if (stacked) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                metrics.forEach { (label, total, goal) -> NutritionProgress(stringResource(label), total, goal) }
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                metrics.forEach { (label, total, goal) ->
                    NutritionProgress(stringResource(label), total, goal, Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * Shows the expandable breakdown of additional nutrients.
 *
 * @param totals Totals supplied to [OtherNutrients].
 */
@Composable
private fun OtherNutrients(totals: NutritionTotals) {
    var nutrientsExpanded by rememberSaveable { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = FitnessSurface)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { nutrientsExpanded = !nutrientsExpanded }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.nutrition_other_nutrients), modifier = Modifier.weight(1f))
                Icon(if (nutrientsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (nutrientsExpanded) {
            HorizontalDivider()
                NutritionAmountRow(stringResource(R.string.saturated_fat), totals.saturatedFatGrams, "g")
                NutritionAmountRow(stringResource(R.string.trans_fat), totals.transFatGrams, "g")
                NutritionAmountRow(stringResource(R.string.fiber), totals.fiberGrams, "g")
                NutritionAmountRow(stringResource(R.string.sugar), totals.sugarGrams, "g")
                NutritionAmountRow(stringResource(R.string.sodium), totals.sodiumMilligrams, "mg")
                NutritionAmountRow(stringResource(R.string.cholesterol), totals.cholesterolMilligrams, "mg")
                NutritionAmountRow(stringResource(R.string.potassium), totals.potassiumMilligrams, "mg")
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/**
 * Shows eaten, target, and remaining calories in one row.
 *
 * @param label Accessible label or display caption.
 * @param value Input value to store, parse, or format.
 * @param unit Unit supplied to [NutritionAmountRow].
 */
@Composable
private fun NutritionAmountRow(label: String, value: Float, unit: String) {
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = "$label, ${value.displayAmount()} $unit"
        },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Text("${value.displayAmount()} $unit", style = MaterialTheme.typography.bodyMedium.tabularDigits)
    }
}
/**
 * Draws progress toward one calorie or macro goal.
 *
 * @param label Accessible label or display caption.
 * @param value Input value to store, parse, or format.
 * @param goal Goal supplied to [NutritionProgress].
 * @param modifier Compose modifier applied to the root layout.
 */
@Composable
private fun NutritionProgress(label: String, value: Float, goal: Int, modifier: Modifier = Modifier) {
    val progress = (value / goal.coerceAtLeast(1)).coerceIn(0f, 1f)
    Column(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = "$label, ${value.displayAmount()} / $goal g"
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text("${value.displayAmount()} / $goal g", style = MaterialTheme.typography.bodySmall.tabularDigits, color = FitnessSubtle)
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
        )
    }
}

/**
 * Shows one diary food with nutrients and a remove action.
 *
 * @param entry Entry supplied to [LoggedFoodRow].
 * @param enabled Whether this feature or control is currently enabled.
 * @param onEdit Callback invoked when edit changes or occurs.
 * @param onRemove Callback invoked when remove changes or occurs.
 */
@Composable
private fun LoggedFoodRow(entry: FoodLogEntry, enabled: Boolean, onEdit: () -> Unit, onRemove: () -> Unit) {
    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = FitnessSurface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).clearAndSetSemantics {
                contentDescription = "${entry.food.name}. ${entry.servings.displayAmount()} × " +
                    "${entry.food.servingDescription}. " +
                    "${(entry.food.calories * entry.servings).displayAmount()} kcal"
            }) {
                Text(entry.food.name, fontWeight = FontWeight.SemiBold)
                Text(
                    "${entry.servings.displayAmount()} × ${entry.food.servingDescription} · " +
                            "${(entry.food.calories * entry.servings).displayAmount()} kcal",
                    color = FitnessSubtle,
                )
            }
            IconButton(onClick = onEdit, enabled = enabled, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(Icons.Outlined.Edit, stringResource(R.string.nutrition_edit_food))
            }
            IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(Icons.Outlined.Delete, stringResource(R.string.remove))
            }
        }
    }
}
/**
 * Searches nutrition providers and lets the user add a food result.
 *
 * @param enabled Whether this feature or control is currently enabled.
 * @param padding Padding supplied to [FoodSearchPage].
 * @param onFoodAdded Callback invoked when food added changes or occurs.
 * @param foodSource Optional food source supplied to [FoodSearchPage].
 * @param library Optional library supplied to [FoodSearchPage].
 * @param initialSection Initial section supplied to [FoodSearchPage].
 * @param recentFoods Collection of recent foods values to process.
 * @param initialMeal Optional initial meal supplied to [FoodSearchPage].
 */
@Composable
internal fun FoodSearchPage(
    enabled: Boolean,
    padding: PaddingValues,
    onFoodAdded: (NutritionFood, Float, MealType, String) -> Unit,
    foodSource: FoodNutritionDataSource? = null,
    library: FitnessFoodLibrary? = null,
    initialSection: FoodLibrarySection = FoodLibrarySection.Search,
    recentFoods: List<FoodLogEntry> = emptyList(),
    initialMeal: MealType? = null,
) {
    val repository = remember(foodSource) { foodSource ?: FoodNutritionRepository() }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf(FoodSearchResult()) }
    var searched by remember { mutableStateOf(false) }
    var selectedFood by remember { mutableStateOf<NutritionFood?>(null) }
    var previousEntry by remember { mutableStateOf<FoodLogEntry?>(null) }
    var section by rememberSaveable(initialSection) { mutableStateOf(initialSection) }
    val savedFoods = rememberSavedFoods(library)
    var searches by remember(library) { mutableStateOf(library?.searches().orEmpty()) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    var manualEntry by rememberSaveable { mutableStateOf(false) }
    var barcodeMode by rememberSaveable { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var scanFailed by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val camera = rememberNutritionCamera(onPhoto = { bytes ->
        scanning = true
        scanFailed = false
        searched = false
        query = ""
        result = FoodSearchResult()
        try {
            val code = readBarcodePhoto(context, bytes)
            if (code == null) scanFailed = true else {
                query = code
                searched = false
                result = FoodSearchResult()
            }
        } finally { scanning = false }
    }, onError = { scanning = false; scanFailed = true })
    var page by remember { mutableIntStateOf(0) }
    /**
     * Searches the target.
     *
     * @param more Whether to apply more.
     */
    fun search(more: Boolean = false) {
        if (loading) return
        loading = true
        suggestions = emptyList()
        searched = true
        val nextPage = if (more) page + 1 else 0
        val request = RecentFoodSearch(query, barcodeMode)
        if (!more && library != null) {
            library.recordSearch(request)
            searches = library.searches()
        }
        scope.launch {
            try {
                val incoming = if (request.barcode) repository.lookupBarcode(request.query)
                    else repository.search(request.query, nextPage)
                result = if (more) incoming.copy(foods = (result.foods + incoming.foods).distinctBy { it.id })
                    else incoming
                // Retain the previous page on a failed load so the same page can be retried.
                if (incoming.providerFailures < 2) page = nextPage
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(query, barcodeMode, loading, searched, enabled, section) {
        suggestions = emptyList()
        if (section != FoodLibrarySection.Search || !enabled || barcodeMode || loading || searched ||
            query.trim().length < 2) return@LaunchedEffect
        kotlinx.coroutines.delay(450.milliseconds)
        suggestions = try {
            repository.suggestions(query)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
    }
    val headingFocus = rememberEntryFocus(Unit)

    Column(
        Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            initialMeal?.let { stringResource(R.string.fitness_diary_log_meal, it.localizedName()) }
                ?: stringResource(R.string.find_food),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        if (library != null) Row(Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FoodLibrarySection.entries.forEach { option ->
                FilterChip(selected = section == option, onClick = { section = option },
                    modifier = Modifier.testTag("food_library_${option.name}").semantics { role = Role.Tab }
                        .focusProperties { canFocus = true }.then(
                        if (option == FoodLibrarySection.Search) Modifier.focusRequester(headingFocus) else Modifier),
                    enabled = enabled && !loading && !scanning, label = { Text(stringResource(option.label)) })
            }
        }
        if (section == FoodLibrarySection.Search) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false to R.string.search_foods, true to R.string.nutrition_barcode).forEach { (mode, label) ->
                    FilterChip(selected = barcodeMode == mode, enabled = enabled && !loading && !scanning,
                        modifier = if (library == null && !mode) Modifier.focusProperties { canFocus = true }.focusRequester(headingFocus) else Modifier,
                        onClick = { scanFailed = false; barcodeMode = mode; query = ""; searched = false; result = FoodSearchResult() },
                        label = { Text(stringResource(label)) })
                }
                IconButton(onClick = { manualEntry = true }, enabled = enabled && !loading && !scanning,
                    modifier = Modifier.testTag("nutrition_manual_entry")) {
                    Icon(Icons.Outlined.Edit, stringResource(R.string.nutrition_manual))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = if (barcodeMode) it.filter(Char::isDigit).take(14) else it.take(80)
                        searched = false
                        result = FoodSearchResult()
                    },
                    label = { Text(stringResource(if (barcodeMode) R.string.nutrition_barcode else R.string.search_foods)) },
                    keyboardOptions = KeyboardOptions(keyboardType = if (barcodeMode) KeyboardType.Number else KeyboardType.Text),
                    trailingIcon = if (barcodeMode) { {
                        IconButton(onClick = camera, enabled = enabled && !loading && !scanning) {
                            Icon(Icons.Outlined.PhotoCamera, stringResource(R.string.nutrition_scan_barcode))
                        }
                    } } else null,
                    singleLine = true,
                    enabled = enabled && !loading && !scanning,
                    modifier = Modifier.weight(1f).testTag("fitness_food_search"),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { search() },
                    enabled = enabled && !loading && !scanning && if (barcodeMode) query.length in listOf(8, 12, 13)
                        else query.trim().length >= 2,
                ) { Icon(Icons.Outlined.Search, stringResource(R.string.search)) }
            }
            if (suggestions.isNotEmpty() && !loading) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { suggestion ->
                        SuggestionChip(
                            onClick = { query = suggestion; search() }, label = { Text(suggestion) })
                    }
                }
            }
            if (scanFailed) Text(stringResource(R.string.nutrition_barcode_failed), color = MaterialTheme.colorScheme.error)
            if (loading || scanning) {
                LoadingSkeleton(SkeletonLayout.FoodResults)
            } else if (searched && result.foods.isEmpty()) {
                val message = when {
                    result.needsSignIn -> R.string.nutrition_sign_in_required
                    result.providerFailures >= 2 -> R.string.food_search_unavailable
                    else -> R.string.no_food_results
                }
                Text(stringResource(message))
            }
            if (!loading && result.needsSignIn && result.foods.isNotEmpty()) {
                Text(stringResource(R.string.nutrition_sign_in_required), color = FitnessSubtle)
            }
            if (!loading && result.needsIpApproval) {
                Text(stringResource(R.string.nutrition_ip_approval), color = MaterialTheme.colorScheme.error)
            }
            if (!loading && result.providerFailures == 1) {
                Text(stringResource(R.string.some_food_sources_unavailable), color = FitnessSubtle)
            }
        }
        val listState = key(section) {
            androidx.compose.foundation.lazy.rememberLazyListState()
        }
        LazyColumn(
            modifier = Modifier.weight(1f).oliveScrollbar(listState),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (section == FoodLibrarySection.Recent) {
                recentFoodItems(recentFoods, searches, enabled, onFood = { entry ->
                    previousEntry = entry
                    selectedFood = entry.food
                }, onSearch = { request ->
                    section = FoodLibrarySection.Search
                    scanFailed = false
                    barcodeMode = request.barcode
                    query = request.query
                    search()
                })
            }
            if (section == FoodLibrarySection.Saved) {
                savedFoodItems(savedFoods, repository, enabled, onFood = { selectedFood = it },
                    onRemove = { library?.remove(it) })
            }
            if (section != FoodLibrarySection.Search) return@LazyColumn
            items(if (loading || scanning) emptyList() else result.foods, key = NutritionFood::id) { food ->
                FoodSearchResultCard(food, enabled && !loading) { selectedFood = food }
            }
            if (!loading && result.hasMore) {
                item {
                    OutlinedButton(onClick = { search(more = true) }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.nutrition_more)) }
                }
            }
        }
        ProviderAttribution()
    }

    if (manualEntry) ManualFoodEntryDialog(
        onDismiss = { manualEntry = false },
        onContinue = { food -> manualEntry = false; selectedFood = food },
    )
    selectedFood?.let { food ->
        val logId = rememberSaveable(food.id) { java.util.UUID.randomUUID().toString() }
        AddFoodDialog(
            food = food,
            onDismiss = { selectedFood = null; previousEntry = null },
            onAdd = { choice, servings, meal ->
                selectedFood = null
                previousEntry = null
                onFoodAdded(choice.copy(servingOptions = emptyList()), servings, meal, logId)
            },
            initialServings = previousEntry?.servings ?: 1f,
            initialMeal = initialMeal ?: previousEntry?.meal ?: MealType.BREAKFAST,
            isSaved = savedFoods.any { it.id == food.id },
            onToggleSaved = library?.let { { saved: Boolean -> it.setSaved(food, saved) } },
        )
    }
}

/**
 * Fitness cover shows this food search result card view so the user can view its details and use its actions.
 *
 * @param food Food supplied to [FoodSearchResultCard].
 * @param enabled Whether this feature or control is currently enabled.
 * @param onAdd Callback invoked when add changes or occurs.
 */
@Composable
private fun FoodSearchResultCard(food: NutritionFood, enabled: Boolean, onAdd: () -> Unit) {
    val description = buildString {
        append(food.name)
        if (food.brand.isNotBlank()) append(". ${food.brand}")
        append(". ${food.servingDescription}. ${food.calories.displayAmount()} kcal. ")
        append("${stringResource(R.string.protein)} ${food.proteinGrams.displayAmount()} g. ")
        append("${stringResource(R.string.carbohydrates)} ${food.carbohydrateGrams.displayAmount()} g. ")
        append("${stringResource(R.string.fat)} ${food.fatGrams.displayAmount()} g. ")
        append(stringResource(R.string.add_to_diary))
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onAdd)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick {
                    onAdd()
                    true
                }
            },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = FitnessSurface),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Column(Modifier.semantics(mergeDescendants = true) { }) {
                Text(food.name, fontWeight = FontWeight.SemiBold)
                if (food.brand.isNotBlank()) Text(food.brand, color = FitnessSubtle)
                Text(
                    "${food.servingDescription} · ${food.calories.displayAmount()} kcal · " +
                            "P ${food.proteinGrams.displayAmount()} g · " +
                            "C ${food.carbohydrateGrams.displayAmount()} g · F ${food.fatGrams.displayAmount()} g",
                )
                Text(
                    "${stringResource(R.string.fiber)} ${food.fiberGrams.displayAmount()} g · " +
                            "${stringResource(R.string.sugar)} ${food.sugarGrams.displayAmount()} g · " +
                            "${stringResource(R.string.saturated_fat)} ${food.saturatedFatGrams.displayAmount()} g",
                    style = MaterialTheme.typography.bodySmall,
                    color = FitnessSubtle,
                )
                Text(
                    "${stringResource(R.string.sodium)} ${food.sodiumMilligrams.displayAmount()} mg · " +
                            "${stringResource(R.string.cholesterol)} ${food.cholesterolMilligrams.displayAmount()} mg · " +
                            "${stringResource(R.string.potassium)} ${food.potassiumMilligrams.displayAmount()} mg",
                    style = MaterialTheme.typography.bodySmall,
                    color = FitnessSubtle,
                )
                Text(food.source.displayName, style = MaterialTheme.typography.labelSmall, color = FitnessSubtle)
            }
            OutlinedButton(
                onClick = onAdd,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
            ) {
                Text(stringResource(R.string.add_to_diary))
            }
        }
    }
}

/**
 * Shows the required FatSecret and Open Food Facts credits.
 */
@Composable
private fun ProviderAttribution() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.fatsecret_attribution), style = MaterialTheme.typography.labelSmall, color = FitnessSubtle)
        Text(stringResource(R.string.open_food_facts_attribution), style = MaterialTheme.typography.labelSmall, color = FitnessSubtle)
    }
}
