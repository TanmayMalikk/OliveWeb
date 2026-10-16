package com.olive.app.ui.covers.olive

import com.olive.app.ui.*

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalFocusManager

// The main food feed lives here—search, categories, location, and all the tempting menu cards.
// UI sizing/text: the feed uses 24dp sides, 22dp item gaps, a 36sp wordmark, and 40/44sp hero copy.
@Composable internal fun FoodDiscoverScreen(vm: OliveViewModel, onSearch: (String) -> Unit, onDeliveryArea: () -> Unit) {
    val focus = LocalFocusManager.current
    val listState=rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState),state=listState,contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal=24.dp, vertical=12.dp), verticalAlignment=Alignment.CenterVertically) {
                // Cover sizing note: this 36sp value is the Olive wordmark; delivery text beside it should wrap or truncate instead of shrinking the brand.
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
                // Hero copy uses 40/44sp. Change fontSize and lineHeight together, then check French and the narrowest supported phone.
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
        item { LocalizedText(vm.catalogMessage,Modifier.padding(horizontal=24.dp),color=Muted,style=MaterialTheme.typography.bodySmall) }
        item { Row(Modifier.fillMaxWidth().padding(horizontal=24.dp),verticalAlignment=Alignment.CenterVertically) {
            LocalizedText(if(vm.query.isNotBlank()) "Search results" else "Made for your cravings",fontSize=22.sp,fontFamily=FontFamily.Serif,modifier=Modifier.weight(1f))
            LocalizedText("${vm.results.size} dishes",style=MaterialTheme.typography.labelMedium,color=Muted)
        } }
        if(vm.results.isEmpty()) item { FoodEmptyState(Icons.Outlined.Search,"Nothing on the menu yet","Try a different search or choose another category.") }
        items(vm.results, key={it.id}) { DishCard(it, vm,Modifier.animateItem()) }
    }
}
