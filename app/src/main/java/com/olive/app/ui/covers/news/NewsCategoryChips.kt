// News cover users: displays horizontal topic chips to filter the news feed.
package com.olive.app.ui.covers.news

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.olive.app.R
import com.olive.app.ui.shared.*

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
                contentColor = if (isSelected) Cream else DarkOlive,
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
