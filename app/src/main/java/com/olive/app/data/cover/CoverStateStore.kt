// Persists account-isolated state for the cover applications.
package com.olive.app.data

import com.olive.app.security.EncryptedVault
import com.olive.app.security.child
import org.json.JSONArray

// Persists account-isolated state for the cover applications.
class CoverStateStore(private val vault: EncryptedVault) {
    // Restores the saved news feed for the given account.
    fun restore(uid: String): List<NewsArticle> = news(uid)

    // Reads cached news articles from the encrypted local vault.
    fun news(uid: String): List<NewsArticle> {
        val item = vault.read().optJSONObject("accounts")?.optJSONObject(uid) ?: return emptyList()
        val rows = item.optJSONArray("news") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { index ->
            runCatching { NewsArticle.fromJson(rows.getJSONObject(index)) }.getOrNull()
        }
    }

    // Saves cached news articles into the encrypted local vault.
    fun saveNews(uid: String, articles: List<NewsArticle>) {
        vault.update { root ->
            root.child("accounts").child(uid).put(
                "news",
                JSONArray().also { rows -> articles.forEach { rows.put(it.toJson()) } },
            )
        }
    }
}

