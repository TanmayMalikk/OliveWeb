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
    fun readingTimeCalculatesFromActualWordCountOrFallsBack() {
        assertEquals(1, estimateReadingMinutes("Short Story", "", wordCount = 200))
        assertEquals(4, estimateReadingMinutes("Standard Report", "", wordCount = 800))
        assertEquals(7, estimateReadingMinutes("In-depth Analysis", "", wordCount = 1400))
        val fallback = estimateReadingMinutes("Regular Title", "A brief preview summary.")
        assertTrue(fallback in 2..6)
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
