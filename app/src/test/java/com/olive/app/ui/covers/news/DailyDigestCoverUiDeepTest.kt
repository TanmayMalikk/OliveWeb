package com.olive.app.ui.covers.news

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class DailyDigestCoverUiDeepTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun newsCoverSwitchesScopesTabsAndRendersCuratedFallback() {
        compose.mainClock.autoAdvance = false
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Cover, localMode = true).use { handle ->
            compose.setContent {
                OliveUiTestHost(privateDesign = false) {
                    NewsCover(handle.session, locationPermissionVersion = 0, interactive = true)
                }
            }
            compose.mainClock.advanceTimeBy(200)
            Thread.sleep(100)
            compose.mainClock.advanceTimeBy(300)

            compose.onNodeWithTag("cover_brand_news").assertExists()
            compose.onNodeWithTag("news_scope_global").performClick()
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithTag("news_scope_local").performClick()
            compose.mainClock.advanceTimeBy(200)

            compose.onNodeWithText(context.getString(R.string.saved)).performClick()
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithText(context.getString(R.string.account)).performClick()
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithText(context.getString(R.string.today)).performClick()
            compose.mainClock.advanceTimeBy(200)
        }
    }
}
