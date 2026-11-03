// Tests supported language names and English fallback in the app.
package com.olive.app.ui

import com.olive.app.ui.shared.*

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SystemLanguageTest {
    // Inputs: the values shown below. Expected: supported system languages select their display names.
    @Test
    fun supportedSystemLanguagesSelectTheirDisplayNames() {
        val original = Locale.getDefault()
        // If supported system languages select their display names fails, catch the error so the app can keep running and explain the problem.
        try {
            mapOf(
                "fr-CA" to "French", "es-MX" to "Spanish", "ar-SA" to "Arabic",
                "zh-CN" to "Chinese", "en-CA" to "English"
            ).forEach { (tag, name) ->
                Locale.setDefault(Locale.forLanguageTag(tag))
                assertEquals(name, AppLocaleManager.resolveSystemDefaultLanguageName())
            }
        } finally {
            Locale.setDefault(original)
        }
    }

    // Inputs: the values shown below. Expected: unsupported system and legacy account languages fall back to english.
    @Test
    fun unsupportedSystemAndLegacyAccountLanguagesFallBackToEnglish() {
        val original = Locale.getDefault()
        // If unsupported system and legacy account languages fall back to english fails, catch the error so the app can keep running and explain the problem.
        try {
            Locale.setDefault(Locale.forLanguageTag("ja-JP"))
            assertEquals("English", AppLocaleManager.resolveSystemDefaultLanguageName())
            listOf("Tagalog", "Cree", "tl", "cr").forEach {
                assertEquals("en", AppLocaleManager.getLanguageTag(it))
            }
            assertEquals(
                listOf("English", "French", "Spanish", "Arabic", "Chinese"),
                AppLocaleManager.supportedLanguages
            )
        } finally {
            Locale.setDefault(original)
        }
    }
}
