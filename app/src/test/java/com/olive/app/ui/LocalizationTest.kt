// Tests translated Android resources and language selection for all roles.
package com.olive.app.ui

import com.olive.app.ui.shared.*

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

        val targetLocales = listOf("values-fr", "values-es", "values-ar", "values-zh-rCN")

        val defaultStringsFile = File(resDir, "values/strings.xml")
        assertTrue("Default values/strings.xml must exist", defaultStringsFile.exists())
        val defaultCount = countStringsInXml(defaultStringsFile)
        assertTrue(
            "Default strings.xml should be populated (found $defaultCount strings)",
            defaultCount > 100
        )

        for (localeDirName in targetLocales) {
            val localeDir = File(resDir, localeDirName)
            assertTrue("Locale directory '$localeDirName' must exist", localeDir.exists())

            val stringsFile = File(localeDir, "strings.xml")
            assertTrue("strings.xml must exist in '$localeDirName'", stringsFile.exists())

            val stringCount = countStringsInXml(stringsFile)
            assertTrue(
                "strings.xml in '$localeDirName' must be populated (found $stringCount strings)",
                stringCount > 50
            )
        }
    }

    @Test
    fun verifyAppLocaleManagerResolution() {
        assertEquals("en", AppLocaleManager.getLanguageTag("English"))
        assertEquals("fr", AppLocaleManager.getLanguageTag("French"))
        assertEquals("es", AppLocaleManager.getLanguageTag("Spanish"))
        assertEquals("ar", AppLocaleManager.getLanguageTag("Arabic"))
        assertEquals("zh-rCN", AppLocaleManager.getLanguageTag("Chinese"))

        assertEquals("Français", AppLocaleManager.getDisplayLanguage("French"))
        assertEquals("Español", AppLocaleManager.getDisplayLanguage("Spanish"))
        assertEquals("العربية", AppLocaleManager.getDisplayLanguage("Arabic"))
        assertEquals("中文", AppLocaleManager.getDisplayLanguage("Chinese"))
        assertEquals("English", AppLocaleManager.getDisplayLanguage("English"))
    }
}
