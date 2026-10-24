// Tests the news reader policy test behavior.
package com.olive.app.ui.covers.news

import com.olive.app.ui.shared.*

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Groups the data and actions for news reader policy test.
class NewsReaderPolicyTest {
    @Test
    fun keepsSecureGoogleAndPublisherLinksInTheReader() {
        assertTrue(isNewsReaderUrlAllowed("https://news.google.com/rss/articles/example?hl=en-CA"))
        assertTrue(isNewsReaderUrlAllowed("https://publisher.example/news/story#section"))
        assertTrue(isNewsReaderUrlAllowed("HTTPS://publisher.example/story"))
    }

    @Test
    fun rejectsDeviceAccessAppIntentsAndMalformedLinks() {
        listOf(
            "file:///data/data/com.olive.app/private", "content://contacts/1",
            "javascript:alert(1)", "intent://story", "http://publisher.example/story",
            "https://", "https://user:password@publisher.example/story", "invalid link"
        ).forEach {
            assertFalse(it, isNewsReaderUrlAllowed(it))
        }
    }
}
