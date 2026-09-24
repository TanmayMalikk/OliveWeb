package com.olive.app.data

data class Dish(
    val id: String,
    val name: String,
    val restaurant: String,
    val category: String,
    val description: String,
    val priceCents: Int,
    val minutes: String,
    val rating: String,
    val art: String,
    val imageUrl: String = "",
    val imageCredit: String = "",
    val sourceUrl: String = "",
)

// Swap the menu source behind this tiny interface without rebuilding the whole food UI.
interface CatalogRepository { fun dishes(): List<Dish> }

// Until a licensed live provider is connected, these clearly labelled samples keep the cover feeling real.
class DemoCatalogRepository : CatalogRepository {
    override fun dishes() = listOf(
        Dish("bowl", "The green goddess", "Roots & Greens", "Bowls", "A bright bowl of avocado, quinoa, crunchy greens, cucumber and our herby tahini dressing.", 1495, "15–25", "4.9", "bowl"),
        Dish("pizza", "Burrata & basil", "Little Italy", "Pizza", "Stone-baked sourdough, slow-roasted tomatoes, creamy burrata and fresh basil.", 1850, "25–35", "4.8", "pizza"),
        Dish("burger", "The house burger", "Good Bun", "Burgers", "A grilled patty, cheddar, crisp lettuce, tomato and house sauce in a toasted sesame bun.", 1395, "20–30", "4.7", "burger"),
        Dish("sushi", "Salmon garden roll", "Maki House", "Sushi", "Salmon, avocado and cucumber rolls with sesame, ginger and a side of soy sauce.", 1675, "20–35", "4.9", "sushi"),
        Dish("bowl2", "Harvest grain bowl", "Roots & Greens", "Bowls", "Roasted sweet potato, ancient grains, baby spinach and lemon dressing.", 1425, "15–25", "4.8", "bowl"),
        Dish("pizza2", "Classic margherita", "Little Italy", "Pizza", "A simple favourite with tomato, mozzarella, basil and extra virgin olive oil.", 1600, "25–35", "4.8", "pizza"),
    )
}

// Search and category chips meet here to decide which dishes make the cut.
fun filterDishes(dishes: List<Dish>, query: String, category: String): List<Dish> {
    val term = query.trim()
    return dishes.filter {
        (category == "All" || it.category == category) &&
            (term.isEmpty() || "${it.name} ${it.restaurant} ${it.category}".contains(term, ignoreCase = true))
    }
}

// Adds up cents as integers so money math stays clean and predictable.
fun cartTotal(dishes: List<Dish>, quantities: Map<String, Int>): Int =
    dishes.sumOf { it.priceCents * (quantities[it.id] ?: 0).coerceAtLeast(0) }
