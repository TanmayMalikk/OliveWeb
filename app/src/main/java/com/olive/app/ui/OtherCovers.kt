package com.olive.app.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.core.net.toUri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.data.GoogleNewsRepository
import com.olive.app.data.NewsArticle
import com.olive.app.data.NewsScope
import com.olive.app.data.CoverLocation
import com.olive.app.data.localPublished

// Daily Digest refreshes local/global headlines while bookmarks stay saved separately.
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
    var currentPlace by remember { mutableStateOf(fallbackPlace) }
    // Back closes an article first, then returns through the previous news tabs.
    BackHandler(interactive) {
        when {
            selected!=null -> selected=null
            tabHistory.isNotEmpty() -> { selected=null;tab=tabHistory.removeAt(tabHistory.lastIndex) }
            tab!="Today" -> { selected=null;tab="Today" }
        }
    }
    LaunchedEffect(session.language,scope,refresh,session.coverReset,fallbackPlace,locationPermissionVersion) {
        loading=true;newsError=false
        if(scope==NewsScope.LOCAL) currentPlace=CoverLocation.city(context,fallbackPlace)
        runCatching { GoogleNewsRepository().latest(session.language,scope,currentPlace) }
            .onSuccess { articles=it;newsError=it.isEmpty() }
            .onFailure { newsError=true }
        loading=false
    }
    Scaffold(bottomBar={
        NavigationBar {
            listOf("Today","Saved","Account").forEach { choice ->
                NavigationBarItem(selected=tab==choice,onClick={
                    if(choice!=tab) tabHistory += tab
                    selected=null;tab=choice
                    if(choice=="Account") session.recordSettingsInteraction("Daily Digest","Opened account page")
                },
                    icon={Icon(when(choice) { "Today" -> Icons.AutoMirrored.Outlined.Article; "Saved" -> Icons.Outlined.BookmarkBorder; else -> Icons.Outlined.PersonOutline },null)},
                    label={LocalizedText(choice)})
            }
        }
    }) { padding ->
        // Pulling from the very top checks the selected feed for newer stories.
        PullToRefreshBox(isRefreshing=loading,onRefresh={refresh++},modifier=Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(Modifier.fillMaxSize().testTag("news_cover"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { LocalizedText("Daily Digest",fontFamily=FontFamily.Serif,fontSize=32.sp,fontWeight=FontWeight.Bold,color=Color(0xFF16324F)) }
            when(tab) {
                "Account" -> {
                    item { LocalizedText("Account",style=MaterialTheme.typography.headlineSmall) }
                    item { CoverAccountPanel(session) }
                }
                else -> {
                    if(selected != null) {
                        val article=selected!!
                        item { TextButton(onClick={selected=null}) { LocalizedText("← Back") } }
                        item { LocalizedText(article.source.uppercase(),color=Color(0xFF2A6593)) }
                        item { LocalizedText(article.title,fontFamily=FontFamily.Serif,fontSize=30.sp) }
                        item { LocalizedText(article.summary,style=MaterialTheme.typography.bodyLarge) }
                        item { LocalizedText(article.localPublished(session.language),color=Color(0xFF2A6593)) }
                        item { Button(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,article.link.toUri()))}) { LocalizedText("Read full story") } }
                        item { OutlinedButton(onClick={session.toggleNewsBookmark(article)}) { LocalizedText(if(saved.any { it.link==article.link }) "Remove bookmark" else "Save article") } }
                    } else {
                        if(tab=="Today") item {
                            OutlinedTextField(query,{ value ->
                                if(!session.interceptSearch(value)) query=value
                                else query=""
                            },label={LocalizedText("Search stories")},leadingIcon={Icon(Icons.Outlined.Search,null)},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("news_search"))
                        }
                        if(tab=="Today") item {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected=scope==NewsScope.LOCAL,onClick={scope=NewsScope.LOCAL},label={LocalizedText("Local")})
                                FilterChip(selected=scope==NewsScope.GLOBAL,onClick={scope=NewsScope.GLOBAL},label={LocalizedText("Global")})
                            }
                        }
                        if(tab=="Today" && scope==NewsScope.LOCAL) item {
                            LocalizedText("News near $currentPlace",style=MaterialTheme.typography.bodySmall,color=Color(0xFF2A6593))
                        }
                        if(tab=="Today") {
                            if(loading) item { CircularProgressIndicator() }
                            if(newsError) item {
                                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    LocalizedText("Live news is unavailable. Check your connection and try again.")
                                    OutlinedButton(onClick={refresh++}) { LocalizedText("Retry") }
                                }
                            }
                        }
                        val visible=(if(tab=="Saved") saved else articles).filter { article ->
                            query.isBlank() || article.title.contains(query,true) || article.source.contains(query,true)
                        }
                        if(visible.isEmpty() && !loading && !newsError) item { LocalizedText(if(tab=="Saved") "No saved stories yet." else "No stories found.") }
                        items(visible,key={it.link}) { article ->
                            Card(onClick={selected=article},modifier=Modifier.animateItem().fillMaxWidth().animateContentSize(),shape=RoundedCornerShape(16.dp)) {
                                Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    LocalizedText(article.source.uppercase(),color=Color(0xFF2A6593),style=MaterialTheme.typography.labelSmall)
                                    LocalizedText(article.title,fontFamily=FontFamily.Serif,fontSize=23.sp)
                                    LocalizedText(article.summary)
                                    LocalizedText(article.localPublished(session.language),color=Color(0xFF2A6593),style=MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

// Keep the calculator looking like a calculator, with no account controls on the cover.
@Composable fun CalculatorCover(session: SessionViewModel, interactive: Boolean=true) {
    val input=remember { CalculatorInput() }
    LaunchedEffect(session.coverReset) { input.reset() }
    val background=Color(0xFF101820)
    val number=Color(0xFF27323B)
    val utility=Color(0xFF34434D)
    val accent=Color(0xFF6EC5B0)
    // The calculator has one page, so Back stays here instead of exposing the phone home screen.
    BackHandler(interactive) { }
    Column(Modifier.fillMaxSize().background(background).safeDrawingPadding().padding(horizontal=20.dp,vertical=14.dp).testTag("calculator_cover"),
        verticalArrangement=Arrangement.spacedBy(12.dp)) {
        LocalizedText("Simple Calculator",color=Color(0xFFF4F7F5),fontSize=19.sp,fontWeight=FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        LocalizedText(input.expression,modifier=Modifier.fillMaxWidth(),color=Color(0xFF91A5A6),
            fontSize=18.sp,textAlign=TextAlign.End,maxLines=1)
        LocalizedText(input.display,modifier=Modifier.fillMaxWidth().testTag("calculator_display"),color=Color.White,
            fontSize=if(input.display.length>12) 31.sp else 48.sp,fontFamily=FontFamily.Monospace,
            textAlign=TextAlign.End,maxLines=1)
        Spacer(Modifier.height(10.dp))
        listOf(listOf("AC","±","%","÷"),listOf("7","8","9","×"),listOf("4","5","6","−"),
            listOf("1","2","3","+"),listOf("⌫","0",".","=")).forEach { row ->
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                row.forEach { key ->
                    val operator=key in setOf("+","−","×","÷")
                    val buttonColor=when {
                        key=="=" -> accent
                        operator -> utility
                        key in setOf("AC","±","%","⌫") -> utility
                        else -> number
                    }
                    Button(onClick={input.press(key)?.let(session::submitCalculatorPin)},enabled=!session.busy,
                        modifier=Modifier.weight(1f).height(68.dp).testTag("calc_key_${when(key) { "=" -> "equals"; "AC" -> "clear"; "⌫" -> "backspace"; else -> key }}"),
                        shape=RoundedCornerShape(20.dp),contentPadding=PaddingValues(0.dp),
                        colors=ButtonDefaults.buttonColors(containerColor=buttonColor,contentColor=if(key=="=") background else if(operator) accent else Color.White)) {
                        LocalizedText(key,fontSize=if(key=="AC") 18.sp else 25.sp,fontWeight=FontWeight.Medium)
                    }
                }
            }
        }
    }
}

// Cover account settings expose ordinary profile details and language, like Olive's account tab.
@Composable private fun CoverAccountPanel(session: SessionViewModel) {
    var fullName by remember(session.coverFullName) { mutableStateOf(session.coverFullName) }
    var language by remember(session.language) { mutableStateOf(session.language) }
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        LocalizedText("@${session.coverUsername}")
        OutlinedTextField(fullName,{fullName=it.take(120)},label={LocalizedText("Full name")},singleLine=true,
            modifier=Modifier.fillMaxWidth().onFocusChanged { if(it.isFocused) session.recordSettingsInteraction("Daily Digest","Focused full name") })
        Row(verticalAlignment=Alignment.CenterVertically) {
            LocalizedText("App language",modifier=Modifier.weight(1f))
            TextButton(onClick={session.recordSettingsInteraction("Daily Digest","Selected language");language=if(language=="French") "English" else "French"}) {
                LocalizedText(if(language=="French") "Français" else "English")
            }
        }
        Button(onClick={session.saveCoverSettings(fullName,language)},enabled=!session.busy && fullName.isNotBlank(),modifier=Modifier.fillMaxWidth()) {
            LocalizedText("Save account settings")
        }
        session.message?.let { LocalizedText(it) }
    }
}
