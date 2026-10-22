package com.olive.app.data
import com.olive.app.R

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
    val diet: String = "Vegetarian",
    val ingredients: List<String> = emptyList(),
)

// Swap the menu source behind this tiny interface without rebuilding the whole food UI.
interface CatalogRepository { fun dishes(): List<Dish> }

// Olive prepares this whole menu itself, so every item can offer predictable ingredient choices.
class DemoCatalogRepository(private val text:(Int)->String) : CatalogRepository {
    override fun dishes() = listOf(
        Dish("bowl",text(R.string.ui_garden_harvest_bowl),text(R.string.ui_olive_kitchen),text(R.string.bowls_1),text(R.string.ui_quinoa_roasted_vegetables_and_lemon_tahini),1495,"20–30","4.9","bowl",diet="Vegan",ingredients=listOf(text(R.string.ui_quinoa),text(R.string.ui_cucumber),text(R.string.ui_tomato),text(R.string.ui_roasted_chickpeas),text(R.string.ui_spinach),text(R.string.ui_lemon_tahini))),
        Dish("bowl2",text(R.string.ui_maple_sweet_potato_bowl),text(R.string.ui_olive_kitchen),text(R.string.bowls_1),text(R.string.ui_warm_grains_maple_sweet_potato_and_crunchy_greens),1525,"20–30","4.8","bowl",diet="Vegan",ingredients=listOf(text(R.string.ui_brown_rice),text(R.string.ui_sweet_potato),text(R.string.ui_kale),text(R.string.ui_pumpkin_seeds),text(R.string.ui_maple_dressing))),
        Dish("bowl3",text(R.string.ui_mediterranean_feta_bowl),text(R.string.ui_olive_kitchen),text(R.string.bowls_1),text(R.string.ui_fresh_vegetables_herbed_rice_and_feta),1575,"20–30","4.8","bowl",diet="Vegetarian",ingredients=listOf(text(R.string.ui_herbed_rice),text(R.string.ui_cucumber),text(R.string.ui_tomato),text(R.string.ui_olives),text(R.string.ui_feta),text(R.string.ui_tzatziki))),
        Dish("bowl4",text(R.string.ui_chicken_power_bowl),text(R.string.ui_olive_kitchen),text(R.string.bowls_1),text(R.string.ui_grilled_chicken_grains_and_crisp_vegetables),1695,"20–30","4.9","bowl",diet="Meat",ingredients=listOf(text(R.string.ui_chicken),text(R.string.ui_quinoa),text(R.string.ui_cucumber),text(R.string.ui_tomato),text(R.string.ui_spinach),text(R.string.ui_garlic_sauce))),
        Dish("pizza",text(R.string.classic_margherita),text(R.string.ui_olive_kitchen),text(R.string.pizza),text(R.string.ui_tomato_mozzarella_and_basil_on_house_made_dough),1600,"25–35","4.8","pizza",diet="Vegetarian",ingredients=listOf(text(R.string.ui_tomato_sauce),text(R.string.ui_mozzarella),text(R.string.ui_basil),text(R.string.ui_olive_oil))),
        Dish("pizza2",text(R.string.ui_roasted_vegetable_pizza),text(R.string.ui_olive_kitchen),text(R.string.pizza),text(R.string.ui_peppers_mushrooms_and_red_onion_with_mozzarella),1750,"25–35","4.7","pizza",diet="Vegetarian",ingredients=listOf(text(R.string.ui_tomato_sauce),text(R.string.ui_mozzarella),text(R.string.ui_peppers),text(R.string.ui_mushrooms),text(R.string.ui_red_onion))),
        Dish("pizza3",text(R.string.ui_vegan_garden_pizza),text(R.string.ui_olive_kitchen),text(R.string.pizza),text(R.string.ui_garden_vegetables_and_dairy_free_cheese),1795,"25–35","4.7","pizza",diet="Vegan",ingredients=listOf(text(R.string.ui_tomato_sauce),text(R.string.ui_vegan_cheese),text(R.string.ui_peppers),text(R.string.ui_mushrooms),text(R.string.ui_spinach))),
        Dish("pizza4",text(R.string.ui_chicken_pesto_pizza),text(R.string.ui_olive_kitchen),text(R.string.pizza),text(R.string.ui_chicken_pesto_tomato_and_mozzarella),1895,"25–35","4.8","pizza",diet="Meat",ingredients=listOf(text(R.string.ui_pesto),text(R.string.ui_chicken),text(R.string.ui_mozzarella),text(R.string.ui_tomato),text(R.string.ui_red_onion))),
        Dish("burger",text(R.string.ui_olive_house_burger),text(R.string.ui_olive_kitchen),text(R.string.burgers_1),text(R.string.ui_beef_cheddar_and_house_sauce_on_a_toasted_bun),1495,"20–30","4.8","burger",diet="Meat",ingredients=listOf(text(R.string.ui_beef_patty),text(R.string.ui_cheddar),text(R.string.ui_lettuce),text(R.string.ui_tomato),text(R.string.ui_pickles),text(R.string.ui_house_sauce))),
        Dish("burger2",text(R.string.ui_crispy_chicken_burger),text(R.string.ui_olive_kitchen),text(R.string.burgers_1),text(R.string.ui_crispy_chicken_with_slaw_and_pickles),1525,"20–30","4.7","burger",diet="Meat",ingredients=listOf(text(R.string.ui_chicken),text(R.string.ui_slaw),text(R.string.ui_pickles),text(R.string.ui_lettuce),text(R.string.ui_garlic_sauce))),
        Dish("burger3",text(R.string.ui_black_bean_burger),text(R.string.ui_olive_kitchen),text(R.string.burgers_1),text(R.string.ui_house_made_black_bean_patty_with_fresh_toppings),1425,"20–30","4.7","burger",diet="Vegan",ingredients=listOf(text(R.string.ui_black_bean_patty),text(R.string.ui_lettuce),text(R.string.ui_tomato),text(R.string.ui_pickles),text(R.string.ui_vegan_sauce))),
        Dish("wrap1",text(R.string.ui_falafel_crunch_wrap),text(R.string.ui_olive_kitchen),text(R.string.ui_wraps),text(R.string.ui_falafel_vegetables_and_tahini_in_a_warm_wrap),1325,"15–25","4.8","bowl",diet="Vegan",ingredients=listOf(text(R.string.ui_falafel),text(R.string.ui_cucumber),text(R.string.ui_tomato),text(R.string.ui_lettuce),text(R.string.ui_pickled_onion),text(R.string.ui_tahini))),
        Dish("wrap2",text(R.string.ui_grilled_chicken_wrap),text(R.string.ui_olive_kitchen),text(R.string.ui_wraps),text(R.string.ui_seasoned_chicken_and_garden_vegetables),1425,"15–25","4.8","bowl",diet="Meat",ingredients=listOf(text(R.string.ui_chicken),text(R.string.ui_cucumber),text(R.string.ui_tomato),text(R.string.ui_lettuce),text(R.string.ui_cheddar),text(R.string.ui_garlic_sauce))),
        Dish("pasta1",text(R.string.ui_creamy_mushroom_pasta),text(R.string.ui_olive_kitchen),text(R.string.ui_pasta),text(R.string.ui_mushrooms_and_spinach_in_a_creamy_parmesan_sauce),1675,"25–35","4.8","bowl",diet="Vegetarian",ingredients=listOf(text(R.string.ui_pasta),text(R.string.ui_mushrooms),text(R.string.ui_spinach),text(R.string.ui_parmesan),text(R.string.ui_cream_sauce))),
        Dish("pasta2",text(R.string.ui_tomato_basil_pasta),text(R.string.ui_olive_kitchen),text(R.string.ui_pasta),text(R.string.ui_slow_cooked_tomato_sauce_with_basil),1495,"25–35","4.7","bowl",diet="Vegan",ingredients=listOf(text(R.string.ui_pasta),text(R.string.ui_tomato_sauce),text(R.string.ui_basil),text(R.string.ui_roasted_garlic))),
        Dish("pasta3",text(R.string.ui_chicken_parmesan_pasta),text(R.string.ui_olive_kitchen),text(R.string.ui_pasta),text(R.string.ui_breaded_chicken_tomato_sauce_and_melted_cheese),1895,"25–35","4.8","bowl",diet="Meat",ingredients=listOf(text(R.string.ui_pasta),text(R.string.ui_chicken),text(R.string.ui_tomato_sauce),text(R.string.ui_mozzarella),text(R.string.ui_parmesan))),
        Dish("salad1",text(R.string.ui_apple_walnut_salad),text(R.string.ui_olive_kitchen),text(R.string.ui_salads),text(R.string.ui_greens_apple_walnuts_and_maple_vinaigrette),1295,"15–25","4.7","bowl",diet="Vegan",ingredients=listOf(text(R.string.ui_mixed_greens),text(R.string.ui_apple),text(R.string.ui_walnuts),text(R.string.ui_cranberries),text(R.string.ui_maple_vinaigrette))),
        Dish("salad2",text(R.string.ui_greek_village_salad),text(R.string.ui_olive_kitchen),text(R.string.ui_salads),text(R.string.ui_cucumber_tomato_olives_and_feta),1325,"15–25","4.8","bowl",diet="Vegetarian",ingredients=listOf(text(R.string.ui_cucumber),text(R.string.ui_tomato),text(R.string.ui_olives),text(R.string.ui_feta),text(R.string.ui_red_onion),text(R.string.ui_herb_dressing))),
        Dish("soup1",text(R.string.ui_tomato_herb_soup),text(R.string.ui_olive_kitchen),text(R.string.ui_soups),text(R.string.ui_a_smooth_tomato_soup_with_basil_and_garlic),995,"15–25","4.7","bowl",diet="Vegan",ingredients=listOf(text(R.string.ui_tomato),text(R.string.ui_basil),text(R.string.ui_garlic),text(R.string.ui_herb_oil))),
        Dish("plate1",text(R.string.ui_homestyle_chicken_plate),text(R.string.ui_olive_kitchen),text(R.string.ui_plates),text(R.string.ui_roasted_chicken_potatoes_and_seasonal_vegetables),1995,"30–40","4.9","bowl",diet="Meat",ingredients=listOf(text(R.string.ui_roasted_chicken),text(R.string.ui_potatoes),text(R.string.ui_carrots),text(R.string.ui_green_beans),text(R.string.ui_gravy))),
        Dish("plate2",text(R.string.ui_stuffed_pepper_plate),text(R.string.ui_olive_kitchen),text(R.string.ui_plates),text(R.string.ui_rice_and_lentil_stuffed_pepper_with_tomato_sauce),1725,"30–40","4.8","bowl",diet="Vegan",ingredients=listOf(text(R.string.ui_bell_pepper),text(R.string.ui_rice),text(R.string.ui_lentils),text(R.string.ui_tomato_sauce),text(R.string.ui_herbs))),
    )
}

// Search and category chips meet here to decide which dishes make the cut.
fun filterDishes(dishes: List<Dish>, query: String, category: String): List<Dish> {
    val term = query.trim()
    return dishes.filter {
        (category == "All" || it.diet == category) &&
            (term.isEmpty() || "${it.name} ${it.restaurant} ${it.category}".contains(term, ignoreCase = true))
    }
}

// Adds up cents as integers so money math stays clean and predictable.
fun cartTotal(dishes: List<Dish>, quantities: Map<String, Int>): Int =
    dishes.sumOf { it.priceCents * (quantities[it.id] ?: 0).coerceAtLeast(0) }
