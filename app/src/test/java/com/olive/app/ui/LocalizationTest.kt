package com.olive.app.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalizationTest {

    private fun findResDir(): File {
        val candidates = listOf(
            File("src/main/res"),
            File("app/src/main/res"),
            File("../app/src/main/res")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: throw IllegalStateException("Res directory not found in candidate paths: $candidates")
    }

    private fun countStringsInXml(file: File): Int {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(file)
            val nodes = doc.getElementsByTagName("string")
            nodes.length
        } catch (e: Exception) {
            0
        }
    }

    @Test
    fun verifyStringsXmlExistsAndIsPopulatedForSupportedLanguages() {
        val resDir = findResDir()
        assertTrue("Base res directory must exist", resDir.exists())

        // Target languages to verify (ignoring Cree / values-cr for now per instructions)
        val targetLocales = listOf("values-fr", "values-es", "values-ar", "values-tl", "values-zh")

        // 1. Check default English strings.xml
        val defaultStringsFile = File(resDir, "values/strings.xml")
        assertTrue("Default values/strings.xml must exist", defaultStringsFile.exists())
        val defaultCount = countStringsInXml(defaultStringsFile)
        assertTrue("Default strings.xml should be populated (found $defaultCount strings)", defaultCount > 100)

        // 2. Check each target locale strings.xml
        for (localeDirName in targetLocales) {
            val localeDir = File(resDir, localeDirName)
            assertTrue("Locale directory '$localeDirName' must exist", localeDir.exists())

            val stringsFile = File(localeDir, "strings.xml")
            assertTrue("strings.xml must exist in '$localeDirName'", stringsFile.exists())

            val stringCount = countStringsInXml(stringsFile)
            assertTrue("strings.xml in '$localeDirName' must be populated (found $stringCount strings)", stringCount > 50)
        }
    }

    @Test
    fun verifyLanguageTagAndDisplayResolution() {
        assertEquals("en", getLanguageTag("English"))
        assertEquals("fr", getLanguageTag("French"))
        assertEquals("es", getLanguageTag("Spanish"))
        assertEquals("ar", getLanguageTag("Arabic"))
        assertEquals("tl", getLanguageTag("Tagalog"))
        assertEquals("zh", getLanguageTag("Chinese"))

        assertEquals("Français", getDisplayLanguage("French"))
        assertEquals("Español", getDisplayLanguage("Spanish"))
        assertEquals("العربية", getDisplayLanguage("Arabic"))
        assertEquals("Tagalog", getDisplayLanguage("Tagalog"))
        assertEquals("中文", getDisplayLanguage("Chinese"))
        assertEquals("English", getDisplayLanguage("English"))
    }

    @Test
    fun verifyLanguageSwappingAndTranslationResolution() {
        val testLanguages = listOf("English", "French", "Spanish", "Arabic", "Tagalog", "Chinese")
        for (lang in testLanguages) {
            val tag = getLanguageTag(lang)
            val translatedLabel = translate("App language", lang)
            assertNotNull("Translation for 'App language' in $lang should not be null", translatedLabel)
            assertTrue("Translation for 'App language' in $lang should not be blank", translatedLabel.isNotBlank())
            assertFalse("Tag for $lang should not be empty", tag.isEmpty())
        }
    }
}
