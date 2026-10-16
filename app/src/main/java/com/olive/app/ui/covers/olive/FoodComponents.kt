package com.olive.app.ui.covers.olive

import com.olive.app.ui.*

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

internal fun formatDishPrice(cents: Int): String = "$%.2f".format(Locale.US, cents / 100.0)

// UI sizing/text: cover page titles use 24dp sides and a 32/37sp serif title pair.
@Composable internal fun FoodPageTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        LocalizedText(title,fontFamily=FontFamily.Serif,fontSize=32.sp,lineHeight=37.sp)
        LocalizedText(subtitle,color=Muted)
    }
}

// UI sizing/color: empty states use 28dp padding, 14dp gaps, a 58dp themed icon circle, and centered Material text.
@Composable internal fun FoodEmptyState(icon: ImageVector, title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(28.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(14.dp)) {
        Icon(icon,null,Modifier.size(58.dp).background(MaterialTheme.colorScheme.primaryContainer,CircleShape).padding(14.dp),tint=OliveGreen)
        LocalizedText(title,style=MaterialTheme.typography.titleMedium,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
        LocalizedText(description,color=Muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
    }
}
