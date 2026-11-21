// News cover users: wraps an article card with swipe actions for quick bookmarking and sharing.
package com.olive.app.ui.covers.news

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.R
import com.olive.app.data.news.NewsArticle
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.LocalizedIcon as Icon

/**
 * Wraps a [NewsArticleCard] with swipe gestures to quickly bookmark or share an article.
 *
 * @param article News article being displayed.
 * @param language Current language code.
 * @param saved Whether the article is currently bookmarked.
 * @param onOpen Callback invoked when the article is tapped to read.
 * @param onBookmark Callback invoked when the user swipes to toggle bookmark.
 * @param onShare Callback invoked when the user swipes to share the article.
 * @param modifier Compose modifier applied to the container.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
@Composable
internal fun SwipeableNewsArticleCard(
    article: NewsArticle,
    language: String,
    saved: Boolean,
    onOpen: () -> Unit,
    onBookmark: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiText = rememberResourceText()
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onBookmark()
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onShare()
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier.fillMaxWidth(),
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val alignment = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                SwipeToDismissBoxValue.Settled -> Alignment.Center
            }
            val backgroundColor = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> PaleOlive
                SwipeToDismissBoxValue.EndToStart -> LightSage.copy(alpha = 0.5f)
                SwipeToDismissBoxValue.Settled -> Color.Transparent
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(backgroundColor)
                    .padding(horizontal = 24.dp),
                contentAlignment = alignment
            ) {
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            if (saved) Icons.Outlined.BookmarkBorder else Icons.Outlined.Bookmark,
                            null,
                            tint = DarkOlive,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            uiText(R.string.news_swipe_bookmark),
                            color = DarkOlive,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }
                    SwipeToDismissBoxValue.EndToStart -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            uiText(R.string.news_swipe_share),
                            color = DarkOlive,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Icon(
                            Icons.Outlined.Share,
                            null,
                            tint = DarkOlive,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    SwipeToDismissBoxValue.Settled -> {}
                }
            }
        }
    ) {
        NewsArticleCard(
            article = article,
            language = language,
            saved = saved,
            onOpen = onOpen,
            onBookmark = onBookmark
        )
    }
}
