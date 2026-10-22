package com.olive.app.ui.covers.olive
import com.olive.app.ui.rememberResourceText
import com.olive.app.R
import androidx.compose.ui.platform.testTag

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import com.olive.app.ui.LocalizedIcon as Icon
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

// Dish details form one TalkBack target; the favourite button keeps its own action and the photo is decorative.
// UI sizing/color: dish cards use 24dp sides, 22dp corners, a 16:9 image, 16dp padding, and 18sp item names.
@Composable internal fun DishCard(dish: Dish, vm: OliveViewModel, modifier: Modifier=Modifier) {
    val uiText=rememberResourceText()
    Card(onClick={vm.select(dish)},modifier=modifier.fillMaxWidth().padding(horizontal=24.dp).animateContentSize().testTag("dish_card_${dish.id}").semantics(mergeDescendants=true) {},shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=Paper)) {
        Box {
            DishImage(dish,Modifier.fillMaxWidth().aspectRatio(16f/9f).clearAndSetSemantics {})
            FilledIconButton(onClick={vm.favorite(dish.id)},modifier=Modifier.align(Alignment.TopEnd).padding(10.dp),colors=IconButtonDefaults.filledIconButtonColors(containerColor=Cream)) {
                Icon(if(dish.id in vm.favorites) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,if(dish.id in vm.favorites) uiText(R.string.ui_unsave_value , uiText.dishName(dish)) else uiText(R.string.ui_save_value , uiText.dishName(dish)),tint=OliveGreen)
            }
        }
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(uiText.dishName(dish),fontWeight=FontWeight.SemiBold,fontSize=18.sp,modifier=Modifier.weight(1f))
                Text(formatDishPrice(dish.priceCents),fontWeight=FontWeight.SemiBold,color=OliveGreen)
            }
            Text(uiText(R.string.ui_value_value , uiText.dishRestaurant(dish), uiText.foodFilter(dish.diet)),color=Muted,style=MaterialTheme.typography.bodyMedium)
            Text(uiText(R.string.ui_value_value_min , dish.rating, dish.minutes),style=MaterialTheme.typography.labelMedium,color=OliveGreen)
        }
    }
}
