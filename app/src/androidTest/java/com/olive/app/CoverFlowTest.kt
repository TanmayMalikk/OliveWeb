package com.olive.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.olive.app.ui.OliveApp
import com.olive.app.ui.OliveTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CoverFlowTest {
    @get:Rule val compose = createComposeRule()
    @Before fun showCover() { compose.setContent { OliveTheme { OliveApp() } } }

    @Test fun searchFiltersAndClears() {
        compose.onNode(hasSetTextAction()).performTextInput("nothingmatches")
        compose.onNodeWithText("Nothing on the menu yet").assertExists()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithTag("olive_ad_placeholder").assertExists()
    }

    @Test fun cartRemovalWorks() {
        compose.onNodeWithText("Garden harvest bowl").performClick()
        compose.onNodeWithText("Add to cart").performScrollTo().performClick()
        compose.onNodeWithText("Cart").performClick()
        compose.onNodeWithContentDescription("Remove one Garden harvest bowl").performScrollTo().performClick()
        compose.onNodeWithText("Something delicious belongs here").assertExists()
    }

    @Test fun savedDishesCanBeRemoved() {
        compose.onNodeWithText("Garden harvest bowl").performClick()
        compose.onNodeWithText("Save for later").performScrollTo().performClick()
        compose.onNodeWithText("Saved").performClick()
        compose.onNodeWithText("Garden harvest bowl").assertExists()
        compose.onNodeWithContentDescription("Unsave Garden harvest bowl").performClick()
        compose.onNodeWithText("A place for your favourites").assertExists()
    }
}
