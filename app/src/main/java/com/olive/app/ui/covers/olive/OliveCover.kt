package com.olive.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.olive.app.R
import com.olive.app.data.Dish
import com.olive.app.data.OrderLine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable private fun money(cents: Int): String = "$%.2f".format(Locale.US, cents / 100.0)

// Change cover navigation, tabs, address popup, or checkout entry in this main food-app section.
// UI sizing/color: the cover shell uses Cream, shared Olive colors, safe-area insets, and Material navigation sizing.
@Composable fun OliveCover(
    vm: OliveViewModel = viewModel(),
    interactive: Boolean = true,
    onSearch: (String) -> Unit = vm::search,
    onAccountSetup: () -> Unit = {},
    configured: Boolean = false,
    session: SessionViewModel? = null,
) {
    // HARDCODED OLIVE NOTIFICATION SPOT: edit AppNotifications.kt, then copy the show call into the event that should send it.
    // val notificationContext=androidx.compose.ui.platform.LocalContext.current
    // com.olive.app.notification.AppNotifications.showOlive(notificationContext)
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
            NavigationBar(containerColor = Cream, tonalElevation = 0.dp) {
                listOf(
                    Triple(CoverTab.Discover, Icons.Outlined.Explore, "Discover"),
                    Triple(CoverTab.Saved, Icons.Outlined.FavoriteBorder, "Saved"),
                    Triple(CoverTab.Cart, Icons.Outlined.ShoppingBag, "Cart"),
                    Triple(CoverTab.Account, Icons.Outlined.PersonOutline, "Account"),
                ).forEach { (tab, icon, label) ->
                    NavigationBarItem(selected = vm.tab == tab, onClick = { vm.navigate(tab) },
                        icon = { BadgedBox(badge = {
                            if(tab == CoverTab.Cart && vm.itemCount > 0) Badge { Text(vm.itemCount.toString()) }
                        }) { Icon(icon, contentDescription = null) } }, label = { Text(label) })
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AnimatedContent(targetState=vm.selectedDish to vm.tab,label="food cover page",
                transitionSpec={
                    (fadeIn(tween(180))+slideInHorizontally(tween(180)) { it/10 })
                        .togetherWith(fadeOut(tween(120))+slideOutHorizontally(tween(120)) { -it/14 })
                }) { (dish,tab) ->
                if(dish != null) Detail(dish, vm)
                else when(tab) {
                    CoverTab.Discover -> Discover(vm,onSearch) { if(configured) areaDialog=true else onAccountSetup() }
                    CoverTab.Saved -> Saved(vm)
                    CoverTab.Cart -> Cart(vm) { if(configured) checkoutNotice = true else onAccountSetup() }
                    CoverTab.Account -> Account(onAccountSetup,configured,session) { areaDialog=true }
                }
            }
        }
    }
    if(areaDialog && interactive) AddressDialog(savedAddress,session?.busy==true,session?.message,
        onSave={session?.saveAddress(it)},onClose={areaDialog=false;session?.dismissAddressPrompt()})
    if(checkoutNotice && interactive) CheckoutDialog(savedAddress,vm.total,
        foods=vm.dishes.filter { it.id in vm.cart }.map { dish ->
            val choices=vm.selectedIngredients(dish).joinToString(", ")
            OrderLine(if(choices.isBlank()) dish.name else "${dish.name} · $choices",vm.cart[dish.id] ?: 0)
        },
        onSaveAddress={session?.saveAddress(it)},
        onAttempt={ foods,total,error -> session?.recordCheckout(foods,total,error) },
        onClose={checkoutNotice=false})

}

// The main food feed lives here—search, categories, location, and all the tempting menu cards.
// UI sizing/text: the feed uses 24dp sides, 22dp item gaps, a 36sp wordmark, and 40/44sp hero copy.
@Composable private fun Discover(vm: OliveViewModel, onSearch: (String) -> Unit, onDeliveryArea: () -> Unit) {
    val focus = LocalFocusManager.current
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState),state=listState,contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal=24.dp, vertical=12.dp), verticalAlignment=Alignment.CenterVertically) {
                // Cover sizing note: this 36sp value is the Olive wordmark; delivery text beside it should wrap or truncate instead of shrinking the brand.
                Text("olive", fontFamily=FontFamily.Serif, fontWeight=FontWeight.Bold, fontSize=36.sp, color=OliveGreen)
                Spacer(Modifier.weight(1f))
                TextButton(onClick=onDeliveryArea) {
                    Icon(Icons.Outlined.LocationOn,null,Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.widthIn(max=150.dp)) {
                        Text(stringResource(R.string.deliver_to),style=MaterialTheme.typography.labelSmall,color=Muted)
                        Text(vm.deliveryArea.ifBlank { "Choose your area" },maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Outlined.KeyboardArrowDown,null,Modifier.size(18.dp))
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal=24.dp)) {
                Text(stringResource(R.string.homemade_by_olive_kitchen), style=MaterialTheme.typography.labelSmall, color=OliveGreen, letterSpacing=1.6.sp)
                Spacer(Modifier.height(10.dp))
                // Hero copy uses 40/44sp. Change fontSize and lineHeight together, then check French and the narrowest supported phone.
                Text(stringResource(R.string.good_food_ngood_mood), fontFamily=FontFamily.Serif, fontSize=40.sp, lineHeight=44.sp, fontWeight=FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.made_in_our_kitchen_and_delivered_to_your_door), color=Muted)
            }
        }
        item {
            OutlinedTextField(value=vm.query, onValueChange=onSearch,
                modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp),
                placeholder={Text(stringResource(R.string.search_olive_s_menu))}, leadingIcon={Icon(Icons.Outlined.Search,null)},
                trailingIcon={if(vm.query.isNotEmpty()) IconButton(onClick={vm.search("")}) { Icon(Icons.Outlined.Close,"Clear search") }},
                singleLine=true, shape=RoundedCornerShape(18.dp),
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search,autoCorrectEnabled=false), keyboardActions=KeyboardActions(onSearch={focus.clearFocus()}))
        }
        item {
            LazyRow(contentPadding=PaddingValues(horizontal=24.dp), horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                items(listOf("All","Vegetarian","Vegan","Meat")) { category ->
                    FilterChip(selected=vm.category == category, onClick={vm.filter(category)}, label={Text(category)}, shape=RoundedCornerShape(24.dp))
                }
            }
        }
        item { Text(vm.catalogMessage,Modifier.padding(horizontal=24.dp),color=Muted,style=MaterialTheme.typography.bodySmall) }
        item { Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(if(vm.query.isNotBlank()) "Search results" else "Made for your cravings",fontSize=22.sp,fontFamily=FontFamily.Serif,modifier=Modifier.weight(1f))
            Text("${vm.results.size} dishes",style=MaterialTheme.typography.labelMedium,color=Muted)
        } }
        if(vm.results.isEmpty()) item { EmptyState(Icons.Outlined.Search,"Nothing on the menu yet","Try a different search or choose another category.") }
        items(vm.results, key={it.id}) { DishCard(it, vm,Modifier.animateItem()) }
    }
}

// One bite-sized menu card with its photo, price, favourite button, and quick add.
// UI sizing/color: dish cards use 24dp sides, 22dp corners, a 16:9 image, 16dp padding, and 18sp item names.
@Composable private fun DishCard(dish: Dish, vm: OliveViewModel, modifier: Modifier=Modifier) {
    Card(onClick={vm.select(dish)},modifier=modifier.fillMaxWidth().padding(horizontal=24.dp).animateContentSize(),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=Paper)) {
        Box {
            DishImage(dish,Modifier.fillMaxWidth().aspectRatio(16f/9f))
            FilledIconButton(onClick={vm.favorite(dish.id)},modifier=Modifier.align(Alignment.TopEnd).padding(10.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=Cream)) {
                Icon(if(dish.id in vm.favorites) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,if(dish.id in vm.favorites) "Unsave ${dish.name}" else "Save ${dish.name}",tint=OliveGreen)
            }
        }
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(dish.name,fontWeight=FontWeight.SemiBold,fontSize=18.sp,modifier=Modifier.weight(1f))
                Text(money(dish.priceCents),fontWeight=FontWeight.SemiBold,color=OliveGreen)
            }
            Text("${dish.restaurant}  ·  ${dish.diet}",color=Muted,style=MaterialTheme.typography.bodyMedium)
            Text("★ ${dish.rating}     ·     ${dish.minutes} min",style=MaterialTheme.typography.labelMedium,color=OliveGreen)
        }
    }
}

// Tap a dish and this gives it the full spotlight before adding it to the cart.
// UI sizing/text: details use 20dp gaps, a 16:9 image, 24dp sides, and a 34/38sp food title.
@Composable private fun Detail(dish: Dish, vm: OliveViewModel) {
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState),state=listState,contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { Row(Modifier.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={vm.select(null)}) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back to menu") }
            Text(dish.restaurant,style=MaterialTheme.typography.titleMedium)
        } }
        item { DishImage(dish,Modifier.fillMaxWidth().aspectRatio(16f/9f)) }
        item { Column(Modifier.padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(dish.category.uppercase(),color=OliveGreen,style=MaterialTheme.typography.labelMedium,letterSpacing=1.sp)
            Text(dish.name,fontFamily=FontFamily.Serif,fontSize=34.sp,lineHeight=38.sp)
            Text("★ ${dish.rating}  ·  ${dish.minutes} min",color=Muted)
            Text(dish.description,style=MaterialTheme.typography.bodyLarge)
            if(dish.ingredients.isNotEmpty()) {
                Text(stringResource(R.string.customize_ingredients),style=MaterialTheme.typography.titleMedium)
                dish.ingredients.forEach { ingredient ->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(checked=ingredient in vm.selectedIngredients(dish),onCheckedChange={vm.toggleIngredient(dish,ingredient)})
                        Text(ingredient)
                    }
                }
            }
            Text(money(dish.priceCents),style=MaterialTheme.typography.headlineSmall,color=OliveGreen)
            Button(onClick={vm.changeQuantity(dish.id,1)}, modifier=Modifier.fillMaxWidth().heightIn(min=54.dp),enabled=(vm.cart[dish.id] ?: 0) < 99) {
                Text(if((vm.cart[dish.id] ?: 0) == 0) "Add to cart" else "Add another · ${vm.cart[dish.id]} in cart")
            }
            OutlinedButton(onClick={vm.favorite(dish.id)},modifier=Modifier.fillMaxWidth()) { Text(if(dish.id in vm.favorites) "Saved to favourites" else "Save for later") }
        } }
    }
}

// Hearts land here so users can find their saved food again.
// UI sizing: saved food uses 24dp vertical padding and 20dp gaps while reusing the shared dish-card sizes.
@Composable private fun Saved(vm: OliveViewModel) {
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState),state=listState,contentPadding=PaddingValues(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { PageTitle("Your favourites", "Good finds, kept close.") }
        val saved = vm.dishes.filter { it.id in vm.favorites }
        if(saved.isEmpty()) item { EmptyState(Icons.Outlined.FavoriteBorder,"A place for your favourites","Tap the heart on any dish to save it here.") }
        items(saved,key={it.id}) { DishCard(it,vm,Modifier.animateItem()) }
    }
}

// The cart handles quantities, subtotal, and the jump into the always-declined checkout.
// UI sizing: cart rows use 24dp sides, 72dp thumbnails, 12dp padding, and full-width checkout controls.
@Composable private fun Cart(vm: OliveViewModel, checkout: () -> Unit) {
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState),state=listState,contentPadding=PaddingValues(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { PageTitle("Your cart", "A few good things, all together.") }
        if(vm.itemCount == 0) item {
            EmptyState(Icons.Outlined.ShoppingBag,"Something delicious belongs here","Explore the menu and add a dish to get started.")
            Button(onClick={vm.navigate(CoverTab.Discover)},modifier=Modifier.padding(horizontal=24.dp).fillMaxWidth()) { Text(stringResource(R.string.explore_the_menu)) }
        }
        items(vm.dishes.filter { it.id in vm.cart },key={it.id}) { dish ->
            Card(modifier=Modifier.animateItem().padding(horizontal=24.dp).animateContentSize(),colors=CardDefaults.cardColors(containerColor=Paper)) {
                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    DishImage(dish,Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)))
                    Column(Modifier.weight(1f).padding(start=12.dp)) {
                        Text(dish.name,fontWeight=FontWeight.SemiBold)
                        Text(money(dish.priceCents),color=Muted)
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
            Row(Modifier.fillMaxWidth()) { Text(stringResource(R.string.subtotal),modifier=Modifier.weight(1f)); Text(money(vm.total),fontWeight=FontWeight.Bold) }
            Text(stringResource(R.string.review_your_delivery_and_payment_details_at_checkout),color=Muted,style=MaterialTheme.typography.bodyMedium)
            Button(onClick=checkout,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)) { Text(stringResource(R.string.continue_to_checkout)) }
        } }
    }
}

// Change cover profile fields here; keep private safe-profile details out of this screen.
// UI sizing/color: account content uses 24dp padding/gaps, Paper cards, 20dp card padding, and Material heading sizes.
@Composable private fun Account(onSetup: () -> Unit, configured: Boolean, session: SessionViewModel?, onAddress: ()->Unit) {
    LaunchedEffect(session?.coverUsername) { if(configured) session?.recordSettingsInteraction("Olive", "Opened account page") }
    var fullName by remember(session?.coverFullName) { mutableStateOf(session?.coverFullName.orEmpty()) }
    var selectedLanguage by remember(session?.language) { mutableStateOf(session?.language ?: "English") }
    var showAllOrders by remember { mutableStateOf(false) }
    val address=session?.deliveryAddress
    val formattedAddress=address?.let { listOf(it.line1,it.line2,it.city,it.province,it.postalCode,it.country).filter(String::isNotBlank).joinToString(", ") }.orEmpty()
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState),state=listState,contentPadding=PaddingValues(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        item { PageTitle("Make yourself at home", "A little space of your own.") }
        if(!configured) {
            item { EmptyState(Icons.Outlined.PersonOutline,"Welcome to Olive","Browse the menu and collect your favourites.") }
            item { Button(onClick=onSetup,modifier=Modifier.padding(horizontal=24.dp).fillMaxWidth()) { Text(stringResource(R.string.set_up_account)) } }
        } else {
            item { Card(Modifier.padding(horizontal=24.dp).fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Paper)) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.your_olive_account),style=MaterialTheme.typography.titleLarge)
                    Text(session?.coverFullName.orEmpty(),fontWeight=FontWeight.SemiBold,modifier=Modifier.testTag("cover_profile_name"))
                    Text("@${session?.coverUsername.orEmpty()}",color=Muted,modifier=Modifier.testTag("cover_profile_username"))
                    HorizontalDivider(Modifier.padding(vertical=4.dp))
                    Text(stringResource(R.string.delivery_address),style=MaterialTheme.typography.labelLarge)
                    Text(formattedAddress.ifBlank { "No delivery address saved" },color=Muted,modifier=Modifier.testTag("cover_profile_address"))
                    OutlinedButton(onClick={session?.recordSettingsInteraction("Olive","Opened delivery address editor");onAddress()},modifier=Modifier.fillMaxWidth().testTag("cover_change_address")) { Text(stringResource(R.string.change_delivery_address)) }
                }
            } }
            item { Card(Modifier.padding(horizontal=24.dp).fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Paper)) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.account_settings),style=MaterialTheme.typography.titleLarge)
                    OutlinedTextField(value=fullName,onValueChange={fullName=it.take(120)},singleLine=true,label={Text(
                        stringResource(R.string.full_name)
                    )},
                        modifier=Modifier.fillMaxWidth().testTag("cover_full_name").onFocusChanged { if(it.isFocused) session?.recordSettingsInteraction("Olive","Focused full name") },
                        enabled=session?.busy!=true)
                    session?.message?.let { Text(it,color=Muted) }
                    Button(onClick={session?.saveCoverSettings(fullName,selectedLanguage)},enabled=session?.busy!=true && fullName.isNotBlank(),modifier=Modifier.fillMaxWidth().testTag("cover_save_settings")) { Text(
                        stringResource(R.string.save_account_settings)
                    ) }
                    if(session?.busy==true) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            } }
        }
        if(configured) {
            item { PageTitle("Order history", "Attempted checkouts on this device.") }
            val history=session?.checkoutHistory.orEmpty()
            if(history.isEmpty()) item { Text(stringResource(R.string.no_checkout_attempts_yet),Modifier.padding(horizontal=24.dp),color=Muted) }
            items(if(showAllOrders) history else history.take(5),key={it.id}) { attempt ->
                Card(Modifier.padding(horizontal=24.dp).fillMaxWidth().testTag("checkout_history_row"),
                    colors=CardDefaults.cardColors(containerColor=Paper)) {
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.payment_declined),fontWeight=FontWeight.SemiBold,color=OliveGreen)
                        val orderDateFormatter = remember { DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault()) }
                        Text(orderDateFormatter.format(Instant.ofEpochMilli(attempt.timestamp)),color=Muted)
                        attempt.foods.forEach { Text("${it.quantity} × ${it.food}") }
                        Text(money(attempt.totalCents),fontWeight=FontWeight.SemiBold)
                        Text(attempt.error,color=Muted,style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if(history.size>5) item { TextButton(onClick={showAllOrders=!showAllOrders},modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp).testTag("cover_order_history_more")) {
                Text(if(showAllOrders) "Show less" else "See more")
            } }
        }
        item { Card(Modifier.padding(horizontal=24.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.find_a_new_favourite),fontFamily=FontFamily.Serif,fontSize=24.sp)
                Text(stringResource(R.string.fresh_bowls_stone_baked_pizza_and_little_everyday_cravings_tap_the_heart_on_a_dish_to_keep_it_close),color=Muted)
            }
        } }
        item { Text("Olive · 0.2.0",Modifier.fillMaxWidth().padding(horizontal=24.dp),color=Muted,style=MaterialTheme.typography.labelMedium) }
    }
}

// UI sizing/text: cover page titles use 24dp sides and a 32/37sp serif title pair.
@Composable private fun PageTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,fontFamily=FontFamily.Serif,fontSize=32.sp,lineHeight=37.sp)
        Text(subtitle,color=Muted)
    }
}

// UI sizing/color: empty states use 28dp padding, 14dp gaps, a 58dp themed icon circle, and centered Material text.
@Composable private fun EmptyState(icon: ImageVector, title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Icon(icon,null,Modifier.size(58.dp).background(MaterialTheme.colorScheme.primaryContainer,CircleShape).padding(14.dp),tint=OliveGreen)
        Text(title,style=MaterialTheme.typography.titleMedium,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        Text(description,color=Muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
    }
}
