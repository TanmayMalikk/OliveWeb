// News cover users: stores recently viewed news articles locally.
package com.olive.app.data.news

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages local on-device reading history for the Daily Digest news cover.
 *
 * @param context Android application or activity context.
 */
class NewsReadingHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * Loads the list of recently read articles.
     *
     * @return List of recently read [NewsArticle] instances in reverse-chronological order.
     */
    fun load(): List<NewsArticle> = runCatching {
        val raw = preferences.getString(KEY_HISTORY, null) ?: return emptyList()
        val rows = JSONArray(raw)
        (0 until rows.length()).mapNotNull { i ->
            rows.optJSONObject(i)?.let { runCatching { NewsArticle.fromJson(it) }.getOrNull() }
        }
    }.getOrDefault(emptyList())

    /**
     * Adds an article to recent reading history, deduplicating and keeping the newest at the front.
     *
     * @param article The [NewsArticle] opened by the user.
     */
    fun record(article: NewsArticle) {
        val existing = load().filterNot { it.link == article.link }
        val updated = (listOf(article) + existing).take(MAX_HISTORY)
        val array = JSONArray()
        updated.forEach { array.put(it.toJson()) }
        preferences.edit { putString(KEY_HISTORY, array.toString()) }
    }

    /**
     * Clears all recorded reading history.
     */
    fun clear() {
        preferences.edit { remove(KEY_HISTORY) }
    }

    private companion object {
        const val PREFERENCES_NAME = "daily_digest_reading_history"
        const val KEY_HISTORY = "history"
        const val MAX_HISTORY = 30
    }
}
