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
    val diet: String = "Vegetarian",
    val ingredients: List<String> = emptyList(),
)

// Swap the menu source behind this tiny interface without rebuilding the whole food UI.
interface CatalogRepository { fun dishes(): List<Dish> }

// Olive prepares this whole menu itself, so every item can offer predictable ingredient choices.
class DemoCatalogRepository : CatalogRepository {
    override fun dishes() = listOf(
        Dish("bowl","Garden harvest bowl","Olive Kitchen","Bowls","Quinoa, roasted vegetables and lemon tahini.",1495,"20–30","4.9","bowl",diet="Vegan",ingredients=listOf("Quinoa","Cucumber","Tomato","Roasted chickpeas","Spinach","Lemon tahini")),
        Dish("bowl2","Maple sweet potato bowl","Olive Kitchen","Bowls","Warm grains, maple sweet potato and crunchy greens.",1525,"20–30","4.8","bowl",diet="Vegan",ingredients=listOf("Brown rice","Sweet potato","Kale","Pumpkin seeds","Maple dressing")),
        Dish("bowl3","Mediterranean feta bowl","Olive Kitchen","Bowls","Fresh vegetables, herbed rice and feta.",1575,"20–30","4.8","bowl",diet="Vegetarian",ingredients=listOf("Herbed rice","Cucumber","Tomato","Olives","Feta","Tzatziki")),
        Dish("bowl4","Chicken power bowl","Olive Kitchen","Bowls","Grilled chicken, grains and crisp vegetables.",1695,"20–30","4.9","bowl",diet="Meat",ingredients=listOf("Chicken","Quinoa","Cucumber","Tomato","Spinach","Garlic sauce")),
        Dish("pizza","Classic margherita","Olive Kitchen","Pizza","Tomato, mozzarella and basil on house-made dough.",1600,"25–35","4.8","pizza",diet="Vegetarian",ingredients=listOf("Tomato sauce","Mozzarella","Basil","Olive oil")),
        Dish("pizza2","Roasted vegetable pizza","Olive Kitchen","Pizza","Peppers, mushrooms and red onion with mozzarella.",1750,"25–35","4.7","pizza",diet="Vegetarian",ingredients=listOf("Tomato sauce","Mozzarella","Peppers","Mushrooms","Red onion")),
        Dish("pizza3","Vegan garden pizza","Olive Kitchen","Pizza","Garden vegetables and dairy-free cheese.",1795,"25–35","4.7","pizza",diet="Vegan",ingredients=listOf("Tomato sauce","Vegan cheese","Peppers","Mushrooms","Spinach")),
        Dish("pizza4","Chicken pesto pizza","Olive Kitchen","Pizza","Chicken, pesto, tomato and mozzarella.",1895,"25–35","4.8","pizza",diet="Meat",ingredients=listOf("Pesto","Chicken","Mozzarella","Tomato","Red onion")),
        Dish("burger","Olive house burger","Olive Kitchen","Burgers","Beef, cheddar and house sauce on a toasted bun.",1495,"20–30","4.8","burger",diet="Meat",ingredients=listOf("Beef patty","Cheddar","Lettuce","Tomato","Pickles","House sauce")),
        Dish("burger2","Crispy chicken burger","Olive Kitchen","Burgers","Crispy chicken with slaw and pickles.",1525,"20–30","4.7","burger",diet="Meat",ingredients=listOf("Chicken","Slaw","Pickles","Lettuce","Garlic sauce")),
        Dish("burger3","Black bean burger","Olive Kitchen","Burgers","House-made black bean patty with fresh toppings.",1425,"20–30","4.7","burger",diet="Vegan",ingredients=listOf("Black bean patty","Lettuce","Tomato","Pickles","Vegan sauce")),
        Dish("wrap1","Falafel crunch wrap","Olive Kitchen","Wraps","Falafel, vegetables and tahini in a warm wrap.",1325,"15–25","4.8","bowl",diet="Vegan",ingredients=listOf("Falafel","Cucumber","Tomato","Lettuce","Pickled onion","Tahini")),
        Dish("wrap2","Grilled chicken wrap","Olive Kitchen","Wraps","Seasoned chicken and garden vegetables.",1425,"15–25","4.8","bowl",diet="Meat",ingredients=listOf("Chicken","Cucumber","Tomato","Lettuce","Cheddar","Garlic sauce")),
        Dish("pasta1","Creamy mushroom pasta","Olive Kitchen","Pasta","Mushrooms and spinach in a creamy parmesan sauce.",1675,"25–35","4.8","bowl",diet="Vegetarian",ingredients=listOf("Pasta","Mushrooms","Spinach","Parmesan","Cream sauce")),
        Dish("pasta2","Tomato basil pasta","Olive Kitchen","Pasta","Slow-cooked tomato sauce with basil.",1495,"25–35","4.7","bowl",diet="Vegan",ingredients=listOf("Pasta","Tomato sauce","Basil","Roasted garlic")),
        Dish("pasta3","Chicken parmesan pasta","Olive Kitchen","Pasta","Breaded chicken, tomato sauce and melted cheese.",1895,"25–35","4.8","bowl",diet="Meat",ingredients=listOf("Pasta","Chicken","Tomato sauce","Mozzarella","Parmesan")),
        Dish("salad1","Apple walnut salad","Olive Kitchen","Salads","Greens, apple, walnuts and maple vinaigrette.",1295,"15–25","4.7","bowl",diet="Vegan",ingredients=listOf("Mixed greens","Apple","Walnuts","Cranberries","Maple vinaigrette")),
        Dish("salad2","Greek village salad","Olive Kitchen","Salads","Cucumber, tomato, olives and feta.",1325,"15–25","4.8","bowl",diet="Vegetarian",ingredients=listOf("Cucumber","Tomato","Olives","Feta","Red onion","Herb dressing")),
        Dish("soup1","Tomato herb soup","Olive Kitchen","Soups","A smooth tomato soup with basil and garlic.",995,"15–25","4.7","bowl",diet="Vegan",ingredients=listOf("Tomato","Basil","Garlic","Herb oil")),
        Dish("plate1","Homestyle chicken plate","Olive Kitchen","Plates","Roasted chicken, potatoes and seasonal vegetables.",1995,"30–40","4.9","bowl",diet="Meat",ingredients=listOf("Roasted chicken","Potatoes","Carrots","Green beans","Gravy")),
        Dish("plate2","Stuffed pepper plate","Olive Kitchen","Plates","Rice and lentil stuffed pepper with tomato sauce.",1725,"30–40","4.8","bowl",diet="Vegan",ingredients=listOf("Bell pepper","Rice","Lentils","Tomato sauce","Herbs")),
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
