package com.olive.app.ui.covers.olive

import androidx.compose.ui.res.stringResource
import com.olive.app.R
import com.olive.app.ui.*

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Hearts land here so users can find their saved food again.
// UI sizing: saved food uses 24dp vertical padding and 20dp gaps while reusing the shared dish-card sizes.
@Composable internal fun SavedDishesScreen(vm: OliveViewModel) {
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState),state=listState,contentPadding=PaddingValues(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { FoodPageTitle("Your favourites", "Good finds, kept close.") }
        val saved = vm.dishes.filter { it.id in vm.favorites }
        if(saved.isEmpty()) item { FoodEmptyState(Icons.Outlined.FavoriteBorder,"A place for your favourites","Tap the heart on any dish to save it here.") }
        items(saved,key={it.id}) { DishCard(it,vm,Modifier.animateItem()) }
    }
}

// The cart handles quantities, subtotal, and the jump into the always-declined checkout.
// UI sizing: cart rows use 24dp sides, 72dp thumbnails, 12dp padding, and full-width checkout controls.
@Composable internal fun FoodCartScreen(vm: OliveViewModel, checkout: () -> Unit) {
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState),state=listState,contentPadding=PaddingValues(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { FoodPageTitle("Your cart", "A few good things, all together.") }
        if(vm.itemCount == 0) item {
            FoodEmptyState(Icons.Outlined.ShoppingBag,"Something delicious belongs here","Explore the menu and add a dish to get started.")
            Button(onClick={vm.navigate(CoverTab.Discover)},modifier=Modifier.padding(horizontal=24.dp).fillMaxWidth()) { Text(stringResource(R.string.explore_the_menu)) }
        }
        items(vm.dishes.filter { it.id in vm.cart },key={it.id}) { dish ->
            Card(modifier=Modifier.animateItem().padding(horizontal=24.dp).animateContentSize(),colors=CardDefaults.cardColors(containerColor=Paper)) {
                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    DishImage(dish,Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)))
                    Column(Modifier.weight(1f).padding(start=12.dp)) {
                        Text(dish.name,fontWeight=FontWeight.SemiBold)
                        Text(formatDishPrice(dish.priceCents),color=Muted)
                        val choices=vm.selectedIngredients(dish)
                        if(choices.isNotEmpty()) Text(choices.joinToString(", "),style=MaterialTheme.typography.bodySmall,color=Muted,maxLines=2)
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            IconButton(onClick={vm.changeQuantity(dish.id,-1)}) { Icon(Icons.Outlined.Remove,"Remove one ${dish.name}") }
                            Text("${vm.cart[dish.id]}")
                            IconButton(onClick={vm.changeQuantity(dish.id,1)},enabled=(vm.cart[dish.id] ?: 0)<99) { Icon(Icons.Outlined.Add,"Add one ${dish.name}") }
                        }
                    }
                }
            }
        }
        if(vm.itemCount > 0) item { Column(Modifier.padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth()) { Text(stringResource(R.string.subtotal),modifier=Modifier.weight(1f)); Text(formatDishPrice(vm.total),fontWeight=FontWeight.Bold) }
            Text(stringResource(R.string.review_your_delivery_and_payment_details_at_checkout),color=Muted,style=MaterialTheme.typography.bodyMedium)
            Button(onClick=checkout,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)) { Text(stringResource(R.string.continue_to_checkout)) }
        } }
    }
}
