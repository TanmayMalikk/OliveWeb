package com.olive.app.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.olive.app.R
import com.olive.app.data.Dish

private fun localDishPhoto(id: String) = when(id) {
    "bowl" -> R.drawable.menu_bowl; "bowl2" -> R.drawable.menu_bowl2
    "bowl3" -> R.drawable.menu_bowl3; "bowl4" -> R.drawable.menu_bowl4
    "pizza" -> R.drawable.menu_pizza; "pizza2" -> R.drawable.menu_pizza2
    "pizza3" -> R.drawable.menu_pizza3; "pizza4" -> R.drawable.menu_pizza4
    "burger" -> R.drawable.menu_burger; "burger2" -> R.drawable.menu_burger2
    "burger3" -> R.drawable.menu_burger3; "wrap1" -> R.drawable.menu_wrap1
    "wrap2" -> R.drawable.menu_wrap2; "pasta1" -> R.drawable.menu_pasta1
    "pasta2" -> R.drawable.menu_pasta2; "pasta3" -> R.drawable.menu_pasta3
    "salad1" -> R.drawable.menu_salad1; "salad2" -> R.drawable.menu_salad2
    "soup1" -> R.drawable.menu_soup1; "plate1" -> R.drawable.menu_plate1
    "plate2" -> R.drawable.menu_plate2; else -> 0
}

// Olive's homemade menu uses bundled photos so every dish still works without a network connection.
@Composable fun DishImage(dish: Dish, modifier: Modifier=Modifier) {
    val localPhoto=localDishPhoto(dish.id)
    if(localPhoto!=0) {
        Image(painterResource(localPhoto),dish.name,modifier,contentScale=ContentScale.Crop)
        return
    }
    FoodArt(dish.art,modifier)
}
