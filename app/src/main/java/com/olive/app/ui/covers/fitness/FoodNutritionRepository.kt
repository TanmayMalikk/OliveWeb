// Fitness cover users: searches FatSecret and Open Food Facts for foods and nutrition values.
package com.olive.app.ui.covers.fitness

import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import com.olive.app.data.network.nonNegativeFloat
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
    /**
     * Searches the food nutrition data source.
     *
     * @param query Search query string entered by the user.
     * @param page Page supplied to [search].
     * @return The resulting [FoodSearchResult] instance.
     */
    suspend fun search(query: String, page: Int): FoodSearchResult
    /**
     * Computes suggestions.
     *
     * @param query Search query string entered by the user.
     * @return List of resulting suggestions items.
     */
    suspend fun suggestions(query: String): List<String>
    /**
     * Looks up barcode.
     *
     * @param barcode Barcode supplied to [lookupBarcode].
     * @return The resulting [FoodSearchResult] for lookup barcode.
     */
    suspend fun lookupBarcode(barcode: String): FoodSearchResult
    /**
     * Computes food by ID.
     *
     * @param id ID supplied to [foodById].
     * @return The resulting [NutritionFood], or `null` if unavailable.
     */
    suspend fun foodById(id: String): NutritionFood? = null
}

internal class FoodNutritionRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val signedIn: () -> Boolean = { runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null }.getOrDefault(false) },
    functions: () -> FirebaseFunctions = {
        FirebaseFunctions.getInstance("northamerica-northeast1")
    },
) : FoodNutritionDataSource {
    // Manual entry and scanning can open before Firebase is available.
    private val remoteFunctions by lazy(functions)
    /**
     * Both sources are independent. A failure in one source does not hide results from the other.
     *
     * @param query Search query string entered by the user.
     * @return The resulting [FoodSearchResult] instance.
     */
    suspend fun search(query: String): FoodSearchResult = search(query, 0)

    /**
     * Searches the target.
     *
     * @param query Search query string entered by the user.
     * @param page Page supplied to [search].
     * @return The resulting [FoodSearchResult] instance.
     */
    override suspend fun search(query: String, page: Int): FoodSearchResult = search(query, page, "")

    /**
     * Searches the target.
     *
     * @param query Search query string entered by the user.
     * @param page Page supplied to [search].
     * @param foodType Food type supplied to [search].
     * @return The resulting [FoodSearchResult] instance.
     */
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
            needsSignIn = !signedIn() && searches.any {
                (it.exceptionOrNull() as? com.google.firebase.functions.FirebaseFunctionsException)?.code ==
                    com.google.firebase.functions.FirebaseFunctionsException.Code.UNAUTHENTICATED
            },
            needsIpApproval = searches.any {
                (it.exceptionOrNull() as? com.google.firebase.functions.FirebaseFunctionsException)?.code ==
                    com.google.firebase.functions.FirebaseFunctionsException.Code.FAILED_PRECONDITION
            },
        )
    }

    /**
     * Computes suggestions.
     *
     * @param query Search query string entered by the user.
     * @return List of resulting suggestions items.
     */
    override suspend fun suggestions(query: String): List<String> {
        val response = remoteFunctions.getHttpsCallable("nutritionFoodSearch")
            .call(mapOf("query" to query.trim().take(80), "action" to "suggest")).await()
            .data as? Map<*, *> ?: return emptyList()
        return (response["suggestions"] as? List<*>).orEmpty().filterIsInstance<String>().take(4)
    }

    /**
     * Looks up barcode.
     *
     * @param barcode Barcode supplied to [lookupBarcode].
     * @return The resulting [FoodSearchResult] for lookup barcode.
     */
    override suspend fun lookupBarcode(barcode: String): FoodSearchResult = try {
        fatSecret(mapOf("barcode" to barcode.trim()))
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        FoodSearchResult(providerFailures = 2, needsIpApproval =
            (error as? com.google.firebase.functions.FirebaseFunctionsException)?.code ==
                com.google.firebase.functions.FirebaseFunctionsException.Code.FAILED_PRECONDITION)
    }

    /**
     * Searches fat secret.
     *
     * @param query Search query string entered by the user.
     * @param page Page supplied to [searchFatSecret].
     * @param foodType Food type supplied to [searchFatSecret].
     * @return The resulting search fat secret.
     */
    private suspend fun searchFatSecret(query: String, page: Int, foodType: String) =
        fatSecret(mapOf("query" to query, "page" to page, "foodType" to foodType))

    /**
     * Computes food by ID.
     *
     * @param id ID supplied to [foodById].
     * @return The resulting [NutritionFood], or `null` if unavailable.
     */
    override suspend fun foodById(id: String): NutritionFood? {
        val foodId = id.removePrefix("fatsecret:").substringBefore(':')
        val food = fatSecret(mapOf("action" to "detail", "foodId" to foodId)).foods.firstOrNull() ?: return null
        if (food.id == id) return food
        return food.servingOptions.firstOrNull { it.id == id }?.copy(servingOptions = food.servingOptions)
    }

    /**
     * Computes fat secret.
     *
     * @param parameters WorkManager parameters supplied for this worker execution.
     * @return The resulting [FoodSearchResult] for fat secret.
     */
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

    /**
     * Searches Open Food Facts when packaged-food results are needed.
     *
     * @param query Search query string entered by the user.
     * @param page Page supplied to [searchOpenFoodFacts].
     * @return The resulting [FoodSearchResult] for search open food facts.
     */
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
    val needsSignIn: Boolean = false,
)

/**
 * Converts a FatSecret result into the shared nutrition model.
 *
 * @param value Input value to store, parse, or format.
 * @return The resulting [NutritionFood], or `null` if unavailable.
 */
internal fun parseFatSecretFood(value: Map<*, *>): NutritionFood? {
    val name = value["name"]?.toString()?.trim().orEmpty()
    if (name.isBlank()) return null
    return NutritionFood(
        id = "fatsecret:${value["id"]}",
        name = name,
        brand = value["brand"]?.toString().orEmpty(),
        servingDescription = value["servingDescription"]?.toString()
            ?.takeIf(String::isNotBlank) ?: "1 serving",
        calories = value.nonNegativeFloat("calories"),
        proteinGrams = value.nonNegativeFloat("proteinGrams"),
        carbohydrateGrams = value.nonNegativeFloat("carbohydrateGrams"),
        fatGrams = value.nonNegativeFloat("fatGrams"),
        source = FoodDataSource.FATSECRET,
        barcode = value["barcode"]?.toString().orEmpty(),
        saturatedFatGrams = value.nonNegativeFloat("saturatedFatGrams"),
        transFatGrams = value.nonNegativeFloat("transFatGrams"),
        fiberGrams = value.nonNegativeFloat("fiberGrams"),
        sugarGrams = value.nonNegativeFloat("sugarGrams"),
        sodiumMilligrams = value.nonNegativeFloat("sodiumMilligrams"),
        cholesterolMilligrams = value.nonNegativeFloat("cholesterolMilligrams"),
        potassiumMilligrams = value.nonNegativeFloat("potassiumMilligrams"),
        servingOptions = (value["servings"] as? List<*>).orEmpty().mapNotNull { raw ->
            val serving = raw as? Map<*, *> ?: return@mapNotNull null
            parseFatSecretFood(value.filterKeys { it != "servings" } + serving +
                mapOf("id" to "${value["id"]}:${serving["servingId"]}"))
        },
    )
}

/**
 * Fitness cover: Converts open food facts product.
 *
 * @param product Optional product supplied to [parseOpenFoodFactsProduct].
 * @return The resulting [NutritionFood], or `null` if unavailable.
 */
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
        calories = nutrients.nonNegativeFloat("energy-kcal_100g"),
        proteinGrams = nutrients.nonNegativeFloat("proteins_100g"),
        carbohydrateGrams = nutrients.nonNegativeFloat("carbohydrates_100g"),
        fatGrams = nutrients.nonNegativeFloat("fat_100g"),
        source = FoodDataSource.OPEN_FOOD_FACTS,
        barcode = product.optString("code"),
        saturatedFatGrams = nutrients.nonNegativeFloat("saturated-fat_100g"),
        transFatGrams = nutrients.nonNegativeFloat("trans-fat_100g"),
        fiberGrams = nutrients.nonNegativeFloat("fiber_100g"),
        sugarGrams = nutrients.nonNegativeFloat("sugars_100g"),
        sodiumMilligrams = nutrients.nonNegativeFloat("sodium_100g") * 1_000f,
        cholesterolMilligrams = nutrients.nonNegativeFloat("cholesterol_100g") * 1_000f,
        potassiumMilligrams = nutrients.nonNegativeFloat("potassium_100g") * 1_000f,
    )
}

/**
 * Keeps only foods that include calories and the three main macros.
 *
 * @return `true` if has core nutrition, or `false` otherwise.
 */
private fun JSONObject.hasCoreNutrition() =
    has("energy-kcal_100g") && has("proteins_100g") &&
            has("carbohydrates_100g") && has("fat_100g")

/**
 * Cancellation belongs to the screen lifecycle, not to provider fallback.
 *
 * @param block Callback or suspending lambda executed within this scope.
 * @return Outcome [Result] wrapping the provider result result or failure.
 */
private suspend fun <T> providerResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: kotlinx.coroutines.CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}
