// News cover users: displays the featured lead story in a prominent hero card.
package com.olive.app.ui.covers.news

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.olive.app.R
import com.olive.app.data.news.*
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.shared.OliveCard as Card
import com.olive.app.ui.shared.OliveIconButton as IconButton

/**
 * Renders the top headline in a featured hero layout with a high-visibility headline,
 * prominent thumbnail, topic badge, and reading time estimation.
 *
 * @param article The featured [NewsArticle] to render.
 * @param language User's current display language code.
 * @param saved Whether this article is currently bookmarked.
 * @param onOpen Callback invoked when the user taps the card to read the article.
 * @param onBookmark Callback invoked when the user toggles the bookmark status.
 * @param modifier Compose modifier applied to the card layout.
 */
@Composable
internal fun NewsHeroCard(
    article: NewsArticle,
    language: String,
    saved: Boolean,
    onOpen: () -> Unit,
    onBookmark: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiText = rememberResourceText()
    val context = LocalContext.current
    val metadata = remember(context) { NewsMetadataRepository(context.applicationContext) }
    var displayed by remember(article) { mutableStateOf(article) }
    var imageFailed by remember(displayed.imageUrl) { mutableStateOf(false) }

    LaunchedEffect(article.link) {
        metadata.cached(article.link, allowStale = true)?.let { displayed = it.applyTo(article) }
        displayed = metadata.load(article).applyTo(article)
    }

    val topic = newsTopicLabel(displayed.topic.ifBlank { fallbackNewsTopic(displayed.title) })
    val readingTime = uiText(
        R.string.news_min_read,
        remember(displayed.title, displayed.summary, displayed.wordCount) {
            estimateReadingMinutes(displayed)
        }
    )

    Card(
        onClick = onOpen,
        modifier = modifier
            .animateContentSize()
            .fillMaxWidth()
            .testTag("news_hero_card")
            .semantics(mergeDescendants = true) {},
        colors = CardDefaults.cardColors(containerColor = Cream),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = BorderStroke(1.dp, SoftGray),
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    color = OliveGreen,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Star,
                            null,
                            modifier = Modifier.size(13.dp),
                            tint = Cream
                        )
                        Text(
                            text = uiText(R.string.news_top_story),
                            color = Cream,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = readingTime,
                        color = Muted,
                        style = MaterialTheme.typography.labelSmall
                    )
                    IconButton(
                        onClick = onBookmark,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            if (saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                            if (saved) uiText(R.string.a11y_remove_bookmark) else uiText(R.string.a11y_save_article),
                            tint = Ink
                        )
                    }
                }
            }

            Text(
                text = displayed.title.removeSuffix(" - ${displayed.source}"),
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                lineHeight = 30.sp,
                color = Ink,
                maxLines = 4,
                modifier = Modifier.semantics { heading() }
            )

            if (isSecureNewsUrl(displayed.imageUrl) && !imageFailed) {
                AsyncImage(
                    model = displayed.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(LightSage)
                        .testTag("news_hero_image")
                        .semantics { hideFromAccessibility() },
                    onError = { imageFailed = true }
                )
            }

            if (displayed.summary.isNotBlank()) {
                Text(
                    text = displayed.summary,
                    color = Muted,
                    fontFamily = FontFamily.Serif,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    maxLines = 4
                )
            }

            HorizontalDivider(thickness = 1.dp, color = PaleOlive)

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = PaleOlive,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = topic,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = DarkOlive,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Text(
                        text = displayed.source.ifBlank { uiText(R.string.ui_daily_digest) },
                        color = Ink,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.read_full_story),
                        color = OliveGreen,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowForward,
                        null,
                        Modifier.size(16.dp),
                        tint = OliveGreen
                    )
                }
            }
        }
    }
}
