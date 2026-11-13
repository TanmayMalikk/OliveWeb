// News cover users: lists headlines and opens saved articles.
package com.olive.app.ui.covers.news

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.olive.app.data.news.NewsArticle
import com.olive.app.data.news.NewsScope
import com.olive.app.R
import com.olive.app.ui.covers.CoverAccountPanel
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.shared.rememberResourceText

// A feed snapshot keeps rendering independent of location lookup, cache loading and tab history.
internal data class NewsFeedState(
    val tab: String,
    val scope: NewsScope,
    val query: String,
    val articles: List<NewsArticle>,
    val saved: List<NewsArticle>,
    val loading: Boolean,
    val newsError: Boolean,
    val currentPlace: String,
) {
    val visibleArticles
        get() = (if (tab == "Saved") saved else articles).filter { article ->
            query.isBlank() || article.title.contains(query, true) || article.source.contains(
                query,
                true
            )
        }
}

internal data class NewsFeedActions(
    val search: (String) -> Unit,
    val changeScope: (NewsScope) -> Unit,
    val editLocation: () -> Unit,
    val refresh: () -> Unit,
    val openArticle: (NewsArticle) -> Unit,
)
// Shows loading, error, and article-list states for the Daily Digest.
@Composable
internal fun NewsFeed(
    session: SessionViewModel,
    feed: NewsFeedState,
    actions: NewsFeedActions,
    modifier: Modifier = Modifier
) {
    val uiText = rememberResourceText()
    with(feed) {
        PullToRefreshBox(isRefreshing = loading, onRefresh = actions.refresh, modifier = modifier) {
            val listState = rememberLazyListState()
            LazyColumn(
                Modifier.fillMaxSize().oliveScrollbar(listState).testTag("news_cover"),
                state = listState,
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 12.dp,
                    bottom = 28.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Shows loading, articles, or an error for the current news request.
                when (tab) {
                    "Account" -> {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(
                                    stringResource(R.string.account),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontFamily = FontFamily.Serif,
                                    modifier = Modifier.semantics { heading() })
                                Text(
                                    stringResource(R.string.manage_the_profile_details_shown_in_daily_digest),
                                    color = Muted
                                )
                            }
                        }
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = Paper),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Box(Modifier.padding(18.dp)) { CoverAccountPanel(session) }
                            }
                        }
                    }

                    else -> {
                        item {
                            OutlinedTextField(
                                query,
                                { value ->
                                    actions.search(value)
                                },
                                placeholder = {
                                    Text(
                                        if (tab == "Saved") uiText(R.string.search_saved_stories) else uiText(
                                            R.string.search_headlines
                                        )
                                    )
                                },
                                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                trailingIcon = if (query.isNotBlank()) {
                                    {
                                        IconButton(onClick = { actions.search("") }) {
                                            Icon(
                                                Icons.Outlined.Close,
                                                uiText(R.string.clear_search)
                                            )
                                        }
                                    }
                                } else null,
                                singleLine = true,
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier.fillMaxWidth().testTag("news_search")
                                    .keepVisibleWhileTyping())
                        }
                        // Skip the next action unless tab is today.
                        if (tab == "Today") item {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                NewsScopeButton(
                                    uiText(R.string.local),
                                    Icons.Outlined.LocationOn,
                                    scope == NewsScope.LOCAL,
                                    Modifier.weight(1f)
                                ) { actions.changeScope(NewsScope.LOCAL) }
                                NewsScopeButton(
                                    uiText(R.string.global),
                                    Icons.Outlined.Public,
                                    scope == NewsScope.GLOBAL,
                                    Modifier.weight(1f)
                                ) { actions.changeScope(NewsScope.GLOBAL) }
                            }
                        }
                        // Local news is available only from the Today tab.
                        if (tab == "Today" && scope == NewsScope.LOCAL) item {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                                    .semantics(mergeDescendants = true) {}) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.NearMe,
                                        null,
                                        tint = OliveGreen,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        uiText(R.string.news_near_location, currentPlace),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = OliveGreen,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { actions.editLocation() },
                                        modifier = Modifier.size(36.dp)
                                            .testTag("change_news_location")
                                    ) {
                                        Icon(
                                            Icons.Outlined.Edit,
                                            uiText(R.string.change_local_news_location),
                                            tint = OliveGreen,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (tab == "Saved") uiText(R.string.saved_stories) else uiText(
                                        R.string.latest_stories
                                    ),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontFamily = FontFamily.Serif,
                                    modifier = (Modifier.weight(1f)).semantics { heading() })
                                if (tab == "Saved" && saved.isNotEmpty()) Text(
                                    saved.size.toString(),
                                    color = Muted
                                )
                            }
                        }
                        if (tab == "Today") {
                            // Show a loading row while the first news stories are being fetched.
                            if (loading && articles.isEmpty()) item { NewsLoadingState() }
                            if (newsError) item {
                                Card(
                                    modifier = Modifier.semantics(mergeDescendants = true) {},
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Column(
                                        Modifier.padding(18.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(Icons.Outlined.CloudOff, null, tint = Muted)
                                        Text(stringResource(R.string.live_news_is_unavailable_check_your_connection_and_try_again))
                                        OutlinedButton(onClick = actions.refresh) {
                                            Text(
                                                stringResource(R.string.retry)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        val visible = visibleArticles
                        // Show the empty-news message only after loading ends without an error.
                        if (visible.isEmpty() && !loading && !newsError) item {
                            NewsEmptyState(
                                if (tab == "Saved") uiText(
                                    R.string.no_saved_stories_yet
                                ) else uiText(R.string.no_stories_found)
                            )
                        }
                        items(visible, key = { it.link }) { article ->
                            NewsArticleCard(
                                article,
                                session.language,
                                saved.any { it.link == article.link },
                                onOpen = { actions.openArticle(article) },
                                onBookmark = { session.toggleNewsBookmark(article) })
                        }
                    }
                }
            }
        }
    }
}

// UI sizing: Local/Global buttons stay 48dp tall with 18dp icons and 15dp corners.
@Composable
private fun NewsScopeButton(
    label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit
) {
    if (selected) Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(15.dp)
    ) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(label)
    } else OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(15.dp)
    ) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(label)
    }
}

// Loading placeholders use the same rounded cream card and olive rule as loaded stories.
@Composable
private fun NewsLoadingState() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        repeat(2) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Cream),
                border = BorderStroke(.7.dp, SoftGray),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(Modifier.width(100.dp).height(16.dp).background(LightSage))
                    HorizontalDivider(thickness = 2.dp, color = OliveGreen.copy(alpha = .4f))
                    Box(
                        Modifier.width(90.dp).height(30.dp)
                            .background(PaleOlive, RoundedCornerShape(8.dp))
                    )
                    Box(Modifier.fillMaxWidth().height(24.dp).background(WarmWhite))
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                            .background(LightSage, RoundedCornerShape(9.dp))
                    )
                }
            }
        }
    }
}

// UI sizing/color: empty news states use 38dp vertical space, a 42dp Muted icon, and centered text.
@Composable
private fun NewsEmptyState(message: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 38.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.AutoMirrored.Outlined.Article, null, Modifier.size(42.dp), tint = Muted)
        Text(message, color = Muted, textAlign = TextAlign.Center)
    }
}
