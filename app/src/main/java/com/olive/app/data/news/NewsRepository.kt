// News cover users: loads articles for the Daily Digest cover.
package com.olive.app.data

import android.text.Html
import android.util.Xml
import com.olive.app.ui.shared.AppLocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

data class NewsArticle(
    val title: String,
    val summary: String,
    val link: String,
    val source: String,
    val published: String,
    val imageUrl: String = "",
    val topic: String = "",
    val isPaywalled: Boolean? = null,
    val publisherUrl: String = ""
) {
    // News: Converts this record into JSON.
    fun toJson(): JSONObject =
        JSONObject().put("title", title).put("summary", summary).put("link", link)
            .put("source", source).put("published", published)
            .put("imageUrl", imageUrl).put("topic", topic)
            .put("isPaywalled", isPaywalled ?: JSONObject.NULL).put("publisherUrl", publisherUrl)

    companion object {
        // Rebuilds one cached news story from its saved JSON fields.
        fun fromJson(json: JSONObject) = NewsArticle(
            json.getString("title"),
            json.optString("summary"),
            json.getString("link"),
            json.optString("source"),
            json.optString("published"),
            json.optString("imageUrl"),
            json.optString("topic"),
            if (json.isNull("isPaywalled")) null else json.optBoolean("isPaywalled"),
            json.optString("publisherUrl")
        )
    }
}

enum class NewsScope { LOCAL, GLOBAL }

// Daily Digest uses Google's live RSS feeds and keeps the original story links.
class GoogleNewsRepository {
    // Builds the Google News language and country query for the selected locale.
    fun rssLocaleQuery(language: String): String {
        val langTag = AppLocaleManager.getLanguageTag(language)
        return when (langTag) {
            "fr" -> "hl=fr-CA&gl=CA&ceid=CA:fr"
            "es" -> "hl=es-419&gl=MX&ceid=MX:es-419"
            "ar" -> "hl=ar&gl=SA&ceid=SA:ar"
            "tl" -> "hl=tl&gl=PH&ceid=PH:tl"
            "zh-rCN", "zh" -> "hl=zh-CN&gl=CN&ceid=CN:zh-Hans"
            else -> "hl=en-CA&gl=CA&ceid=CA:en"
        }
    }

    // Loads the newest news articles from cache and available feeds.
    suspend fun latest(language: String, scope: NewsScope, place: String): List<NewsArticle> =
        withContext(Dispatchers.IO) {
            val locale = rssLocaleQuery(language)
            val path = when (scope) {
                NewsScope.LOCAL -> "/rss/search?q=" + URLEncoder.encode(
                    place.ifBlank { "Canada" },
                    StandardCharsets.UTF_8.name()
                ) + "&$locale"

                NewsScope.GLOBAL -> "/rss/headlines/section/topic/WORLD?$locale"
            }
            val connection =
                (URL("https://news.google.com$path").openConnection() as HttpsURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/rss+xml, application/xml")
                }
            // Use the fallback when latest is unavailable.
            try {
                check(connection.responseCode in 200..299)
                connection.inputStream.use { stream ->
                    val parser = Xml.newPullParser().apply { setInput(stream, "UTF-8") }
                    val articles = mutableListOf<NewsArticle>()
                    var inItem = false
                    var title = ""
                    var summary = ""
                    var link = ""
                    var source = ""
                    var published = ""
                    // News: Keeps latest active while parser event type does not equal xml pull parser end document and articles size < 40.
                    while (parser.eventType != XmlPullParser.END_DOCUMENT && articles.size < 40) {
                        // Keeps the newest valid news result when feeds return at different times.
                        when (parser.eventType) {
                            XmlPullParser.START_TAG -> when (parser.name) {
                                "item" -> {
                                    inItem = true
                                    title = ""
                                    summary = ""
                                    link = ""
                                    source =
                                        ""
                                    published = ""
                                }

                                "title" -> if (inItem) title = parser.nextText()
                                "description" -> if (inItem) summary = parser.nextText()
                                "link" -> if (inItem) link = parser.nextText()
                                "source" -> if (inItem) source = parser.nextText()
                                "pubDate" -> if (inItem) published = parser.nextText()
                            }

                            XmlPullParser.END_TAG -> if (parser.name == "item") {
                                inItem = false
                                // Keep only titled HTTPS Google News results before resolving publisher links.
                                if (title.isNotBlank() && link.startsWith("https://news.google.com/")) {
                                    val clean = Html.fromHtml(summary, Html.FROM_HTML_MODE_LEGACY)
                                        .toString().trim().take(240)
                                    articles += NewsArticle(
                                        title.take(250),
                                        clean,
                                        link,
                                        source.take(120),
                                        published.take(100),
                                        topic = fallbackNewsTopic(
                                            title,
                                            if (scope == NewsScope.LOCAL) "Local" else "World"
                                        )
                                    )
                                }
                            }
                        }
                        parser.next()
                    }
                    articles
                }
            } finally {
                connection.disconnect()
            }
        }
}

// Turn Google's timestamp into the phone's local time zone before showing it.
fun NewsArticle.localPublishedDate(language: String): String = runCatching {
    val locale = Locale.forLanguageTag(AppLocaleManager.getLanguageTag(language))
    ZonedDateTime.parse(published, DateTimeFormatter.RFC_1123_DATE_TIME)
        .withZoneSameInstant(java.time.ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
}.getOrDefault(published)
