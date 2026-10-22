package com.olive.app.data

import org.junit.Assert.*
import org.junit.Test

class NewsRepositoryTest {

    private val repository = GoogleNewsRepository()

    @Test
    fun rssLocaleQueryGeneratesCorrectGoogleNewsParametersForEachLanguage() {
        // Chinese (Simplified) must specify zh-CN and zh-Hans
        val zhQuery = repository.rssLocaleQuery("Chinese")
        assertTrue("Chinese query should contain zh-CN, got: $zhQuery", zhQuery.contains("hl=zh-CN"))
        assertTrue("Chinese query should contain zh-Hans ceid, got: $zhQuery", zhQuery.contains("ceid=CN:zh-Hans"))

        val zhCodeQuery = repository.rssLocaleQuery("zh")
        assertTrue("zh query should contain zh-CN, got: $zhCodeQuery", zhCodeQuery.contains("hl=zh-CN"))

        // French
        val frQuery = repository.rssLocaleQuery("French")
        assertTrue("French query should contain fr-CA, got: $frQuery", frQuery.contains("hl=fr-CA"))

        // Spanish
        val esQuery = repository.rssLocaleQuery("Spanish")
        assertTrue("Spanish query should contain es-419, got: $esQuery", esQuery.contains("hl=es-419"))

        // Arabic
        val arQuery = repository.rssLocaleQuery("Arabic")
        assertTrue("Arabic query should contain ar, got: $arQuery", arQuery.contains("hl=ar"))

        // English (default)
        val enQuery = repository.rssLocaleQuery("English")
        assertTrue("English query should contain en-CA, got: $enQuery", enQuery.contains("hl=en-CA"))

        // Unsupported language (e.g. Japanese) defaults to English
        val jaQuery = repository.rssLocaleQuery("Japanese")
        assertTrue("Unsupported language query should default to en-CA, got: $jaQuery", jaQuery.contains("hl=en-CA"))
    }
}
