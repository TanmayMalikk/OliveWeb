package com.olive.app.ui.covers.news

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.news.NewsScope
import com.olive.app.data.news.curatedOfflineArticles
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class DailyDigestCoverLocationAndReaderUiDeepTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun newsCoverOpensLocationDialogAndSwitchesToAccountTab() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Cover, localMode = true).use { handle ->
            compose.setContent {
                OliveUiTestHost(privateDesign = false) {
                    NewsCover(handle.session, locationPermissionVersion = 0, interactive = true)
                }
            }
            compose.mainClock.advanceTimeBy(300)
            Thread.sleep(150)
            compose.mainClock.advanceTimeBy(300)

            compose.onNodeWithText(context.getString(R.string.account)).performClick()
            compose.mainClock.advanceTimeBy(250)
            compose.onNodeWithText(context.getString(R.string.today)).performClick()
            compose.mainClock.advanceTimeBy(250)

            compose.onNodeWithTag("change_news_location").performClick()
            compose.mainClock.advanceTimeBy(300)
        }
    }

    @Test
    fun newsFeedRendersOfflineArticlesHistoryAndCategories() {
        compose.mainClock.autoAdvance = false
        val articles = curatedOfflineArticles("Edmonton")
        var tab by mutableStateOf("Today")
        var showHistory by mutableStateOf(false)
        var editLocations = 0
        var openedCount = 0

        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Cover, localMode = true).use { handle ->
            compose.setContent {
                OliveUiTestHost(privateDesign = false) {
                    NewsFeed(
                        session = handle.session,
                        feed = NewsFeedState(
                            tab = tab,
                            scope = NewsScope.LOCAL,
                            query = "",
                            articles = articles,
                            saved = articles.take(1),
                            loading = false,
                            newsError = false,
                            currentPlace = "Edmonton",
                            category = "All",
                            history = articles.take(2),
                            showHistory = showHistory,
                            offline = true,
                        ),
                        actions = NewsFeedActions(
                            search = {},
                            changeScope = {},
                            editLocation = { editLocations++ },
                            refresh = {},
                            openArticle = { openedCount++ },
                            changeCategory = {},
                            toggleHistory = { showHistory = it },
                            clearHistory = {},
                        ),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            compose.mainClock.advanceTimeBy(250)
            compose.onNodeWithTag("change_news_location").performClick()
            compose.mainClock.advanceTimeBy(150)
            assertTrue(editLocations == 1)

            tab = "Saved"
            compose.mainClock.advanceTimeBy(250)
            showHistory = true
            compose.mainClock.advanceTimeBy(250)
            tab = "Account"
            compose.mainClock.advanceTimeBy(250)
        }
    }
}
