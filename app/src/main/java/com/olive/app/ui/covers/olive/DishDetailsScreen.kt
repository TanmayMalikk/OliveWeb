package com.olive.app.ui.covers.olive

import com.olive.app.ui.*

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.data.Dish

// Tap a dish and this gives it the full spotlight before adding it to the cart.
// UI sizing/text: details use 20dp gaps, a 16:9 image, 24dp sides, and a 34/38sp food title.
@Composable internal fun DishDetailsScreen(dish: Dish, vm: OliveViewModel) {
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState),state=listState,contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { Row(Modifier.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={vm.select(null)}) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back to menu") }
            LocalizedText(dish.restaurant,style=MaterialTheme.typography.titleMedium)
        } }
        item { DishImage(dish,Modifier.fillMaxWidth().aspectRatio(16f/9f)) }
        item { Column(Modifier.padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            LocalizedText(dish.category.uppercase(),color=OliveGreen,style=MaterialTheme.typography.labelMedium,letterSpacing=1.sp)
            LocalizedText(dish.name,fontFamily=FontFamily.Serif,fontSize=34.sp,lineHeight=38.sp)
            LocalizedText("★ ${dish.rating}  ·  ${dish.minutes} min",color=Muted)
            LocalizedText(dish.description,style=MaterialTheme.typography.bodyLarge)
            if(dish.ingredients.isNotEmpty()) {
                LocalizedText("Customize ingredients",style=MaterialTheme.typography.titleMedium)
                dish.ingredients.forEach { ingredient ->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(checked=ingredient in vm.selectedIngredients(dish),onCheckedChange={vm.toggleIngredient(dish,ingredient)})
                        Text(ingredient)
                    }
                }
            }
            LocalizedText(formatDishPrice(dish.priceCents),style=MaterialTheme.typography.headlineSmall,color=OliveGreen)
            Button(onClick={vm.changeQuantity(dish.id,1)}, modifier=Modifier.fillMaxWidth().heightIn(min=54.dp),enabled=(vm.cart[dish.id] ?: 0) < 99) {
                LocalizedText(if((vm.cart[dish.id] ?: 0) == 0) "Add to cart" else "Add another · ${vm.cart[dish.id]} in cart")
            }
            OutlinedButton(onClick={vm.favorite(dish.id)},modifier=Modifier.fillMaxWidth()) { LocalizedText(if(dish.id in vm.favorites) "Saved to favourites" else "Save for later") }
        } }
    }
}
