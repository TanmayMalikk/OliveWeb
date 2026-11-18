package com.olive.app.ui.covers.fitness

import com.google.android.gms.tasks.Tasks
import com.google.firebase.functions.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class FoodNutritionRepositorySdkTest {
    private val functions=mock(FirebaseFunctions::class.java)
    private val callable=mock(HttpsCallableReference::class.java)
    private val requests=mutableListOf<Map<String,Any>>()
    private val http=mutableListOf<Request>()
    private var result:Any=mapOf("foods" to listOf(mapOf("id" to "rice","name" to "Rice","barcode" to "same","calories" to 100,"proteinGrams" to 4)),"hasMore" to true)
    private var failure:Exception?=null
    private var status=200
    private var body="""{"count":30,"products":[{"code":"same","product_name":"Rice","nutriments":{"energy-kcal_100g":100,"proteins_100g":4,"carbohydrates_100g":20,"fat_100g":1}},{"code":"beans","product_name":"Beans","brands":"Fixture, Other","nutriments":{"energy-kcal_100g":80,"proteins_100g":6,"carbohydrates_100g":15,"fat_100g":1,"sodium_100g":0.1}},{"product_name":"Incomplete"}]}"""
    private val client=OkHttpClient.Builder().addInterceptor { chain ->
        http+=chain.request()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture").body(body.toResponseBody()).build()
    }.build()
    private val repo=FoodNutritionRepository(client){functions}
    init {
        `when`(functions.getHttpsCallable("nutritionFoodSearch")).thenReturn(callable)
        `when`(callable.call(anyMap<String,Any>())).thenAnswer { call ->
            requests+=call.getArgument<Map<String,Any>>(0)
            failure?.let { throw it }
            Tasks.forResult(HttpsCallableResult::class.java.getConstructor(Any::class.java).newInstance(result))
        }
    }
    @Test
    fun searchesMergeProvidersDeduplicateBarcodesAndKeepOpenFoodFactsValuesPer100Grams()=runBlocking<Unit> {
        val found=repo.search("  rice  ")
        assertEquals(listOf("Rice","Beans"),found.foods.map { it.name })
        assertEquals(0,found.providerFailures)
        assertTrue(found.hasMore)
        val beans=found.foods.last()
        assertEquals("100 g",beans.servingDescription)
        assertEquals(100f,beans.sodiumMilligrams)
        assertEquals("Fixture",beans.brand)
        assertEquals("rice",requests.single()["query"])
        assertTrue(http.single().url.toString().contains("search_terms=rice"))
        assertNotNull(http.single().header("User-Agent"))
    }
    @Test
    fun oneProviderFailureKeepsTheOtherProvidersResultsAndTypedSearchSkipsPackagedFoods()=runBlocking<Unit> {
        failure=IOException("Synthetic provider outage")
        val off=repo.search("beans")
        assertEquals(1,off.providerFailures)
        assertEquals(2,off.foods.size)
        failure=null
        http.clear()
        val generic=repo.search("rice",page=1,foodType="Generic")
        assertEquals(1,generic.foods.size)
        assertTrue(http.isEmpty())
        assertEquals(1,requests.last()["page"])
        status=503
        failure=IOException("Both unavailable")
        assertEquals(2,repo.search("beans").providerFailures)
    }
    @Test
    fun autocompleteLimitsSuggestionsAndBarcodeFailureDoesNotProduceFakeFood()=runBlocking<Unit> {
        result=mapOf("suggestions" to listOf("Rice","Rice flour","Rice milk","Rice cakes","Rice pudding",null,4))
        assertEquals(4,repo.suggestions(" rice ").size)
        assertEquals("suggest",requests.single()["action"])
        failure=IOException("Barcode not available")
        val found=repo.lookupBarcode("123456")
        assertTrue(found.foods.isEmpty())
        assertEquals(2,found.providerFailures)
        assertEquals("123456",requests.last()["barcode"])
    }
    @Test
    fun savedFatSecretLookupRetainsTheChosenServingAndOffersOtherPortions() = runBlocking<Unit> {
        result = mapOf("foods" to listOf(mapOf(
            "id" to "123", "name" to "Rice", "servingDescription" to "100 g", "calories" to 130,
            "servings" to listOf(
                mapOf("servingId" to "4", "servingDescription" to "1 cup", "calories" to 205),
                mapOf("servingId" to "5", "servingDescription" to "100 g", "calories" to 130),
            ),
        )))
        val food = checkNotNull(repo.foodById("fatsecret:123:4"))
        assertEquals(mapOf("action" to "detail", "foodId" to "123"), requests.single())
        assertEquals("1 cup", food.servingDescription)
        assertEquals(205f, food.calories)
        assertEquals(listOf("fatsecret:123:4", "fatsecret:123:5"), food.servingOptions.map { it.id })
        assertNull(repo.foodById("fatsecret:123:removed"))
    }

    @Test
    fun shortQueriesDoNotCallProvidersAndCancellationStopsTheSearch()=runBlocking<Unit> {
        assertTrue(repo.search(" r ").foods.isEmpty())
        assertTrue(requests.isEmpty())
        assertTrue(http.isEmpty())
        failure=CancellationException("Screen closed")
        assertThrows(CancellationException::class.java) { runBlocking { repo.lookupBarcode("123456") } }
        assertThrows(CancellationException::class.java) { runBlocking { repo.search("rice") } }
    }
}
