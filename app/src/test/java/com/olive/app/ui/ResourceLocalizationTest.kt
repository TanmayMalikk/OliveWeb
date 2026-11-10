// Tests translated strings and placeholders.
package com.olive.app.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ResourceLocalizationTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val locales = listOf("values-fr", "values-es", "values-ar", "values-zh-rCN")

    // Checks the strings step with the test's sample data.
    private fun strings(folder: String): Map<String, String> =
        File(root, "res/$folder").listFiles().orEmpty()
            .filter { it.extension == "xml" }.flatMap { file ->
                val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                    .getElementsByTagName("string")
                (0 until nodes.length).map { index ->
                    val node = nodes.item(index)
                    node.attributes.getNamedItem("name").nodeValue to node.textContent
                }
            }.toMap()

    // Inputs: the values shown below. Expected: every referenced string has translations with matching placeholders.
    @Test
    fun everyReferencedStringHasTranslationsWithMatchingPlaceholders() {
        val used =
            File(root, "java").walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
                Regex("R\\.string\\.(\\w+)").findAll(file.readText()).map { it.groupValues[1] }
            }.toSet()
        val base = strings("values")
        // Checks the placeholders step with the test's sample data.
        fun placeholders(value: String) = Regex("%[0-9]+\\$[a-zA-Z]").findAll(value)
            .map { it.value }.groupingBy { it }.eachCount()
        locales.forEach { locale ->
            val translated = strings(locale)
            used.forEach { name ->
                assertTrue("Missing $locale/$name", translated.containsKey(name))
                assertEquals(
                    "Placeholders differ in $locale/$name",
                    placeholders(base.getValue(name)),
                    placeholders(translated.getValue(name))
                )
            }
        }
    }

    // Inputs: the values shown below. Expected: ordinary ui copy does not return to literal text arguments.
    @Test
    fun ordinaryUiCopyDoesNotReturnToLiteralTextArguments() {
        val directEnglish =
            Regex("\\b(?:Text|Field|Heading|RequirementBullet)\\(\\s*\"([A-Za-z][^\"]*)\"")
        val violations =
            File(root, "java/com/olive/app/ui").walkTopDown().filter { it.extension == "kt" }
                .flatMap { file ->
                    directEnglish.findAll(file.readText()).filterNot { match ->
                        match.groupValues[1].startsWith("Y")
                    }.map { "${file.name}: ${it.value}" }
                }.toList()
        assertTrue("Use string resources for app copy: $violations", violations.isEmpty())
    }
}
