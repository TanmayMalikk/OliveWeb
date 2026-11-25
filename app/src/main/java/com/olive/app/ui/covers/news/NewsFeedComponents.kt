// News cover users: displays topic chips, editorial date header, hero lead card, and swipeable cards.
package com.olive.app.ui.covers.news

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.olive.app.ui.shared.systemLocale

/** Category chips in display order; each maps to a Google News topic section except "All". */
internal val NEWS_TOPIC_CATEGORIES = listOf(
    "All", "World", "Business", "Technology", "Science", "Health", "Sports", "Culture"
)

/**
 * Localized label for a news topic or category key, falling back to the raw topic text.
 *
 * @param topic Topic key such as "Business" or a publisher-provided section name.
 * @return Display label in the current app language.
 */
@Composable
internal fun newsTopicLabel(topic: String): String {
    val uiText = rememberResourceText()
    return when (topic) {
        "All" -> uiText(R.string.news_category_all)
        "World" -> uiText(R.string.news_category_world)
        "News" -> uiText(R.string.ui_news)
        "Politics" -> uiText(R.string.ui_politics)
        "Sports" -> uiText(R.string.ui_sports)
        "Business" -> uiText(R.string.ui_business)
        "Technology" -> uiText(R.string.ui_technology)
        "Health" -> uiText(R.string.ui_health)
        "Science" -> uiText(R.string.ui_science)
        "Environment" -> uiText(R.string.ui_environment)
        "Culture" -> uiText(R.string.culture)
        else -> topic
    }
}

/**
 * Horizontal row of filter chips letting the user switch the feed to a topic section.
 *
 * @param selectedCategory Currently active category ("All" shows the default edition).
 * @param onSelectCategory Callback triggered when a category chip is tapped.
 * @param modifier Compose modifier applied to the row layout.
 */
@Composable
internal fun NewsCategoryChips(
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth().testTag("news_category_chips"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp)
    ) {
        items(NEWS_TOPIC_CATEGORIES, key = { it }) { category ->
            val isSelected = selectedCategory == category
            Surface(
                onClick = { onSelectCategory(category) },
                shape = RoundedCornerShape(18.dp),
                color = if (isSelected) OliveGreen else WarmWhite,
                contentColor = if (isSelected) Cream else Ink,
                border = BorderStroke(1.dp, if (isSelected) OliveGreen else SoftGray),
                modifier = Modifier.height(34.dp).testTag("news_category_$category")
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 13.dp)
                ) {
                    Text(
                        text = newsTopicLabel(category),
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * Editorial date and location strip above the feed showing today's date, the local edition/location,
 * location picker action, and an offline badge when stories come from the device cache.
 *
 * @param edition Edition label, such as the local city or "Global".
 * @param isOffline Whether stories are currently served from cache or offline fallback.
 * @param onEditLocation Callback invoked to open the location picker dialog, or null if editing is not applicable.
 * @param loading Whether the local place or news is currently loading.
 * @param scope News scope (Local or Global).
 * @param modifier Compose modifier applied to the strip.
 */
@Composable
internal fun NewsDateHeader(
    edition: String,
    isOffline: Boolean,
    onEditLocation: (() -> Unit)? = null,
    loading: Boolean = false,
    scope: NewsScope = NewsScope.LOCAL,
    modifier: Modifier = Modifier
) {
    val uiText = rememberResourceText()
    val locale = systemLocale()
    val dateFormat = remember(locale) {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd")
        DateTimeFormatter.ofPattern(pattern, locale)
    }
    val dateText = LocalDate.now().format(dateFormat)
    Surface(
        modifier = modifier.fillMaxWidth().testTag("news_date_header"),
        color = Cream,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(0.6.dp, SoftGray)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = PaleOlive,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (scope == NewsScope.LOCAL) Icons.Outlined.LocationOn else Icons.Outlined.Today,
                        null,
                        Modifier.size(20.dp),
                        tint = OliveGreen
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    dateText,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = Ink
                )
                if (loading) {
                    SkeletonBlock(Modifier.width(110.dp).height(14.dp))
                } else {
                    Text(
                        if (scope == NewsScope.LOCAL) uiText(R.string.news_near_location, edition)
                        else uiText(R.string.news_daily_edition, edition),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        maxLines = 1
                    )
                }
            }
            if (isOffline) Surface(color = PaleOlive, shape = RoundedCornerShape(10.dp)) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Outlined.CloudOff, null, Modifier.size(13.dp), tint = DarkOlive)
                    Text(
                        uiText(R.string.news_offline),
                        style = MaterialTheme.typography.labelSmall,
                        color = DarkOlive,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            if (scope == NewsScope.LOCAL && onEditLocation != null) {
                IconButton(
                    onClick = onEditLocation,
                    modifier = Modifier.size(38.dp).testTag("change_news_location")
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
}

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
                            fontSize = 13.sp,
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
                            color = Ink,
                            fontSize = 13.sp,
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
