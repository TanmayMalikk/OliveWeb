package com.olive.app.data

import com.olive.app.security.EncryptedVault
import com.olive.app.security.child
import org.json.JSONArray
import org.json.JSONObject

data class FoodCoverState(val cart: Map<String,Int> = emptyMap(), val favorites: Set<String> = emptySet())

// Keep cover favourites, bookmarks, and cart on this phone under the account ID.
class CoverStateStore(private val vault: EncryptedVault) {
    fun food(uid: String): FoodCoverState {
        val item=vault.read().optJSONObject("accounts")?.optJSONObject(uid) ?: return FoodCoverState()
        val cart=item.optJSONObject("cart") ?: JSONObject()
        val saved=item.optJSONArray("favorites") ?: JSONArray()
        return FoodCoverState(cart.keys().asSequence().associateWith { cart.optInt(it).coerceIn(0,99) }.filterValues { it>0 },
            (0 until saved.length()).mapNotNull { saved.optString(it).takeIf(String::isNotBlank) }.toSet())
    }
    fun saveFood(uid: String, state: FoodCoverState) {
        vault.update { root ->
            val item=root.child("accounts").child(uid)
            item.put("cart",JSONObject().also { cart -> state.cart.forEach { (id,amount) -> cart.put(id,amount) } })
            item.put("favorites",JSONArray().also { saved -> state.favorites.forEach(saved::put) })
        }
    }
    fun news(uid: String): List<NewsArticle> {
        val rows=vault.read().optJSONObject("accounts")?.optJSONObject(uid)?.optJSONArray("news") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { runCatching { NewsArticle.fromJson(rows.getJSONObject(it)) }.getOrNull() }
    }
    fun saveNews(uid: String, articles: List<NewsArticle>) {
        vault.update { root ->
            root.child("accounts").child(uid).put("news",JSONArray().also { rows -> articles.forEach { rows.put(it.toJson()) } })
        }
    }
}
