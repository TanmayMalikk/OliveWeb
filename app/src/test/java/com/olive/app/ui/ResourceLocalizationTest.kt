// Tests translated strings, placeholders, and food-menu labels.
package com.olive.app.ui

import com.olive.app.ui.shared.*

import com.olive.app.R
import com.olive.app.data.DemoCatalogRepository
import com.olive.app.data.CoverType
import com.olive.app.notification.AppNotifications
import com.olive.app.ui.covers.food.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ResourceLocalizationTest {
    private val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
    private val locales = listOf("values-fr", "values-es", "values-ar", "values-zh-rCN")

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

    @Test
    fun everyReferencedStringHasTranslationsWithMatchingPlaceholders() {
        val used =
            File(root, "java").walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
                Regex("R\\.string\\.(\\w+)").findAll(file.readText()).map { it.groupValues[1] }
            }.toSet()
        val base = strings("values")
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

    @Test
    fun translatingMenuLabelsPreservesIngredientsAndCartIdentity() {
        val english = testResourceText()
        val french = testResourceText("values-fr")
        val vm = OliveViewModel(DemoCatalogRepository(english::invoke))
        val dish = vm.dishes.first()
        vm.changeQuantity(dish.id, 1)
        val ingredient = dish.ingredients.first()
        vm.toggleIngredient(dish, ingredient)
        val choices = vm.selectedIngredients(dish)
        assertNotEquals(english.dishName(dish), french.dishName(dish))
        vm.updateSearchLabels(vm.dishes.associate { it.id to french.dishName(it) })
        vm.search(french.dishName(dish))
        assertEquals(listOf(dish.id), vm.results.map { it.id })
        assertEquals(choices, vm.selectedIngredients(dish))
        assertEquals(1, vm.cart[dish.id])
        assertFalse(vm.selectedIngredients(dish).contains(ingredient))
    }

    @Test
    fun notificationDefaultsUseResourcesAndPreserveSuppliedContent() {
        val french = testResourceText("values-fr")
        val defaults = AppNotifications.contentFor(null, null, CoverType.FOOD, text = french)
        assertEquals(french(R.string.ui_olive_order_update), defaults.title)
        assertNotEquals("Your order is being prepared.", defaults.body)
        val custom = AppNotifications.contentFor(
            "Publisher title",
            "Original content",
            CoverType.NEWS,
            text = french
        )
        assertEquals("Publisher title", custom.title)
        assertEquals("Original content", custom.body)
    }

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
