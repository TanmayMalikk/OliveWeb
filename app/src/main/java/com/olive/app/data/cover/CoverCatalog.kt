// Persists account-isolated state for the remaining cover applications.
package com.olive.app.data

import com.olive.app.security.EncryptedVault
import com.olive.app.security.child
import org.json.JSONArray

class CoverStateStore(private val vault: EncryptedVault) {
    fun restore(uid: String): List<NewsArticle> = news(uid)

    fun news(uid: String): List<NewsArticle> {
        val item = vault.read().optJSONObject("accounts")?.optJSONObject(uid) ?: return emptyList()
        val rows = item.optJSONArray("news") ?: JSONArray()
        return (0 until rows.length()).mapNotNull { index ->
            runCatching { NewsArticle.fromJson(rows.getJSONObject(index)) }.getOrNull()
        }
    }

    fun saveNews(uid: String, articles: List<NewsArticle>) {
        vault.update { root ->
            root.child("accounts").child(uid).put(
                "news",
                JSONArray().also { rows -> articles.forEach { rows.put(it.toJson()) } },
            )
        }
    }
}
