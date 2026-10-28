// Tests supported language names and English fallback in the app.
package com.olive.app.ui

import com.olive.app.ui.shared.*

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class SystemLanguageTest {
    @Test
    fun supportedSystemLanguagesSelectTheirDisplayNames() {
        val original = Locale.getDefault()
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

    @Test
    fun unsupportedSystemAndLegacyAccountLanguagesFallBackToEnglish() {
        val original = Locale.getDefault()
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
