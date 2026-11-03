// Food cover users: defines text and labels for food-cover items.
package com.olive.app.ui.covers.food

import com.olive.app.R
import com.olive.app.data.Dish
import com.olive.app.ui.shared.ResourceText

private data class DishLabels(
    val name: Int,
    val description: Int,
    val category: Int,
    val restaurant: Int
)

private val dishLabels = mapOf(
    "bowl" to DishLabels(
        R.string.ui_garden_harvest_bowl,
        R.string.ui_quinoa_roasted_vegetables_and_lemon_tahini,
        R.string.bowls_1,
        R.string.ui_olive_kitchen
    ),
    "bowl2" to DishLabels(
        R.string.ui_maple_sweet_potato_bowl,
        R.string.ui_warm_grains_maple_sweet_potato_and_crunchy_greens,
        R.string.bowls_1,
        R.string.ui_olive_kitchen
    ),
    "bowl3" to DishLabels(
        R.string.ui_mediterranean_feta_bowl,
        R.string.ui_fresh_vegetables_herbed_rice_and_feta,
        R.string.bowls_1,
        R.string.ui_olive_kitchen
    ),
    "bowl4" to DishLabels(
        R.string.ui_chicken_power_bowl,
        R.string.ui_grilled_chicken_grains_and_crisp_vegetables,
        R.string.bowls_1,
        R.string.ui_olive_kitchen
    ),
    "pizza" to DishLabels(
        R.string.classic_margherita,
        R.string.ui_tomato_mozzarella_and_basil_on_house_made_dough,
        R.string.pizza,
        R.string.ui_olive_kitchen
    ),
    "pizza2" to DishLabels(
        R.string.ui_roasted_vegetable_pizza,
        R.string.ui_peppers_mushrooms_and_red_onion_with_mozzarella,
        R.string.pizza,
        R.string.ui_olive_kitchen
    ),
    "pizza3" to DishLabels(
        R.string.ui_vegan_garden_pizza,
        R.string.ui_garden_vegetables_and_dairy_free_cheese,
        R.string.pizza,
        R.string.ui_olive_kitchen
    ),
    "pizza4" to DishLabels(
        R.string.ui_chicken_pesto_pizza,
        R.string.ui_chicken_pesto_tomato_and_mozzarella,
        R.string.pizza,
        R.string.ui_olive_kitchen
    ),
    "burger" to DishLabels(
        R.string.ui_olive_house_burger,
        R.string.ui_beef_cheddar_and_house_sauce_on_a_toasted_bun,
        R.string.burgers_1,
        R.string.ui_olive_kitchen
    ),
    "burger2" to DishLabels(
        R.string.ui_crispy_chicken_burger,
        R.string.ui_crispy_chicken_with_slaw_and_pickles,
        R.string.burgers_1,
        R.string.ui_olive_kitchen
    ),
    "burger3" to DishLabels(
        R.string.ui_black_bean_burger,
        R.string.ui_house_made_black_bean_patty_with_fresh_toppings,
        R.string.burgers_1,
        R.string.ui_olive_kitchen
    ),
    "wrap1" to DishLabels(
        R.string.ui_falafel_crunch_wrap,
        R.string.ui_falafel_vegetables_and_tahini_in_a_warm_wrap,
        R.string.ui_wraps,
        R.string.ui_olive_kitchen
    ),
    "wrap2" to DishLabels(
        R.string.ui_grilled_chicken_wrap,
        R.string.ui_seasoned_chicken_and_garden_vegetables,
        R.string.ui_wraps,
        R.string.ui_olive_kitchen
    ),
    "pasta1" to DishLabels(
        R.string.ui_creamy_mushroom_pasta,
        R.string.ui_mushrooms_and_spinach_in_a_creamy_parmesan_sauce,
        R.string.ui_pasta,
        R.string.ui_olive_kitchen
    ),
    "pasta2" to DishLabels(
        R.string.ui_tomato_basil_pasta,
        R.string.ui_slow_cooked_tomato_sauce_with_basil,
        R.string.ui_pasta,
        R.string.ui_olive_kitchen
    ),
    "pasta3" to DishLabels(
        R.string.ui_chicken_parmesan_pasta,
        R.string.ui_breaded_chicken_tomato_sauce_and_melted_cheese,
        R.string.ui_pasta,
        R.string.ui_olive_kitchen
    ),
    "salad1" to DishLabels(
        R.string.ui_apple_walnut_salad,
        R.string.ui_greens_apple_walnuts_and_maple_vinaigrette,
        R.string.ui_salads,
        R.string.ui_olive_kitchen
    ),
    "salad2" to DishLabels(
        R.string.ui_greek_village_salad,
        R.string.ui_cucumber_tomato_olives_and_feta,
        R.string.ui_salads,
        R.string.ui_olive_kitchen
    ),
    "soup1" to DishLabels(
        R.string.ui_tomato_herb_soup,
        R.string.ui_a_smooth_tomato_soup_with_basil_and_garlic,
        R.string.ui_soups,
        R.string.ui_olive_kitchen
    ),
    "plate1" to DishLabels(
        R.string.ui_homestyle_chicken_plate,
        R.string.ui_roasted_chicken_potatoes_and_seasonal_vegetables,
        R.string.ui_plates,
        R.string.ui_olive_kitchen
    ),
    "plate2" to DishLabels(
        R.string.ui_stuffed_pepper_plate,
        R.string.ui_rice_and_lentil_stuffed_pepper_with_tomato_sauce,
        R.string.ui_plates,
        R.string.ui_olive_kitchen
    ),
)

// Persisted ingredient/diet values remain stable across languages and existing installations.
// Only their displayed labels change; toggling a translation still updates the original ingredient key.
private val ingredientLabels = mapOf(
    "Quinoa" to R.string.ui_quinoa,
    "Cucumber" to R.string.ui_cucumber,
    "Tomato" to R.string.ui_tomato,
    "Roasted chickpeas" to R.string.ui_roasted_chickpeas,
    "Spinach" to R.string.ui_spinach,
    "Lemon tahini" to R.string.ui_lemon_tahini,
    "Brown rice" to R.string.ui_brown_rice,
    "Sweet potato" to R.string.ui_sweet_potato,
    "Kale" to R.string.ui_kale,
    "Pumpkin seeds" to R.string.ui_pumpkin_seeds,
    "Maple dressing" to R.string.ui_maple_dressing,
    "Herbed rice" to R.string.ui_herbed_rice,
    "Olives" to R.string.ui_olives,
    "Feta" to R.string.ui_feta,
    "Tzatziki" to R.string.ui_tzatziki,
    "Chicken" to R.string.ui_chicken,
    "Garlic sauce" to R.string.ui_garlic_sauce,
    "Tomato sauce" to R.string.ui_tomato_sauce,
    "Mozzarella" to R.string.ui_mozzarella,
    "Basil" to R.string.ui_basil,
    "Olive oil" to R.string.ui_olive_oil,
    "Peppers" to R.string.ui_peppers,
    "Mushrooms" to R.string.ui_mushrooms,
    "Red onion" to R.string.ui_red_onion,
    "Vegan cheese" to R.string.ui_vegan_cheese,
    "Pesto" to R.string.ui_pesto,
    "Beef patty" to R.string.ui_beef_patty,
    "Cheddar" to R.string.ui_cheddar,
    "Lettuce" to R.string.ui_lettuce,
    "Pickles" to R.string.ui_pickles,
    "House sauce" to R.string.ui_house_sauce,
    "Slaw" to R.string.ui_slaw,
    "Black bean patty" to R.string.ui_black_bean_patty,
    "Vegan sauce" to R.string.ui_vegan_sauce,
    "Falafel" to R.string.ui_falafel,
    "Pickled onion" to R.string.ui_pickled_onion,
    "Tahini" to R.string.ui_tahini,
    "Pasta" to R.string.ui_pasta,
    "Parmesan" to R.string.ui_parmesan,
    "Cream sauce" to R.string.ui_cream_sauce,
    "Roasted garlic" to R.string.ui_roasted_garlic,
    "Mixed greens" to R.string.ui_mixed_greens,
    "Apple" to R.string.ui_apple,
    "Walnuts" to R.string.ui_walnuts,
    "Cranberries" to R.string.ui_cranberries,
    "Maple vinaigrette" to R.string.ui_maple_vinaigrette,
    "Herb dressing" to R.string.ui_herb_dressing,
    "Garlic" to R.string.ui_garlic,
    "Herb oil" to R.string.ui_herb_oil,
    "Roasted chicken" to R.string.ui_roasted_chicken,
    "Potatoes" to R.string.ui_potatoes,
    "Carrots" to R.string.ui_carrots,
    "Green beans" to R.string.ui_green_beans,
    "Gravy" to R.string.ui_gravy,
    "Bell pepper" to R.string.ui_bell_pepper,
    "Rice" to R.string.ui_rice,
    "Lentils" to R.string.ui_lentils,
    "Herbs" to R.string.ui_herbs,
)

// Returns the translated name for a menu dish.
internal fun ResourceText.dishName(dish: Dish) =
    dishLabels[dish.id]?.let { this(it.name) } ?: dish.name

// Returns the translated description for a menu dish.
internal fun ResourceText.dishDescription(dish: Dish) =
    dishLabels[dish.id]?.let { this(it.description) } ?: dish.description

// Returns the translated category for a menu dish.
internal fun ResourceText.dishCategory(dish: Dish) =
    dishLabels[dish.id]?.let { this(it.category) } ?: dish.category

// Returns the translated restaurant name for a menu dish.
internal fun ResourceText.dishRestaurant(dish: Dish) =
    dishLabels[dish.id]?.let { this(it.restaurant) } ?: dish.restaurant

// Returns the translated name for one food ingredient.
internal fun ResourceText.ingredient(value: String) =
    ingredientLabels[value]?.let { this(it) } ?: value

// Returns the translated label for a menu filter.
internal fun ResourceText.foodFilter(value: String) = when (value) {
    "All" -> this(R.string.all)
    "Vegetarian" -> this(R.string.vegetarian)
    "Vegan" -> this(R.string.vegan)
    "Meat" -> this(R.string.meat)
    else -> value
}
