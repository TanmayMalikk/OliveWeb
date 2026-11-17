// Fitness cover users: shows nutrition totals, logged meals, and food search.
package com.olive.app.ui.covers.fitness

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
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

// Shows today's calorie and macro totals with meal logging.
@Composable
internal fun TodayPage(
    snapshot: FitnessSnapshot,
    enabled: Boolean,
    padding: PaddingValues,
    onAddFood: () -> Unit,
    onRemoveFood: (String) -> Unit,
    onAddWater: (Int, Int) -> Unit,
    onUpdateFood: (FoodLogEntry) -> Unit = {},
) {
    var editingEntry by remember { mutableStateOf<FoodLogEntry?>(null) }
    var editDetails by remember { mutableStateOf(false) }
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
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(R.string.daily_nutrition), color = FitnessSubtle)
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
        item { DailyNutritionCard(snapshot) }
        item { Text(stringResource(R.string.meals), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() }) }
        MealType.entries.forEach { meal ->
            val entries = snapshot.foodsToday.filter { it.meal == meal }
            item {
                Text(
                    meal.displayName,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            // Show the empty diary message until the user logs food.
            if (entries.isEmpty()) {
                item { Text(stringResource(R.string.no_food_logged), color = FitnessSubtle) }
            } else {
                items(entries, key = FoodLogEntry::logId) { entry ->
                    LoggedFoodRow(entry, enabled, onEdit = { editingEntry = entry; editDetails = false }, onRemove = { onRemoveFood(entry.logId) })
                }
            }
        }
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
            editing = true, onEditFood = { editDetails = true })
    }

}

// fitness cover shows this daily nutrition card view so the user can view its details and use its actions.
@Composable
internal fun DailyNutritionCard(snapshot: FitnessSnapshot) {
    val totals = snapshot.nutrition
    val profile = snapshot.profile
    val caloriesLabel = stringResource(R.string.calories)
    var nutrientsExpanded by remember { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = FitnessWarmCard),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().clearAndSetSemantics {
                    contentDescription = "$caloriesLabel, " +
                        "${totals.calories.displayAmount()} of ${profile.calorieGoal} kcal"
                },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(totals.calories.displayAmount(), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.calories), color = FitnessSubtle)
                }
                Text(
                    "${profile.calorieGoal} kcal · ${stringResource(R.string.calorie_goal)}",
                    color = FitnessSubtle,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            val calorieProgress = (totals.calories / profile.calorieGoal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { calorieProgress },
                modifier = Modifier.fillMaxWidth().height(9.dp).clip(CircleShape)
                    .clearAndSetSemantics { },
            )
            NutritionProgress(stringResource(R.string.protein), totals.proteinGrams, profile.proteinGoal.toFloat(), "g")
            NutritionProgress(stringResource(R.string.carbohydrates), totals.carbohydrateGrams, profile.carbohydrateGoal.toFloat(), "g")
            NutritionProgress(stringResource(R.string.fat), totals.fatGrams, profile.fatGoal.toFloat(), "g")
            HorizontalDivider()
            TextButton(onClick = { nutrientsExpanded = !nutrientsExpanded }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.nutrition_other_nutrients), modifier = Modifier.weight(1f))
                Icon(if (nutrientsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
            }
            if (nutrientsExpanded) {
            NutritionAmountRow(stringResource(R.string.saturated_fat), totals.saturatedFatGrams, "g")
            NutritionAmountRow(stringResource(R.string.trans_fat), totals.transFatGrams, "g")
            NutritionAmountRow(stringResource(R.string.fiber), totals.fiberGrams, "g")
            NutritionAmountRow(stringResource(R.string.sugar), totals.sugarGrams, "g")
            NutritionAmountRow(stringResource(R.string.sodium), totals.sodiumMilligrams, "mg")
            NutritionAmountRow(stringResource(R.string.cholesterol), totals.cholesterolMilligrams, "mg")
            NutritionAmountRow(stringResource(R.string.potassium), totals.potassiumMilligrams, "mg")
            }
        }
    }
}

// Shows eaten, target, and remaining calories in one row.
@Composable
private fun NutritionAmountRow(label: String, value: Float, unit: String) {
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = "$label, ${value.displayAmount()} $unit"
        },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label)
        Text("${value.displayAmount()} $unit")
    }
}
// Draws progress toward one calorie or macro goal.
@Suppress("SameParameterValue") // unit is always 'g' for gram.
@Composable
private fun NutritionProgress(label: String, value: Float, goal: Float, unit: String) {
    val progress = (value / goal.coerceAtLeast(1f)).coerceIn(0f, 1f)
    Column(
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "$label, ${value.displayAmount()} of ${goal.displayAmount()} $unit"
        },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text("${value.displayAmount()} / ${goal.displayAmount()} $unit")
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics { },
        )
    }
}

// Shows one diary food with nutrients and a remove action.
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
            IconButton(onClick = onEdit, enabled = enabled) {
                Icon(Icons.Outlined.Edit, stringResource(R.string.nutrition_edit_food))
            }
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(Icons.Outlined.Delete, stringResource(R.string.remove))
            }
        }
    }
}
// Searches nutrition providers and lets the user add a food result.
@Composable
internal fun FoodSearchPage(
    enabled: Boolean,
    padding: PaddingValues,
    onFoodAdded: (NutritionFood, Float, MealType) -> Unit,
) {
    val repository = remember { FoodNutritionRepository() }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf(FoodSearchResult()) }
    var searched by remember { mutableStateOf(false) }
    var selectedFood by remember { mutableStateOf<NutritionFood?>(null) }
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
    var page by remember { mutableStateOf(0) }
    fun search(more: Boolean = false) {
        if (loading) return
        loading = true
        suggestions = emptyList()
        searched = true
        val nextPage = if (more) page + 1 else 0
        scope.launch {
            try {
                val incoming = if (barcodeMode) repository.lookupBarcode(query)
                    else repository.search(query, nextPage)
                result = if (more) incoming.copy(foods = (result.foods + incoming.foods).distinctBy { it.id })
                    else incoming
                // Retain the previous page on a failed load so the same page can be retried.
                if (incoming.providerFailures < 2) page = nextPage
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(query, barcodeMode, loading, searched, enabled) {
        suggestions = emptyList()
        if (!enabled || barcodeMode || loading || searched || query.trim().length < 2) return@LaunchedEffect
        kotlinx.coroutines.delay(450)
        suggestions = try {
            repository.suggestions(query)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
    }
    val headingFocus = remember { FocusRequester() }

    // Place TalkBack at the food-search heading when this submenu opens.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        headingFocus.requestFocus()
    }

    Column(
        Modifier.fillMaxSize().padding(padding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.find_food),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.focusRequester(headingFocus).focusable().semantics { heading() },
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(false to R.string.search_foods, true to R.string.nutrition_barcode).forEach { (mode, label) ->
                FilterChip(selected = barcodeMode == mode, enabled = enabled && !loading && !scanning,
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
                    androidx.compose.material3.SuggestionChip(
                        onClick = { query = suggestion; search() }, label = { Text(suggestion) })
                }
            }
        }
        if (scanFailed) Text(stringResource(R.string.nutrition_barcode_failed), color = MaterialTheme.colorScheme.error)
        if (loading || scanning) {
            com.olive.app.ui.shared.LoadingSkeleton(SkeletonLayout.FoodResults)
        } else if (searched && result.foods.isEmpty()) {
            val message = if (result.providerFailures >= 2) R.string.food_search_unavailable else R.string.no_food_results
            Text(stringResource(message))
        }
        if (!loading && result.needsIpApproval) {
            Text(stringResource(R.string.nutrition_ip_approval), color = MaterialTheme.colorScheme.error)
        }
        if (!loading && result.providerFailures == 1) {
            Text(stringResource(R.string.some_food_sources_unavailable), color = FitnessSubtle)
        }
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyColumn(
            modifier = Modifier.weight(1f).oliveScrollbar(listState),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
        AddFoodDialog(
            food = food,
            onDismiss = { selectedFood = null },
            onAdd = { choice, servings, meal ->
                selectedFood = null
                onFoodAdded(choice.copy(servingOptions = emptyList()), servings, meal)
            },
        )
    }
}

// fitness cover shows this food search result card view so the user can view its details and use its actions.
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

// fitness cover shows this add food dialog view so the user can view its details and use its actions.
@Composable
internal fun AddFoodDialog(
    food: NutritionFood,
    onDismiss: () -> Unit,
    onAdd: (NutritionFood, Float, MealType) -> Unit,
    initialServings: Float = 1f,
    initialMeal: MealType = MealType.BREAKFAST,
    editing: Boolean = false,
    onEditFood: (() -> Unit)? = null,
) {
    var servings by remember { mutableStateOf(initialServings.toString()) }
    var choice by remember(food.id) { mutableStateOf(food) }
    var servingMenu by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    var customUnit by remember { mutableStateOf(servingUnits(food).firstOrNull() ?: "serving") }
    val portion = if (custom) customFoodPortion(food, servings, customUnit)
        else parseServingAmount(servings)?.let { FoodPortion(choice, it) }
    var meal by remember { mutableStateOf(initialMeal) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(food.name, modifier = Modifier.weight(1f))
                onEditFood?.let { edit -> IconButton(onClick = edit) {
                    Icon(Icons.Outlined.Edit, stringResource(R.string.nutrition_edit_details))
                } }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (food.servingOptions.isNotEmpty() || food.servingDescription.isNotBlank()) {
                    Box {
                        OutlinedButton(onClick = { servingMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (custom) stringResource(R.string.nutrition_custom_portion) else choice.servingDescription)
                        }
                        DropdownMenu(expanded = servingMenu, onDismissRequest = { servingMenu = false }) {
                            food.servingOptions.ifEmpty { listOf(food) }.forEach { option ->
                                DropdownMenuItem(text = { Text(option.servingDescription) }, onClick = {
                                    choice = option
                                    custom = false
                                    servings = "1"
                                    servingMenu = false
                                })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.nutrition_custom_portion)) }, onClick = {
                                custom = true; servings = ""; servingMenu = false
                            })
                        }
                    }
                }
                if (custom) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (servingUnits(food) + "serving").forEach { unit ->
                        FilterChip(selected = customUnit == unit, onClick = { customUnit = unit },
                            label = { Text(if (unit == "serving") stringResource(R.string.servings) else unit) })
                    }
                }
                Text(
                    "${(portion?.food ?: choice).servingDescription} · ${((portion?.food?.calories ?: 0f) * (portion?.servings ?: 0f)).displayAmount()} kcal · " +
                            "P ${((portion?.food?.proteinGrams ?: 0f) * (portion?.servings ?: 0f)).displayAmount()} g · " +
                            "C ${((portion?.food?.carbohydrateGrams ?: 0f) * (portion?.servings ?: 0f)).displayAmount()} g · F ${((portion?.food?.fatGrams ?: 0f) * (portion?.servings ?: 0f)).displayAmount()} g",
                )
                OutlinedTextField(
                    value = servings,
                    onValueChange = { value -> servings = value.take(12) },
                    label = { Text(if (custom) stringResource(R.string.nutrition_amount) + " ($customUnit)" else stringResource(R.string.servings)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                MealType.entries.forEach { option ->
                    FilterChip(
                        selected = meal == option,
                        onClick = { meal = option },
                        label = { Text(option.displayName) },
                        leadingIcon = if (meal == option) {
                            { Icon(Icons.Outlined.Check, null) }
                        } else null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { portion?.let { onAdd(it.food, it.servings, meal) } },
                enabled = portion != null,
            ) { Text(stringResource(if (editing) R.string.save else R.string.add_food)) }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
// Shows the required FatSecret and Open Food Facts credits.
@Composable
private fun ProviderAttribution() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.fatsecret_attribution), style = MaterialTheme.typography.labelSmall, color = FitnessSubtle)
        Text(stringResource(R.string.open_food_facts_attribution), style = MaterialTheme.typography.labelSmall, color = FitnessSubtle)
    }
}
