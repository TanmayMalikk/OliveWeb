package com.olive.app.ui.covers.news

import com.olive.app.ui.LocalizedIcon as Icon
import com.olive.app.ui.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// UI sizing: Local/Global buttons stay 48dp tall with 18dp icons and 15dp corners.
@Composable internal fun NewsScopeButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean,
    modifier: Modifier=Modifier, onClick: ()->Unit) {
    if(selected) Button(onClick=onClick,modifier=modifier.height(48.dp),shape=RoundedCornerShape(15.dp)) {
        Icon(icon,null,Modifier.size(18.dp));Spacer(Modifier.width(7.dp));Text(label)
    } else OutlinedButton(onClick=onClick,modifier=modifier.height(48.dp),shape=RoundedCornerShape(15.dp)) {
        Icon(icon,null,Modifier.size(18.dp));Spacer(Modifier.width(7.dp));Text(label)
    }
}

// Loading placeholders use the same rounded cream card and olive rule as loaded stories.
@Composable internal fun NewsLoadingState() {
    Column(verticalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.fillMaxWidth()) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        repeat(2) { Card(colors=CardDefaults.cardColors(containerColor=Cream),border=BorderStroke(.7.dp,SoftGray),shape=RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(100.dp).height(16.dp).background(LightSage))
                HorizontalDivider(thickness=2.dp,color=OliveGreen.copy(alpha=.4f))
                Box(Modifier.width(90.dp).height(30.dp).background(PaleOlive,RoundedCornerShape(8.dp)))
                Box(Modifier.fillMaxWidth().height(24.dp).background(WarmWhite))
                Box(Modifier.fillMaxWidth().aspectRatio(16f/9f).background(LightSage,RoundedCornerShape(9.dp)))
            }
        } }
    }
}

// UI sizing/color: empty news states use 38dp vertical space, a 42dp Muted icon, and centered text.
@Composable internal fun NewsEmptyState(message: String) {
    Column(Modifier.fillMaxWidth().padding(vertical=38.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Icon(Icons.AutoMirrored.Outlined.Article,null,Modifier.size(42.dp),tint=Muted)
        Text(message,color=Muted,textAlign=TextAlign.Center)
    }
}
