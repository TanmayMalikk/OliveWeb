package com.olive.app.ui.covers.olive

import com.olive.app.ui.*

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.data.Dish

// One bite-sized menu card with its photo, price, favourite button, and quick add.
// UI sizing/color: dish cards use 24dp sides, 22dp corners, a 16:9 image, 16dp padding, and 18sp item names.
@Composable internal fun DishCard(dish: Dish, vm: OliveViewModel, modifier: Modifier=Modifier) {
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
                Text(formatDishPrice(dish.priceCents),fontWeight=FontWeight.SemiBold,color=OliveGreen)
            }
            Text("${dish.restaurant}  ·  ${dish.diet}",color=Muted,style=MaterialTheme.typography.bodyMedium)
            Text("★ ${dish.rating}     ·     ${dish.minutes} min",style=MaterialTheme.typography.labelMedium,color=OliveGreen)
        }
    }
}
