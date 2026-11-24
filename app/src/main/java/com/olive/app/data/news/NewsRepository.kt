// News cover users: loads articles for the Daily Digest cover.
package com.olive.app.data.news

import android.annotation.SuppressLint
import android.content.Context
import android.icu.util.ULocale
import android.text.Html
import android.util.Xml
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.edit
import com.olive.app.data.media.sha256Hex
import com.olive.app.data.network.decodeJsonObject
import com.olive.app.data.network.encodeToJsonObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.xmlpull.v1.XmlPullParser

@Serializable
data class NewsArticle(
    val title: String,
    val summary: String = "",
    val link: String,
    val source: String = "",
    val published: String = "",
    val imageUrl: String = "",
    val topic: String = "",
    val isPaywalled: Boolean? = null,
    val publisherUrl: String = "",
    val wordCount: Int? = null
) {
    /**
     * News: Converts this record into JSON.
     *
     * @return JSON object representing to JSON.
     */
    fun toJson(): JSONObject = encodeToJsonObject()

    companion object {
        /**
         * Rebuilds one cached news story from its saved JSON fields.
         *
         * @param json JSON object containing serialized fields.
         * @return Parsed instance constructed from the JSON data.
         */
        fun fromJson(json: JSONObject): NewsArticle = json.decodeJsonObject()
    }
}

enum class NewsScope { LOCAL, GLOBAL }

// Daily Digest uses Google's live RSS feeds and keeps the original story links.
class GoogleNewsRepository(
    private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) {
    /**
     * Builds the Google News language and country query for the selected locale.
     *
     * @param languageTag The device's primary BCP 47 language tag.
     * @return The resulting rss locale query string.
     */
    fun rssLocaleQuery(languageTag: String): String {
        val locale = Locale.forLanguageTag(languageTag)
        val language = locale.language
        val editionLocale = ULocale.addLikelySubtags(ULocale.forLocale(locale))
        val market = editionLocale.country
        val displayLanguage = "hl=${locale.toLanguageTag()}"
        if (language.isEmpty() || market.length != 2 || !market.all(Char::isLetter)) return displayLanguage
        val editionLanguage = if (language == "zh" && editionLocale.script.isNotEmpty()) {
            "zh-${editionLocale.script}"
        } else language
        return "$displayLanguage&gl=$market&ceid=$market:$editionLanguage"
    }

    /**
     * Loads the newest news articles from cache and available feeds.
     *
     * @param language Language supplied to [latest].
     * @param scope Scope supplied to [latest].
     * @param place Place supplied to [latest].
     * @param category Category chip; "All" keeps the default local search or world headlines.
     * @return List of resulting latest items.
     */
    suspend fun latest(
        language: String,
        scope: NewsScope,
        place: String,
        category: String = "All"
    ): List<NewsArticle> =
        withContext(Dispatchers.IO) {
            val locale = rssLocaleQuery(language)
            val topicId = googleNewsTopicId(category)
            val localQuery = place.ifBlank { "Canada" } + if (topicId != null) " $category" else ""
            val path = when (scope) {
                NewsScope.LOCAL -> "/rss/search?q=" + URLEncoder.encode(
                    localQuery,
                    StandardCharsets.UTF_8.name()
                ) + "&$locale"

                NewsScope.GLOBAL -> "/rss/headlines/section/topic/${topicId ?: "WORLD"}?$locale"
            }
            val defaultTopic = when {
                topicId != null -> category
                scope == NewsScope.LOCAL -> "Local"
                else -> "World"
            }
            val connection =
                openConnection(URL("https://news.google.com$path")).apply {
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
                                        topic = if (topicId != null) category
                                        else fallbackNewsTopic(title, defaultTopic)
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

/**
 * Turn Google's timestamp into the phone's local time zone before showing it.
 *
 * @receiver The news article whose publication date is displayed.
 * @param language Language supplied to [localPublishedDate].
 * @return The resulting local published date string.
 */
fun NewsArticle.localPublishedDate(language: String): String = runCatching {
    val locale = Locale.forLanguageTag(language)
    ZonedDateTime.parse(published, DateTimeFormatter.RFC_1123_DATE_TIME)
        .withZoneSameInstant(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
}.getOrDefault(published)

/**
 * Allows only HTTPS article links in the news reader.
 *
 * @param url HTTPS URL to validate, fetch, or open.
 * @return `true` if is secure news URL, or `false` otherwise.
 */
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
    val isPaywalled: Boolean? = null, val description: String = "",
    val wordCount: Int? = null
) {
    /**
     * Converts this article into JSON for the local news cache.
     *
     * @return JSON object representing to JSON.
     */
    fun toJson(): JSONObject = encodeToJsonObject()

    /**
     * Fills missing article fields with publisher metadata while preserving existing feed values.
     *
     * @param article Article supplied to [applyTo].
     * @return The resulting [NewsArticle] for apply to.
     */
    fun applyTo(article: NewsArticle): NewsArticle = article.copy(
        publisherUrl = publisherUrl.ifBlank { article.publisherUrl },
        imageUrl = imageUrl.ifBlank { article.imageUrl },
        topic = topic.ifBlank { article.topic },
        isPaywalled = isPaywalled ?: article.isPaywalled,
        summary = description.ifBlank { article.summary },
        wordCount = wordCount ?: article.wordCount)

    companion object {
        /**
         * Deserializes a news article metadata from the provided JSON representation.
         *
         * @param value Input value to store, parse, or format.
         * @return Parsed instance constructed from the JSON data.
         */
        fun fromJson(value: JSONObject): NewsArticleMetadata = value.decodeJsonObject()
    }
}

/**
 * Publisher section data wins. These broad headline categories are only a fallback, never paywall evidence.
 *
 * @param title Primary display title text.
 * @param fallback Fallback supplied to [fallbackNewsTopic].
 * @return The resulting fallback news topic string.
 */
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

/**
 * Use a publisher-declared card-sized version of the same photo, preserving crop and photo identity.
 *
 * @param document Safety document metadata record.
 * @param original Original supplied to [responsiveArticleImage].
 * @param pageUrl Page URL supplied to [responsiveArticleImage].
 * @return The resulting responsive article image string.
 */
private fun responsiveArticleImage(
    document: Document,
    original: String,
    pageUrl: String
): String {
    if (original.isBlank()) return original
    val sizingKeys = setOf("w", "width", "h", "height", "q", "quality", "dpr", "size")
    /**
     * Cleans publisher text so the article card shows a readable source name.
     *
     * @param uri Content or file URI identifying the selected local item.
     * @return List of resulting identity items.
     */
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

/**
 * Reads news article metadata from saved text.
 *
 * @param html HTML supplied to [parseNewsArticleMetadata].
 * @param pageUrl Page URL supplied to [parseNewsArticleMetadata].
 * @return The resulting [NewsArticleMetadata] for parse news article metadata.
 */
internal fun parseNewsArticleMetadata(html: String, pageUrl: String): NewsArticleMetadata {
    require(isSecureNewsUrl(pageUrl))
    val document = Jsoup.parse(html, pageUrl)
    /**
     * Combines the publisher and date shown under an article title.
     *
     * @param names Variable number of names arguments.
     * @return The resulting meta string.
     */
    fun meta(vararg names: String): String = document.select("meta").firstOrNull { element ->
        names.any { name ->
            element.attr("property").equals(name, true) || element.attr("name")
                .equals(name, true) || element.attr("itemprop").equals(name, true)
        }
    }?.attr("content").orEmpty()

    /**
     * Reads one article from the RSS or Atom feed node.
     *
     * @param value Input value to store, parse, or format, or null when not provided.
     * @param depth Depth supplied to [articleNode].
     * @return JSON object representing article node.
     */
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

    /**
     * Trims feed text and removes markup that should not appear in article cards.
     *
     * @param value Input value to store, parse, or format, or null when not provided.
     * @return The resulting text string.
     */
    fun text(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> (0 until value.length()).firstNotNullOfOrNull {
            text(
                value.opt(it)
            ).takeIf(String::isNotBlank)
        }.orEmpty()

        else -> ""
    }

    /**
     * Finds the best article image in supported feed tags or page metadata.
     *
     * @param value Input value to store, parse, or format, or null when not provided.
     * @return The resulting image string.
     */
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

    /**
     * Reads a publisher's free-access value as a true, false, or missing result.
     *
     * @param value Input value to store, parse, or format, or null when not provided.
     * @return `true` or `false` indicating free flag, or `null` if unknown.
     */
    fun freeFlag(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> value.trim().lowercase()
            .toBooleanStrictOrNull()

        else -> null
    }

    /**
     * Searches article metadata for any section marked as paid-only.
     *
     * @param value Input value to store, parse, or format, or null when not provided.
     * @return `true` if paid part, or `false` otherwise.
     */
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
    val wordCount = article?.optInt("wordCount")?.takeIf { it > 30 }
        ?: runCatching {
            val body = document.select("article, [itemprop=articleBody], main, .article-body, .story-body")
                .text().ifBlank { document.body().text() }
            body.split(Regex("\\s+")).count { it.isNotBlank() }.takeIf { it > 30 }
        }.getOrNull()
    return NewsArticleMetadata(
        pageUrl, responsiveArticleImage(document, imageUrl, pageUrl),
        text(article?.opt("articleSection")).ifBlank { meta("article:section", "section") }.trim()
            .take(40), paywall,
        text(article?.opt("description")).ifBlank {
            if (isArticle) meta(
                "og:description",
                "description"
            ) else ""
        }.trim().take(300),
        wordCount
    )
}

// Visible cards load concurrently with bounded network and disposable Google redirect views.
internal class NewsMetadataRepository(
    context: Context,
    private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) {
    private val appContext = context.applicationContext
    private val preferences =
        appContext.getSharedPreferences("daily_digest_article_metadata", Context.MODE_PRIVATE)

    /**
     * Reads cached article details and rejects entries that are too old unless allowed.
     *
     * @param link Web link or URL associated with the item.
     * @param allowStale Whether to allow stale.
     * @return The resulting [NewsArticleMetadata], or `null` if unavailable.
     */
    fun cached(link: String, allowStale: Boolean = false): NewsArticleMetadata? = runCatching {
        val value = JSONObject(preferences.getString(sha256Hex(link), null) ?: return@runCatching null)
        val expired = value.optLong("expiresAt") < System.currentTimeMillis()
        if (expired && !allowStale) null else NewsArticleMetadata.fromJson(value).let {
            // Keep the photo visible during refresh, but do not reuse an expired paywall verdict.
            if (expired) it.copy(isPaywalled = null) else it
        }
    }.getOrNull()

    /**
     * Loads cached news stories saved on this phone.
     *
     * @param article Article supplied to [load].
     * @return The resulting [NewsArticleMetadata] instance.
     */
    suspend fun load(article: NewsArticle): NewsArticleMetadata {
        cached(article.link)?.let { return it }
        if (article.imageUrl.isNotBlank() && article.topic.isNotBlank() && article.isPaywalled != null)
            return NewsArticleMetadata(
                article.publisherUrl,
                article.imageUrl,
                article.topic,
                article.isPaywalled,
                article.summary,
                article.wordCount
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
            val cacheKey = sha256Hex(article.link)
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

    /**
     * Reads a clean publisher name from article metadata or its web address.
     *
     * @param link Web link or URL associated with the item.
     * @return The resulting resolve publisher string, or `null` when unavailable.
     */
    private suspend fun resolvePublisher(link: String): String? {
        if (!isSecureNewsUrl(link)) return null
        if (!URI(link).host.equals("news.google.com", true)) return link
        return redirectSlots.withPermit { resolveGooglePublisher(link) }
    }

    /**
     * Google News redirects require JavaScript. This resolver allows HTTPS navigation only and exposes no native bridge.
     *
     * @param link Web link or URL associated with the item.
     * @return The resulting resolve google publisher string, or `null` when unavailable.
     */
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
                                /**
                                 * Keeps safe article links in the reader and blocks unsupported link types.
                                 *
                                 * @param view View supplied to [shouldOverrideUrlLoading].
                                 * @param request Request supplied to [shouldOverrideUrlLoading].
                                 * @return `true` if should override URL loading, or `false` otherwise.
                                 */
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

                                /**
                                 * Saves the final publisher URL after the article page finishes loading.
                                 *
                                 * @param view View supplied to [onPageFinished].
                                 * @param url HTTPS URL to validate, fetch, or open.
                                 */
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

    /**
     * Loads article metadata from the publisher page when the feed is incomplete.
     *
     * @param initialUrl Initial URL supplied to [fetch].
     * @return The resulting [NewsArticleMetadata], or `null` if unavailable.
     */
    private fun fetch(initialUrl: String): NewsArticleMetadata? {
        var current = initialUrl
        repeat(6) {
            if (!isSecureNewsUrl(current)) return null
            val connection = openConnection(URL(current))
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
        /**
         * Detects Google News links that need their publisher address resolved.
         *
         * @param host Optional host supplied to [isGoogleHost].
         * @return `true` if is google host, or `false` otherwise.
         */
        fun isGoogleHost(host: String?): Boolean =
            host?.lowercase()?.let { it == "google.com" || it.endsWith(".google.com") } ?: false
    }
}

// News cover users: stores news feed cache state.

data class CachedNewsFeed(val place: String, val articles: List<NewsArticle>)

// Keep the last successful feed local so Daily Digest can paint content before the network responds.
class NewsFeedCacheStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * Loads cached news stories saved on this phone.
     *
     * @param language Language supplied to [load].
     * @param scope Scope supplied to [load].
     * @return The resulting [CachedNewsFeed], or `null` if unavailable.
     */
    fun load(language: String, scope: NewsScope): CachedNewsFeed? = runCatching {
        val raw = preferences.getString(key(language, scope), null) ?: return@runCatching null
        val root = JSONObject(raw)
        val rows = root.optJSONArray(ARTICLES) ?: return@runCatching null
        val articles = (0 until rows.length()).mapNotNull { index ->
            rows.optJSONObject(index)?.let { runCatching { NewsArticle.fromJson(it) }.getOrNull() }
        }
        if (articles.isEmpty()) null else CachedNewsFeed(root.optString(PLACE), articles)
    }.getOrNull()

    /**
     * Saves the latest news response in the local feed cache.
     *
     * @param language Language supplied to [save].
     * @param scope Scope supplied to [save].
     * @param place Place supplied to [save].
     * @param articles Collection of articles values to process.
     */
    fun save(language: String, scope: NewsScope, place: String, articles: List<NewsArticle>) {
        if (articles.isEmpty()) return
        val root = JSONObject()
            .put(PLACE, place)
            .put(ARTICLES, JSONArray().also { rows -> articles.forEach { rows.put(it.toJson()) } })
        preferences.edit { putString(key(language, scope), root.toString()) }
    }

    /**
     * Creates the stable cache key used for this news request.
     *
     * @param language Language supplied to [key].
     * @param scope Scope supplied to [key].
     * @return The resulting key.
     */
    private fun key(language: String, scope: NewsScope) =
        "${language.lowercase()}_${scope.name.lowercase()}"

    private companion object {
        const val PREFERENCES_NAME = "daily_digest_feed_cache"
        const val PLACE = "place"
        const val ARTICLES = "articles"
    }
}

/**
 * Estimates reading time in minutes based on actual article word count.
 * Standard adult reading speed for news prose is ~200 words per minute.
 *
 * @param title Article title string.
 * @param summary Article summary string.
 * @param wordCount Actual article body word count when extracted from publisher metadata.
 * @return Estimated minutes to read the full story (minimum 1 minute).
 */
fun estimateReadingMinutes(title: String, summary: String, wordCount: Int? = null): Int {
    if (wordCount != null && wordCount > 0) {
        return (wordCount / 200).coerceAtLeast(1)
    }
    // Fallback when full publisher body text has not loaded yet:
    val words = "$title $summary".trim().split(Regex("\\s+")).count { it.isNotBlank() }
    val base = (words * 8 / 200).coerceAtLeast(1)
    val variation = kotlin.math.abs(title.hashCode()) % 3
    return (base + variation).coerceIn(2, 6)
}

/**
 * Estimates reading time in minutes for a [NewsArticle] using its actual word count when available.
 *
 * @param article News article to evaluate.
 * @return Estimated reading minutes.
 */
fun estimateReadingMinutes(article: NewsArticle): Int =
    estimateReadingMinutes(article.title, article.summary, article.wordCount)

/**
 * Maps a Daily Digest category chip to its Google News topic section id.
 *
 * @param category Category label shown in the chip row.
 * @return Google News topic id, or `null` for "All".
 */
internal fun googleNewsTopicId(category: String): String? = when (category) {
    "World" -> "WORLD"
    "Business" -> "BUSINESS"
    "Technology" -> "TECHNOLOGY"
    "Science" -> "SCIENCE"
    "Health" -> "HEALTH"
    "Sports" -> "SPORTS"
    "Culture" -> "ENTERTAINMENT"
    else -> null
}

/**
 * Structure holding an offline evergreen story's topic, title, summary, and realistic word count.
 */
private data class OfflineEvergreenStory(
    val topic: String,
    val title: String,
    val summary: String,
    val wordCount: Int
)

/**
 * Builds evergreen in-house stories shown when no live or cached feed is available, so the
 * cover never looks empty offline. Stories are credited to Daily Digest (never to real outlets)
 * and link to a live Google News search for their topic once the connection returns.
 *
 * @param place City or region used to label the local feature.
 * @return Offline evergreen articles, newest first.
 */
fun curatedOfflineArticles(place: String): List<NewsArticle> {
    val area = place.ifBlank { "your area" }
    val now = ZonedDateTime.now()
    val rfc = DateTimeFormatter.RFC_1123_DATE_TIME
    val stories = listOf(
        OfflineEvergreenStory("Local", "Things to do around $area this week", "A roundup of community events, markets, and free activities worth checking out nearby.", 620),
        OfflineEvergreenStory("Health", "Why a consistent sleep schedule matters", "Going to bed and waking at the same time each day supports energy, mood, and focus.", 840),
        OfflineEvergreenStory("Technology", "Simple steps to keep your phone secure", "Updates, strong screen locks, and reviewing app permissions go a long way toward privacy.", 420),
        OfflineEvergreenStory("Business", "Building a household budget that sticks", "Tracking spending for a month is often the easiest first step toward saving more.", 1080),
        OfflineEvergreenStory("Science", "A beginner's guide to stargazing", "You don't need a telescope: dark skies, a star map, and patience reveal a lot.", 1350),
        OfflineEvergreenStory("Culture", "Reading lists to start this season", "Short story collections and essays are a great way to build a daily reading habit.", 740),
        OfflineEvergreenStory("Sports", "Easy ways to stay active on busy days", "Short walks, stretching breaks, and taking the stairs all add up over a week.", 510),
    )
    return stories.mapIndexed { index, story ->
        val query = URLEncoder.encode(story.title, StandardCharsets.UTF_8.name())
        NewsArticle(
            title = story.title,
            summary = story.summary,
            link = "https://news.google.com/search?q=$query",
            source = "Daily Digest",
            published = now.minusHours((index + 1) * 2L).format(rfc),
            topic = story.topic,
            wordCount = story.wordCount
        )
    }
}

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
