package com.olive.app.ui.covers.olive

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.olive.app.data.*

enum class CoverTab { Discover, Saved, Cart, Account }

// Runs Olive's own homemade menu, search, favourites, ingredient choices, and cart.
class OliveViewModel(private val repository: CatalogRepository = DemoCatalogRepository()) : ViewModel() {
    var dishes by mutableStateOf(repository.dishes()); private set
    var catalogMessage by mutableStateOf("Homemade by Olive Kitchen"); private set
    // The address changes delivery details only because Olive prepares every menu item itself.
    fun updateLocation(address: DeliveryAddress) {
        chooseDeliveryArea(address.label.ifBlank { address.city })
        dishes=repository.dishes();catalogMessage="Homemade by Olive Kitchen"
    }
    var query by mutableStateOf(""); private set
    var category by mutableStateOf("All"); private set
    var tab by mutableStateOf(CoverTab.Discover); private set
    var selectedDish by mutableStateOf<Dish?>(null); private set
    private val tabHistory=mutableListOf<CoverTab>()
    var favorites by mutableStateOf(setOf<String>()); private set
    var cart by mutableStateOf(mapOf<String, Int>()); private set
    var customizations by mutableStateOf<Map<String,Set<String>>>(emptyMap()); private set
    var persistCover: ((Map<String,Int>,Set<String>,Map<String,Set<String>>)->Unit)?=null
    fun restoreCover(cart: Map<String,Int>, favorites: Set<String>, customizations: Map<String,Set<String>>) {
        this.cart=cart;this.favorites=favorites;this.customizations=customizations
    }
    var deliveryArea by mutableStateOf(""); private set
    fun chooseDeliveryArea(value: String) { deliveryArea=value.trim().take(60) }
    val results get() = filterDishes(dishes, query, category)
    val total get() = cartTotal(dishes, cart)
    val itemCount get() = cart.values.sum()
    // Normal food searches filter the menu; the session layer checks the secret trigger first.
    fun search(value: String) { query = value }
    fun filter(value: String) { category = value }
    fun navigate(value: CoverTab) {
        if(value!=tab) tabHistory += tab
        tab = value; selectedDish = null
    }
    fun select(dish: Dish?) { selectedDish = dish }
    // Phone Back closes a dish or returns to the tab the user actually came from.
    fun back(): Boolean {
        if(selectedDish!=null) { selectedDish=null;return true }
        if(tabHistory.isNotEmpty()) { tab=tabHistory.removeAt(tabHistory.lastIndex);return true }
        if(tab!=CoverTab.Discover) { tab=CoverTab.Discover;return true }
        return false
    }
    fun favorite(id: String) {
        favorites = if (id in favorites) favorites - id else favorites + id
        persistCover?.invoke(cart,favorites,customizations)
    }
    fun selectedIngredients(dish: Dish)=customizations[dish.id] ?: dish.ingredients.toSet()
    fun toggleIngredient(dish: Dish, ingredient: String) {
        val selected=selectedIngredients(dish)
        customizations=customizations+(dish.id to if(ingredient in selected) selected-ingredient else selected+ingredient)
        persistCover?.invoke(cart,favorites,customizations)
    }
    // Keeps cart quantities sane and removes a dish when its count hits zero.
    fun changeQuantity(id: String, delta: Int) {
        if (dishes.none { it.id == id }) return
        val quantity = ((cart[id] ?: 0) + delta).coerceIn(0, 99)
        cart = if (quantity == 0) cart - id else cart + (id to quantity)
        if(quantity>0 && id !in customizations) dishes.firstOrNull { it.id==id }?.let { customizations=customizations+(id to it.ingredients.toSet()) }
        persistCover?.invoke(cart,favorites,customizations)
    }
    fun returnToCover() { selectedDish = null; query = ""; tab = CoverTab.Discover;tabHistory.clear() }
}
