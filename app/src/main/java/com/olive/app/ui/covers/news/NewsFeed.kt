package com.olive.app.ui.covers.news

import com.olive.app.ui.*
import com.olive.app.ui.covers.shared.CoverAccountPanel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.olive.app.data.NewsArticle
import com.olive.app.data.NewsScope

// A feed snapshot keeps rendering independent of location lookup, cache loading and tab history.
internal data class NewsFeedState(
    val tab:String,
    val scope:NewsScope,
    val query:String,
    val articles:List<NewsArticle>,
    val saved:List<NewsArticle>,
    val loading:Boolean,
    val newsError:Boolean,
    val currentPlace:String,
) {
    val visibleArticles get()=(if(tab=="Saved") saved else articles).filter { article ->
        query.isBlank() || article.title.contains(query,true) || article.source.contains(query,true)
    }
}

internal data class NewsFeedActions(
    val search:(String)->Unit,
    val changeScope:(NewsScope)->Unit,
    val editLocation:()->Unit,
    val refresh:()->Unit,
    val openArticle:(NewsArticle)->Unit,
)

@Composable internal fun NewsFeed(session:SessionViewModel,feed:NewsFeedState,actions:NewsFeedActions,modifier:Modifier=Modifier) {
    with(feed) {
        PullToRefreshBox(isRefreshing=loading,onRefresh=actions.refresh,modifier=modifier) {
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
                                            actions.search(value)
                                        },placeholder={LocalizedText(if(tab=="Saved") "Search saved stories" else "Search headlines")},leadingIcon={Icon(Icons.Outlined.Search,null)},
                                            trailingIcon=if(query.isNotBlank()) {
                                                { IconButton(onClick={actions.search("")}) { Icon(Icons.Outlined.Close,"Clear search") } }
                                            } else null,
                                            singleLine=true,shape=RoundedCornerShape(18.dp),modifier=Modifier.fillMaxWidth().testTag("news_search"))
                                    }
                                    if(tab=="Today") item {
                                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                            NewsScopeButton("Local",Icons.Outlined.LocationOn,scope==NewsScope.LOCAL,Modifier.weight(1f)) { actions.changeScope(NewsScope.LOCAL) }
                                            NewsScopeButton("Global",Icons.Outlined.Public,scope==NewsScope.GLOBAL,Modifier.weight(1f)) { actions.changeScope(NewsScope.GLOBAL) }
                                        }
                                    }
                                    if(tab=="Today" && scope==NewsScope.LOCAL) item {
                                        Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth()) {
                                            Row(Modifier.padding(horizontal=14.dp,vertical=11.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(9.dp)) {
                                                Icon(Icons.Outlined.NearMe,null,tint=OliveGreen,modifier=Modifier.size(18.dp))
                                                LocalizedText("News near $currentPlace",style=MaterialTheme.typography.bodyMedium,color=OliveGreen,modifier=Modifier.weight(1f))
                                                IconButton(onClick={actions.editLocation()},modifier=Modifier.size(36.dp).testTag("change_news_location")) {
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
                                                    OutlinedButton(onClick=actions.refresh) { LocalizedText("Retry") }
                                                }
                                            }
                                        }
                                    }
                                    val visible=visibleArticles
                                    if(visible.isEmpty() && !loading && !newsError) item { NewsEmptyState(if(tab=="Saved") "No saved stories yet." else "No stories found.") }
                                    items(visible,key={it.link}) { article ->
                                        NewsArticleCard(article,session.language,saved.any { it.link==article.link },
                                            onOpen={actions.openArticle(article)},onBookmark={session.toggleNewsBookmark(article)})
                                    }
                            }
                        }
                    }
                }
    }
}
