package com.olive.app.ui.covers.olive
import com.olive.app.ui.rememberResourceText

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role

import androidx.compose.ui.semantics.semantics
import com.olive.app.ui.LocalizedIcon as Icon
import androidx.compose.ui.res.stringResource
import com.olive.app.R
import com.olive.app.ui.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
    val uiText=rememberResourceText()
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState),state=listState,contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { Row(Modifier.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={vm.select(null)}) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,uiText(R.string.back_to_menu)) }
            Text(uiText.dishRestaurant(dish),style=MaterialTheme.typography.titleMedium)
        } }
        item { DishImage(dish,Modifier.fillMaxWidth().aspectRatio(16f/9f)) }
        item { Column(Modifier.padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(uiText.dishCategory(dish).uppercase(),color=OliveGreen,style=MaterialTheme.typography.labelMedium,letterSpacing=1.sp)
            Text(uiText.dishName(dish),fontFamily=FontFamily.Serif,fontSize=34.sp,lineHeight=38.sp)
            Text(uiText(R.string.ui_value_value_min_fe5b84 , dish.rating, dish.minutes),color=Muted)
            Text(uiText.dishDescription(dish),style=MaterialTheme.typography.bodyLarge)
            if(dish.ingredients.isNotEmpty()) {
                Text(stringResource(R.string.customize_ingredients),style=MaterialTheme.typography.titleMedium)
                dish.ingredients.forEach { ingredient ->
                    Row(Modifier.fillMaxWidth().toggleable(value=ingredient in vm.selectedIngredients(dish),role=Role.Checkbox,
                        onValueChange={vm.toggleIngredient(dish,ingredient)}),verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(checked=ingredient in vm.selectedIngredients(dish),onCheckedChange=null)
                        Text(uiText.ingredient(ingredient))
                    }
                }
            }
            Text(formatDishPrice(dish.priceCents),style=MaterialTheme.typography.headlineSmall,color=OliveGreen,modifier=Modifier.semantics { heading() })
            Button(onClick={vm.changeQuantity(dish.id,1)}, modifier=Modifier.fillMaxWidth().heightIn(min=54.dp),enabled=(vm.cart[dish.id] ?: 0) < 99) {
                Text(if((vm.cart[dish.id] ?: 0) == 0) uiText(R.string.add_to_cart) else uiText(R.string.ui_add_another_value_in_cart , vm.cart[dish.id]))
            }
            OutlinedButton(onClick={vm.favorite(dish.id)},modifier=Modifier.fillMaxWidth()) { Text(if(dish.id in vm.favorites) uiText(R.string.saved_to_favourites) else uiText(R.string.save_for_later)) }
        } }
    }
}
