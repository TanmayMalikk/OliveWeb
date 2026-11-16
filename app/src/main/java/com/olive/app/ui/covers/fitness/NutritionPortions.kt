package com.olive.app.ui.covers.fitness

// Match the diary storage range and accept the decimal separator used by the keyboard locale.
internal fun parseServingAmount(value: String): Float? {
    val clean = value.trim()
    if (!Regex("""(?:[0-9]+(?:[.,][0-9]*)?|[.,][0-9]+)""").matches(clean)) return null
    return clean.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it in 0.0001f..10_000f }
}

internal data class FoodPortion(val food: NutritionFood, val servings: Float)
internal data class ServingBasis(val amount: Float, val unit: String)
internal fun servingBasis(description: String): ServingBasis? {
    val match = Regex("""([0-9]+(?:[.,][0-9]+)?)\s*(kg|ml|oz|g|l)\b""", RegexOption.IGNORE_CASE).find(description) ?: return null
    val raw = match.groupValues[1].replace(',', '.').toFloatOrNull() ?: return null
    val unit = match.groupValues[2].lowercase()
    val amount = raw * if (unit == "kg" || unit == "l") 1000f else 1f
    return ServingBasis(amount, when(unit) { "kg" -> "g"; "l" -> "ml"; else -> unit }).takeIf { amount.isFinite() && amount > 0 }
}
internal fun servingUnits(food: NutritionFood) = (listOf(food) + food.servingOptions)
    .mapNotNull { servingBasis(it.servingDescription)?.unit }.distinct()
internal fun customFoodPortion(food: NutritionFood, amount: String, unit: String): FoodPortion? {
    val quantity = parseServingAmount(amount) ?: return null
    if (unit == "serving") return FoodPortion(food, quantity)
    val base = (listOf(food) + food.servingOptions).mapNotNull { option ->
        servingBasis(option.servingDescription)?.takeIf { it.unit == unit }?.let { option to it.amount }
    }.maxByOrNull { it.second } ?: return null
    val factor = quantity / base.second
    return FoodPortion(base.first, factor).takeIf { factor.isFinite() && factor in 0.0001f..10_000f }
}
