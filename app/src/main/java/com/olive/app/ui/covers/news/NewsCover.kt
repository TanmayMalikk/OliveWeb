package com.olive.app.ui.covers.news
import com.olive.app.ui.rememberResourceText

import com.olive.app.ui.LocalizedIcon as Icon
import androidx.compose.ui.res.stringResource
import com.olive.app.R
import com.olive.app.ui.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import com.olive.app.data.CoverLocation
import com.olive.app.data.GoogleNewsRepository
import com.olive.app.data.NewsArticle
import com.olive.app.data.NewsFeedCacheStore
import com.olive.app.data.NewsScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

// Daily Digest refreshes local/global headlines while bookmarks stay saved separately.
// UI sizing/color: the feed uses Cream bars, 20dp side padding, 14dp gaps, and the shared Olive typography/colors.
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NewsCover(session: SessionViewModel, locationPermissionVersion: Int=0, interactive: Boolean=true) {
    val uiText=rememberResourceText()
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
        title={Text(stringResource(R.string.change_local_news_location))},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(locationDraft,{ value ->
                locationDraft=if(!session.interceptSearch(value)) value.take(120) else ""
            },label={Text(stringResource(R.string.city_or_region))},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("news_location_input").keepVisibleWhileTyping())
            Text(uiText(R.string.ui_this_changes_the_local_news_search_on_this_device),color=Muted,style=MaterialTheme.typography.bodySmall)
        }},
        confirmButton={TextButton(onClick={
            customPlace=locationDraft.trim();currentPlace=customPlace
            locationPreferences.edit { putString(locationPreferenceKey,customPlace) }
            locationEditorOpen=false;refresh++
        },enabled=locationDraft.isNotBlank()) { Text(stringResource(R.string.save)) }},
        dismissButton={Row {
            TextButton(onClick={
                customPlace="";currentPlace=fallbackPlace
                locationPreferences.edit { remove(locationPreferenceKey) }
                locationEditorOpen=false;refresh++
            }) { Text(stringResource(R.string.use_device_location)) }
            TextButton(onClick={locationEditorOpen=false}) { Text(stringResource(R.string.cancel)) }
        }},
    )
    Scaffold(containerColor=Cream,topBar={
        TopAppBar(
            title={Column {
                // Text sizing note: 27sp is the news masthead. Keep the edition label on its own line for longer translations.
                Text(uiText(R.string.ui_daily_digest),fontFamily=FontFamily.Serif,fontSize=27.sp,fontWeight=FontWeight.Bold,color=OliveGreen)
                if(tab=="Today" && selected==null) Text(if(scope==NewsScope.LOCAL) uiText(R.string.local_edition) else uiText(R.string.global_edition),style=MaterialTheme.typography.labelSmall,color=Muted)
            }},
            actions={if(tab=="Today" && selected==null) IconButton(onClick={refresh++},enabled=!loading) { Icon(Icons.Outlined.Refresh,uiText(R.string.refresh)) }},
            colors=TopAppBarDefaults.topAppBarColors(containerColor=Cream),
        )
    },bottomBar={
        NavigationBar(modifier=Modifier.testTag("cover_navigation"),containerColor=Cream,tonalElevation=0.dp) {
            listOf("Today","Saved","Account").forEach { choice ->
                NavigationBarItem(selected=tab==choice,onClick={
                    if(choice!=tab) tabHistory += tab
                    selected=null;tab=choice
                    if(choice=="Account") session.recordSettingsInteraction("Daily Digest","Opened account page")
                },
                    icon={Icon(when(choice) { "Today" -> Icons.AutoMirrored.Outlined.Article; "Saved" -> if(tab==choice) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder; else -> Icons.Outlined.PersonOutline },null)},
                    label={Text(when(choice) { "Today" -> uiText(R.string.today); "Saved" -> uiText(R.string.saved); else -> uiText(R.string.account) })})
            }
        }
    }) { padding ->
        val article=selected
        if(article!=null && tab!="Account") {
            NewsArticleReader(article,saved.any { it.link==article.link },interactive,
                modifier=Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().testTag("news_cover"),
                onBack={selected=null},onBookmark={session.toggleNewsBookmark(article)})
        } else {
            NewsFeed(session,
                NewsFeedState(tab,scope,query,articles,saved,loading,newsError,currentPlace),
                NewsFeedActions(
                    search={ value -> query=if(!session.interceptSearch(value)) value else "" },
                    changeScope={scope=it},
                    editLocation={locationDraft=currentPlace;locationEditorOpen=true},
                    refresh={refresh++},
                    openArticle={selected=it},
                ),Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding())
        }
    }
}
