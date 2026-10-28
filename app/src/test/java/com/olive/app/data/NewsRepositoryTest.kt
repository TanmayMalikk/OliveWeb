// Tests news-feed locale queries for Daily Digest cover users.
package com.olive.app.data

import com.olive.app.ui.shared.*

import org.junit.Assert.*
import org.junit.Test

class NewsRepositoryTest {

    private val repository = GoogleNewsRepository()

    @Test
    fun rssLocaleQueryGeneratesCorrectGoogleNewsParametersForEachLanguage() {
        // Chinese needs both the region and script tag in the news query.
        val zhQuery = repository.rssLocaleQuery("Chinese")
        assertTrue(
            "Chinese query should contain zh-CN, got: $zhQuery",
            zhQuery.contains("hl=zh-CN")
        )
        assertTrue(
            "Chinese query should contain zh-Hans ceid, got: $zhQuery",
            zhQuery.contains("ceid=CN:zh-Hans")
        )

        val zhCodeQuery = repository.rssLocaleQuery("zh")
        assertTrue(
            "zh query should contain zh-CN, got: $zhCodeQuery",
            zhCodeQuery.contains("hl=zh-CN")
        )

        val frQuery = repository.rssLocaleQuery("French")
        assertTrue("French query should contain fr-CA, got: $frQuery", frQuery.contains("hl=fr-CA"))

        val esQuery = repository.rssLocaleQuery("Spanish")
        assertTrue(
            "Spanish query should contain es-419, got: $esQuery",
            esQuery.contains("hl=es-419")
        )

        val arQuery = repository.rssLocaleQuery("Arabic")
        assertTrue("Arabic query should contain ar, got: $arQuery", arQuery.contains("hl=ar"))

        val enQuery = repository.rssLocaleQuery("English")
        assertTrue(
            "English query should contain en-CA, got: $enQuery",
            enQuery.contains("hl=en-CA")
        )

        // Unsupported languages fall back to English news.
        val jaQuery = repository.rssLocaleQuery("Japanese")
        assertTrue(
            "Unsupported language query should default to en-CA, got: $jaQuery",
            jaQuery.contains("hl=en-CA")
        )
    }
}
