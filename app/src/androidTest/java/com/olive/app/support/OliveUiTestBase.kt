// Tests the olive UI test base behavior.
package com.olive.app

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import androidx.compose.ui.test.*
import android.content.Intent
import androidx.compose.ui.test.junit4.v2.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import java.io.File
import android.view.MotionEvent
import android.os.SystemClock

// ActivityScenario's class-based constructor can resolve the activity under the test APK after
// a cold emulator boot. An explicit target-app intent keeps every device run deterministic.
internal fun createOliveComposeRule() = Intent(Intent.ACTION_MAIN).setClass(
    InstrumentationRegistry.getInstrumentation().targetContext,
    OliveActivity::class.java,
).let { intent -> ActivityScenarioRule<OliveActivity>(intent) }.let { activityRule ->
    AndroidComposeTestRule(activityRule) { rule ->
        lateinit var activity: OliveActivity
        rule.scenario.onActivity { activity = it }
        activity
    }
}

// Shared setup keeps every end-to-end UI test isolated and gives feature files the same common actions.
abstract class OliveUiTestBase {
    protected open val grantLocationPermissions: Boolean get() = true
    protected fun tapOnScreen(x: Float, y: Float) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(time, time + 32, MotionEvent.ACTION_UP, x, y, 0)
        try {
            check(automation.injectInputEvent(down, true))
            check(automation.injectInputEvent(up, true))
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    protected val compose = createOliveComposeRule()
    private val cleanStore = object : ExternalResource() {
        override fun before() {
            check(!BuildConfig.FIREBASE_CONFIGURED) { "These tests only run against local test accounts." }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            if (grantLocationPermissions) {
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                )
                instrumentation.uiAutomation.grantRuntimePermission(
                    context.packageName,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                )
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
                    instrumentation.uiAutomation.grantRuntimePermission(
                        context.packageName,
                        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    )
            }
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                android.Manifest.permission.CAMERA
            )
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                android.Manifest.permission.RECORD_AUDIO
            )
            val directory = context.noBackupFilesDir
            listOf(
                "olive-demo.vault",
                "olive-demo.vault.bak",
                "olive-demo.vault.new",
                "olive-safety-demo.vault",
                "olive-safety-demo.vault.bak",
                "olive-safety-demo.vault.new",
                "olive-demo-activity.vault",
                "olive-demo-activity.vault.bak",
                "olive-demo-activity.vault.new",
                "olive-cover.vault",
                "olive-cover.vault.bak",
                "olive-cover.vault.new"
            ).forEach { name ->
                val file = File(directory, name)
                check(!file.exists() || file.delete()) { "Could not clear test store: $name" }
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(cleanStore).around(compose)

    // Finish pending session writes before the next test deletes and recreates the account stores.
    @After
    fun stopSessionWork() {
        val session = ViewModelProvider(compose.activity)[SessionViewModel::class.java]
        val sessionJob = session.viewModelScope.coroutineContext[Job] ?: return
        runBlocking { sessionJob.cancelAndJoin() }
    }

    protected fun waitFor(tag: String) {
        try {
            compose.waitUntil(45_000) {
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Throwable) {
            val session =
                androidx.lifecycle.ViewModelProvider(compose.activity)[com.olive.app.ui.users.shared.navigation.SessionViewModel::class.java]
            throw AssertionError(
                "Waiting for $tag; screen=${session.screen}, busy=${session.busy}, message=${session.message}",
                e
            )
        }
    }

    private fun visibleNode(tag: String): SemanticsNodeInteraction {
        val node = compose.onNodeWithTag(tag)
        if (runCatching { node.assertIsDisplayed() }.isFailure) node.performScrollTo()
        return node
    }

    protected fun fill(tag: String, text: String) {
        visibleNode(tag).performTextReplacement(text)
    }

    protected fun click(tag: String) {
        visibleNode(tag).performClick()
    }

    protected fun nextOnboarding() {
        click("onboarding_next")
    }

    protected fun chooseBirthday() {
        click("dob_year")
        click("dob_year_2000")
        click("dob_month")
        click("dob_month_1")
        click("dob_day")
        click("dob_day_2")
    }

    protected fun register(pin: String = "123456", username: String = "olive_tester") {
        waitFor("new_user")
        click("new_user")
        fill("username", username)
        fill("password", "test-password-123")
        fill(
            "confirm_password",
            "test-password-123"
        )
        click("credentials_submit")
        waitFor("full_name")
        fill("full_name", "Test Person")
        chooseBirthday()
        fill(
            "abuser_name",
            "Test Person Causing Harm"
        )
        nextOnboarding()
        fill("address_line1", "123 Test Street")
        fill("address_city", "Edmonton")
        fill("address_province", "Alberta")
        fill("address_postal", "T5J 0N3")
        nextOnboarding()
        fill("trigger", "moon garden")
        waitFor("profile_submit")
        click("profile_submit")
        chooseAppPin()
        fill("new_pin", pin)
        fill("confirm_pin", pin)
        click("pin_setup_submit")
        waitCover()
    }

    protected fun chooseAppPin() {
        waitFor("entry_knows_pin_yes")
        click("entry_knows_pin_yes")
        click("entry_choose_pin")
        waitFor(
            "pin_setup_submit"
        )
    }

    protected fun waitCover() {
        try {
            compose.waitUntil(30_000) {
                compose.onAllNodesWithTag("cover_root").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (e: Exception) {
            val session =
                androidx.lifecycle.ViewModelProvider(compose.activity)[com.olive.app.ui.users.shared.navigation.SessionViewModel::class.java]
            throw AssertionError(
                "Expected cover; screen=${session.screen}, busy=${session.busy}, message=${session.message}\n${
                    compose.onRoot().printToString()
                }", e
            )
        }
    }

    protected fun trigger() {
        val session =
            androidx.lifecycle.ViewModelProvider(compose.activity)[com.olive.app.ui.users.shared.navigation.SessionViewModel::class.java]
        compose.runOnIdle { org.junit.Assert.assertTrue(session.interceptSearch("moon garden")) }
        waitFor("unlock_pin")
    }

    protected fun enterPin(pin: String) {
        pin.forEach { compose.onNodeWithTag("pin_digit_$it").performClick() }
        compose.onNodeWithTag("unlock_submit").performClick()
    }

    protected fun unlock(pin: String = "123456") {
        trigger()
        enterPin(pin)
        waitFor("safe_settings")
    }
}
