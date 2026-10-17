package com.olive.app.ui.covers.news
import com.olive.app.ui.rememberResourceText

import androidx.compose.ui.semantics.hideFromAccessibility
import com.olive.app.ui.LocalizedIcon as Icon
import androidx.compose.ui.res.stringResource
import com.olive.app.R
import com.olive.app.ui.*

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons

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

// Newspaper cards load cached/publisher metadata, show the section and article photo, and tag known paywalls without filtering.
// Story details form one TalkBack target; the bookmark remains a separate action.
@Composable internal fun NewsArticleCard(article:NewsArticle,language:String,saved:Boolean,onOpen:()->Unit,onBookmark:()->Unit) {
    val uiText=rememberResourceText()
    val context=LocalContext.current
    val metadata=remember(context) { NewsMetadataRepository(context.applicationContext) }
    var displayed by remember(article) { mutableStateOf(article) }
    LaunchedEffect(article.link) {
        metadata.cached(article.link,allowStale=true)?.let { displayed=it.applyTo(article) }
        displayed=metadata.load(article).applyTo(article)
    }
    val topic=displayed.topic.ifBlank { fallbackNewsTopic(displayed.title) }
    var imageFailed by remember(displayed.imageUrl) { mutableStateOf(false) }
    Card(onClick=onOpen,modifier=Modifier.animateContentSize().fillMaxWidth().testTag("news_article_card").semantics(mergeDescendants=true) {},
        colors=CardDefaults.cardColors(containerColor=Cream),elevation=CardDefaults.cardElevation(defaultElevation=3.dp),
        border=BorderStroke(.7.dp,SoftGray),shape=RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Text(displayed.source.ifBlank { uiText(R.string.ui_daily_digest) },fontFamily=FontFamily.Serif,fontWeight=FontWeight.Bold,
                    fontSize=18.sp,color=Ink,modifier=Modifier.weight(1f),maxLines=2)
                Text(displayed.localPublishedDate(language),color=Muted,style=MaterialTheme.typography.labelSmall,maxLines=2)
                IconButton(onClick=onBookmark) { Icon(if(saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                    if(saved) uiText(R.string.a11y_remove_bookmark) else uiText(R.string.a11y_save_article),tint=Ink) }
            }
            HorizontalDivider(thickness=2.dp,color=OliveGreen)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                Surface(color=PaleOlive,shape=RoundedCornerShape(8.dp),border=BorderStroke(.7.dp,OliveGreen.copy(alpha=.3f)),
                    modifier=Modifier.weight(1f,fill=false).testTag("news_article_topic")) {
                    Text(when(topic) { "News" -> uiText(R.string.ui_news); "Politics" -> uiText(R.string.ui_politics); "Sports" -> uiText(R.string.ui_sports); "Business" -> uiText(R.string.ui_business); "Technology" -> uiText(R.string.ui_technology); "Health" -> uiText(R.string.ui_health); "Science" -> uiText(R.string.ui_science); "Environment" -> uiText(R.string.ui_environment); "Culture" -> uiText(R.string.culture); else -> topic },modifier=Modifier.padding(horizontal=12.dp,vertical=7.dp),color=DarkOlive,
                        fontSize=15.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                if(displayed.isPaywalled==true) Surface(color=WarmWhite,shape=RoundedCornerShape(8.dp),
                    border=BorderStroke(.7.dp,SoftGray),modifier=Modifier.testTag("news_article_paywall")) {
                    Row(Modifier.padding(horizontal=10.dp,vertical=7.dp),horizontalArrangement=Arrangement.spacedBy(5.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Lock,null,Modifier.size(14.dp),tint=Ink)
                        Text(uiText(R.string.ui_paywall),color=Ink,style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.SemiBold)
                    }
                }
            }
            Text(displayed.title.removeSuffix(" - ${displayed.source}"),fontFamily=FontFamily.Serif,fontWeight=FontWeight.Bold,fontSize=24.sp,
                lineHeight=29.sp,color=Ink,maxLines=4)
            if(isSecureNewsUrl(displayed.imageUrl) && !imageFailed) AsyncImage(model=displayed.imageUrl,
                contentDescription=null,contentScale=ContentScale.Crop,
                modifier=Modifier.fillMaxWidth().aspectRatio(16f/9f).clip(RoundedCornerShape(9.dp)).background(LightSage).testTag("news_article_image")
                    .semantics { hideFromAccessibility() },
                onError={imageFailed=true})
            if(displayed.summary.isNotBlank()) Text(displayed.summary,color=Muted,fontFamily=FontFamily.Serif,
                fontSize=15.sp,lineHeight=21.sp,maxLines=3,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(R.string.read_full_story),color=OliveGreen,style=MaterialTheme.typography.labelMedium)
                Spacer(Modifier.width(6.dp));Icon(Icons.AutoMirrored.Outlined.ArrowForward,null,Modifier.size(18.dp),tint=OliveGreen)
            }
        }
    }
}
