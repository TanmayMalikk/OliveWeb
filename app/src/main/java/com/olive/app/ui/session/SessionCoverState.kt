// Signed-in users: stores the selected discreet cover.
package com.olive.app.ui.session

import com.olive.app.R

import com.olive.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

// Owns account-scoped cover persistence and checkout records.
internal class SessionCoverState(private val runtime: SessionRuntime) {
    // Loads the signed-in account's cart, favourites, customizations, and saved news state.
    suspend fun restoreCoverData(uid: String) {
        with(runtime) {
            with(state) {
                val saved = withContext(Dispatchers.IO) { coverStore.restore(uid) }
                foodCart = saved.first.cart
                foodFavorites =
                    saved.first.favorites
                foodCustomizations = saved.first.customizations
                savedNews =
                    saved.second
                coverDataVersion++
            }
        }
    }

    // Saves the food cover state so its cart and choices survive Safe App entry.
    fun saveFoodCover(
        cart: Map<String, Int>,
        favorites: Set<String>,
        customizations: Map<String, Set<String>>
    ) {
        with(runtime) {
            with(state) {
                val uid = enrollment?.uid ?: return
                foodCart = cart
                foodFavorites = favorites
                foodCustomizations = customizations
                val latest = FoodCoverState(cart, favorites, customizations)
                val revision = (foodRevisions[uid] ?: 0L) + 1L
                foodRevisions[uid] = revision
                scope.launch {
                    coverSaveMutex.withLock {
                        if (foodRevisions[uid] != revision) return@withLock
                        withContext(Dispatchers.IO) { coverStore.saveFood(uid, latest) }
                    }
                }
            }
        }
    }

    // Switches news bookmark on or off.
    fun toggleNewsBookmark(article: NewsArticle) {
        with(runtime) {
            with(state) {
                val uid = enrollment?.uid ?: return
                savedNews =
                    if (savedNews.any { it.link == article.link }) savedNews.filterNot { it.link == article.link }
                    else (listOf(article) + savedNews).take(100)
                val latest = savedNews
                val revision = (newsRevisions[uid] ?: 0L) + 1L
                newsRevisions[uid] = revision
                scope.launch {
                    coverSaveMutex.withLock {
                        if (newsRevisions[uid] != revision) return@withLock
                        withContext(Dispatchers.IO) { coverStore.saveNews(uid, latest) }
                    }
                }
            }
        }
    }

    // Records checkout for session cover state.
    suspend fun recordCheckout(foods: List<OrderLine>, totalCents: Int, error: String) {
        with(runtime) {
            with(state) {
                val uid = enrollment?.uid ?: return
                withContext(Dispatchers.IO) {
                    store.recordCheckout(
                        uid,
                        CheckoutEvent(foods = foods, totalCents = totalCents, error = error)
                    )
                }
                checkoutHistory = withContext(Dispatchers.IO) { store.checkoutHistory(uid) }
                history.queueHistorySync()
            }
        }
    }

    // Dismisses the food-cover address prompt for this visit.
    fun dismissAddressPrompt() {
        with(runtime) {
            with(state) {
                addressPrompt = false
            }
        }
    }

    // Saves the delivery address locally and to the account profile.
    fun saveAddress(address: DeliveryAddress) {
        with(runtime) {
            with(state) {
                val device = enrollment ?: return
                history.recordSettingsInteraction("Cover", "Pressed save delivery address")
                if (busy || !address.valid) {
                    message =
                        runtime.application.getString(R.string.enter_a_complete_delivery_address)
                    return
                }
                operations.runOperation(runtime.application.getString(R.string.unable_to_save_the_address_please_retry)) { token ->
                    val updated = withContext(Dispatchers.IO) {
                        val before = store.profile(device.uid)
                        val after = before.copy(address = address, region = address.generalLocation)
                        store.cacheProfile(
                            device.uid, after, pending = !localMode,
                            audit = SettingsEvent(
                                area = "Cover",
                                action = "Saved delivery address",
                                changes = profileChanges(before, after)
                            )
                        )
                        after
                    }
                    if (epoch == token) {
                        deliveryAddress = address
                        profile = profile?.copy(address = address)
                        addressPrompt = false
                        addressSaveVersion++
                        message =
                            runtime.application.getString(R.string.address_saved)
                    }
                    history.queueHistorySync()
                    if (!localMode) settings.syncProfile(device.uid, updated, token)
                }
            }
        }
    }
}
