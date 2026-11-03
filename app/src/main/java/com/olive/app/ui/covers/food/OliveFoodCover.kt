// Food cover users: shows the olive food cover.
package com.olive.app.ui.covers.food

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.data.DeliveryAddress
import com.olive.app.data.OrderLine
import com.olive.app.R
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.shared.rememberResourceText

// Food-cover shell: route tabs and dialogs to focused screens while OliveViewModel owns menu/cart state.
// UI sizing/color: the cover shell uses Cream, shared Olive colors, safe-area insets, and Material navigation sizing.
@Composable
internal fun OliveCover(
    vm: OliveViewModel = oliveViewModel(),
    interactive: Boolean = true,
    onSearch: (String) -> Unit = vm::search,
    onAccountSetup: () -> Unit = {},
    configured: Boolean = false,
    session: SessionViewModel? = null,
) {
    val uiText = rememberResourceText()
    // Rebuild searchable dish text when the menu or language changes.
    LaunchedEffect(uiText, vm.dishes) {
        vm.updateSearchLabels(vm.dishes.associate { dish ->
            dish.id to listOf(
                uiText.dishName(dish), uiText.dishCategory(dish), uiText.dishRestaurant(dish),
                uiText.foodFilter(dish.diet), uiText.dishDescription(dish),
            ).joinToString(" ")
        })
    }
    var checkoutNotice by remember { mutableStateOf(false) }
    var areaDialog by remember { mutableStateOf(false) }
    var guestAddress by remember { mutableStateOf(DeliveryAddress()) }
    val savedAddress = if (configured) session?.deliveryAddress ?: DeliveryAddress() else guestAddress
    // Update delivery estimates when the saved address changes.
    LaunchedEffect(savedAddress) { vm.updateLocation(savedAddress) }
    LaunchedEffect(session?.coverReset) {
        checkoutNotice = false
        areaDialog = false
        vm.returnToCover()
    }
    LaunchedEffect(session?.addressPrompt) { if (session?.addressPrompt == true) areaDialog = true }
    LaunchedEffect(session?.addressSaveVersion) {
        if ((session?.addressSaveVersion ?: 0) > 0) areaDialog = false
    }
    LaunchedEffect(checkoutNotice) { session?.checkoutActive = checkoutNotice }
    // Clear the checkout-active flag when this cover leaves the screen.
    DisposableEffect(Unit) { onDispose { session?.checkoutActive = false } }
    // Close private food dialogs while the cover is hidden behind Safe App.
    LaunchedEffect(interactive) {
        if (!interactive) {
            checkoutNotice = false
            areaDialog = false
        }
    }
    // Keep system Back inside the cover and walk through its real tab/detail history.
    BackHandler(interactive) { vm.back() }
    Scaffold(
        bottomBar = {
            NavigationBar(
                modifier = Modifier.testTag("cover_navigation"),
                containerColor = Cream,
                tonalElevation = 0.dp
            ) {
                listOf(
                    Triple(CoverTab.Discover, Icons.Outlined.Explore, uiText(R.string.discover)),
                    Triple(CoverTab.Saved, Icons.Outlined.FavoriteBorder, uiText(R.string.saved)),
                    Triple(CoverTab.Cart, Icons.Outlined.ShoppingBag, uiText(R.string.cart)),
                    Triple(
                        CoverTab.Account,
                        Icons.Outlined.PersonOutline,
                        uiText(R.string.account)
                    ),
                ).forEach { (tab, icon, label) ->
                    NavigationBarItem(
                        modifier = Modifier.testTag("cover_tab_${tab.name.lowercase()}"),
                        selected = vm.tab == tab, onClick = { vm.navigate(tab) },
                        icon = {
                            BadgedBox(badge = {
                                if (tab == CoverTab.Cart && vm.itemCount > 0) Badge { Text(vm.itemCount.toString()) }
                            }) { Icon(icon, contentDescription = null) }
                        }, label = { Text(label) })
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            AnimatedContent(
                targetState = vm.selectedDish to vm.tab, label = "food cover page",
                transitionSpec = {
                    (fadeIn(tween(180)) + slideInHorizontally(tween(180)) { it / 10 })
                        .togetherWith(fadeOut(tween(120)) + slideOutHorizontally(tween(120)) { -it / 14 })
                }) { (dish, tab) ->
                if (dish != null) DishDetailsScreen(dish, vm)
                else when (tab) {
                    CoverTab.Discover -> FoodDiscoverScreen(
                        vm,
                        onSearch
                    ) { areaDialog = true }

                    CoverTab.Saved -> SavedDishesScreen(vm)
                    CoverTab.Cart -> FoodCartScreen(vm) {
                        if (configured) checkoutNotice = true else onAccountSetup()
                    }

                    CoverTab.Account -> FoodAccountScreen(
                        onAccountSetup,
                        configured,
                        session
                    ) { areaDialog = true }
                }
            }
        }
    }
    if (areaDialog && interactive) AddressDialog(
        savedAddress,
        configured && session?.busy == true,
        session?.message.takeIf { configured },
        onSave = { address ->
            if (configured) session?.saveAddress(address)
            else {
                guestAddress = address
                areaDialog = false
            }
        },
        onClose = {
            areaDialog = false
            session?.dismissAddressPrompt()
        })
    if (checkoutNotice && interactive) CheckoutDialog(
        savedAddress, vm.total,
        foods = vm.dishes.filter { it.id in vm.cart }.map { dish ->
            val choices = vm.selectedIngredients(dish).joinToString(", ") { uiText.ingredient(it) }
            OrderLine(
                if (choices.isBlank()) uiText.dishName(dish) else "${uiText.dishName(dish)} · $choices",
                vm.cart[dish.id] ?: 0
            )
        },
        onSaveAddress = { session?.saveAddress(it) },
        onAttempt = { foods, total, error -> session?.recordCheckout(foods, total, error) },
        onClose = { checkoutNotice = false })

}

// The main food feed lives here—search, categories, location, and all the tempting menu cards.
// UI sizing/text: the feed uses 24dp sides, 22dp item gaps, a 36sp wordmark, and 40/44sp hero copy.
@Composable
private fun FoodDiscoverScreen(
    vm: OliveViewModel,
    onSearch: (String) -> Unit,
    onDeliveryArea: () -> Unit
) {
    val uiText = rememberResourceText()
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()
    LazyColumn(
        Modifier.fillMaxSize().oliveScrollbar(listState),
        state = listState,
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cover sizing note: this 36sp value is the Olive wordmark; delivery text beside it should wrap or truncate instead of shrinking the brand.
                Icon(
                    Icons.Outlined.LocalDining,
                    null,
                    Modifier.size(30.dp).testTag("cover_brand_food"),
                    tint = OliveGreen
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    uiText(R.string.ui_olive),
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 36.sp,
                    color = OliveGreen
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDeliveryArea) {
                    Icon(Icons.Outlined.LocationOn, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.widthIn(max = 150.dp)) {
                        Text(
                            stringResource(R.string.deliver_to),
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted
                        )
                        Text(
                            vm.deliveryArea.ifBlank { uiText(R.string.choose_your_area) },
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(18.dp))
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(
                    stringResource(R.string.homemade_by_olive_kitchen),
                    style = MaterialTheme.typography.labelSmall,
                    color = OliveGreen,
                    letterSpacing = 1.6.sp
                )
                Spacer(Modifier.height(10.dp))
                // Hero copy uses 40/44sp. Change fontSize and lineHeight together, then check French and the narrowest supported phone.
                Text(
                    stringResource(R.string.good_food_ngood_mood),
                    fontFamily = FontFamily.Serif,
                    fontSize = 40.sp,
                    lineHeight = 44.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.made_in_our_kitchen_and_delivered_to_your_door),
                    color = Muted
                )
            }
        }
        item {
            OutlinedTextField(
                value = vm.query,
                onValueChange = onSearch,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                    .testTag("food_search").keepVisibleWhileTyping(),
                placeholder = { Text(stringResource(R.string.search_olive_s_menu)) },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    // Show the clear-search button only while a search phrase is present.
                    if (vm.query.isNotEmpty()) IconButton(onClick = { vm.search("") }) {
                        Icon(
                            Icons.Outlined.Close,
                            uiText(R.string.clear_search)
                        )
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Search,
                    autoCorrectEnabled = false
                ),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() })
            )
        }
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(listOf("All", "Vegetarian", "Vegan", "Meat")) { category ->
                    FilterChip(
                        selected = vm.category == category,
                        onClick = { vm.filter(category) },
                        label = { Text(uiText.foodFilter(category)) },
                        shape = RoundedCornerShape(24.dp)
                    )
                }
            }
        }
        item {
            Text(
                uiText(R.string.homemade_by_olive_kitchen_1),
                Modifier.padding(horizontal = 24.dp),
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (vm.query.isNotBlank()) uiText(R.string.search_results) else uiText(R.string.made_for_your_cravings),
                    fontSize = 22.sp,
                    fontFamily = FontFamily.Serif,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    uiText(R.string.ui_value_dishes, vm.results.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = Muted
                )
            }
        }
        if (vm.results.isEmpty()) item {
            FoodEmptyState(
                Icons.Outlined.Search,
                uiText(R.string.nothing_on_the_menu_yet),
                uiText(R.string.try_a_different_search_or_choose_another_category)
            )
        }
        items(vm.results, key = { it.id }) { DishCard(it, vm, Modifier.animateItem()) }
    }
}
