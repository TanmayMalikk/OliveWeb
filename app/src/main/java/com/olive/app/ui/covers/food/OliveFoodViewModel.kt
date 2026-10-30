// Food cover users: holds olive food view state.
package com.olive.app.ui.covers.food

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.olive.app.data.*
import com.olive.app.data.DemoCatalogRepository
import java.util.Locale

internal enum class CoverTab { Discover, Saved, Cart, Account }

// Runs Olive's own homemade menu, search, favourites, ingredient choices, and cart.
internal class OliveViewModel(private val repository: CatalogRepository) : ViewModel() {
    var dishes by mutableStateOf(repository.dishes())
        private set

    // The address changes delivery details only because Olive prepares every menu item itself.
    fun updateLocation(address: DeliveryAddress) {
        chooseDeliveryArea(address.label.ifBlank { address.city })
        dishes = repository.dishes()
    }

    var query by mutableStateOf("")
        private set
    var category by mutableStateOf("All")
        private set
    var tab by mutableStateOf(CoverTab.Discover)
        private set
    var selectedDish by mutableStateOf<Dish?>(null)
        private set
    private val tabHistory = mutableListOf<CoverTab>()
    var favorites by mutableStateOf(setOf<String>())
        private set
    var cart by mutableStateOf(mapOf<String, Int>())
        private set
    var customizations by mutableStateOf<Map<String, Set<String>>>(emptyMap())
        private set
    var persistCover: ((Map<String, Int>, Set<String>, Map<String, Set<String>>) -> Unit)? = null
    // Restores this account's cart, favourites, and ingredient choices after sign-in.
    fun restoreCover(
        cart: Map<String, Int>,
        favorites: Set<String>,
        customizations: Map<String, Set<String>>
    ) {
        val knownIds = dishes.mapTo(mutableSetOf()) { it.id }
        this.cart = cart.mapNotNull { (id, amount) ->
            id.takeIf { it in knownIds && amount > 0 }?.let { it to amount.coerceAtMost(99) }
        }.toMap()
        this.favorites = favorites.filterTo(mutableSetOf()) { it in knownIds }
        this.customizations = customizations.filterKeys { it in knownIds }
    }

    var deliveryArea by mutableStateOf("")
        private set

    fun chooseDeliveryArea(value: String) {
        deliveryArea = value.trim().take(60)
    }

    private var localizedSearch by mutableStateOf<Map<String, String>>(emptyMap())
    // Replaces searchable dish labels when Android loads a different language.
    fun updateSearchLabels(labels: Map<String, String>) {
        localizedSearch = labels
    }

    val results
        get():List<Dish> {
            val canonical = filterDishes(dishes, query, category).map { it.id }.toSet()
            return filterDishes(dishes, "", category).filter { dish ->
                dish.id in canonical || localizedSearch[dish.id]?.contains(
                    query.trim(),
                    ignoreCase = true
                ) == true
            }
        }
    val total get() = cartTotal(dishes, cart)
    val itemCount get() = cart.values.sum()

    // Normal food searches filter the menu; the session layer checks the secret trigger first.
    fun search(value: String) {
        query = value
    }

    fun filter(value: String) {
        category = value
    }

    fun navigate(value: CoverTab) {
        if (value != tab) tabHistory += tab
        tab = value
        selectedDish = null
    }

    fun select(dish: Dish?) {
        selectedDish = dish
    }

    // Phone Back closes a dish or returns to the tab the user actually came from.
    fun back(): Boolean {
        if (selectedDish != null) {
            selectedDish = null
            return true
        }
        if (tabHistory.isNotEmpty()) {
            tab = tabHistory.removeAt(tabHistory.lastIndex)
            return true
        }
        if (tab != CoverTab.Discover) {
            tab = CoverTab.Discover
            return true
        }
        return false
    }

    fun favorite(id: String) {
        favorites = if (id in favorites) favorites - id else favorites + id
        persistCover?.invoke(cart, favorites, customizations)
    }

    // Returns the user's saved ingredient choices or the dish defaults when none were changed.
    fun selectedIngredients(dish: Dish) = customizations[dish.id] ?: dish.ingredients.toSet()
    // Switches ingredient on or off.
    fun toggleIngredient(dish: Dish, ingredient: String) {
        val selected = selectedIngredients(dish)
        customizations =
            customizations + (dish.id to if (ingredient in selected) selected - ingredient else selected + ingredient)
        persistCover?.invoke(cart, favorites, customizations)
    }

    // Keeps cart quantities sane and removes a dish when its count hits zero.
    fun changeQuantity(id: String, delta: Int) {
        if (dishes.none { it.id == id }) return
        val quantity = ((cart[id] ?: 0).toLong() + delta.toLong()).coerceIn(0L, 99L).toInt()
        cart = if (quantity == 0) cart - id else cart + (id to quantity)
        if (quantity > 0 && id !in customizations) dishes.firstOrNull { it.id == id }
            ?.let { customizations = customizations + (id to it.ingredients.toSet()) }
        persistCover?.invoke(cart, favorites, customizations)
    }

    fun returnToCover() {
        selectedDish = null
        query = ""
        tab = CoverTab.Discover
        tabHistory.clear()
    }
}

// Base resources preserve legacy ingredient identities in saved carts; FoodLabels renders the active locale.
@Composable
internal fun oliveViewModel(): OliveViewModel {
    val application = LocalContext.current.applicationContext
    val factory = remember(application) {
        val base =
            Configuration(application.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        val resources = application.createConfigurationContext(base).resources
        viewModelFactory { initializer { OliveViewModel(DemoCatalogRepository(resources::getString)) } }
    }
    return viewModel(factory = factory)
}
