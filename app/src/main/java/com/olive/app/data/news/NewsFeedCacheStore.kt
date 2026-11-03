// News cover users: stores news feed cache state.
package com.olive.app.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class CachedNewsFeed(val place: String, val articles: List<NewsArticle>)

// Keep the last successful feed local so Daily Digest can paint content before the network responds.
class NewsFeedCacheStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    // Loads cached news stories saved on this phone.
    fun load(language: String, scope: NewsScope): CachedNewsFeed? = runCatching {
        val raw = preferences.getString(key(language, scope), null) ?: return@runCatching null
        val root = JSONObject(raw)
        val rows = root.optJSONArray(ARTICLES) ?: return@runCatching null
        val articles = (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.let { runCatching { NewsArticle.fromJson(it) }.getOrNull() }
        }
        if (articles.isEmpty()) null else CachedNewsFeed(root.optString(PLACE), articles)
    }.getOrNull()

    // Saves the latest news response in the local feed cache.
    fun save(language: String, scope: NewsScope, place: String, articles: List<NewsArticle>) {
        if (articles.isEmpty()) return
        val root = JSONObject()
            .put(PLACE, place)
            .put(ARTICLES, JSONArray().also { rows -> articles.forEach { rows.put(it.toJson()) } })
        preferences.edit { putString(key(language, scope), root.toString()) }
    }

    // Creates the stable cache key used for this news request.
    private fun key(language: String, scope: NewsScope) =
        "${language.lowercase()}_${scope.name.lowercase()}"

    private companion object {
        const val PREFERENCES_NAME = "daily_digest_feed_cache"
        const val PLACE = "place"
        const val ARTICLES = "articles"
    }
}
