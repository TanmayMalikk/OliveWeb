// Tests French food and news cards without losing their actions.
package com.olive.app

import com.olive.app.ui.shared.*

import android.content.res.Configuration
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.data.DemoCatalogRepository
import com.olive.app.data.NewsArticle
import com.olive.app.ui.covers.news.NewsArticleCard
import com.olive.app.ui.covers.food.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LocalizedCardsUiTest : OliveUiTestBase() {
    @Test
    fun foodCardUsesNativeFrenchResourcesAndKeepsItsActions() {
        val configuration =
            Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.FRENCH) }
        val french = compose.activity.createConfigurationContext(configuration)
        val base = compose.activity.createConfigurationContext(Configuration(configuration).apply {
            setLocale(Locale.ENGLISH)
        })
        val vm = OliveViewModel(DemoCatalogRepository(base.resources::getString))
        val dish = vm.dishes.first()
        val text = ResourceText(french.resources)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent {
                CompositionLocalProvider(
                    LocalContext provides french,
                    LocalConfiguration provides configuration
                ) {
                    OliveTheme { DishCard(dish, vm) }
                }
            }
        }
        compose.onNodeWithText(text.dishName(dish), substring = true).assertExists()
        compose.onNodeWithContentDescription(text(R.string.ui_save_value, text.dishName(dish)))
            .performClick()
        compose.runOnIdle { assertEquals(setOf(dish.id), vm.favorites) }
        compose.onNodeWithTag("dish_card_${dish.id}").performClick()
        compose.runOnIdle { assertEquals(dish.id, vm.selectedDish?.id) }
    }

    @Test
    fun newsLabelsTranslateWhilePublisherContentStaysOriginal() {
        val configuration =
            Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.FRENCH) }
        val french = compose.activity.createConfigurationContext(configuration)
        val article = NewsArticle(
            "Publisher headline",
            "Original summary",
            "about:blank",
            "Publisher",
            "",
            topic = "Politics",
            isPaywalled = true
        )
        var opened = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.setContent {
                CompositionLocalProvider(
                    LocalContext provides french,
                    LocalConfiguration provides configuration
                ) {
                    OliveTheme { NewsArticleCard(article, "French", false, { opened = true }, {}) }
                }
            }
        }
        compose.onNodeWithText("Publisher headline", substring = true).assertExists()
        compose.onNodeWithText(french.getString(R.string.ui_paywall), substring = true)
            .assertExists()
        compose.onNodeWithText(french.getString(R.string.read_full_story), substring = true)
            .assertExists()
        compose.onNodeWithTag("news_article_card").performClick()
        compose.runOnIdle { assertEquals(true, opened) }
    }
}
