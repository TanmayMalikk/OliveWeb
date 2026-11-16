package com.olive.app.ui.covers.fitness



import com.olive.app.R

import java.util.UUID



internal enum class LabelNutrient(val label: Int, val unit: String, val required: Boolean = false) {

    CALORIES(R.string.calories, "kcal", true),

    PROTEIN(R.string.protein, "g", true),

    CARBS(R.string.carbohydrates, "g", true),

    FAT(R.string.fat, "g", true),

    SATURATED_FAT(R.string.saturated_fat, "g"),

    TRANS_FAT(R.string.trans_fat, "g"),

    FIBER(R.string.fiber, "g"),

    SUGAR(R.string.sugar, "g"),

    SODIUM(R.string.sodium, "mg"),

    CHOLESTEROL(R.string.cholesterol, "mg"),

    POTASSIUM(R.string.potassium, "mg"),

}



internal data class ManualNutritionDraft(

    val name: String = "",

    val serving: String = "",

    val nutrients: Map<LabelNutrient, String> = emptyMap(),

) {

    fun amount(field: LabelNutrient): Float? {

        val raw = nutrients[field].orEmpty().trim()

        if (raw.isEmpty()) return if (field.required) null else 0f

        if (!Regex("""(?:[0-9]+(?:[.,][0-9]*)?|[.,][0-9]+)""").matches(raw)) return null

        return raw.replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it in 0f..1_000_000f }

    }



    val valid: Boolean get() = name.isNotBlank() && serving.isNotBlank() &&

        LabelNutrient.entries.all { amount(it) != null }



    fun food(): NutritionFood? {

        if (!valid) return null

        return NutritionFood(

            id = "manual:${UUID.randomUUID()}", name = name.trim(), brand = "",

            servingDescription = serving.trim(), source = FoodDataSource.MANUAL,

            calories = amount(LabelNutrient.CALORIES)!!,

            proteinGrams = amount(LabelNutrient.PROTEIN)!!,

            carbohydrateGrams = amount(LabelNutrient.CARBS)!!,

            fatGrams = amount(LabelNutrient.FAT)!!,

            saturatedFatGrams = amount(LabelNutrient.SATURATED_FAT)!!,

            transFatGrams = amount(LabelNutrient.TRANS_FAT)!!,

            fiberGrams = amount(LabelNutrient.FIBER)!!,

            sugarGrams = amount(LabelNutrient.SUGAR)!!,

            sodiumMilligrams = amount(LabelNutrient.SODIUM)!!,

            cholesterolMilligrams = amount(LabelNutrient.CHOLESTEROL)!!,

            potassiumMilligrams = amount(LabelNutrient.POTASSIUM)!!,

        )

    }

}



internal data class NutritionLabelReading(

    val serving: String?,

    val nutrients: Map<LabelNutrient, String>,

)



// OCR only proposes values. Missing fields remain blank for the user to check against the label.

internal fun parseNutritionLabel(text: String): NutritionLabelReading {

    val lines = text.take(30_000).lines().map(String::trim).filter(String::isNotEmpty)

    val labels = mapOf(

        LabelNutrient.CALORIES to "(?:calories|energy|énergie)",

        LabelNutrient.PROTEIN to "(?:protein|protéines?)",

        LabelNutrient.CARBS to "(?:(?:total )?carbohydrates?|glucides)",

        LabelNutrient.FAT to "(?:(?:total )?fat|lipides)",

        LabelNutrient.SATURATED_FAT to "(?:saturated(?: fat)?|saturés?)",

        LabelNutrient.TRANS_FAT to "(?:trans(?: fat)?)",

        LabelNutrient.FIBER to "(?:(?:dietary )?fib(?:er|re)|fibres)",

        LabelNutrient.SUGAR to "(?:(?:total )?sugars?|sucres)",

        LabelNutrient.SODIUM to "sodium",

        LabelNutrient.CHOLESTEROL to "(?:cholesterol|cholestérol)",

        LabelNutrient.POTASSIUM to "potassium",

    )

    val values = mutableMapOf<LabelNutrient, String>()

    for ((field, label) in labels) {

        val index = lines.indexOfFirst { Regex("""^$label\b""", RegexOption.IGNORE_CASE).containsMatchIn(it) }

        if (index < 0) continue

        val line = lines[index]

        val remainder = line.replaceFirst(Regex("""^$label\b""", RegexOption.IGNORE_CASE), "")

        // A following line is used only when it starts with a value, never another nutrient label.

        val next = lines.getOrNull(index + 1).orEmpty().takeIf {

            Regex("""^[0-9]""").containsMatchIn(it)

        }.orEmpty()

        val candidate = if (remainder.any(Char::isDigit)) remainder else next

        val number = if (field == LabelNutrient.CALORIES) {

            // Prefer kcal explicitly; never interpret kJ as kcal or a daily-value percentage as energy.

            Regex("""(?<![0-9.,-])([0-9]+(?:[.,][0-9]+)?)\s*kcal""", RegexOption.IGNORE_CASE)

                .find(candidate)?.groupValues?.get(1)

                ?: Regex("""^\s*[:/]?\s*([0-9]+(?:[.,][0-9]+)?)(?![0-9.,])\s*(?:$|cal\b)""", RegexOption.IGNORE_CASE)

                    .find(candidate)?.groupValues?.get(1)

        } else {

            Regex("""(?<![0-9.,-])([0-9]+(?:[.,][0-9]+)?)\s*(mg|g)\b""", RegexOption.IGNORE_CASE)

                .find(candidate)?.let { match ->

                    val amount = match.groupValues[1].replace(',', '.').toFloatOrNull() ?: return@let null

                    val unit = match.groupValues[2].lowercase()

                    val converted = when {

                        field.unit == "mg" && unit == "g" -> amount * 1000f

                        field.unit == "g" && unit == "mg" -> amount / 1000f

                        else -> amount

                    }

                    converted.takeIf { it.isFinite() && it in 0f..1_000_000f }?.toString()

                }

        }

        number?.replace(',', '.')?.let { values[field] = it }

    }

    val serving = lines.firstNotNullOfOrNull { line ->

        Regex("""^(?:serving size|portion|per|pour)\s*[:/]?\s+(.+)$""", RegexOption.IGNORE_CASE)

            .find(line)?.groupValues?.get(1)?.trim()?.takeIf {

                it.any(Char::isDigit) && !it.contains('%') && !it.contains("container", ignoreCase = true)

            }

    }

    return NutritionLabelReading(serving, values)

}



internal fun manualDraftFromFood(food: NutritionFood) = ManualNutritionDraft(food.name, food.servingDescription,

    listOf(food.calories, food.proteinGrams, food.carbohydrateGrams, food.fatGrams,

        food.saturatedFatGrams, food.transFatGrams, food.fiberGrams, food.sugarGrams,

        food.sodiumMilligrams, food.cholesterolMilligrams, food.potassiumMilligrams)

        .mapIndexed { index, value -> LabelNutrient.entries[index] to java.math.BigDecimal(value.toString()).stripTrailingZeros().toPlainString() }.toMap())
