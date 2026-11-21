package com.olive.app.data

import com.olive.app.data.news.curatedOfflineArticles
import com.olive.app.data.news.estimateReadingMinutes
import com.olive.app.data.news.googleNewsTopicId
import com.olive.app.data.news.isSecureNewsUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsOfflineFallbackTest {
    @Test
    fun readingTimeIsClampedToRealisticRange() {
        assertEquals(2, estimateReadingMinutes("Short", ""))
        assertEquals(8, estimateReadingMinutes("Long", "word ".repeat(2_000)))
    }

    @Test
    fun categoriesMapToGoogleNewsTopics() {
        assertNull(googleNewsTopicId("All"))
        assertEquals("TECHNOLOGY", googleNewsTopicId("Technology"))
        assertEquals("ENTERTAINMENT", googleNewsTopicId("Culture"))
    }

    @Test
    fun offlineStoriesAreSecureInHouseAndUnique() {
        val stories = curatedOfflineArticles("Edmonton")
        assertTrue(stories.isNotEmpty())
        assertTrue(stories.all { isSecureNewsUrl(it.link) && it.source == "Daily Digest" })
        assertEquals(stories.size, stories.map { it.link }.toSet().size)
        assertTrue(stories.first().title.contains("Edmonton"))
    }
}
