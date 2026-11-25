// Tests news-feed locale queries, RSS parsing, and local caching for Daily Digest cover users.
package com.olive.app.data

import com.olive.app.data.news.GoogleNewsRepository
import com.olive.app.data.news.NewsArticle
import com.olive.app.data.news.NewsArticleMetadata
import com.olive.app.data.news.NewsFeedCacheStore
import com.olive.app.data.news.NewsMetadataRepository
import com.olive.app.data.news.NewsScope
import com.olive.app.data.news.localPublishedDate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NewsRepositoryTest {

    private val repository = GoogleNewsRepository()

    @Test
    fun newsEditionUsesDeviceLanguageAndRegion() {
        assertEquals("hl=fr-CA&gl=CA&ceid=CA:fr", repository.rssLocaleQuery("fr-CA"))
        assertEquals("hl=es-MX&gl=MX&ceid=MX:es", repository.rssLocaleQuery("es-MX"))
        assertEquals("hl=ar-SA&gl=SA&ceid=SA:ar", repository.rssLocaleQuery("ar-SA"))
        assertEquals("hl=zh-CN&gl=CN&ceid=CN:zh-Hans", repository.rssLocaleQuery("zh-CN"))
        assertEquals("hl=zh-TW&gl=TW&ceid=TW:zh-Hant", repository.rssLocaleQuery("zh-TW"))
        assertEquals("hl=en-GB&gl=GB&ceid=GB:en", repository.rssLocaleQuery("en-GB"))
    }

    @Test
    fun deviceLanguagesWithoutOliveTranslationsStillDriveTheNewsEdition() {
        assertEquals("hl=ja-JP&gl=JP&ceid=JP:ja", repository.rssLocaleQuery("ja-JP"))
        assertEquals("hl=de&gl=DE&ceid=DE:de", repository.rssLocaleQuery("de"))
    }

    @Test
    fun latestParsesHttpsGoogleNewsRssAndFormatsPublishedDates() = runBlocking {
        val rssXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <item>
                <title>Hockey championship begins</title>
                <description>&lt;b&gt;Opening&lt;/b&gt; game tonight</description>
                <link>https://news.google.com/rss/articles/story-1</link>
                <source>City News</source>
                <pubDate>Wed, 18 Nov 2026 12:00:00 GMT</pubDate>
              </item>
              <item>
                <title>Insecure item filtered out</title>
                <link>http://insecure.example/story-2</link>
              </item>
            </channel></rss>
        """.trimIndent()
        val urls = mutableListOf<String>()
        val repo = GoogleNewsRepository { url ->
            urls += url.toString()
            mock(HttpsURLConnection::class.java).also { conn ->
                `when`(conn.responseCode).thenReturn(200)
                `when`(conn.inputStream).thenReturn(ByteArrayInputStream(rssXml.toByteArray(Charsets.UTF_8)))
            }
        }

        val local = repo.latest("en-CA", NewsScope.LOCAL, "")
        val global = repo.latest("en-CA", NewsScope.GLOBAL, "Edmonton")

        assertEquals(1, local.size)
        assertEquals(1, global.size)
        assertTrue(urls.first().contains("/rss/search?q=Canada"))
        assertTrue(urls.last().contains("/rss/headlines/section/topic/WORLD"))
        assertEquals("Opening game tonight", local.single().summary)
        assertEquals("Sports", local.single().topic)
        assertTrue(local.single().localPublishedDate("en-CA").contains("2026"))
        assertEquals("bad-date", NewsArticle("T", link = "https://news.google.com/1", published = "bad-date").localPublishedDate("en-CA"))
    }

    @Test
    fun feedCacheStorePersistsArticlesAndRejectsCorruptOrEmptyPayloads() {
        val app = RuntimeEnvironment.getApplication()
        val store = NewsFeedCacheStore(app)
        assertNull(store.load("en-CA", NewsScope.LOCAL))

        store.save("en-CA", NewsScope.LOCAL, "Edmonton", emptyList())
        assertNull(store.load("en-CA", NewsScope.LOCAL))

        val articles = listOf(NewsArticle("Headline", "Summary", "https://news.google.com/rss/articles/1"))
        store.save("en-CA", NewsScope.LOCAL, "Edmonton", articles)
        val restored = store.load("en-CA", NewsScope.LOCAL)
        assertEquals("Edmonton", restored?.place)
        assertEquals(articles, restored?.articles)

        app.getSharedPreferences("daily_digest_feed_cache", 0).edit()
            .putString("french_global", "{not-json").commit()
        assertNull(store.load("French", NewsScope.GLOBAL))
    }

    @Test
    fun metadataRepositoryCachesFetchesRedirectsGzipPaywallsAndEvictsOldestEntries() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("daily_digest_article_metadata", 0)
        prefs.edit().clear().commit()
        repeat(129) { idx ->
            prefs.edit().putString("old-$idx", NewsArticleMetadata(publisherUrl = "https://p/$idx").toJson().put("expiresAt", idx.toLong()).toString()).commit()
        }

        val html = """
            <meta property="og:type" content="article">
            <meta property="og:image" content="https://publisher.example/photo.jpg">
            <meta property="article:section" content="Technology">
            <meta property="og:description" content="Compressed preview">
        """.trimIndent()
        val gzipped = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(html.toByteArray(Charsets.UTF_8)) }
        }.toByteArray()

        val redirectConn = mock(HttpsURLConnection::class.java).also {
            `when`(it.responseCode).thenReturn(301)
            `when`(it.getHeaderField("Location")).thenReturn("https://publisher.example/story")
        }
        val okConn = mock(HttpsURLConnection::class.java).also {
            `when`(it.responseCode).thenReturn(200)
            `when`(it.contentEncoding).thenReturn("gzip")
            `when`(it.inputStream).thenReturn(ByteArrayInputStream(gzipped))
        }
        val paywallConn = mock(HttpsURLConnection::class.java).also {
            `when`(it.responseCode).thenReturn(402)
        }
        val errorConn = mock(HttpsURLConnection::class.java).also {
            `when`(it.responseCode).thenReturn(500)
        }

        val repoRedirect = NewsMetadataRepository(app) { url ->
            if (url.toString().contains("redirect")) redirectConn else okConn
        }
        val loaded = repoRedirect.load(NewsArticle("Title", link = "https://publisher.example/redirect", publisherUrl = "https://publisher.example/redirect"))
        assertEquals("https://publisher.example/story", loaded.publisherUrl)
        assertEquals("Technology", loaded.topic)
        assertTrue(prefs.all.size <= 128)
        assertEquals(loaded, repoRedirect.load(NewsArticle("Title", link = "https://publisher.example/redirect")))

        val completeFastPath = repoRedirect.load(
            NewsArticle(
                "Ready",
                link = "https://publisher.example/uncached-complete",
                imageUrl = "https://publisher.example/img.jpg",
                topic = "Health",
                isPaywalled = false,
                publisherUrl = "https://publisher.example/uncached-complete",
            ),
        )
        assertEquals("Health", completeFastPath.topic)

        val paywalled = NewsMetadataRepository(app) { paywallConn }.load(
            NewsArticle("Paid", link = "https://publisher.example/paid", publisherUrl = "https://publisher.example/paid"),
        )
        assertEquals(true, paywalled.isPaywalled)

        val unavailable = NewsMetadataRepository(app) { errorConn }.load(
            NewsArticle("Down", link = "https://publisher.example/down", publisherUrl = "https://publisher.example/down"),
        )
        assertEquals("", unavailable.publisherUrl)

        val staleJson = NewsArticleMetadata("https://publisher.example/stale", isPaywalled = true).toJson().put("expiresAt", 1L).toString()
        prefs.edit().putString(com.olive.app.data.media.sha256Hex("https://publisher.example/stale"), staleJson).commit()
        assertNull(repoRedirect.cached("https://publisher.example/stale", allowStale = false))
        val stale = repoRedirect.cached("https://publisher.example/stale", allowStale = true)
        assertNotNull(stale)
        assertNull(stale?.isPaywalled)
    }
}
