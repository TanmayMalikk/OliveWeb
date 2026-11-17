// Fitness cover users: searches FatSecret and Open Food Facts for foods and nutrition values.
package com.olive.app.ui.covers.fitness

import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

internal interface FoodNutritionDataSource {
    suspend fun search(query: String, page: Int): FoodSearchResult
    suspend fun suggestions(query: String): List<String>
    suspend fun lookupBarcode(barcode: String): FoodSearchResult
}

internal class FoodNutritionRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    functions: () -> FirebaseFunctions = {
        FirebaseFunctions.getInstance("northamerica-northeast1")
    },
) : FoodNutritionDataSource {
    // Manual entry and scanning can open before Firebase is available.
    private val remoteFunctions by lazy(functions)
    // Both sources are independent. A failure in one source does not hide results from the other.
    suspend fun search(query: String): FoodSearchResult = search(query, 0)

    override suspend fun search(query: String, page: Int): FoodSearchResult = search(query, page, "")

    suspend fun search(query: String, page: Int = 0, foodType: String): FoodSearchResult = coroutineScope {
        val cleanQuery = query.trim().take(80)
        if (cleanQuery.length < 2) return@coroutineScope FoodSearchResult()

        val searches = listOf(
            async { providerResult { searchFatSecret(cleanQuery, page, foodType) } },
            async { providerResult {
                if (foodType.isBlank()) searchOpenFoodFacts(cleanQuery, page) else FoodSearchResult()
            } },
        ).awaitAll()
        searches.forEachIndexed { index, search ->
            search.exceptionOrNull()?.let { error ->
                val provider = if (index == 0) "FatSecret" else "Open Food Facts"
                Log.w("FitnessFoodSearch", "$provider search failed", error)
            }
        }
        val foods = searches.flatMap { it.getOrDefault(FoodSearchResult()).foods }
            .distinctBy { food ->
                food.barcode.takeIf(String::isNotBlank)
                    ?: "${food.name.lowercase()}|${food.brand.lowercase()}"
            }
            .take(40)
        FoodSearchResult(
            foods = foods,
            providerFailures = searches.count { it.isFailure } * if (foodType.isBlank()) 1 else 2,
            hasMore = searches.any { it.getOrNull()?.hasMore == true },
            needsIpApproval = searches.any {
                (it.exceptionOrNull() as? com.google.firebase.functions.FirebaseFunctionsException)?.code ==
                    com.google.firebase.functions.FirebaseFunctionsException.Code.FAILED_PRECONDITION
            },
        )
    }

    override suspend fun suggestions(query: String): List<String> {
        val response = remoteFunctions.getHttpsCallable("nutritionFoodSearch")
            .call(mapOf("query" to query.trim().take(80), "action" to "suggest")).await()
            .data as? Map<*, *> ?: return emptyList()
        return (response["suggestions"] as? List<*>).orEmpty().filterIsInstance<String>().take(4)
    }

    override suspend fun lookupBarcode(barcode: String): FoodSearchResult = try {
        fatSecret(mapOf("barcode" to barcode.trim()))
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        FoodSearchResult(providerFailures = 2, needsIpApproval =
            (error as? com.google.firebase.functions.FirebaseFunctionsException)?.code ==
                com.google.firebase.functions.FirebaseFunctionsException.Code.FAILED_PRECONDITION)
    }

    private suspend fun searchFatSecret(query: String, page: Int, foodType: String) =
        fatSecret(mapOf("query" to query, "page" to page, "foodType" to foodType))

    private suspend fun fatSecret(parameters: Map<String, Any>): FoodSearchResult {
        val response = remoteFunctions.getHttpsCallable("nutritionFoodSearch")
            .call(parameters).await().data as? Map<*, *> ?: error("Missing food response")
        return FoodSearchResult(
            foods = (response["foods"] as? List<*>).orEmpty().mapNotNull { raw ->
                parseFatSecretFood(raw as? Map<*, *> ?: return@mapNotNull null)
            },
            hasMore = response["hasMore"] == true,
        )
    }

    // Searches Open Food Facts when packaged-food results are needed.
    private suspend fun searchOpenFoodFacts(query: String, page: Int): FoodSearchResult =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            val url = "https://world.openfoodfacts.org/cgi/search.pl" +
                    "?search_terms=$encoded&search_simple=1&action=process&json=1&page_size=20" +
                    "&page=${page + 1}&fields=code,product_name,brands,nutriments"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "OliveBranchSchoolProject/1.0 (yourolivebranch.ca)")
                .build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Open Food Facts returned ${response.code}" }
                val body = response.body.string()
                val payload = JSONObject(body)
                val products = payload.optJSONArray("products") ?: return@use FoodSearchResult()
                val foods = buildList {
                    for (index in 0 until products.length()) {
                        parseOpenFoodFactsProduct(products.optJSONObject(index))?.let(::add)
                    }
                }
                FoodSearchResult(foods = foods, hasMore = (page + 1) * 20 < payload.optInt("count"))
            }
        }
}

internal data class FoodSearchResult(
    val foods: List<NutritionFood> = emptyList(),
    val providerFailures: Int = 0,
    val hasMore: Boolean = false,
    val needsIpApproval: Boolean = false,
)

// Converts a FatSecret result into the shared nutrition model.
internal fun parseFatSecretFood(value: Map<*, *>): NutritionFood? {
    val name = value["name"]?.toString()?.trim().orEmpty()
    if (name.isBlank()) return null
    return NutritionFood(
        id = "fatsecret:${value["id"]}",
        name = name,
        brand = value["brand"]?.toString().orEmpty(),
        servingDescription = value["servingDescription"]?.toString()
            ?.takeIf(String::isNotBlank) ?: "1 serving",
        calories = value.number("calories"),
        proteinGrams = value.number("proteinGrams"),
        carbohydrateGrams = value.number("carbohydrateGrams"),
        fatGrams = value.number("fatGrams"),
        source = FoodDataSource.FATSECRET,
        barcode = value["barcode"]?.toString().orEmpty(),
        saturatedFatGrams = value.number("saturatedFatGrams"),
        transFatGrams = value.number("transFatGrams"),
        fiberGrams = value.number("fiberGrams"),
        sugarGrams = value.number("sugarGrams"),
        sodiumMilligrams = value.number("sodiumMilligrams"),
        cholesterolMilligrams = value.number("cholesterolMilligrams"),
        potassiumMilligrams = value.number("potassiumMilligrams"),
        servingOptions = (value["servings"] as? List<*>).orEmpty().mapNotNull { raw ->
            val serving = raw as? Map<*, *> ?: return@mapNotNull null
            parseFatSecretFood(value.filterKeys { it != "servings" } + serving +
                mapOf("id" to "${value["id"]}:${serving["servingId"]}"))
        },
    )
}

// Fitness nutrition: Reads a nutrient number from an API map and prevents negative or missing values.
private fun Map<*, *>.number(key: String) =
    (this[key] as? Number)?.toFloat()?.takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f

// Fitness cover: Converts open food facts product.
private fun parseOpenFoodFactsProduct(product: JSONObject?): NutritionFood? {
    product ?: return null
    val name = product.optString("product_name").trim()
    if (name.isBlank()) return null
    val nutrients = product.optJSONObject("nutriments") ?: JSONObject()
    if (!nutrients.hasCoreNutrition()) return null
    return NutritionFood(
        id = "openfoodfacts:${product.optString("code", name)}",
        name = name,
        brand = product.optString("brands").substringBefore(',').trim(),
        // OFF nutrients below are per 100 g, never per the product's arbitrary serving size.
        servingDescription = "100 g",
        calories = nutrients.nutrient("energy-kcal_100g"),
        proteinGrams = nutrients.nutrient("proteins_100g"),
        carbohydrateGrams = nutrients.nutrient("carbohydrates_100g"),
        fatGrams = nutrients.nutrient("fat_100g"),
        source = FoodDataSource.OPEN_FOOD_FACTS,
        barcode = product.optString("code"),
        saturatedFatGrams = nutrients.nutrient("saturated-fat_100g"),
        transFatGrams = nutrients.nutrient("trans-fat_100g"),
        fiberGrams = nutrients.nutrient("fiber_100g"),
        sugarGrams = nutrients.nutrient("sugars_100g"),
        sodiumMilligrams = nutrients.nutrient("sodium_100g") * 1_000f,
        cholesterolMilligrams = nutrients.nutrient("cholesterol_100g") * 1_000f,
        potassiumMilligrams = nutrients.nutrient("potassium_100g") * 1_000f,
    )
}

// Keeps only foods that include calories and the three main macros.
private fun JSONObject.hasCoreNutrition() =
    has("energy-kcal_100g") && has("proteins_100g") &&
            has("carbohydrates_100g") && has("fat_100g")

// Reads one nutrient value from the food API response.
private fun JSONObject.nutrient(name: String) =
    optDouble(name, 0.0).toFloat().takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f

// Cancellation belongs to the screen lifecycle, not to provider fallback.
private suspend fun <T> providerResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: kotlinx.coroutines.CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}
