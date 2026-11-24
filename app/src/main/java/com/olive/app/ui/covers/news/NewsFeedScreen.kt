// News cover users: lists headlines and opens saved articles.
package com.olive.app.ui.covers.news

import com.olive.app.ui.shared.systemLocale
import com.olive.app.ui.shared.OliveCard as Card
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val category: String = "All",
    val history: List<NewsArticle> = emptyList(),
    val showHistory: Boolean = false,
    val offline: Boolean = false,
) {
    val visibleArticles
        get() = (when {
            tab == "Saved" && showHistory -> history
            tab == "Saved" -> saved
            else -> articles
        }).filter { article ->
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
    val changeCategory: (String) -> Unit = {},
    val toggleHistory: (Boolean) -> Unit = {},
    val clearHistory: () -> Unit = {},
)
/**
 * Shows loading, error, and article-list states for the Daily Digest.
 *
 * @param session Shared session view model owning application state and actions.
 * @param feed Feed supplied to [NewsFeed].
 * @param actions Actions supplied to [NewsFeed].
 * @param modifier Compose modifier applied to the root layout.
 */
@Composable
internal fun NewsFeed(
    session: SessionViewModel,
    feed: NewsFeedState,
    actions: NewsFeedActions,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val uiText = rememberResourceText()
    val context = LocalContext.current
    val languageTag = systemLocale().toLanguageTag()
    val shareTitle = uiText(R.string.news_share_article)
    with(feed) {
        PullToRefreshBox(isRefreshing = loading, onRefresh = actions.refresh, modifier = modifier) {
            key(tab) {
                LazyColumn(
                    Modifier.fillMaxSize().oliveScrollbar(listState).testTag("news_cover"),
                    state = listState,
                    contentPadding = PaddingValues(
                        start = 18.dp,
                        end = 18.dp,
                        top = 8.dp,
                        bottom = 28.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        item { AppVersionFooter() }
                    }

                    else -> {
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    query,
                                    { value ->
                                        actions.search(value)
                                    },
                                    placeholder = {
                                        Text(
                                            if (tab == "Saved") uiText(R.string.search_saved_stories) else uiText(
                                                R.string.search_headlines
                                            ),
                                            fontSize = 14.sp
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(20.dp)) },
                                    trailingIcon = if (query.isNotBlank()) {
                                        {
                                            IconButton(onClick = { actions.search("") }, modifier = Modifier.size(32.dp)) {
                                                Icon(
                                                    Icons.Outlined.Close,
                                                    uiText(R.string.clear_search),
                                                    Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    } else null,
                                    singleLine = true,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("news_search")
                                        .keepVisibleWhileTyping(),
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                        focusedBorderColor = OliveGreen,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                    )
                                )
                                if (tab == "Today") {
                                    NewsDateHeader(
                                        edition = if (scope == NewsScope.LOCAL) currentPlace else uiText(R.string.global),
                                        isOffline = offline && !loading,
                                        onEditLocation = if (scope == NewsScope.LOCAL) actions.editLocation else null,
                                        loading = loading && scope == NewsScope.LOCAL,
                                        scope = scope
                                    )
                                    NewsCategoryChips(category, actions.changeCategory)
                                }
                                if (tab == "Saved") {
                                    NewsSavedToggle(showHistory, history.isNotEmpty(), actions.toggleHistory, actions.clearHistory)
                                }
                            }
                        }
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    when {
                                        tab == "Saved" && showHistory -> uiText(R.string.news_recently_read)
                                        tab == "Saved" -> uiText(R.string.saved_stories)
                                        else -> uiText(R.string.latest_stories)
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Serif,
                                    modifier = (Modifier.weight(1f)).semantics { heading() })
                                if (tab == "Saved" && visibleArticles.isNotEmpty()) Text(
                                    visibleArticles.size.toString(),
                                    color = Muted
                                )
                            }
                        }
                        if (tab == "Today") {
                            // Skeleton cards mirror the hero + standard card layout while stories load.
                            if (loading) item { LoadingSkeleton(SkeletonLayout.NewsFeed, rows = 3) }
                            if (newsError && !loading && articles.isEmpty()) item {
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
                        val visible = if (loading && tab == "Today") emptyList() else visibleArticles
                        // Show the empty-news message only after loading ends without an error.
                        if (visible.isEmpty() && !loading && !newsError) item {
                            NewsEmptyState(
                                when {
                                    tab == "Saved" && showHistory -> uiText(R.string.news_no_recent_stories)
                                    tab == "Saved" -> uiText(R.string.no_saved_stories_yet)
                                    else -> uiText(R.string.no_stories_found)
                                }
                            )
                        }
                        // The first Today story is featured as a hero card when not searching.
                        val hero = visible.firstOrNull()?.takeIf { tab == "Today" && query.isBlank() }
                        if (hero != null) item(key = "hero_${hero.link}") {
                            NewsHeroCard(
                                hero,
                                languageTag,
                                saved.any { it.link == hero.link },
                                onOpen = { actions.openArticle(hero) },
                                onBookmark = newsBookmarkAction(session, hero))
                        }
                        items(if (hero != null) visible.drop(1) else visible, key = { it.link }) { article ->
                            SwipeableNewsArticleCard(
                                article,
                                languageTag,
                                saved.any { it.link == article.link },
                                onOpen = { actions.openArticle(article) },
                                onBookmark = newsBookmarkAction(session, article),
                                onShare = {
                                    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_SUBJECT, article.title)
                                        putExtra(
                                            android.content.Intent.EXTRA_TEXT,
                                            "${article.title}\n\n${article.publisherUrl.ifBlank { article.link }}"
                                        )
                                    }
                                    context.startActivity(android.content.Intent.createChooser(shareIntent, shareTitle))
                                }
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

/** Applies a bookmark change immediately and offers to restore a removed article. */
@Composable
internal fun newsBookmarkAction(session: SessionViewModel, article: NewsArticle): () -> Unit {
    val undo = LocalOliveUndo.current
    val removed = stringResource(R.string.undo_bookmark_removed)
    return {
        val wasSaved = session.savedNews.any { it.link == article.link }
        session.setNewsBookmark(article, !wasSaved)
        if (wasSaved) undo(removed) { session.setNewsBookmark(article, true) }
    }
}

/**
 * Switches the Saved tab between bookmarks and on-device reading history using a compact segmented toggle.
 *
 * @param showHistory Whether reading history is currently shown.
 * @param hasHistory Whether any reading history exists, enabling the clear action.
 * @param onToggle Callback receiving `true` to show history or `false` for bookmarks.
 * @param onClear Callback that clears reading history.
 */
@Composable
private fun NewsSavedToggle(
    showHistory: Boolean,
    hasHistory: Boolean,
    onToggle: (Boolean) -> Unit,
    onClear: () -> Unit
) {
    val uiText = rememberResourceText()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = WarmWhite,
            border = BorderStroke(0.8.dp, SoftGray),
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier.padding(2.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = { onToggle(false) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (!showHistory) OliveGreen else Color.Transparent,
                    contentColor = if (!showHistory) Cream else Ink,
                    modifier = Modifier.weight(1f).height(34.dp).testTag("news_saved_bookmarks")
                ) {
                    Row(
                        Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Outlined.Bookmark, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            uiText(R.string.news_bookmarks),
                            fontSize = 13.sp,
                            fontWeight = if (!showHistory) FontWeight.Bold else FontWeight.SemiBold
                        )
                    }
                }
                Surface(
                    onClick = { onToggle(true) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (showHistory) OliveGreen else Color.Transparent,
                    contentColor = if (showHistory) Cream else Ink,
                    modifier = Modifier.weight(1f).height(34.dp).testTag("news_saved_history")
                ) {
                    Row(
                        Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Outlined.History, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            uiText(R.string.news_history),
                            fontSize = 13.sp,
                            fontWeight = if (showHistory) FontWeight.Bold else FontWeight.SemiBold
                        )
                    }
                }
            }
        }
        if (showHistory && hasHistory) IconButton(
            onClick = onClear,
            modifier = Modifier.size(28.dp).testTag("news_clear_history")
        ) {
            Icon(Icons.Outlined.DeleteOutline, uiText(R.string.news_clear_history), tint = Muted, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * UI sizing/color: empty news states use 38dp vertical space, a 42dp Muted icon, and centered text.
 *
 * @param message Chat message or remote payload.
 */
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
