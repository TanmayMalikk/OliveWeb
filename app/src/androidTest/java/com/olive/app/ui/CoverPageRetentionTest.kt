package com.olive.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.R
import com.olive.app.security.DeviceEnrollment
import com.olive.app.ui.covers.fitness.FitnessCover
import com.olive.app.ui.covers.fitness.ManualFoodEntryDialog
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class CoverPageRetentionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Before fun wakeAndAllowPhoneSteps() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.ACTIVITY_RECOGNITION)
        compose.activityRule.scenario.onActivity {
            it.setShowWhenLocked(true); it.setTurnScreenOn(true)
            it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    private fun session(): SessionViewModel {
        lateinit var value: SessionViewModel
        compose.activityRule.scenario.onActivity { value = ViewModelProvider(it)[SessionViewModel::class.java] }
        compose.waitUntil(10000) { value.screen != SessionScreen.Loading }
        return value
    }
    @Test fun nutritionTabSurvivesBackgroundEventsAndActivityStateRestoration() {
        val session = session()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { FitnessCover(session) }
        val nutrition = hasContentDescription(context.getString(R.string.nutrition)) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onNode(nutrition).performClick().assertIsSelected()
        compose.runOnIdle {
            session.runtime.state.screen = SessionScreen.Cover
            val reset = session.coverReset
            val epoch = session.runtime.epoch
            session.runtime.navigation.background()
            assertEquals(reset, session.coverReset)
            assertEquals(epoch, session.runtime.epoch)
        }
        compose.onNode(nutrition).assertIsSelected()
        compose.runOnIdle { session.runtime.navigation.quickExit() }
        compose.onNode(nutrition).assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(nutrition).assertIsSelected()
    }
    @Test fun manualFoodFormSurvivesRecreationWhileAnExternalCameraIsOpen() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { OliveTheme { ManualFoodEntryDialog({}, {}) } }
        compose.onNodeWithTag("manual_food_name").performTextInput("Test oats")
        compose.onNodeWithTag("manual_food_serving").performTextInput("100 g")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("manual_food_name").assertTextContains("Test oats")
        compose.onNodeWithTag("manual_food_serving").assertTextContains("100 g")
    }
    @Test fun leavingPrivateScreensStillClearsPinAuthorization() {
        val session = session()
        compose.runOnIdle {
            val runtime = session.runtime
            runtime.enrollment = DeviceEnrollment("synthetic-cover-retention", "test-trigger")
            runtime.pinAuthorizedUid = runtime.enrollment!!.uid
            runtime.pinAuthorizedEpoch = runtime.epoch
            runtime.state.screen = SessionScreen.Safe
            assertEquals(SessionScreen.Safe, runtime.state.screen)
            runtime.navigation.background()
            assertEquals(SessionScreen.Cover, runtime.state.screen)
            assertNull(runtime.pinAuthorizedUid)
            assertEquals(-1L, runtime.pinAuthorizedEpoch)
        }
    }
}
