package com.olive.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import com.olive.app.data.Dish
import com.olive.app.data.OrderLine
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable private fun money(cents: Int): String = NumberFormat.getCurrencyInstance(if(LocalOliveLanguage.current=="French") Locale.CANADA_FRENCH else Locale.CANADA).format(cents / 100.0)

// Change cover navigation, tabs, address popup, or checkout entry in this main food-app section.
@Composable fun OliveApp(
    vm: OliveViewModel = viewModel(),
    interactive: Boolean = true,
    onSearch: (String) -> Unit = vm::search,
    onAccountSetup: () -> Unit = {},
    configured: Boolean = false,
    session: SessionViewModel? = null,
) {
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
                            if(tab == CoverTab.Cart && vm.itemCount > 0) Badge { LocalizedText(vm.itemCount.toString()) }
                        }) { Icon(icon, contentDescription = null) } }, label = { LocalizedText(label) })
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val dish = vm.selectedDish
            if(dish != null) Detail(dish, vm)
            else when(vm.tab) {
                CoverTab.Discover -> Discover(vm,onSearch) { if(configured) areaDialog=true else onAccountSetup() }
                CoverTab.Saved -> Saved(vm)
                CoverTab.Cart -> Cart(vm) { if(configured) checkoutNotice = true else onAccountSetup() }
                CoverTab.Account -> Account(onAccountSetup,configured,session) { areaDialog=true }
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
@Composable private fun Discover(vm: OliveViewModel, onSearch: (String) -> Unit, onDeliveryArea: () -> Unit) {
    val focus = LocalFocusManager.current
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal=24.dp, vertical=12.dp), verticalAlignment=Alignment.CenterVertically) {
                LocalizedText("olive", fontFamily=FontFamily.Serif, fontWeight=FontWeight.Bold, fontSize=36.sp, color=OliveGreen)
                Spacer(Modifier.weight(1f))
                TextButton(onClick=onDeliveryArea) {
                    Icon(Icons.Outlined.LocationOn,null,Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.widthIn(max=150.dp)) {
                        LocalizedText("DELIVER TO",style=MaterialTheme.typography.labelSmall,color=Muted)
                        LocalizedText(vm.deliveryArea.ifBlank { "Choose your area" },maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Outlined.KeyboardArrowDown,null,Modifier.size(18.dp))
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal=24.dp)) {
                LocalizedText("HOMEMADE BY OLIVE KITCHEN", style=MaterialTheme.typography.labelSmall, color=OliveGreen, letterSpacing=1.6.sp)
                Spacer(Modifier.height(10.dp))
                LocalizedText("Good food.\nGood mood.", fontFamily=FontFamily.Serif, fontSize=40.sp, lineHeight=44.sp, fontWeight=FontWeight.Medium)
                Spacer(Modifier.height(10.dp))
                LocalizedText("Made in our kitchen and delivered to your door.", color=Muted)
            }
        }
        item {
            OutlinedTextField(value=vm.query, onValueChange=onSearch,
                modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp),
                placeholder={LocalizedText("Search Olive’s menu")}, leadingIcon={Icon(Icons.Outlined.Search,null)},
                trailingIcon={if(vm.query.isNotEmpty()) IconButton(onClick={vm.search("")}) { Icon(Icons.Outlined.Close,"Clear search") }},
                singleLine=true, shape=RoundedCornerShape(18.dp),
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search,autoCorrectEnabled=false), keyboardActions=KeyboardActions(onSearch={focus.clearFocus()}))
        }
        item {
            LazyRow(contentPadding=PaddingValues(horizontal=24.dp), horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                items(listOf("All","Vegetarian","Vegan","Meat")) { category ->
                    FilterChip(selected=vm.category == category, onClick={vm.filter(category)}, label={LocalizedText(category)}, shape=RoundedCornerShape(24.dp))
                }
            }
        }
        if(vm.query.isBlank() && vm.category == "All") item {
            // This blank card is reserved for a future Olive advertisement.
            Card(modifier=Modifier.fillMaxWidth().height(170.dp).padding(horizontal=24.dp).testTag("olive_ad_placeholder"),shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFFF3F3F0))) {
                Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                    LocalizedText("Put ad here",style=MaterialTheme.typography.titleMedium,color=Muted)
                }
            }
        }
        item { LocalizedText(vm.catalogMessage,Modifier.padding(horizontal=24.dp),color=Muted,style=MaterialTheme.typography.bodySmall) }
        item { Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),verticalAlignment=Alignment.CenterVertically) {
            LocalizedText(if(vm.query.isNotBlank()) "Search results" else "Made for your cravings",fontSize=22.sp,fontFamily=FontFamily.Serif,modifier=Modifier.weight(1f))
            LocalizedText("${vm.results.size} dishes",style=MaterialTheme.typography.labelMedium,color=Muted)
        } }
        if(vm.results.isEmpty()) item { EmptyState(Icons.Outlined.Search,"Nothing on the menu yet","Try a different search or choose another category.") }
        items(vm.results, key={it.id}) { DishCard(it, vm) }
    }
}

// One bite-sized menu card with its photo, price, favourite button, and quick add.
@Composable private fun DishCard(dish: Dish, vm: OliveViewModel) {
    Card(onClick={vm.select(dish)},modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=Color.White)) {
        Box {
            DishImage(dish,Modifier.fillMaxWidth().aspectRatio(16f/9f))
            FilledIconButton(onClick={vm.favorite(dish.id)},modifier=Modifier.align(Alignment.TopEnd).padding(10.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=Cream)) {
                Icon(if(dish.id in vm.favorites) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,if(dish.id in vm.favorites) "Unsave ${dish.name}" else "Save ${dish.name}",tint=OliveGreen)
            }
        }
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                LocalizedText(dish.name,fontWeight=FontWeight.SemiBold,fontSize=18.sp,modifier=Modifier.weight(1f))
                LocalizedText(money(dish.priceCents),fontWeight=FontWeight.SemiBold,color=OliveGreen)
            }
            LocalizedText("${dish.restaurant}  ·  ${translate(dish.diet,LocalOliveLanguage.current)}",color=Muted,style=MaterialTheme.typography.bodyMedium)
            LocalizedText("★ ${dish.rating}     ·     ${dish.minutes} min",style=MaterialTheme.typography.labelMedium,color=OliveGreen)
        }
    }
}

// Tap a dish and this gives it the full spotlight before adding it to the cart.
@Composable private fun Detail(dish: Dish, vm: OliveViewModel) {
    LazyColumn(contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
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
            LocalizedText(money(dish.priceCents),style=MaterialTheme.typography.headlineSmall,color=OliveGreen)
            Button(onClick={vm.changeQuantity(dish.id,1)}, modifier=Modifier.fillMaxWidth().heightIn(min=54.dp),enabled=(vm.cart[dish.id] ?: 0) < 99) {
                LocalizedText(if((vm.cart[dish.id] ?: 0) == 0) "Add to cart" else "Add another · ${vm.cart[dish.id]} in cart")
            }
            OutlinedButton(onClick={vm.favorite(dish.id)},modifier=Modifier.fillMaxWidth()) { LocalizedText(if(dish.id in vm.favorites) "Saved to favourites" else "Save for later") }
        } }
    }
}

// Hearts land here so users can find their saved food again.
@Composable private fun Saved(vm: OliveViewModel) {
    LazyColumn(contentPadding=PaddingValues(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { PageTitle("Your favourites", "Good finds, kept close.") }
        val saved = vm.dishes.filter { it.id in vm.favorites }
        if(saved.isEmpty()) item { EmptyState(Icons.Outlined.FavoriteBorder,"A place for your favourites","Tap the heart on any dish to save it here.") }
        items(saved,key={it.id}) { DishCard(it,vm) }
    }
}

// The cart handles quantities, subtotal, and the jump into the always-declined checkout.
@Composable private fun Cart(vm: OliveViewModel, checkout: () -> Unit) {
    LazyColumn(contentPadding=PaddingValues(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        item { PageTitle("Your cart", "A few good things, all together.") }
        if(vm.itemCount == 0) item {
            EmptyState(Icons.Outlined.ShoppingBag,"Something delicious belongs here","Explore the menu and add a dish to get started.")
            Button(onClick={vm.navigate(CoverTab.Discover)},modifier=Modifier.padding(horizontal=24.dp).fillMaxWidth()) { LocalizedText("Explore the menu") }
        }
        items(vm.dishes.filter { it.id in vm.cart },key={it.id}) { dish ->
            Card(modifier=Modifier.padding(horizontal=24.dp),colors=CardDefaults.cardColors(containerColor=Color.White)) {
                Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                    DishImage(dish,Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)))
                    Column(Modifier.weight(1f).padding(start=12.dp)) {
                        LocalizedText(dish.name,fontWeight=FontWeight.SemiBold)
                        LocalizedText(money(dish.priceCents),color=Muted)
                        val choices=vm.selectedIngredients(dish)
                        if(choices.isNotEmpty()) Text(choices.joinToString(", "),style=MaterialTheme.typography.bodySmall,color=Muted,maxLines=2)
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            IconButton(onClick={vm.changeQuantity(dish.id,-1)}) { Icon(Icons.Outlined.Remove,"Remove one ${dish.name}") }
                            LocalizedText("${vm.cart[dish.id]}")
                            IconButton(onClick={vm.changeQuantity(dish.id,1)},enabled=(vm.cart[dish.id] ?: 0)<99) { Icon(Icons.Outlined.Add,"Add one ${dish.name}") }
                        }
                    }
                }
            }
        }
        if(vm.itemCount > 0) item { Column(Modifier.padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth()) { LocalizedText("Subtotal",modifier=Modifier.weight(1f)); LocalizedText(money(vm.total),fontWeight=FontWeight.Bold) }
            LocalizedText("Review your delivery and payment details at checkout.",color=Muted,style=MaterialTheme.typography.bodyMedium)
            Button(onClick=checkout,modifier=Modifier.fillMaxWidth().heightIn(min=54.dp)) { LocalizedText("Continue to checkout") }
        } }
    }
}

// Add a cover language here only after its text is added in Localization and the safe picker.
@Composable private fun CoverLanguagePicker(value: String, enabled: Boolean, onChange: (String)->Unit) {
    var open by remember { mutableStateOf(false) }
    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus=androidx.compose.ui.platform.LocalFocusManager.current
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick={keyboard?.hide();focus.clearFocus(force=true);open=true},enabled=enabled,modifier=Modifier.fillMaxWidth().testTag("cover_language")) {
            LocalizedText("App language")
            Spacer(Modifier.weight(1f))
            LocalizedText(if(value=="French") "Français" else "English")
        }
        DropdownMenu(expanded=open,onDismissRequest={open=false}) {
            listOf("English","French").forEach { choice ->
                DropdownMenuItem(text={LocalizedText(if(choice=="French") "Français" else "English")},onClick={onChange(choice);open=false})
            }
        }
    }
}

// Change cover profile fields here; keep private safe-profile details out of this screen.
@Composable private fun Account(onSetup: () -> Unit, configured: Boolean, session: SessionViewModel?, onAddress: ()->Unit) {
    LaunchedEffect(session?.coverUsername) { if(configured) session?.recordSettingsInteraction("Olive", "Opened account page") }
    var fullName by remember(session?.coverFullName) { mutableStateOf(session?.coverFullName.orEmpty()) }
    var selectedLanguage by remember(session?.language) { mutableStateOf(session?.language ?: "English") }
    var showAllOrders by remember { mutableStateOf(false) }
    val address=session?.deliveryAddress
    val formattedAddress=address?.let { listOf(it.line1,it.line2,it.city,it.province,it.postalCode,it.country).filter(String::isNotBlank).joinToString(", ") }.orEmpty()
    LazyColumn(contentPadding=PaddingValues(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        item { PageTitle("Make yourself at home", "A little space of your own.") }
        if(!configured) {
            item { EmptyState(Icons.Outlined.PersonOutline,"Welcome to Olive","Browse the menu and collect your favourites.") }
            item { Button(onClick=onSetup,modifier=Modifier.padding(horizontal=24.dp).fillMaxWidth()) { LocalizedText("Set up account") } }
        } else {
            item { Card(Modifier.padding(horizontal=24.dp).fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color.White)) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    LocalizedText("Your Olive account",style=MaterialTheme.typography.titleLarge)
                    LocalizedText(session?.coverFullName.orEmpty(),fontWeight=FontWeight.SemiBold,modifier=Modifier.testTag("cover_profile_name"))
                    LocalizedText("@${session?.coverUsername.orEmpty()}",color=Muted,modifier=Modifier.testTag("cover_profile_username"))
                    HorizontalDivider(Modifier.padding(vertical=4.dp))
                    LocalizedText("Delivery address",style=MaterialTheme.typography.labelLarge)
                    LocalizedText(formattedAddress.ifBlank { "No delivery address saved" },color=Muted,modifier=Modifier.testTag("cover_profile_address"))
                    OutlinedButton(onClick={session?.recordSettingsInteraction("Olive","Opened delivery address editor");onAddress()},modifier=Modifier.fillMaxWidth().testTag("cover_change_address")) { LocalizedText("Change delivery address") }
                }
            } }
            item { Card(Modifier.padding(horizontal=24.dp).fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Color.White)) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    LocalizedText("Account settings",style=MaterialTheme.typography.titleLarge)
                    OutlinedTextField(value=fullName,onValueChange={fullName=it.take(120)},singleLine=true,label={LocalizedText("Full name")},
                        modifier=Modifier.fillMaxWidth().testTag("cover_full_name").onFocusChanged { if(it.isFocused) session?.recordSettingsInteraction("Olive","Focused full name") },
                        enabled=session?.busy!=true)
                    CoverLanguagePicker(selectedLanguage,session?.busy!=true) { session?.recordSettingsInteraction("Olive","Selected language");selectedLanguage=it }
                    session?.message?.let { LocalizedText(it,color=Muted) }
                    Button(onClick={session?.saveCoverSettings(fullName,selectedLanguage)},enabled=session?.busy!=true && fullName.isNotBlank(),modifier=Modifier.fillMaxWidth().testTag("cover_save_settings")) { LocalizedText("Save account settings") }
                    if(session?.busy==true) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            } }
        }
        if(configured) {
            item { PageTitle("Order history", "Attempted checkouts on this device.") }
            val history=session?.checkoutHistory.orEmpty()
            if(history.isEmpty()) item { LocalizedText("No checkout attempts yet.",Modifier.padding(horizontal=24.dp),color=Muted) }
            items(if(showAllOrders) history else history.take(5),key={it.id}) { attempt ->
                Card(Modifier.padding(horizontal=24.dp).fillMaxWidth().testTag("checkout_history_row"),
                    colors=CardDefaults.cardColors(containerColor=Color.White)) {
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        LocalizedText("Payment declined",fontWeight=FontWeight.SemiBold,color=OliveGreen)
                        LocalizedText(DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm",if(LocalOliveLanguage.current=="French") Locale.CANADA_FRENCH else Locale.CANADA)
                            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(attempt.timestamp)),color=Muted)
                        attempt.foods.forEach { LocalizedText("${it.quantity} × ${it.food}") }
                        LocalizedText(money(attempt.totalCents),fontWeight=FontWeight.SemiBold)
                        LocalizedText(attempt.error,color=Muted,style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if(history.size>5) item { TextButton(onClick={showAllOrders=!showAllOrders},modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp).testTag("cover_order_history_more")) {
                LocalizedText(if(showAllOrders) "Show less" else "See more")
            } }
        }
        item { Card(Modifier.padding(horizontal=24.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                LocalizedText("Find a new favourite",fontFamily=FontFamily.Serif,fontSize=24.sp)
                LocalizedText("Fresh bowls, stone-baked pizza and little everyday cravings. Tap the heart on a dish to keep it close.",color=Muted)
            }
        } }
        item { LocalizedText("Olive · 0.2.0",Modifier.fillMaxWidth().padding(horizontal=24.dp),color=Muted,style=MaterialTheme.typography.labelMedium) }
    }
}

@Composable private fun PageTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        LocalizedText(title,fontFamily=FontFamily.Serif,fontSize=32.sp,lineHeight=37.sp)
        LocalizedText(subtitle,color=Muted)
    }
}

@Composable private fun EmptyState(icon: ImageVector, title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Icon(icon,null,Modifier.size(58.dp).background(MaterialTheme.colorScheme.primaryContainer,CircleShape).padding(14.dp),tint=OliveGreen)
        LocalizedText(title,style=MaterialTheme.typography.titleMedium,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        LocalizedText(description,color=Muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
    }
}
