// News cover users: loads articles for the Daily Digest cover.
package com.olive.app.data.news

import android.annotation.SuppressLint
import android.content.Context
import android.text.Html
import android.util.Xml
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.edit
import com.olive.app.ui.shared.AppLocaleManager
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import kotlin.collections.iterator
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.xmlpull.v1.XmlPullParser

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
        .withZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
}.getOrDefault(published)

// Allows only HTTPS article links in the news reader.
internal fun isSecureNewsUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme.equals(
        "https",
        ignoreCase = true
    ) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
}.getOrDefault(false)

@Serializable
internal data class NewsArticleMetadata(
    val publisherUrl: String = "", val imageUrl: String = "", val topic: String = "",
    val isPaywalled: Boolean? = null, val description: String = ""
) {
    // Converts this article into JSON for the local news cache.
    fun toJson(): JSONObject = JSONObject(cacheJson.encodeToString(this))

    // Fills missing article fields with publisher metadata while preserving existing feed values.
    fun applyTo(article: NewsArticle): NewsArticle = article.copy(
        publisherUrl = publisherUrl.ifBlank { article.publisherUrl },
        imageUrl = imageUrl.ifBlank { article.imageUrl },
        topic = topic.ifBlank { article.topic },
        isPaywalled = isPaywalled ?: article.isPaywalled,
        summary = description.ifBlank { article.summary })

    companion object {
        private val cacheJson = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        fun fromJson(value: JSONObject): NewsArticleMetadata = cacheJson.decodeFromString(value.toString())
    }
}

// Publisher section data wins. These broad headline categories are only a fallback, never paywall evidence.
internal fun fallbackNewsTopic(title: String, fallback: String = "News"): String {
    val words = title.lowercase()
    val categories = listOf(
        "Politics" to "\\b(election|referendum|parliament|president|prime minister|government|minister|senate)\\b",
        "Sports" to "\\b(championship|league|playoffs|soccer|football|hockey|basketball|olympics)\\b",
        "Business" to "\\b(stock|stocks|market|economy|economic|inflation|bank|trade|business)\\b",
        "Technology" to "\\b(technology|software|artificial intelligence|smartphone|cybersecurity|tech)\\b",
        "Health" to "\\b(health|hospital|medical|vaccine|cancer|disease)\\b",
        "Science" to "\\b(science|scientists|nasa|space|researchers)\\b",
        "Environment" to "\\b(climate|environment|wildfire|garden|conservation)\\b",
        "Culture" to "\\b(film|movie|music|concert|festival|arts)\\b",
    )
    return categories.firstOrNull { Regex(it.second).containsMatchIn(words) }?.first ?: fallback
}

// Use a publisher-declared card-sized version of the same photo, preserving crop and photo identity.
private fun responsiveArticleImage(
    document: Document,
    original: String,
    pageUrl: String
): String {
    if (original.isBlank()) return original
    val sizingKeys = setOf("w", "width", "h", "height", "q", "quality", "dpr", "size")
    // Cleans publisher text so the article card shows a readable source name.
    fun identity(uri: URI): List<Any?> = listOf(
        uri.host?.lowercase(), uri.port, uri.rawPath,
        uri.rawQuery.orEmpty().split('&')
            .filter { it.isNotBlank() && it.substringBefore('=').lowercase() !in sizingKeys }
            .sorted()
    )

    val originalIdentity = identity(URI(original))
    val candidates =
        document.select("img[srcset],source[srcset],img[data-srcset],source[data-srcset]")
            .flatMap { element ->
                listOf(element.attr("srcset"), element.attr("data-srcset")).flatMap { srcset ->
                    Regex("(\\S+)\\s+(\\d+)w(?:\\s*,|\\s*$)").findAll(srcset).mapNotNull { match ->
                        val url = runCatching {
                            URI(pageUrl).resolve(match.groupValues[1]).toString()
                        }.getOrNull()
                        val width = match.groupValues[2].toIntOrNull()
                        if (url != null && url.length <= 4096 && isSecureNewsUrl(url) && width != null && width > 0 && identity(
                                URI(url)
                            ) == originalIdentity
                        )
                            width to url else null
                    }.toList()
                }
            }.distinct().sortedBy { it.first }
    return (candidates.firstOrNull { it.first >= 960 } ?: candidates.lastOrNull())?.second
        ?: original
}

// Reads news article metadata from saved text.
internal fun parseNewsArticleMetadata(html: String, pageUrl: String): NewsArticleMetadata {
    require(isSecureNewsUrl(pageUrl))
    val document = Jsoup.parse(html, pageUrl)
    // Combines the publisher and date shown under an article title.
    fun meta(vararg names: String): String = document.select("meta").firstOrNull { element ->
        names.any { name ->
            element.attr("property").equals(name, true) || element.attr("name")
                .equals(name, true) || element.attr("itemprop").equals(name, true)
        }
    }?.attr("content").orEmpty()

    // Reads one article from the RSS or Atom feed node.
    fun articleNode(value: Any?, depth: Int = 0): JSONObject? {
        if (depth > 20) return null
        // Reads article details from whichever supported feed shape is present.
        when (value) {
            is JSONObject -> {
                val types: List<String> = when (val type = value.opt("@type")) {
                    null -> emptyList()
                    is String -> listOf(type)
                    is JSONArray -> (0 until type.length()).map { type.optString(it) }
                    else -> emptyList()
                }
                if (types.any {
                        it.substringAfterLast('/').endsWith("Article") || it == "BlogPosting"
                    }) return value
                for (key in value.keys()) articleNode(value.opt(key), depth + 1)?.let { return it }
            }

            is JSONArray -> for (index in 0 until value.length()) articleNode(
                value.opt(index),
                depth + 1
            )?.let { return it }

            else -> return null
        }
        return null
    }

    val article = document.select("script[type=application/ld+json]").firstNotNullOfOrNull {
        runCatching { articleNode(JSONTokener(it.data()).nextValue()) }.getOrNull()
    }

    // Trims feed text and removes markup that should not appear in article cards.
    fun text(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> (0 until value.length()).firstNotNullOfOrNull {
            text(
                value.opt(it)
            ).takeIf(String::isNotBlank)
        }.orEmpty()

        else -> ""
    }

    // Finds the best article image in supported feed tags or page metadata.
    fun image(value: Any?): String = when (value) {
        is String -> value
        is JSONObject -> text(value.opt("contentUrl")).ifBlank {
            text(
                value.opt(
                    "url"
                )
            )
        }

        is JSONArray -> (0 until value.length()).firstNotNullOfOrNull {
            image(value.opt(it)).takeIf(
                String::isNotBlank
            )
        }.orEmpty()

        else -> ""
    }

    // Reads a publisher's free-access value as a true, false, or missing result.
    fun freeFlag(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> value.trim().lowercase()
            .toBooleanStrictOrNull()

        else -> null
    }

    // Searches article metadata for any section marked as paid-only.
    fun paidPart(value: Any?): Boolean = when (value) {
        is JSONObject -> freeFlag(value.opt("isAccessibleForFree")) == false
        is JSONArray -> (0 until value.length()).any { paidPart(value.opt(it)) }
        else -> false
    }

    val isArticle = article != null || meta("og:type").contains("article", true)
    val rawImage = image(article?.opt("image")).ifBlank {
        if (isArticle) meta(
            "og:image",
            "twitter:image"
        ) else ""
    }
    val imageUrl = runCatching { URI(pageUrl).resolve(rawImage).toString() }.getOrDefault("")
        .takeIf { rawImage.isNotBlank() && it.length <= 4096 && isSecureNewsUrl(it) }.orEmpty()
    val accessible = freeFlag(article?.opt("isAccessibleForFree"))
        ?: if (isArticle) freeFlag(meta("isAccessibleForFree")) else null
    val paywall = when {
        accessible == false || paidPart(article?.opt("hasPart")) -> true
        accessible == true -> false
        else -> null
    }
    return NewsArticleMetadata(
        pageUrl, responsiveArticleImage(document, imageUrl, pageUrl),
        text(article?.opt("articleSection")).ifBlank { meta("article:section", "section") }.trim()
            .take(40), paywall,
        text(article?.opt("description")).ifBlank {
            if (isArticle) meta(
                "og:description",
                "description"
            ) else ""
        }.trim().take(300)
    )
}

// Visible cards load concurrently with bounded network and disposable Google redirect views.
internal class NewsMetadataRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences =
        appContext.getSharedPreferences("daily_digest_article_metadata", Context.MODE_PRIVATE)

    // Creates the stable cache key used for this news request.
    private fun key(link: String): String =
        MessageDigest.getInstance("SHA-256").digest(link.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    // Reads cached article details and rejects entries that are too old unless allowed.
    fun cached(link: String, allowStale: Boolean = false): NewsArticleMetadata? = runCatching {
        val value = JSONObject(preferences.getString(key(link), null) ?: return@runCatching null)
        val expired = value.optLong("expiresAt") < System.currentTimeMillis()
        if (expired && !allowStale) null else NewsArticleMetadata.fromJson(value).let {
            // Keep the photo visible during refresh, but do not reuse an expired paywall verdict.
            if (expired) it.copy(isPaywalled = null) else it
        }
    }.getOrNull()

    // Loads cached news stories saved on this phone.
    suspend fun load(article: NewsArticle): NewsArticleMetadata {
        cached(article.link)?.let { return it }
        if (article.imageUrl.isNotBlank() && article.topic.isNotBlank() && article.isPaywalled != null)
            return NewsArticleMetadata(
                article.publisherUrl,
                article.imageUrl,
                article.topic,
                article.isPaywalled
            )
        return articleLocks[(article.link.hashCode() and Int.MAX_VALUE) % articleLocks.size].withLock {
            cached(article.link)?.let { return@withLock it }
            val result = try {
                networkSlots.withPermit {
                    val url = article.publisherUrl.takeIf(::isSecureNewsUrl)
                        ?: cached(
                            article.link,
                            allowStale = true
                        )?.publisherUrl?.takeIf(::isSecureNewsUrl)
                        ?: resolvePublisher(article.link)
                    url?.let { withContext(Dispatchers.IO) { fetch(it) } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { // One malformed metadata source should be skipped so the remaining article fallbacks can still be tried.
                null
            }
            val metadata =
                result ?: cached(article.link, allowStale = true) ?: NewsArticleMetadata()
            val ttl = if (result == null) 300_000L else 21_600_000L
            val cacheKey = key(article.link)
            preferences.edit {
                putString(
                    cacheKey,
                    metadata.toJson().put("expiresAt", System.currentTimeMillis() + ttl).toString()
                )
                // Trim the oldest articles when the phone cache grows past 128 entries.
                if (preferences.all.size >= 128) preferences.all.entries.filter { it.key != cacheKey }
                    .sortedBy {
                        runCatching { JSONObject(it.value.toString()).optLong("expiresAt") }.getOrDefault(
                            0L
                        )
                    }.take(preferences.all.size - 127).forEach { remove(it.key) }
            }
            metadata
        }
    }

    // Reads a clean publisher name from article metadata or its web address.
    private suspend fun resolvePublisher(link: String): String? {
        if (!isSecureNewsUrl(link)) return null
        if (!URI(link).host.equals("news.google.com", true)) return link
        return redirectSlots.withPermit { resolveGooglePublisher(link) }
    }

    // Google News redirects require JavaScript. This resolver allows HTTPS navigation only and exposes no native bridge.
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveGooglePublisher(link: String): String? {
        var resolver: WebView? = null
        return try {
            withTimeoutOrNull(12.seconds) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { continuation ->
                        resolver = WebView(appContext).apply {
                            // Required for Google's scripted RSS redirects. This runs remote scripts, so the XSS
                            // warning remains visible. HTTPS-only navigation, no native bridge and no file access limit exposure.
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            webViewClient = object : WebViewClient() {
                                // Keeps safe article links in the reader and blocks unsupported link types.
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest
                                ): Boolean {
                                    val url = request.url.toString()
                                    val publisher =
                                        isSecureNewsUrl(url) && !isGoogleHost(request.url.host)
                                    if (request.isForMainFrame && publisher) {
                                        if (continuation.isActive) continuation.resume(url)
                                        return true
                                    }
                                    return !isSecureNewsUrl(url)
                                }

                                // Saves the final publisher URL after the article page finishes loading.
                                override fun onPageFinished(view: WebView, url: String) {
                                    if (isSecureNewsUrl(url) && !isGoogleHost(URI(url).host) && continuation.isActive) continuation.resume(
                                        url
                                    )
                                }
                            }
                            loadUrl(link)
                        }
                    }
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                resolver?.apply {
                    stopLoading()
                    destroy()
                }
            }
        }
    }

    // Loads article metadata from the publisher page when the feed is incomplete.
    private fun fetch(initialUrl: String): NewsArticleMetadata? {
        var current = initialUrl
        repeat(6) {
            if (!isSecureNewsUrl(current)) return null
            val connection = URL(current).openConnection() as HttpsURLConnection
            connection.connectTimeout = 6_000
            connection.readTimeout =
                6_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
            )
            connection.setRequestProperty("Accept-Encoding", "gzip")
            // Turn an unexpected fetch failure into a readable message.
            try {
                val status = connection.responseCode
                // Follow normal web redirects while fetching the full article.
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: return null
                    current = URI(current).resolve(location).toString()
                } else {
                    if (status == 402) return NewsArticleMetadata(
                        publisherUrl = current,
                        isPaywalled = true
                    )
                    if (status !in 200..299) return null
                    val bytes = ByteArrayOutputStream()
                    val response = connection.inputStream
                    val decoded =
                        if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(
                            response
                        ) else response
                    decoded.use { stream ->
                        val buffer = ByteArray(8192)
                        // News: Keeps fetch active while bytes size < 2 000 000.
                        while (bytes.size() < 2_000_000) {
                            val count =
                                stream.read(buffer, 0, minOf(buffer.size, 2_000_000 - bytes.size()))
                            if (count < 0) break
                            bytes.write(buffer, 0, count)
                        }
                    }
                    return parseNewsArticleMetadata(
                        bytes.toByteArray().toString(Charsets.UTF_8),
                        current
                    )
                }
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private companion object {
        // Lock stripes deduplicate simultaneous requests for a story without retaining every feed URL.
        val articleLocks = Array(64) { Mutex() }
        val networkSlots = Semaphore(3)
        val redirectSlots = Semaphore(2)
        // Detects Google News links that need their publisher address resolved.
        fun isGoogleHost(host: String?): Boolean =
            host?.lowercase()?.let { it == "google.com" || it.endsWith(".google.com") } ?: false
    }
}

// News cover users: stores news feed cache state.

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
