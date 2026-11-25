package com.olive.app.ui.covers.news

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.news.NewsArticle
import com.olive.app.data.news.NewsScope
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.covers.CoverAccountPanel
import com.olive.app.ui.covers.CoverSettingsPage
import com.olive.app.ui.covers.DiscreetCoverEntry
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class NewsAndDiscreetCoverUiTest {
    @get:Rule
    val compose = createComposeRule()

    private fun appContext(): Context = RuntimeEnvironment.getApplication()

    private val sampleArticles = listOf(
        NewsArticle(
            title = "Local Community Garden Opens Spring Beds",
            summary = "Volunteers gathered across town to prepare raised beds.",
            link = "https://example.org/news/garden",
            source = "Daily Herald",
            published = "2026-11-24T10:00:00Z",
            topic = "Environment",
            isPaywalled = false,
            wordCount = 320,
        ),
        NewsArticle(
            title = "Tech Briefing on Privacy Tools",
            summary = "An overview of mobile device encryption.",
            link = "https://example.org/news/privacy",
            source = "Tech Review",
            published = "2026-11-24T09:00:00Z",
            topic = "Technology",
            isPaywalled = true,
            wordCount = 600,
        ),
    )

    @Test
    fun newsFeedRendersLoadingErrorEmptySavedAndAccountStates() {
        val context = appContext()
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Cover, localMode = true).use { handle ->
            var openedArticle: NewsArticle? = null
            var categoryChanged = ""
            var refreshed = false

            val actions = NewsFeedActions(
                search = {},
                changeScope = {},
                editLocation = {},
                refresh = { refreshed = true },
                openArticle = { openedArticle = it },
                changeCategory = { categoryChanged = it },
                toggleHistory = {},
                clearHistory = {},
            )
            // Switch Today populated -> Error -> Saved -> Account within a single setContent host.
            var stage by mutableIntStateOf(0)
            compose.setContent {
                OliveUiTestHost(privateDesign = false) {
                    when (stage) {
                        0 -> NewsFeed(
                            session = handle.session,
                            feed = NewsFeedState(
                                tab = "Today",
                                scope = NewsScope.LOCAL,
                                query = "",
                                articles = sampleArticles,
                                saved = listOf(sampleArticles.first()),
                                loading = false,
                                newsError = false,
                                currentPlace = "Edmonton",
                                offline = true,
                            ),
                            actions = actions,
                        )
                        1 -> NewsFeed(
                            session = handle.session,
                            feed = NewsFeedState(
                                tab = "Today",
                                scope = NewsScope.GLOBAL,
                                query = "",
                                articles = emptyList(),
                                saved = emptyList(),
                                loading = false,
                                newsError = true,
                                currentPlace = "",
                            ),
                            actions = actions,
                        )
                        2 -> NewsFeed(
                            session = handle.session,
                            feed = NewsFeedState(
                                tab = "Saved",
                                scope = NewsScope.LOCAL,
                                query = "",
                                articles = emptyList(),
                                saved = sampleArticles,
                                history = sampleArticles,
                                showHistory = true,
                                loading = false,
                                newsError = false,
                                currentPlace = "Edmonton",
                            ),
                            actions = actions,
                        )
                        else -> NewsFeed(
                            session = handle.session,
                            feed = NewsFeedState(
                                tab = "Account",
                                scope = NewsScope.LOCAL,
                                query = "",
                                articles = emptyList(),
                                saved = emptyList(),
                                loading = false,
                                newsError = false,
                                currentPlace = "Edmonton",
                            ),
                            actions = actions,
                        )
                    }
                }
            }
            compose.onNodeWithText("Local Community Garden Opens Spring Beds", substring = true).assertIsDisplayed()
            compose.onNodeWithTag("news_category_Technology").performClick()
            assertEquals("Technology", categoryChanged)
            compose.onNodeWithText("Local Community Garden Opens Spring Beds", substring = true).performClick()
            assertEquals("https://example.org/news/garden", openedArticle?.link)

            stage = 1
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.retry)).performClick()
            assertTrue(refreshed)

            stage = 2
            compose.waitForIdle()
            compose.onNodeWithText("Local Community Garden Opens Spring Beds", substring = true).assertExists()

            stage = 3
            compose.waitForIdle()
            compose.onNodeWithTag("cover_full_name").assertExists()
        }
    }

    @Test
    fun newsArticleCardBookmarkAndReaderUrlCheck() {
        assertTrue(isNewsReaderUrlAllowed("https://example.com/story"))
        assertFalse(isNewsReaderUrlAllowed("http://example.com/story"))
        assertFalse(isNewsReaderUrlAllowed("file:///etc/passwd"))

        var bookmarked = false
        var opened = false
        compose.setContent {
            OliveUiTestHost(privateDesign = false) {
                NewsArticleCard(
                    article = sampleArticles[1],
                    language = "en-CA",
                    saved = false,
                    onOpen = { opened = true },
                    onBookmark = { bookmarked = true },
                )
            }
        }
        compose.onNodeWithContentDescription(appContext().getString(R.string.a11y_save_article)).performClick()
        compose.onNodeWithTag("news_article_card").performClick()
        assertTrue(bookmarked)
        assertTrue(opened)
    }

    @Test
    fun newsCoverMountsWithManualClock() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Cover, localMode = true).use { handle ->
            compose.mainClock.autoAdvance = false
            compose.setContent {
                OliveUiTestHost(privateDesign = false) {
                    NewsCover(handle.session, interactive = true)
                }
            }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("news_cover").assertExists()
        }
    }

    @Test
    fun discreetCoverEntryAndCoverSettingsValidationAndActions() {
        var opened = 0
        var emergency = 0
        val context = appContext()
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Cover, localMode = true).use { handle ->
            compose.setContent {
                OliveUiTestHost(privateDesign = false) {
                    Column {
                        DiscreetCoverEntry(
                            enabled = true,
                            onOpen = { opened++ },
                            onEmergency = { emergency++ },
                            onEmergencyFeedback = {},
                        )
                        CoverSettingsPage(session = handle.session, onBack = {})
                    }
                }
            }
            compose.onAllNodesWithTag("discreet_cover_olive")[0].performSemanticsAction(SemanticsActions.OnClick)
            compose.onAllNodesWithTag("discreet_cover_olive")[0].performSemanticsAction(SemanticsActions.OnLongClick)
            assertEquals(1, opened)
            assertEquals(1, emergency)

            compose.onNodeWithTag("cover_full_name").performScrollTo().performTextInput("Jordan Lee")
            compose.onNodeWithTag("cover_save_settings").performScrollTo().performClick()
            compose.waitForIdle()
            assertTrue(compose.onAllNodesWithText(context.getString(R.string.settings)).fetchSemanticsNodes().isNotEmpty())
        }
    }
}
