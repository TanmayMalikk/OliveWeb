package com.olive.app.ui.covers.olive

import com.olive.app.ui.*

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Change cover profile fields here; keep private safe-profile details out of this screen.
// UI sizing/color: account content uses 24dp padding/gaps, Paper cards, 20dp card padding, and Material heading sizes.
@Composable internal fun FoodAccountScreen(onSetup: () -> Unit, configured: Boolean, session: SessionViewModel?, onAddress: ()->Unit) {
    LaunchedEffect(session?.coverUsername) { if(configured) session?.recordSettingsInteraction("Olive", "Opened account page") }
    var fullName by remember(session?.coverFullName) { mutableStateOf(session?.coverFullName.orEmpty()) }
    var selectedLanguage by remember(session?.language) { mutableStateOf(session?.language ?: "English") }
    var showAllOrders by remember { mutableStateOf(false) }
    val address=session?.deliveryAddress
    val formattedAddress=address?.let { listOf(it.line1,it.line2,it.city,it.province,it.postalCode,it.country).filter(String::isNotBlank).joinToString(", ") }.orEmpty()
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState),state=listState,contentPadding=PaddingValues(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        item { FoodPageTitle("Make yourself at home", "A little space of your own.") }
        if(!configured) {
            item { FoodEmptyState(Icons.Outlined.PersonOutline,"Welcome to Olive","Browse the menu and collect your favourites.") }
            item { Button(onClick=onSetup,modifier=Modifier.padding(horizontal=24.dp).fillMaxWidth()) { LocalizedText("Set up account") } }
        } else {
            item { Card(Modifier.padding(horizontal=24.dp).fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Paper)) {
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
            item { Card(Modifier.padding(horizontal=24.dp).fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Paper)) {
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
            item { FoodPageTitle("Order history", "Attempted checkouts on this device.") }
            val history=session?.checkoutHistory.orEmpty()
            if(history.isEmpty()) item { LocalizedText("No checkout attempts yet.",Modifier.padding(horizontal=24.dp),color=Muted) }
            items(if(showAllOrders) history else history.take(5),key={it.id}) { attempt ->
                Card(Modifier.padding(horizontal=24.dp).fillMaxWidth().testTag("checkout_history_row"),
                    colors=CardDefaults.cardColors(containerColor=Paper)) {
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        LocalizedText("Payment declined",fontWeight=FontWeight.SemiBold,color=OliveGreen)
                        LocalizedText(DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm",Locale.forLanguageTag(getLanguageTag(LocalOliveLanguage.current)))
                            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(attempt.timestamp)),color=Muted)
                        attempt.foods.forEach { LocalizedText("${it.quantity} × ${it.food}") }
                        LocalizedText(formatDishPrice(attempt.totalCents),fontWeight=FontWeight.SemiBold)
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
