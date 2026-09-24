package com.olive.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import com.olive.app.BuildConfig
import com.olive.app.data.*

enum class CoverTab { Discover, Saved, Cart, Account }

// Runs the food side of Olive: dishes, search, favourites, cart, and nearby-menu loading.
class OliveViewModel(private val repository: CatalogRepository = DemoCatalogRepository()) : ViewModel() {
    var dishes by mutableStateOf(repository.dishes()); private set
    var catalogMessage by mutableStateOf("Sample menu · nearby restaurant menus are not connected yet"); private set
    private var catalogJob: Job? = null
    private var locationKey: String? = null
    // A new saved address refreshes the delivery label and, when connected, the nearby catalogue.
    fun updateLocation(address: DeliveryAddress) {
        chooseDeliveryArea(address.label.ifBlank { address.city })
        if(locationKey==address.generalLocation) return
        locationKey=address.generalLocation
        catalogJob?.cancel()
        selectedDish=null
        if(BuildConfig.CATALOG_ENDPOINT.isBlank() || !address.valid) {
            dishes=repository.dishes()
            catalogMessage="Sample menu · nearby restaurant menus are not connected yet"
            return
        }
        dishes=emptyList(); catalogMessage="Finding restaurants near your delivery area…"
        catalogJob=viewModelScope.launch {
            try {
                val result=NearbyCatalogRepository(BuildConfig.CATALOG_ENDPOINT).dishes(address)
                ensureActive()
                dishes=result
                catalogMessage=if(result.isEmpty()) "No verified menus are available in your delivery area." else "Menus near your delivery area"
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { catalogMessage="Nearby menus are unavailable. Please try again later." }
        }
    }
    var query by mutableStateOf(""); private set
    var category by mutableStateOf("All"); private set
    var tab by mutableStateOf(CoverTab.Discover); private set
    var selectedDish by mutableStateOf<Dish?>(null); private set
    var favorites by mutableStateOf(setOf<String>()); private set
    var cart by mutableStateOf(mapOf<String, Int>()); private set
    var persistCover: ((Map<String,Int>,Set<String>)->Unit)?=null
    fun restoreCover(cart: Map<String,Int>, favorites: Set<String>) {
        this.cart=cart;this.favorites=favorites
    }
    var deliveryArea by mutableStateOf(""); private set
    fun chooseDeliveryArea(value: String) { deliveryArea=value.trim().take(60) }
    val results get() = filterDishes(dishes, query, category)
    val total get() = cartTotal(dishes, cart)
    val itemCount get() = cart.values.sum()
    // Normal food searches filter the menu; the session layer checks the secret trigger first.
    fun search(value: String) { query = value }
    fun filter(value: String) { category = value }
    fun navigate(value: CoverTab) { tab = value; selectedDish = null }
    fun select(dish: Dish?) { selectedDish = dish }
    fun favorite(id: String) {
        favorites = if (id in favorites) favorites - id else favorites + id
        persistCover?.invoke(cart,favorites)
    }
    // Keeps cart quantities sane and removes a dish when its count hits zero.
    fun changeQuantity(id: String, delta: Int) {
        if (dishes.none { it.id == id }) return
        val quantity = ((cart[id] ?: 0) + delta).coerceIn(0, 99)
        cart = if (quantity == 0) cart - id else cart + (id to quantity)
        persistCover?.invoke(cart,favorites)
    }
    fun returnToCover() { selectedDish = null; query = ""; tab = CoverTab.Discover }
}
