package com.olive.app.ui
import com.olive.app.R
import androidx.compose.ui.res.stringResource

import android.content.Intent
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
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
import androidx.core.net.toUri
import com.olive.app.data.CoverLocation
import com.olive.app.data.GoogleNewsRepository
import com.olive.app.data.NewsArticle
import com.olive.app.data.NewsFeedCacheStore
import com.olive.app.data.NewsScope
import com.olive.app.data.localPublished
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
// Daily Digest refreshes local/global headlines while bookmarks stay saved separately.
// UI sizing/color: the feed uses Cream bars, 20dp side padding, 14dp gaps, and the shared Olive typography/colors.
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NewsCover(session: SessionViewModel, locationPermissionVersion: Int=0, interactive: Boolean=true) {
    val context=LocalContext.current
    // HARDCODED DAILY DIGEST NOTIFICATION SPOT: edit AppNotifications.kt, then copy this into the event that should send it.
    // com.olive.app.notification.AppNotifications.showDailyDigest(context)
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
        runCatching { GoogleNewsRepository().latest(session.language,scope,currentPlace) }
            .onSuccess {
                articles=it;newsError=it.isEmpty()
                withContext(Dispatchers.IO) { feedCache.save(session.language,scope,currentPlace,it) }
            }
            .onFailure { newsError=articles.isEmpty() }
        loading=false
    }
    if(locationEditorOpen) AlertDialog(
        onDismissRequest={locationEditorOpen=false},
        title={Text(stringResource(R.string.change_local_news_location))},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(locationDraft,{ value ->
                if(!session.interceptSearch(value)) locationDraft=value.take(120) else locationDraft=""
            },label={Text(stringResource(R.string.city_or_region))},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("news_location_input"))
            Text("This changes the local news search on this device.",color=Muted,style=MaterialTheme.typography.bodySmall)
        }},
        confirmButton={TextButton(onClick={
            customPlace=locationDraft.trim();currentPlace=customPlace
            locationPreferences.edit().putString(locationPreferenceKey,customPlace).apply()
            locationEditorOpen=false;refresh++
        },enabled=locationDraft.isNotBlank()) { Text(stringResource(R.string.save)) }},
        dismissButton={Row {
            TextButton(onClick={
                customPlace="";currentPlace=fallbackPlace
                locationPreferences.edit().remove(locationPreferenceKey).apply()
                locationEditorOpen=false;refresh++
            }) { Text(stringResource(R.string.use_device_location)) }
            TextButton(onClick={locationEditorOpen=false}) { Text(stringResource(R.string.cancel)) }
        }},
    )
    Scaffold(containerColor=Cream,topBar={
        TopAppBar(
            title={Column {
                // Text sizing note: 27sp is the news masthead. Keep the edition label on its own line for longer translations.
                Text("Daily Digest",fontFamily=FontFamily.Serif,fontSize=27.sp,fontWeight=FontWeight.Bold,color=OliveGreen)
                if(tab=="Today" && selected==null) Text(if(scope==NewsScope.LOCAL) "Local edition" else "Global edition",style=MaterialTheme.typography.labelSmall,color=Muted)
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
                    label={Text(choice)})
            }
        }
    }) { padding ->
        // Pulling from the very top checks the selected feed for newer stories.
        PullToRefreshBox(isRefreshing=loading,onRefresh={refresh++},modifier=Modifier.fillMaxSize().padding(padding)) {
        val listState=rememberLazyListState()
        LazyColumn(Modifier.fillMaxSize().oliveScrollbar(listState).testTag("news_cover"),state=listState,contentPadding=PaddingValues(start=20.dp,end=20.dp,top=12.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            when(tab) {
                "Account" -> {
                    item {
                        Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
                            Text(stringResource(R.string.account),style=MaterialTheme.typography.headlineMedium,fontFamily=FontFamily.Serif)
                            Text(stringResource(R.string.manage_the_profile_details_shown_in_daily_digest),color=Muted)
                        }
                    }
                    item { Card(colors=CardDefaults.cardColors(containerColor=Paper),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outline),shape=RoundedCornerShape(20.dp)) {
                        Box(Modifier.padding(18.dp)) { CoverAccountPanel(session) }
                    } }
                }
                else -> {
                    if(selected != null) {
                        val article=selected!!
                        item { NewsArticleDetail(article,session.language,saved.any { it.link==article.link },
                            onBack={selected=null},onOpen={context.startActivity(Intent(Intent.ACTION_VIEW,article.link.toUri()))},
                            onBookmark={session.toggleNewsBookmark(article)}) }
                    } else {
                        item {
                            OutlinedTextField(query,{ value ->
                                if(!session.interceptSearch(value)) query=value
                                else query=""
                            },placeholder={Text(if(tab=="Saved") "Search saved stories" else "Search headlines")},leadingIcon={Icon(Icons.Outlined.Search,null)},
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
                                    Text("News near $currentPlace",style=MaterialTheme.typography.bodyMedium,color=OliveGreen,modifier=Modifier.weight(1f))
                                    IconButton(onClick={locationDraft=currentPlace;locationEditorOpen=true},modifier=Modifier.size(36.dp).testTag("change_news_location")) {
                                        Icon(Icons.Outlined.Edit,"Change local news location",tint=OliveGreen,modifier=Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        item { Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                            Text(if(tab=="Saved") "Saved stories" else "Latest stories",style=MaterialTheme.typography.titleLarge,fontFamily=FontFamily.Serif,modifier=Modifier.weight(1f))
                            if(tab=="Saved" && saved.isNotEmpty()) Text(saved.size.toString(),color=Muted)
                        } }
                        if(tab=="Today") {
                            if(loading && articles.isEmpty()) item { NewsLoadingState() }
                            if(newsError) item {
                                Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant),shape=RoundedCornerShape(18.dp)) {
                                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Outlined.CloudOff,null,tint=Muted)
                                        Text(stringResource(R.string.live_news_is_unavailable_check_your_connection_and_try_again))
                                        OutlinedButton(onClick={refresh++}) { Text(stringResource(R.string.retry)) }
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
        Icon(icon,null,Modifier.size(18.dp));Spacer(Modifier.width(7.dp));Text(label)
    } else OutlinedButton(onClick=onClick,modifier=modifier.height(48.dp),shape=RoundedCornerShape(15.dp)) {
        Icon(icon,null,Modifier.size(18.dp));Spacer(Modifier.width(7.dp));Text(label)
    }
}

// UI sizing/color: story cards use Paper, a 1dp outline, 20dp corners, 18dp padding, and explicit editorial title sizing.
@Composable private fun NewsArticleCard(article: NewsArticle, language: String, saved: Boolean, onOpen: ()->Unit, onBookmark: ()->Unit) {
    Card(onClick=onOpen,modifier=Modifier.animateContentSize().fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=Paper),
        border=BorderStroke(1.dp,MaterialTheme.colorScheme.outline),shape=RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(start=18.dp,end=10.dp,top=16.dp,bottom=14.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(8.dp)) {
                    Text(article.source.uppercase(),color=OliveGreen,style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(horizontal=8.dp,vertical=5.dp),maxLines=1)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick=onBookmark) { Icon(if(saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,if(saved) "Remove bookmark" else "Save article",tint=OliveGreen) }
            }
            // Card titles use 22/27sp and three lines. Raise maxLines before shrinking text when a language needs more room.
            Text(article.title,fontFamily=FontFamily.Serif,fontSize=22.sp,lineHeight=27.sp,fontWeight=FontWeight.SemiBold,maxLines=3)
            if(article.summary.isNotBlank()) Text(article.summary,color=Muted,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Row(verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Outlined.Schedule,null,Modifier.size(15.dp),tint=Muted)
                Spacer(Modifier.width(5.dp))
                Text(article.localPublished(language),color=Muted,style=MaterialTheme.typography.bodySmall,modifier=Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Outlined.ArrowForward,null,Modifier.size(18.dp),tint=OliveGreen)
            }
        }
    }
}

// UI sizing/text: detail content uses 16dp gaps, 31/37sp titles, 26sp body line height, and OliveGreen source labels.
@Composable private fun NewsArticleDetail(article: NewsArticle, language: String, saved: Boolean,
    onBack: ()->Unit, onOpen: ()->Unit, onBookmark: ()->Unit) {
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        TextButton(onClick=onBack,contentPadding=PaddingValues(horizontal=0.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack,null);Spacer(Modifier.width(5.dp));Text(stringResource(R.string.back_to_stories))
        }
        Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(8.dp),modifier=Modifier.align(Alignment.Start)) {
            Text(article.source.uppercase(),color=OliveGreen,style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(horizontal=9.dp,vertical=6.dp))
        }
        // Full article titles can wrap freely; change 31sp and 37sp together to preserve readable line spacing.
        Text(article.title,fontFamily=FontFamily.Serif,fontSize=31.sp,lineHeight=37.sp,fontWeight=FontWeight.SemiBold)
        Row(verticalAlignment=Alignment.CenterVertically) {
            Icon(Icons.Outlined.Schedule,null,Modifier.size(16.dp),tint=Muted);Spacer(Modifier.width(6.dp))
            Text(article.localPublished(language),color=Muted)
        }
        HorizontalDivider()
        Text(article.summary,style=MaterialTheme.typography.bodyLarge,lineHeight=26.sp)
        Button(onClick=onOpen,modifier=Modifier.fillMaxWidth()) { Icon(Icons.AutoMirrored.Outlined.OpenInNew,null);Spacer(Modifier.width(8.dp));Text(stringResource(R.string.read_full_story)) }
        OutlinedButton(onClick=onBookmark,modifier=Modifier.fillMaxWidth()) {
            Icon(if(saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,null);Spacer(Modifier.width(8.dp))
            Text(if(saved) "Remove bookmark" else "Save article")
        }
    }
}

// UI sizing/color: loading cards mirror article cards with Paper, 20dp corners, 18dp padding, and theme placeholder colors.
@Composable private fun NewsLoadingState() {
    Column(verticalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.fillMaxWidth()) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        repeat(2) { Card(colors=CardDefaults.cardColors(containerColor=Paper),border=BorderStroke(1.dp,MaterialTheme.colorScheme.outline),shape=RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(90.dp).height(18.dp).background(MaterialTheme.colorScheme.primaryContainer,RoundedCornerShape(6.dp)))
                Box(Modifier.fillMaxWidth().height(24.dp).background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(6.dp)))
                Box(Modifier.fillMaxWidth(.7f).height(18.dp).background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(6.dp)))
            }
        } }
    }
}

// UI sizing/color: empty news states use 38dp vertical space, a 42dp Muted icon, and centered text.
@Composable private fun NewsEmptyState(message: String) {
    Column(Modifier.fillMaxWidth().padding(vertical=38.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Icon(Icons.AutoMirrored.Outlined.Article,null,Modifier.size(42.dp),tint=Muted)
        Text(message,color=Muted,textAlign=TextAlign.Center)
    }
}
