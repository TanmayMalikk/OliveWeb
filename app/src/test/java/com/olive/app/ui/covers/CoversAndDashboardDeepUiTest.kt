package com.olive.app.ui.covers

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.FailedAttempt
import com.olive.app.data.account.FieldChange
import com.olive.app.data.account.SettingsEvent
import com.olive.app.data.account.TriggerEvent
import com.olive.app.data.news.NewsArticle
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.covers.fitness.CoachGoalsCard
import com.olive.app.ui.covers.fitness.FitnessActivityDashboard
import com.olive.app.ui.covers.fitness.FitnessHealthConnection
import com.olive.app.ui.covers.fitness.FitnessOutdoorWorkoutCard
import com.olive.app.ui.covers.fitness.FitnessStore
import com.olive.app.ui.covers.fitness.FitnessVitalsPage
import com.olive.app.ui.covers.fitness.FitnessWorkoutStore
import com.olive.app.ui.covers.fitness.ManualFoodEntryDialog
import com.olive.app.ui.covers.fitness.OutdoorWorkoutType
import com.olive.app.ui.covers.fitness.barcodeFromLabelText
import com.olive.app.ui.covers.fitness.parseNutritionLabel
import com.olive.app.ui.covers.fitness.parseServingAmount
import com.olive.app.ui.covers.fitness.servingBasis
import com.olive.app.ui.covers.fitness.validFoodBarcode
import com.olive.app.ui.covers.news.NewsArticleCard
import com.olive.app.ui.covers.news.NewsArticleReader
import com.olive.app.ui.covers.news.NewsCover
import com.olive.app.ui.users.shared.navigation.ActivityCategory
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.user.dashboard.ActivityHistory
import com.olive.app.ui.users.user.dashboard.SafeSupportControls
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class CoversAndDashboardDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun fitnessOutdoorWorkoutVitalsActivityChartsAndFoodDialogWork() {
        compose.mainClock.autoAdvance = false
        val labelReading = parseNutritionLabel(
            "Nutrition Facts\nServing size 1 cup (240ml)\nCalories 180\nProtein 8g\nCarbohydrate 22g\nFat 5g\nUPC 012345678905",
        )
        assertTrue(labelReading.nutrients.isNotEmpty())
        assertTrue(validFoodBarcode("012345678905"))
        assertFalse(validFoodBarcode("abc"))
        barcodeFromLabelText("Code 012345678905")
        assertNotNull(parseServingAmount("1.5"))
        assertNotNull(servingBasis("240 ml"))

        val accountId = "owner"
        val store = FitnessStore(context, accountId)
        val snapshot = store.addWater(250, 2000)
        val workoutStore = FitnessWorkoutStore(context, accountId)
        workoutStore.start(OutdoorWorkoutType.RUN)

        var stage by mutableIntStateOf(0)
        var savedFood = false

        compose.setContent {
            OliveUiTestHost(privateDesign = false) {
                when (stage) {
                    0 -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        FitnessOutdoorWorkoutCard(accountId = accountId, enabled = true)
                        FitnessHealthConnection(accountId = accountId, enabled = true)
                        CoachGoalsCard(
                            title = R.string.settings,
                            goals = mapOf("steps" to 8000, "calories" to 2100, "protein" to 120),
                            onApply = {},
                        )
                    }
                    1 -> FitnessVitalsPage(snapshot = snapshot)
                    2 -> FitnessActivityDashboard(snapshot = snapshot)
                    else -> ManualFoodEntryDialog(
                        onDismiss = {},
                        onContinue = { savedFood = true },
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText(context.getString(R.string.fitness_pause)).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(150)
        compose.onNodeWithText(context.getString(R.string.fitness_finish)).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(150)

        stage = 1
        compose.mainClock.advanceTimeBy(150)
        stage = 2
        compose.mainClock.advanceTimeBy(150)
        stage = 3
        compose.mainClock.advanceTimeBy(150)
        assertFalse(savedFood)
    }

    @Test
    fun newsCoverReaderCoverSettingsAndDiscreetEntryWork() {
        compose.mainClock.autoAdvance = false
        val sampleArticle = NewsArticle(
            title = "Local Library Expands Evening Hours",
            summary = "Extended study spaces open across five neighbourhood branches.",
            link = "https://example.com/news/library",
            source = "Civic Beat",
            topic = "Community",
            isPaywalled = true,
            publisherUrl = "https://example.com/news/library",
        )
        var readerClosed = 0
        var bookmarked = 0
        var stage by mutableIntStateOf(0)

        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Cover, page = SafePage.Home, localMode = true).use { handle ->
            compose.setContent {
                OliveUiTestHost {
                    when (stage) {
                        0 -> Column(Modifier.fillMaxSize()) {
                            NewsCover(session = handle.session, interactive = true)
                        }
                        1 -> Column(Modifier.verticalScroll(rememberScrollState())) {
                            NewsArticleCard(
                                article = sampleArticle,
                                language = "en",
                                saved = false,
                                onOpen = {},
                                onBookmark = { bookmarked++ },
                            )
                            NewsArticleReader(
                                article = sampleArticle,
                                saved = false,
                                active = true,
                                onBack = { readerClosed++ },
                                onBookmark = { bookmarked++ },
                            )
                        }
                        else -> CoverSettingsPage(session = handle.session, onBack = {})
                    }
                }
            }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("news_cover").assertExists()
            compose.onNodeWithTag("news_scope_local").performClick()
            compose.onNodeWithTag("news_scope_global").performClick()

            stage = 1
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithTag("news_reader_bookmark").performClick()
            compose.onNodeWithTag("news_reader_back").performClick()
            assertTrue(bookmarked >= 1 && readerClosed >= 1)

            stage = 2
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithTag("discreet_cover_olive").assertExists()
        }
    }

    @Test
    fun activityHistoryFiltersPickersAndSupportControlsWork() {
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Activity, localMode = true).use { handle ->
            handle.session.runtime.state.settingsHistory = (1..6).map { i ->
                SettingsEvent(
                    id = "set-$i",
                    timestamp = 1_700_000_000_000L + i * 1000L,
                    area = "Security",
                    action = "Updated setting $i",
                    changes = listOf(FieldChange("Timeout", "60s", "120s")),
                )
            }
            handle.session.runtime.state.triggerHistory = (1..6).map { i ->
                TriggerEvent(
                    id = "trg-$i",
                    timestamp = 1_700_000_000_000L + i * 1000L,
                    source = "Cover entry",
                    pinAttempts = i,
                    lastResult = if (i % 2 == 0) "Accepted" else "Incorrect PIN",
                )
            }
            handle.session.runtime.state.attempts = (1..6).map { i ->
                FailedAttempt(id = "att-$i", timestamp = 1_700_000_000_000L + i * 1000L)
            }
            handle.session.runtime.state.privacyHistory = listOf(
                SettingsEvent(
                    id = "prv-1",
                    timestamp = 1_700_000_000_000L,
                    area = "Privacy",
                    action = "Screen capture blocked",
                ),
            )
            var stage by mutableIntStateOf(0)

            compose.setContent {
                OliveUiTestHost {
                    if (stage == 0) {
                        ActivityHistory(handle.session)
                    } else {
                        SafeSupportControls(
                            session = handle.session,
                            exit = {},
                            showExit = true,
                            showSupport = true,
                            expanded = true,
                            onExpanded = {},
                        )
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("activity_from").performClick()
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.next)).performClick()
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.apply)).performClick()
            compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.clear)).performClick()
            ActivityCategory.entries.forEach { cat ->
                compose.runOnUiThread { handle.session.showActivityCategory(cat) }
                compose.waitForIdle()
            }

            stage = 1
            compose.waitForIdle()
        }
    }
}
