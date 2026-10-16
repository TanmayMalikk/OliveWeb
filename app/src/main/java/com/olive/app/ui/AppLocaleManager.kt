package com.olive.app.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object AppLocaleManager {

    val supportedLanguages = listOf("English", "French", "Spanish", "Arabic", "Chinese")

    fun getLanguageTag(language: String): String {
        return when (language.trim().lowercase()) {
            "french", "fr", "français" -> "fr"
            "spanish", "es", "español" -> "es"
            "arabic", "ar", "العربية" -> "ar"
            "chinese", "zh", "zh-cn", "zh-rcn", "中文" -> "zh-rCN"
            else -> "en"
        }
    }

    fun getDisplayLanguage(language: String): String {
        return when (getLanguageTag(language)) {
            "fr" -> "Français"
            "es" -> "Español"
            "ar" -> "العربية"
            "zh-rCN" -> "中文"
            else -> "English"
        }
    }

    fun resolveSystemDefaultLanguageTag(): String {
        val systemLocale = Locale.getDefault()
        val lang = systemLocale.language.lowercase()
        return when {
            lang == "fr" -> "fr"
            lang == "es" -> "es"
            lang == "ar" -> "ar"
            lang == "zh" -> "zh-rCN"
            lang == "en" -> "en"
            else -> "en"
        }
    }

    fun resolveSystemDefaultLanguageName(): String {
        return when (resolveSystemDefaultLanguageTag()) {
            "fr" -> "French"
            "es" -> "Spanish"
            "ar" -> "Arabic"
            "zh-rCN" -> "Chinese"
            else -> "English"
        }
    }

    fun applyLocale(language: String) {
        val tag = getLanguageTag(language)
        val locales = LocaleListCompat.forLanguageTags(tag)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun initSystemDefaultLocale() {
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            val defaultTag = resolveSystemDefaultLanguageTag()
            applyLocale(defaultTag)
        }
    }
}
