package com.olive.app.ui.covers.olive
import com.olive.app.R

import com.olive.app.ui.LocalizedIcon as Icon
import com.olive.app.ui.*

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.olive.app.data.OrderLine

// Food-cover shell: route tabs and dialogs to focused screens while OliveViewModel owns menu/cart state.
// UI sizing/color: the cover shell uses Cream, shared Olive colors, safe-area insets, and Material navigation sizing.
@Composable fun OliveCover(
    vm: OliveViewModel = oliveViewModel(),
    interactive: Boolean = true,
    onSearch: (String) -> Unit = vm::search,
    onAccountSetup: () -> Unit = {},
    configured: Boolean = false,
    session: SessionViewModel? = null,
) {
    val uiText=com.olive.app.ui.rememberResourceText()
    LaunchedEffect(uiText,vm.dishes) {
        vm.updateSearchLabels(vm.dishes.associate { dish -> dish.id to listOf(
            uiText.dishName(dish),uiText.dishCategory(dish),uiText.dishRestaurant(dish),
            uiText.foodFilter(dish.diet),uiText.dishDescription(dish),
        ).joinToString(" ") })
    }
    var checkoutNotice by remember { mutableStateOf(false) }
    var areaDialog by remember { mutableStateOf(false) }
    val savedAddress=session?.deliveryAddress ?: com.olive.app.data.DeliveryAddress()
    LaunchedEffect(savedAddress) { vm.updateLocation(savedAddress) }
    LaunchedEffect(session?.coverReset) { checkoutNotice=false;areaDialog=false;vm.returnToCover() }
    LaunchedEffect(session?.addressPrompt) { if(session?.addressPrompt==true) areaDialog=true }
    LaunchedEffect(session?.addressSaveVersion) {
        if((session?.addressSaveVersion ?: 0) > 0) areaDialog=false
    }
    LaunchedEffect(checkoutNotice) { session?.checkoutActive=checkoutNotice }
    DisposableEffect(Unit) { onDispose { session?.checkoutActive=false } }
    LaunchedEffect(interactive) { if(!interactive) { checkoutNotice=false; areaDialog=false } }
    // Keep system Back inside the cover and walk through its real tab/detail history.
    BackHandler(interactive) { vm.back() }
    Scaffold(
        bottomBar = {
            NavigationBar(modifier=Modifier.testTag("cover_navigation"),containerColor = Cream, tonalElevation = 0.dp) {
                listOf(
                    Triple(CoverTab.Discover, Icons.Outlined.Explore, uiText(R.string.discover)),
                    Triple(CoverTab.Saved, Icons.Outlined.FavoriteBorder, uiText(R.string.saved)),
                    Triple(CoverTab.Cart, Icons.Outlined.ShoppingBag, uiText(R.string.cart)),
                    Triple(CoverTab.Account, Icons.Outlined.PersonOutline, uiText(R.string.account)),
                ).forEach { (tab, icon, label) ->
                    NavigationBarItem(selected = vm.tab == tab, onClick = { vm.navigate(tab) },
                        icon = { BadgedBox(badge = {
                            if(tab == CoverTab.Cart && vm.itemCount > 0) Badge { Text(vm.itemCount.toString()) }
                        }) { Icon(icon, contentDescription = null) } }, label = { Text(label) })
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            AnimatedContent(targetState=vm.selectedDish to vm.tab,label="food cover page",
                transitionSpec={
                    (fadeIn(tween(180))+slideInHorizontally(tween(180)) { it/10 })
                        .togetherWith(fadeOut(tween(120))+slideOutHorizontally(tween(120)) { -it/14 })
                }) { (dish,tab) ->
                if(dish != null) DishDetailsScreen(dish, vm)
                else when(tab) {
                    CoverTab.Discover -> FoodDiscoverScreen(vm,onSearch) { if(configured) areaDialog=true else onAccountSetup() }
                    CoverTab.Saved -> SavedDishesScreen(vm)
                    CoverTab.Cart -> FoodCartScreen(vm) { if(configured) checkoutNotice = true else onAccountSetup() }
                    CoverTab.Account -> FoodAccountScreen(onAccountSetup,configured,session) { areaDialog=true }
                }
            }
        }
    }
    if(areaDialog && interactive) AddressDialog(savedAddress,session?.busy==true,session?.message,
        onSave={session?.saveAddress(it)},onClose={areaDialog=false;session?.dismissAddressPrompt()})
    if(checkoutNotice && interactive) CheckoutDialog(savedAddress,vm.total,
        foods=vm.dishes.filter { it.id in vm.cart }.map { dish ->
            val choices=vm.selectedIngredients(dish).joinToString(", ") { uiText.ingredient(it) }
            OrderLine(if(choices.isBlank()) uiText.dishName(dish) else "${uiText.dishName(dish)} · $choices",vm.cart[dish.id] ?: 0)
        },
        onSaveAddress={session?.saveAddress(it)},
        onAttempt={ foods,total,error -> session?.recordCheckout(foods,total,error) },
        onClose={checkoutNotice=false})

}
