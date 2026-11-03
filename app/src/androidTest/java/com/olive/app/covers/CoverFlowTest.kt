// Tests food-cover search, cart removal, and saved dishes.
package com.olive.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import com.olive.app.ui.covers.food.OliveCover
import com.olive.app.ui.shared.OliveTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CoverFlowTest {
    @get:Rule
    val compose = createOliveComposeRule()

    // Cover flow test cover: Starts cover. The test gives the code a clear input and checks the expected result.
    @Before
    fun showCover() {
        compose.runOnIdle { compose.activity.setContent { OliveTheme { OliveCover() } } }
    }

    // Finds filters and clears for cover flow test cover. The test gives the code a clear input and checks the expected result.
    @Test
    fun searchFiltersAndClears() {
        compose.onNodeWithTag("cover_brand_food").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("nothingmatches")
        compose.onNodeWithText("Nothing on the menu yet").assertExists()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithText("Made for your cravings").assertExists()
    }

    // Cover flow test cover: returns the cart removal works. The test gives the code a clear input and checks the expected result.
    @Test
    fun cartRemovalWorks() {
        compose.onNodeWithText("Garden harvest bowl").performClick()
        compose.onNodeWithText("Add to cart").performScrollTo().performClick()
        compose.onNodeWithText("Cart").performClick()
        compose.onNodeWithContentDescription("Remove one Garden harvest bowl").performScrollTo()
            .performClick()
        compose.onNodeWithText("Something delicious belongs here").assertExists()
    }

    // Cover flow test cover: returns the saved dishes can be removed. The test gives the code a clear input and checks the expected result.
    @Test
    fun savedDishesCanBeRemoved() {
        compose.onNodeWithText("Garden harvest bowl").performClick()
        compose.onNodeWithText("Save for later").performScrollTo().performClick()
        compose.onNodeWithText("Saved").performClick()
        compose.onNodeWithText("Garden harvest bowl").assertExists()
        compose.onNodeWithContentDescription("Unsave Garden harvest bowl").performClick()
        compose.onNodeWithText("A place for your favourites").assertExists()
    }
}
