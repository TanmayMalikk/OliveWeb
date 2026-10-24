// Contains the news article metadata code for this part of Olive.
package com.olive.app.data

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.edit
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
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

internal fun isSecureNewsUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme.equals(
        "https",
        ignoreCase = true
    ) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
}.getOrDefault(false)

// Groups the data and actions for news article metadata.
internal data class NewsArticleMetadata(
    val publisherUrl: String = "", val imageUrl: String = "", val topic: String = "",
    val isPaywalled: Boolean? = null, val description: String = ""
) {
    fun toJson(): JSONObject =
        JSONObject().put("publisherUrl", publisherUrl).put("imageUrl", imageUrl)
            .put("topic", topic).put("isPaywalled", isPaywalled ?: JSONObject.NULL)
            .put("description", description)

    // Applies to.
    fun applyTo(article: NewsArticle): NewsArticle = article.copy(
        publisherUrl = publisherUrl.ifBlank { article.publisherUrl },
        imageUrl = imageUrl.ifBlank { article.imageUrl },
        topic = topic.ifBlank { article.topic },
        isPaywalled = isPaywalled ?: article.isPaywalled,
        summary = description.ifBlank { article.summary })

    companion object {
        fun fromJson(value: JSONObject): NewsArticleMetadata = NewsArticleMetadata(
            value.optString("publisherUrl"),
            value.optString("imageUrl"),
            value.optString("topic"),
            if (value.isNull("isPaywalled")) null else value.optBoolean("isPaywalled"),
            value.optString("description")
        )
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
    document: org.jsoup.nodes.Document,
    original: String,
    pageUrl: String
): String {
    if (original.isBlank()) return original
    val sizingKeys = setOf("w", "width", "h", "height", "q", "quality", "dpr", "size")
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
    fun meta(vararg names: String): String = document.select("meta").firstOrNull { element ->
        names.any { name ->
            element.attr("property").equals(name, true) || element.attr("name")
                .equals(name, true) || element.attr("itemprop").equals(name, true)
        }
    }?.attr("content").orEmpty()

    fun articleNode(value: Any?, depth: Int = 0): JSONObject? {
        if (depth > 20) return null
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

    fun text(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> (0 until value.length()).firstNotNullOfOrNull {
            text(
                value.opt(it)
            ).takeIf(String::isNotBlank)
        }.orEmpty()

        else -> ""
    }

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

    fun freeFlag(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is String -> value.trim().lowercase()
            .toBooleanStrictOrNull()

        else -> null
    }

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

    private fun key(link: String): String =
        MessageDigest.getInstance("SHA-256").digest(link.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun cached(link: String, allowStale: Boolean = false): NewsArticleMetadata? = runCatching {
        val value = JSONObject(preferences.getString(key(link), null) ?: return@runCatching null)
        val expired = value.optLong("expiresAt") < System.currentTimeMillis()
        if (expired && !allowStale) null else NewsArticleMetadata.fromJson(value).let {
            // Keep the photo visible during refresh, but do not reuse an expired paywall verdict.
            if (expired) it.copy(isPaywalled = null) else it
        }
    }.getOrNull()

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
            } catch (_: Exception) {
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

    // Works out publisher.
    private suspend fun resolvePublisher(link: String): String? {
        if (!isSecureNewsUrl(link)) return null
        if (!URI(link).host.equals("news.google.com", true)) return link
        return redirectSlots.withPermit { resolveGooglePublisher(link) }
    }

    // Google News redirects require JavaScript. This resolver allows HTTPS navigation only and exposes no native bridge.
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
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

                                // Runs when page finished happens.
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
            try {
                val status = connection.responseCode
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
        fun isGoogleHost(host: String?): Boolean =
            host?.lowercase()?.let { it == "google.com" || it.endsWith(".google.com") } ?: false
    }
}
