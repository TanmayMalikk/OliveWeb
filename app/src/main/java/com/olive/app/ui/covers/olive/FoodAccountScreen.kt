package com.olive.app.ui.covers.olive
import com.olive.app.ui.rememberResourceText

import androidx.compose.ui.semantics.heading

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.res.stringResource
import com.olive.app.R
import com.olive.app.ui.*

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

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
    val uiText=rememberResourceText()
    LaunchedEffect(session?.coverUsername) { if(configured) session?.recordSettingsInteraction("Olive", "Opened account page") }
    var showAllOrders by remember { mutableStateOf(false) }
    val address=session?.deliveryAddress
    val formattedAddress=address?.let { listOf(it.line1,it.line2,it.city,it.province,it.postalCode,it.country).filter(String::isNotBlank).joinToString(", ") }.orEmpty()
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState),state=listState,contentPadding=PaddingValues(vertical=24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        item { FoodPageTitle(uiText(R.string.make_yourself_at_home), uiText(R.string.a_little_space_of_your_own)) }
        if(!configured) {
            item { FoodEmptyState(Icons.Outlined.PersonOutline,uiText(R.string.welcome_to_olive),uiText(R.string.browse_the_menu_and_collect_your_favourites)) }
            item { Button(onClick=onSetup,modifier=Modifier.padding(horizontal=24.dp).fillMaxWidth()) { Text(stringResource(R.string.set_up_account)) } }
        } else {
            item { Card(Modifier.padding(horizontal=24.dp).fillMaxWidth().testTag("cover_profile_summary").semantics(mergeDescendants=true) {},colors=CardDefaults.cardColors(containerColor=Paper)) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.your_olive_account),style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics { heading() })
                    Text(session?.coverFullName.orEmpty(),fontWeight=FontWeight.SemiBold,modifier=Modifier.testTag("cover_profile_name"))
                    Text("@${session?.coverUsername.orEmpty()}",color=Muted,modifier=Modifier.testTag("cover_profile_username"))
                    HorizontalDivider(Modifier.padding(vertical=4.dp))
                    Text(stringResource(R.string.delivery_address),style=MaterialTheme.typography.labelLarge)
                    Text(formattedAddress.ifBlank { uiText(R.string.no_delivery_address_saved) },color=Muted,modifier=Modifier.testTag("cover_profile_address"))
                    OutlinedButton(onClick={session?.recordSettingsInteraction("Olive","Opened delivery address editor");onAddress()},modifier=Modifier.fillMaxWidth().testTag("cover_change_address")) { Text(stringResource(R.string.change_delivery_address)) }
                }
            } }
            item { Card(Modifier.padding(horizontal=24.dp).fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Paper)) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.account_settings),style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics { heading() })
                    if(session!=null) com.olive.app.ui.covers.shared.CoverAccountPanel(session)
                    if(session?.busy==true) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            } }
        }
        if(configured) {
            item { FoodPageTitle(uiText(R.string.order_history), uiText(R.string.attempted_checkouts_on_this_device)) }
            val history=session?.checkoutHistory.orEmpty()
            if(history.isEmpty()) item { Text(stringResource(R.string.no_checkout_attempts_yet),Modifier.padding(horizontal=24.dp),color=Muted) }
            items(if(showAllOrders) history else history.take(5),key={it.id}) { attempt ->
                Card(Modifier.padding(horizontal=24.dp).fillMaxWidth().testTag("checkout_history_row").semantics(mergeDescendants=true) {},
                    colors=CardDefaults.cardColors(containerColor=Paper)) {
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.payment_declined),fontWeight=FontWeight.SemiBold,color=OliveGreen)
                        val orderDateFormatter = remember { DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm", Locale.getDefault()).withZone(ZoneId.systemDefault()) }
                        Text(orderDateFormatter.format(Instant.ofEpochMilli(attempt.timestamp)),color=Muted)
                        attempt.foods.forEach { Text("${it.quantity} × ${it.food}") }
                        Text(formatDishPrice(attempt.totalCents),fontWeight=FontWeight.SemiBold)
                        Text(attempt.error,color=Muted,style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if(history.size>5) item { TextButton(onClick={showAllOrders=!showAllOrders},modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp).testTag("cover_order_history_more")) {
                Text(if(showAllOrders) uiText(R.string.show_less) else uiText(R.string.see_more))
            } }
        }
        item { Card(Modifier.padding(horizontal=24.dp).semantics(mergeDescendants=true) {},colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.find_a_new_favourite),fontFamily=FontFamily.Serif,fontSize=24.sp)
                Text(stringResource(R.string.fresh_bowls_stone_baked_pizza_and_little_everyday_cravings_tap_the_heart_on_a_dish_to_keep_it_close),color=Muted)
            }
        } }
        item { Text(uiText(R.string.ui_olive_value , com.olive.app.BuildConfig.VERSION_NAME),Modifier.fillMaxWidth().padding(horizontal=24.dp),color=Muted,style=MaterialTheme.typography.labelMedium) }
    }
}
