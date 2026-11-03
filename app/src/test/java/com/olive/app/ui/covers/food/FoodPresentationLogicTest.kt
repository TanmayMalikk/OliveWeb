// Tests food-cover labels, address summaries, and price formatting without rendering Compose UI.
package com.olive.app.ui.covers.food

import com.olive.app.data.DeliveryAddress
import com.olive.app.data.Dish
import com.olive.app.ui.shared.ResourceText
import org.junit.Assert.*
import org.junit.Test

class FoodPresentationLogicTest {
    private val text = ResourceText { id, _ -> "resource-$id" }
    // returns the dish for food cover. It uses the current cover data to update what the user sees.
    private fun dish(id: String = "bowl") = Dish(
        id = id,
        name = "Stored name",
        restaurant = "Stored restaurant",
        category = "Stored category",
        description = "Stored description",
        priceCents = 1495,
        minutes = "20-30",
        rating = "4.9",
        art = "bowl",
        ingredients = listOf("Quinoa", "Unknown ingredient"),
    )

    // Input: a known catalog ID. Expected: all visible catalog fields resolve through Android resources.
    @Test
    fun knownDishFieldsUseLocalizedResources() {
        val known = dish()
        assertTrue(text.dishName(known).startsWith("resource-"))
        assertTrue(text.dishDescription(known).startsWith("resource-"))
        assertTrue(text.dishCategory(known).startsWith("resource-"))
        assertTrue(text.dishRestaurant(known).startsWith("resource-"))
    }

    // Input: an unknown future catalog ID. Expected: stored server labels remain visible instead of becoming blank.
    @Test
    fun unknownDishUsesStoredFallbackLabels() {
        val unknown = dish("future-item")
        assertEquals("Stored name", text.dishName(unknown))
        assertEquals("Stored description", text.dishDescription(unknown))
        assertEquals("Stored category", text.dishCategory(unknown))
        assertEquals("Stored restaurant", text.dishRestaurant(unknown))
    }

    // Input: a known and unknown ingredient. Expected: known text localizes and unknown text survives unchanged.
    @Test
    fun ingredientLabelsPreserveUnknownValues() {
        assertTrue(text.ingredient("Quinoa").startsWith("resource-"))
        assertEquals("Dragon fruit", text.ingredient("Dragon fruit"))
        assertEquals("", text.ingredient(""))
    }

    // Input: every supported filter plus hostile text. Expected: only known filters are localized.
    @Test
    fun foodFiltersLocalizeOnlySupportedValues() {
        listOf("All", "Vegetarian", "Vegan", "Meat").forEach {
            assertTrue(text.foodFilter(it).startsWith("resource-"))
        }
        assertEquals("DROP TABLE dishes", text.foodFilter("DROP TABLE dishes"))
        assertEquals("vegan", text.foodFilter("vegan"))
    }

    // Input: a complete two-line address. Expected: fields appear once in delivery order.
    @Test
    fun completeDeliveryAddressUsesReadableOrder() {
        val address = DeliveryAddress(
            line1 = "1 Olive Street",
            line2 = "Unit 2",
            city = "Edmonton",
            province = "AB",
            postalCode = "T5J 0N3",
            country = "Canada",
        )
        assertEquals(
            "1 Olive Street, Unit 2, Edmonton, AB, T5J 0N3, Canada",
            address.displayText(),
        )
    }

    // Input: city-only, sparse, and empty addresses. Expected: no duplicate separators or literal null values.
    @Test
    fun sparseDeliveryAddressOmitsBlankParts() {
        assertEquals("Edmonton, Canada", DeliveryAddress(city = "Edmonton").displayText())
        assertEquals("Canada", DeliveryAddress().displayText())
        assertFalse(DeliveryAddress(line1 = "   ", country = "").displayText().contains(","))
    }

    // Input: zero, cent, ordinary, and maximum Int prices. Expected: formatting uses dollars without overflow.
    @Test
    fun dishPricesFormatBoundaryCentValues() {
        assertEquals("$0.00", formatDishPrice(0))
        assertEquals("$0.01", formatDishPrice(1))
        assertEquals("$19.95", formatDishPrice(1995))
        assertEquals("$21474836.47", formatDishPrice(Int.MAX_VALUE))
    }

    // Input: Unicode stored labels for an unknown item. Expected: fallback text is returned exactly.
    @Test
    fun unknownDishFallbackPreservesUnicode() {
        val unicode = dish("new-item").copy(name = "橄榄 🫒", description = "Crème brûlée")
        assertEquals("橄榄 🫒", text.dishName(unicode))
        assertEquals("Crème brûlée", text.dishDescription(unicode))
    }
}
