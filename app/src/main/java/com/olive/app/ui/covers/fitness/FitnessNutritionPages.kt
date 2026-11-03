// Fitness cover users: shows nutrition totals, logged meals, and food search.
package com.olive.app.ui.covers.fitness

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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.shared.oliveScrollbar
import kotlinx.coroutines.launch

// Shows today's calorie and macro totals with meal logging.
@Composable
internal fun TodayPage(
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
                    stringResource(R.string.nutrition_work_in_progress),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(stringResource(R.string.daily_nutrition), color = FitnessSubtle)
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
            // Show the empty diary message until the user logs food.
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

// fitness cover shows this daily nutrition card view so the user can view its details and use its actions.
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
                modifier = Modifier.fillMaxWidth().height(9.dp).clip(CircleShape).semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(calorieProgress, 0f..1f)
                },
            )
            NutritionProgress(stringResource(R.string.protein), totals.proteinGrams, profile.proteinGoal.toFloat(), "g")
            NutritionProgress(stringResource(R.string.carbohydrates), totals.carbohydrateGrams, profile.carbohydrateGoal.toFloat(), "g")
            NutritionProgress(stringResource(R.string.fat), totals.fatGrams, profile.fatGoal.toFloat(), "g")
            HorizontalDivider()
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

// Shows eaten, target, and remaining calories in one row.
@Composable
private fun NutritionAmountRow(label: String, value: Float, unit: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text("${value.displayAmount()} $unit")
    }
}
// Draws progress toward one calorie or macro goal.
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

// Shows one diary food with nutrients and a remove action.
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
// Searches nutrition providers and lets the user add a food result.
@Composable
internal fun FoodSearchPage(
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
            ) { Icon(Icons.Outlined.Search, stringResource(R.string.search)) }
        }
        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (searched && result.foods.isEmpty()) {
            val message = if (result.providerFailures >= 2) R.string.food_search_unavailable else R.string.no_food_results
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

// fitness cover shows this food search result card view so the user can view its details and use its actions.
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
            OutlinedButton(onClick = onAdd, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.add_to_diary))
            }
        }
    }
}

// fitness cover shows this add food dialog view so the user can view its details and use its actions.
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
                            "C ${food.carbohydrateGrams.displayAmount()} g · F ${food.fatGrams.displayAmount()} g",
                )
                OutlinedTextField(
                    value = servings,
                    onValueChange = { value -> servings = value.filter { it.isDigit() || it == '.' }.take(6) },
                    label = { Text(stringResource(R.string.servings)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                MealType.entries.forEach { option ->
                    OutlinedButton(
                        onClick = { meal = option },
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = if (meal == option) "${option.displayName}, selected" else option.displayName
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
// Shows the required FatSecret and Open Food Facts credits.
@Composable
private fun ProviderAttribution() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.fatsecret_attribution), style = MaterialTheme.typography.labelSmall, color = FitnessSubtle)
        Text(stringResource(R.string.open_food_facts_attribution), style = MaterialTheme.typography.labelSmall, color = FitnessSubtle)
    }
}
