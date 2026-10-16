package com.olive.app.ui.covers.news

import com.olive.app.ui.*

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.data.NewsArticle
import com.olive.app.data.localPublishedDate
import com.olive.app.data.NewsMetadataRepository
import com.olive.app.data.fallbackNewsTopic
import com.olive.app.data.isSecureNewsUrl
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

// Modern newspaper cards keep publisher attribution, a visible section, and the article's own photo.
@Composable internal fun NewsArticleCard(article:NewsArticle,language:String,saved:Boolean,onOpen:()->Unit,onBookmark:()->Unit) {
    val context=LocalContext.current
    val metadata=remember(context) { NewsMetadataRepository(context.applicationContext) }
    var displayed by remember(article) { mutableStateOf(article) }
    LaunchedEffect(article.link) {
        metadata.cached(article.link,allowStale=true)?.let { displayed=it.applyTo(article) }
        displayed=metadata.load(article).applyTo(article)
    }
    val topic=displayed.topic.ifBlank { fallbackNewsTopic(displayed.title) }
    var imageFailed by remember(displayed.imageUrl) { mutableStateOf(false) }
    var imageLoaded by remember(displayed.imageUrl) { mutableStateOf(false) }
    Card(onClick=onOpen,modifier=Modifier.animateContentSize().fillMaxWidth().testTag("news_article_card"),
        colors=CardDefaults.cardColors(containerColor=Cream),elevation=CardDefaults.cardElevation(defaultElevation=3.dp),
        border=BorderStroke(.7.dp,SoftGray),shape=RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                LocalizedText(displayed.source.ifBlank { "Daily Digest" },fontFamily=FontFamily.Serif,fontWeight=FontWeight.Bold,
                    fontSize=18.sp,color=Ink,modifier=Modifier.weight(1f),maxLines=2)
                LocalizedText(displayed.localPublishedDate(language),color=Muted,style=MaterialTheme.typography.labelSmall,maxLines=2)
                IconButton(onClick=onBookmark) { Icon(if(saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                    if(saved) "Remove bookmark" else "Save article",tint=Ink) }
            }
            HorizontalDivider(thickness=2.dp,color=OliveGreen)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                Surface(color=PaleOlive,shape=RoundedCornerShape(8.dp),border=BorderStroke(.7.dp,OliveGreen.copy(alpha=.3f)),
                    modifier=Modifier.weight(1f,fill=false).testTag("news_article_topic")) {
                    LocalizedText(topic,modifier=Modifier.padding(horizontal=12.dp,vertical=7.dp),color=DarkOlive,
                        fontSize=15.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                if(displayed.isPaywalled==true) Surface(color=WarmWhite,shape=RoundedCornerShape(8.dp),
                    border=BorderStroke(.7.dp,SoftGray),modifier=Modifier.testTag("news_article_paywall")) {
                    Row(Modifier.padding(horizontal=10.dp,vertical=7.dp),horizontalArrangement=Arrangement.spacedBy(5.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lock,null,Modifier.size(14.dp),tint=Ink)
                        LocalizedText("Paywall",color=Ink,style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.SemiBold)
                    }
                }
            }
            LocalizedText(displayed.title.removeSuffix(" - ${displayed.source}"),fontFamily=FontFamily.Serif,fontWeight=FontWeight.Bold,fontSize=24.sp,
                lineHeight=29.sp,color=Ink,maxLines=4)
            if(isSecureNewsUrl(displayed.imageUrl) && !imageFailed) AsyncImage(model=displayed.imageUrl,
                contentDescription=displayed.title,contentScale=ContentScale.Crop,
                modifier=Modifier.fillMaxWidth().aspectRatio(16f/9f).clip(RoundedCornerShape(9.dp)).background(LightSage).testTag("news_article_image")
                    .semantics { stateDescription=if(imageLoaded) "Article photo loaded" else "Article photo loading" },
                onSuccess={imageLoaded=true},onError={imageFailed=true})
            if(displayed.summary.isNotBlank()) LocalizedText(displayed.summary,color=Muted,fontFamily=FontFamily.Serif,
                fontSize=15.sp,lineHeight=21.sp,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically) {
                LocalizedText("Read full story",color=OliveGreen,style=MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp));Icon(Icons.AutoMirrored.Outlined.ArrowForward,null,Modifier.size(18.dp),tint=OliveGreen)
            }
        }
    }
}
