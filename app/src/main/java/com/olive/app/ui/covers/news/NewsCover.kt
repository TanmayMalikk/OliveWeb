package com.olive.app.ui.covers.news

import com.olive.app.ui.*
import com.olive.app.ui.covers.shared.CoverAccountPanel

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.olive.app.data.CoverLocation
import com.olive.app.data.GoogleNewsRepository
import com.olive.app.data.NewsArticle
import com.olive.app.data.NewsFeedCacheStore
import com.olive.app.data.NewsScope
import com.olive.app.data.localPublishedDate
import com.olive.app.data.NewsMetadataRepository
import com.olive.app.data.fallbackNewsTopic
import com.olive.app.data.isSecureNewsUrl
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
// Daily Digest refreshes local/global headlines while bookmarks stay saved separately.
// UI sizing/color: the feed uses Cream bars, 20dp side padding, 14dp gaps, and the shared Olive typography/colors.
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NewsCover(session: SessionViewModel, locationPermissionVersion: Int=0, interactive: Boolean=true) {
    val context=LocalContext.current
    var tab by remember { mutableStateOf("Today") }
    var scope by remember { mutableStateOf(NewsScope.LOCAL) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<NewsArticle?>(null) }
    var articles by remember { mutableStateOf<List<NewsArticle>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var newsError by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val tabHistory=remember { mutableListOf<String>() }
    val saved=session.savedNews
    val fallbackPlace=session.deliveryAddress.generalLocation.ifBlank { "Canada" }
    val locationPreferenceKey="custom_location_${session.coverAccountId ?: "guest"}"
    val locationPreferences=remember(context) { context.getSharedPreferences("daily_digest_preferences",android.content.Context.MODE_PRIVATE) }
    val feedCache=remember(context) { NewsFeedCacheStore(context.applicationContext) }
    var customPlace by rememberSaveable(locationPreferenceKey) { mutableStateOf(locationPreferences.getString(locationPreferenceKey,"").orEmpty()) }
    var currentPlace by rememberSaveable(locationPreferenceKey) { mutableStateOf(customPlace.ifBlank { fallbackPlace }) }
    var locationEditorOpen by remember { mutableStateOf(false) }
    var locationDraft by remember { mutableStateOf("") }
    // Back closes an article first, then returns through the previous news tabs.
    BackHandler(interactive) {
        when {
            selected!=null -> selected=null
            tabHistory.isNotEmpty() -> { selected=null;tab=tabHistory.removeAt(tabHistory.lastIndex) }
            tab!="Today" -> { selected=null;tab="Today" }
        }
    }
    LaunchedEffect(session.language,scope,refresh,session.coverReset,fallbackPlace,locationPermissionVersion,customPlace) {
        val cached=withContext(Dispatchers.IO) { feedCache.load(session.language,scope) }
        if(cached!=null) {
            articles=cached.articles
            if(scope==NewsScope.LOCAL && customPlace.isBlank() && cached.place.isNotBlank()) currentPlace=cached.place
        }
        loading=articles.isEmpty();newsError=false
        if(scope==NewsScope.LOCAL) currentPlace=customPlace.ifBlank { CoverLocation.city(context,fallbackPlace) }
        try {
            GoogleNewsRepository().latest(session.language,scope,currentPlace).also {
                articles=it;newsError=it.isEmpty()
                withContext(Dispatchers.IO) { feedCache.save(session.language,scope,currentPlace,it) }
            }
        } catch(e:CancellationException) { throw e } catch(_:Exception) {
            newsError=articles.isEmpty()
        }
        loading=false
    }
    if(locationEditorOpen) AlertDialog(
        onDismissRequest={locationEditorOpen=false},
        title={LocalizedText("Change local news location")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(locationDraft,{ value ->
                locationDraft=if(!session.interceptSearch(value)) value.take(120) else ""
            },label={LocalizedText("City or region")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("news_location_input"))
            LocalizedText("This changes the local news search on this device.",color=Muted,style=MaterialTheme.typography.bodySmall)
        }},
        confirmButton={TextButton(onClick={
            customPlace=locationDraft.trim();currentPlace=customPlace
            locationPreferences.edit { putString(locationPreferenceKey,customPlace) }
            locationEditorOpen=false;refresh++
        },enabled=locationDraft.isNotBlank()) { LocalizedText("Save") }},
        dismissButton={Row {
            TextButton(onClick={
                customPlace="";currentPlace=fallbackPlace
                locationPreferences.edit { remove(locationPreferenceKey) }
                locationEditorOpen=false;refresh++
            }) { LocalizedText("Use device location") }
            TextButton(onClick={locationEditorOpen=false}) { LocalizedText("Cancel") }
        }},
    )
    Scaffold(containerColor=Cream,topBar={
        TopAppBar(
            title={Column {
                // Text sizing note: 27sp is the news masthead. Keep the edition label on its own line for longer translations.
                LocalizedText("Daily Digest",fontFamily=FontFamily.Serif,fontSize=27.sp,fontWeight=FontWeight.Bold,color=OliveGreen)
                if(tab=="Today" && selected==null) LocalizedText(if(scope==NewsScope.LOCAL) "Local edition" else "Global edition",style=MaterialTheme.typography.labelSmall,color=Muted)
            }},
            actions={if(tab=="Today" && selected==null) IconButton(onClick={refresh++},enabled=!loading) { Icon(Icons.Outlined.Refresh,"Refresh") }},
            colors=TopAppBarDefaults.topAppBarColors(containerColor=Cream),
        )
    },bottomBar={
        NavigationBar(containerColor=Cream,tonalElevation=0.dp) {
            listOf("Today","Saved","Account").forEach { choice ->
                NavigationBarItem(selected=tab==choice,onClick={
                    if(choice!=tab) tabHistory += tab
                    selected=null;tab=choice
                    if(choice=="Account") session.recordSettingsInteraction("Daily Digest","Opened account page")
                },
                    icon={Icon(when(choice) { "Today" -> Icons.AutoMirrored.Outlined.Article; "Saved" -> if(tab==choice) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder; else -> Icons.Outlined.PersonOutline },null)},
                    label={LocalizedText(choice)})
            }
        }
    }) { padding ->
        val article=selected
        if(article!=null && tab!="Account") {
            NewsArticleReader(article,saved.any { it.link==article.link },interactive,
                modifier=Modifier.fillMaxSize().padding(padding).testTag("news_cover"),
                onBack={selected=null},onBookmark={session.toggleNewsBookmark(article)})
        } else {
            // Pulling from the very top checks the selected feed for newer stories.
            PullToRefreshBox(isRefreshing=loading,onRefresh={refresh++},modifier=Modifier.fillMaxSize().padding(padding)) {
            val listState=rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState).testTag("news_cover"),state=listState,contentPadding=PaddingValues(start=20.dp,end=20.dp,top=12.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                when(tab) {
                    "Account" -> {
                        item {
                            Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                LocalizedText("Account",style=MaterialTheme.typography.headlineMedium,fontFamily=FontFamily.Serif)
                                LocalizedText("Manage the profile details shown in Daily Digest.",color=Muted)
                            }
                        }
                        item { Card(colors=CardDefaults.cardColors(containerColor=Paper),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outline),shape=RoundedCornerShape(20.dp)) {
                            Box(Modifier.padding(18.dp)) { CoverAccountPanel(session) }
                        } }
                    }
                    else -> {
                            item {
                                OutlinedTextField(query,{ value ->
                                    query=if(!session.interceptSearch(value)) value else ""
                                },placeholder={LocalizedText(if(tab=="Saved") "Search saved stories" else "Search headlines")},leadingIcon={Icon(Icons.Outlined.Search,null)},
                                    trailingIcon=if(query.isNotBlank()) {
                                        { IconButton(onClick={query=""}) { Icon(Icons.Outlined.Close,"Clear search") } }
                                    } else null,
                                    singleLine=true,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().testTag("news_search"))
                            }
                            if(tab=="Today") item {
                                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                    NewsScopeButton("Local",Icons.Outlined.LocationOn,scope==NewsScope.LOCAL,Modifier.weight(1f)) { scope=NewsScope.LOCAL }
                                    NewsScopeButton("Global",Icons.Outlined.Public,scope==NewsScope.GLOBAL,Modifier.weight(1f)) { scope=NewsScope.GLOBAL }
                                }
                            }
                            if(tab=="Today" && scope==NewsScope.LOCAL) item {
                                Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()) {
                                    Row(Modifier.padding(horizontal=14.dp,vertical=11.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                                        Icon(Icons.Outlined.NearMe,null,tint=OliveGreen,modifier=Modifier.size(18.dp))
                                        LocalizedText("News near $currentPlace",style=MaterialTheme.typography.bodyMedium,color=OliveGreen,modifier=Modifier.weight(1f))
                                        IconButton(onClick={locationDraft=currentPlace;locationEditorOpen=true},modifier=Modifier.size(36.dp).testTag("change_news_location")) {
                                            Icon(Icons.Outlined.Edit,"Change local news location",tint=OliveGreen,modifier=Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                            item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                LocalizedText(if(tab=="Saved") "Saved stories" else "Latest stories",style=MaterialTheme.typography.titleLarge,fontFamily=FontFamily.Serif,modifier=Modifier.weight(1f))
                                if(tab=="Saved" && saved.isNotEmpty()) LocalizedText(saved.size.toString(),color=Muted)
                            } }
                            if(tab=="Today") {
                                if(loading && articles.isEmpty()) item { NewsLoadingState() }
                                if(newsError) item {
                                    Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant),shape=RoundedCornerShape(18.dp)) {
                                        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                            Icon(Icons.Outlined.CloudOff,null,tint=Muted)
                                            LocalizedText("Live news is unavailable. Check your connection and try again.")
                                            OutlinedButton(onClick={refresh++}) { LocalizedText("Retry") }
                                        }
                                    }
                                }
                            }
                            val visible=(if(tab=="Saved") saved else articles).filter { article ->
                                query.isBlank() || article.title.contains(query,true) || article.source.contains(query,true)
                            }
                            if(visible.isEmpty() && !loading && !newsError) item { NewsEmptyState(if(tab=="Saved") "No saved stories yet." else "No stories found.") }
                            items(visible,key={it.link}) { article ->
                                NewsArticleCard(article,session.language,saved.any { it.link==article.link },
                                    onOpen={selected=article},onBookmark={session.toggleNewsBookmark(article)})
                            }
                    }
                }
            }
        }
        }
    }
}
// UI sizing: Local/Global buttons stay 48dp tall with 18dp icons and 15dp corners.
@Composable private fun NewsScopeButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean,
    modifier: Modifier=Modifier, onClick: ()->Unit) {
    if(selected) Button(onClick=onClick,modifier=modifier.height(48.dp),shape=RoundedCornerShape(15.dp)) {
        Icon(icon,null,Modifier.size(18.dp));Spacer(Modifier.width(7.dp));LocalizedText(label)
    } else OutlinedButton(onClick=onClick,modifier=modifier.height(48.dp),shape=RoundedCornerShape(15.dp)) {
        Icon(icon,null,Modifier.size(18.dp));Spacer(Modifier.width(7.dp));LocalizedText(label)
    }
}

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

// Loading placeholders use the same rounded cream card and olive rule as loaded stories.
@Composable private fun NewsLoadingState() {
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
@Composable private fun NewsEmptyState(message: String) {
    Column(Modifier.fillMaxWidth().padding(vertical=38.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Icon(Icons.AutoMirrored.Outlined.Article,null,Modifier.size(42.dp),tint=Muted)
        LocalizedText(message,color=Muted,textAlign=TextAlign.Center)
    }
}
