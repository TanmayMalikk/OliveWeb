// Tests the cover state store behavior.
package com.olive.app.data

import com.olive.app.security.EncryptedVault
import com.olive.app.security.child
import org.json.JSONArray
import org.json.JSONObject

// Groups the data and actions for food cover state.
data class FoodCoverState(
    val cart: Map<String, Int> = emptyMap(),
    val favorites: Set<String> = emptySet(),
    val customizations: Map<String, Set<String>> = emptyMap(),
)

// Keep cover favourites, bookmarks, and cart on this phone under the account ID.
class CoverStateStore(private val vault: EncryptedVault) {
    // Restore all cover state from one decrypted snapshot during a cold launch.
    fun restore(uid: String): Pair<FoodCoverState, List<NewsArticle>> {
        val root = vault.read()
        val item = root.optJSONObject("accounts")?.optJSONObject(uid)
            ?: return FoodCoverState() to emptyList()
        return foodFrom(item) to newsFrom(item)
    }

    fun food(uid: String): FoodCoverState {
        val item =
            vault.read().optJSONObject("accounts")?.optJSONObject(uid) ?: return FoodCoverState()
        return foodFrom(item)
    }

    private fun foodFrom(item: JSONObject): FoodCoverState {
        val cart = item.optJSONObject("cart") ?: JSONObject()
        val saved = item.optJSONArray("favorites") ?: JSONArray()
        val choices = item.optJSONObject("customizations") ?: JSONObject()
        return FoodCoverState(
            cart.keys().asSequence()
                .associateWith { cart.optInt(it).coerceIn(0, 99) }.filterValues { it > 0 },
            (0 until saved.length()).mapNotNull { saved.optString(it).takeIf(String::isNotBlank) }
                .toSet(),
            choices.keys().asSequence().associateWith { id ->
                val values = choices.optJSONArray(id) ?: JSONArray()
                (0 until values.length()).mapNotNull {
                    values.optString(it).takeIf(String::isNotBlank)
                }.toSet()
            })
    }

    // Saves food.
    fun saveFood(uid: String, state: FoodCoverState) {
        vault.update { root ->
            val item = root.child("accounts").child(uid)
            item.put(
                "cart",
                JSONObject().also { cart ->
                    state.cart.forEach { (id, amount) ->
                        cart.put(
                            id,
                            amount
                        )
                    }
                })
            item.put("favorites", JSONArray().also { saved -> state.favorites.forEach(saved::put) })
            item.put("customizations", JSONObject().also { choices ->
                state.customizations.forEach { (id, values) ->
                    choices.put(id, JSONArray().also { rows -> values.forEach(rows::put) })
                }
            })
        }
    }

    fun news(uid: String): List<NewsArticle> {
        val item = vault.read().optJSONObject("accounts")?.optJSONObject(uid) ?: return emptyList()
        return newsFrom(item)
    }

    private fun newsFrom(item: JSONObject): List<NewsArticle> {
        val rows = item.optJSONArray("news") ?: JSONArray()
        return (0 until rows.length()).mapNotNull {
            runCatching {
                NewsArticle.fromJson(
                    rows.getJSONObject(
                        it
                    )
                )
            }.getOrNull()
        }
    }

    // Saves news.
    fun saveNews(uid: String, articles: List<NewsArticle>) {
        vault.update { root ->
            root.child("accounts").child(uid).put(
                "news",
                JSONArray().also { rows -> articles.forEach { rows.put(it.toJson()) } })
        }
    }
}
