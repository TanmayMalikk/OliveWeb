package com.olive.app.ui.covers.olive
import com.olive.app.ui.rememberResourceText
import androidx.compose.ui.platform.testTag

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import com.olive.app.ui.LocalizedIcon as Icon
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
    val uiText=rememberResourceText()
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState),state=listState,contentPadding=PaddingValues(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { FoodPageTitle(uiText(R.string.your_favourites), uiText(R.string.good_finds_kept_close)) }
        val saved = vm.dishes.filter { it.id in vm.favorites }
        if(saved.isEmpty()) item { FoodEmptyState(Icons.Outlined.FavoriteBorder,uiText(R.string.a_place_for_your_favourites),uiText(R.string.tap_the_heart_on_any_dish_to_save_it_here)) }
        items(saved,key={it.id}) { DishCard(it,vm,Modifier.animateItem()) }
    }
}

// Cart details are grouped for TalkBack with separate quantity actions; checkout always declines locally.
// UI sizing: cart rows use 24dp sides, 72dp thumbnails, 12dp padding, and full-width checkout controls.
@Composable internal fun FoodCartScreen(vm: OliveViewModel, checkout: () -> Unit) {
    val uiText=rememberResourceText()
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState),state=listState,contentPadding=PaddingValues(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { FoodPageTitle(uiText(R.string.your_cart), uiText(R.string.a_few_good_things_all_together)) }
        if(vm.itemCount == 0) item {
            FoodEmptyState(Icons.Outlined.ShoppingBag,uiText(R.string.something_delicious_belongs_here),uiText(R.string.explore_the_menu_and_add_a_dish_to_get_started))
            Button(onClick={vm.navigate(CoverTab.Discover)},modifier=Modifier.padding(horizontal=24.dp).fillMaxWidth()) { Text(stringResource(R.string.explore_the_menu)) }
        }
        items(vm.dishes.filter { it.id in vm.cart },key={it.id}) { dish ->
            Card(modifier=Modifier.animateItem().padding(horizontal=24.dp).animateContentSize().testTag("cart_row_${dish.id}").semantics(mergeDescendants=true) {},colors=CardDefaults.cardColors(containerColor=Paper)) {
                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    DishImage(dish,Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).clearAndSetSemantics {})
                    Column(Modifier.weight(1f).padding(start=12.dp)) {
                        Text(uiText.dishName(dish),fontWeight=FontWeight.SemiBold)
                        Text(formatDishPrice(dish.priceCents),color=Muted)
                        val choices=vm.selectedIngredients(dish)
                        if(choices.isNotEmpty()) Text(choices.joinToString(", ") { uiText.ingredient(it) },style=MaterialTheme.typography.bodySmall,color=Muted,maxLines=2)
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            IconButton(onClick={vm.changeQuantity(dish.id,-1)}) { Icon(Icons.Outlined.Remove,uiText(R.string.ui_remove_one_value , uiText.dishName(dish))) }
                            Text("${vm.cart[dish.id]}")
                            IconButton(onClick={vm.changeQuantity(dish.id,1)},enabled=(vm.cart[dish.id] ?: 0)<99) { Icon(Icons.Outlined.Add,uiText(R.string.ui_add_one_value , uiText.dishName(dish))) }
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
