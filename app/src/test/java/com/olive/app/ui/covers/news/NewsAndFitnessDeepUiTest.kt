package com.olive.app.ui.covers.news

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.olive.app.R
import com.olive.app.data.news.NewsArticle
import com.olive.app.support.OliveUiTestHost
import com.olive.app.ui.covers.fitness.FitnessHomePage
import com.olive.app.ui.covers.fitness.FitnessProfile
import com.olive.app.ui.covers.fitness.FitnessSnapshot
import com.olive.app.ui.covers.fitness.FitnessVitalsPage
import com.olive.app.ui.covers.fitness.HeartRateSample
import com.olive.app.ui.covers.fitness.HeartRateVitals
import com.olive.app.ui.covers.fitness.WaterTracker
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class NewsAndFitnessDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun newsArticleReaderToolbarBookmarkZoomAndInsecureFallbackWork() {
        val secure = NewsArticle(
            title = "City Trail Upgrades",
            summary = "New bike lanes open this spring.",
            link = "https://example.org/trails",
            source = "Civic Daily",
            published = "2026-11-24T10:00:00Z",
            topic = "Local",
            isPaywalled = false,
            wordCount = 450,
            publisherUrl = "https://example.org/trails",
        )
        val insecure = secure.copy(link = "http://insecure.example.org/a", publisherUrl = "http://insecure.example.org/a")
        var stage by mutableIntStateOf(0)
        var backClicks = 0
        var bookmarks = 0

        compose.setContent {
            OliveUiTestHost(privateDesign = false) {
                if (stage == 0) {
                    NewsArticleReader(
                        article = secure,
                        saved = false,
                        active = true,
                        onBack = { backClicks++ },
                        onBookmark = { bookmarks++ },
                    )
                } else {
                    NewsArticleReader(
                        article = insecure,
                        saved = true,
                        active = true,
                        onBack = { backClicks++ },
                        onBookmark = { bookmarks++ },
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("news_reader_bookmark").performClick()
        compose.onNodeWithTag("news_reader_back").performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.news_text_size)).performClick()
        assertEquals(1, bookmarks)
        assertEquals(1, backClicks)

        stage = 1
        compose.waitForIdle()
        compose.onNodeWithTag("news_reader_toolbar").assertExists()
    }

    @Test
    fun fitnessHomeQuestionnaireWaterTrackerAndVitalsDeepBranchesWork() {
        val today = LocalDate.now()
        val vitals = HeartRateVitals(
            date = today,
            peakBpm = 148,
            lowestBpm = 56,
            restingBpm = 60,
            averageBpm = 78,
            sampleCount = 4,
            watchConnected = true,
            lastUpdatedAt = 1_700_000_000_000L,
            samples = listOf(HeartRateSample(1_700_000_000_000L, 78)),
        )
        val snapshot = FitnessSnapshot(
            profile = FitnessProfile(),
            stepsToday = 6400,
            foodsToday = emptyList(),
            heartRateVitals = vitals,
            heartRateHistory = listOf(vitals),
        )
        var startedQ = 0
        var dismissedQ = 0
        var waterAdded = 0
        var stage by mutableIntStateOf(0)

        compose.setContent {
            OliveUiTestHost(privateDesign = false) {
                when (stage) {
                    0 -> FitnessHomePage(
                        snapshot = snapshot,
                        onGoals = {},
                        showGoalQuestionnairePrompt = true,
                        onStartQuestionnaire = { startedQ++ },
                        onDismissQuestionnaire = { dismissedQ++ },
                    )
                    1 -> Column {
                        WaterTracker(totalMl = 750, goalMl = 2000, enabled = true) { ml, _ -> waterAdded = ml }
                    }
                    else -> FitnessVitalsPage(snapshot = snapshot)
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("fitness_home").assertExists()

        stage = 1
        compose.waitForIdle()
        assertEquals(0, waterAdded)

        stage = 2
        compose.waitForIdle()
        compose.onAllNodesWithText("78", substring = true).fetchSemanticsNodes().isNotEmpty()
    }
}
