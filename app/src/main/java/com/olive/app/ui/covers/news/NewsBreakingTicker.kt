// News cover users: displays a breaking news alert ticker banner above the feed.
package com.olive.app.ui.covers.news

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.R
import com.olive.app.data.news.NewsArticle
import com.olive.app.ui.shared.*
import com.olive.app.ui.shared.LocalizedIcon as Icon
import com.olive.app.ui.shared.OliveIconButton as IconButton

/**
 * Editorial breaking news alert banner displaying urgent or top headlines with a quick-tap reader shortcut.
 *
 * @param article Top headline article featured in the ticker.
 * @param onOpen Callback invoked when the user taps the ticker to open the article.
 * @param modifier Compose modifier applied to the ticker layout.
 */
@Composable
internal fun NewsBreakingTicker(
    article: NewsArticle,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiText = rememberResourceText()
    var dismissed by remember(article.link) { mutableStateOf(false) }

    AnimatedVisibility(
        visible = !dismissed,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("news_breaking_ticker")
                .clickable(onClick = onOpen),
            color = WarmWhite,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, SoftGray),
            tonalElevation = 1.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Surface(
                    color = CallRed,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Bolt,
                            null,
                            modifier = Modifier.size(12.dp),
                            tint = Cream
                        )
                        Text(
                            text = uiText(R.string.news_breaking),
                            color = Cream,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                Text(
                    text = article.title.removeSuffix(" - ${article.source}"),
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Ink,
                    modifier = Modifier.weight(1f)
                )

                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    null,
                    modifier = Modifier.size(15.dp),
                    tint = OliveGreen
                )

                IconButton(
                    onClick = { dismissed = true },
                    modifier = Modifier
                        .size(28.dp)
                        .testTag("news_dismiss_ticker")
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        uiText(R.string.news_swipe_dismiss),
                        modifier = Modifier.size(14.dp),
                        tint = Muted
                    )
                }
            }
        }
    }
}
