package com.olive.app.ui.covers.news

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.news.NewsArticle
import com.olive.app.ui.shared.OliveTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class NewsArticleScreenDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    private fun sampleArticle(title: String = "Local Library Expands Community Hours", topic: String = "News") = NewsArticle(
        title = title,
        summary = "Extended weekday schedule starts next month across neighbourhood branches.",
        link = "https://example.org/story-${title.hashCode()}",
        source = "City Ledger",
        published = "2026-05-01T12:00:00Z",
        topic = topic,
        publisherUrl = "https://example.org/story-1",
        wordCount = 650,
    )

    @Test
    fun newsArticleCardsRenderAllTopicsAndActions() {
        compose.mainClock.autoAdvance = false
        val topics = listOf("News", "Politics", "Sports", "Business", "Technology", "Health", "World", "Other", "")
        var opened = 0
        var bookmarked = 0

        compose.setContent {
            OliveTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    topics.forEachIndexed { idx, topic ->
                        NewsArticleCard(
                            article = sampleArticle("Story $idx", topic).copy(isPaywalled = idx == 0),
                            language = "en",
                            saved = idx % 2 == 0,
                            onOpen = { opened++ },
                            onBookmark = { bookmarked++ },
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(300)
    }

    @Test
    fun newsArticleReaderExercisesZoomBookmarkShareAndBack() {
        compose.mainClock.autoAdvance = false
        var backed = 0
        var saved by mutableStateOf(false)

        compose.setContent {
            OliveTheme {
                NewsArticleReader(
                    article = sampleArticle(),
                    saved = saved,
                    active = true,
                    onBack = { backed++ },
                    onBookmark = { saved = !saved },
                )
            }
        }
        compose.mainClock.advanceTimeBy(300)

        compose.onNodeWithTag("news_reader_bookmark").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.news_text_size)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.news_text_size)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.news_text_size)).performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.news_share_article)).performClick()
        compose.onNodeWithTag("news_reader_back").performClick()
        compose.mainClock.advanceTimeBy(200)

        assertTrue(saved)
        assertTrue(backed == 1)
    }
}
