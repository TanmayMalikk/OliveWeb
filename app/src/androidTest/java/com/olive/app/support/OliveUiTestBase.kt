// Starts and resets the app for Android UI tests across user roles.
package com.olive.app

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
import android.view.inputmethod.InputMethodManager
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
    // Sends a real screen tap when a test needs to check native touch handling.
    protected fun tapOnScreen(x: Float, y: Float) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(time, time + 32, MotionEvent.ACTION_UP, x, y, 0)
        // Turn an unexpected tap on screen failure into a readable message.
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
        // Deletes local test data before each test so one account cannot affect another.
        override fun before() {
            check(!BuildConfig.FIREBASE_CONFIGURED) { "These tests only run against local test accounts." }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
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

    // Waits until a tagged control exists, even while Compose briefly rebuilds the screen.
    protected fun waitFor(tag: String) {
        // Include the current session state when the control never appears.
        try {
            compose.waitUntil(45_000) {
                runCatching {
                    compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
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

    // Finds a tagged control and scrolls it onto the screen when needed.
    private fun visibleNode(tag: String): SemanticsNodeInteraction {
        val node = compose.onNodeWithTag(tag)
        if (runCatching { node.assertIsDisplayed() }.isFailure) node.performScrollTo()
        return node
    }

    // Replaces all text in a tagged input field.
    protected fun fill(tag: String, text: String) {
        visibleNode(tag).performTextReplacement(text)
    }

    // Scrolls to a tagged control and clicks it.
    protected fun click(tag: String) {
        visibleNode(tag).performClick()
    }

    // Hides the emulator keyboard before a UI test presses controls located below a text field.
    protected fun hideKeyboard() {
        compose.activityRule.scenario.onActivity { activity ->
            val keyboard = activity.getSystemService(InputMethodManager::class.java)
            val token = activity.currentFocus?.windowToken ?: activity.window.decorView.windowToken
            keyboard.hideSoftInputFromWindow(token, 0)
            activity.currentFocus?.clearFocus()
        }
        compose.waitForIdle()
    }

    // A clean install stays inside the cover until the discreet olive is double-tapped.
    protected fun openOnboarding(grantPermissions: Boolean = true) {
        if (grantPermissions) grantFeaturePermissions()
        waitCover()
        click("cover_tab_account")
        waitFor("discreet_cover_olive")
        visibleNode("discreet_cover_olive").performTouchInput { doubleClick() }
        waitFor("new_user")
    }

    // Presses the next button used by onboarding tests.
    protected fun nextOnboarding() {
        click("onboarding_next")
    }

    // Opens the birthday picker and chooses a valid test date.
    protected fun chooseBirthday() {
        click("dob_year")
        click("dob_year_2000")
        click("dob_month")
        click("dob_month_1")
        click("dob_day")
        click("dob_day_2")
    }

    // Completes registration with a unique local test account.
    protected fun register(pin: String = "123456", username: String = "olive_tester") {
        openOnboarding()
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
        advanceOnboarding("full_name", "address_city")
        fill("address_city", "Edmonton")
        advanceOnboarding("address_city", "trigger")
        fill("trigger", "moon garden")
        waitFor("profile_submit")
        submitProfile()
        chooseAppPin()
        fill("new_pin", pin)
        fill("confirm_pin", pin)
        submitPinSetup()
        waitCover()
    }

    // Submits the final profile page and retries once if Android consumed the tap while closing
    // the keyboard or settling the scrolling form. This mirrors the guarded page-to-page helper.
    private fun submitProfile() {
        hideKeyboard()
        click("profile_submit")
        val moved = runCatching {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("entry_knows_pin_yes").fetchSemanticsNodes().isNotEmpty()
            }
        }.isSuccess
        if (!moved) {
            waitFor("profile_submit")
            compose.waitForIdle()
            click("profile_submit")
        }
    }

    // Finishes PIN enrollment after closing the keyboard. Some emulator frames consume the first
    // tap while bringing the off-screen Finish button into view, so retry only while DevicePin is
    // still present and the cover has not appeared.
    private fun submitPinSetup() {
        hideKeyboard()
        click("pin_setup_submit")
        val moved = runCatching {
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("cover_root").fetchSemanticsNodes().isNotEmpty()
            }
        }.isSuccess
        if (!moved) {
            waitFor("pin_setup_submit")
            compose.waitForIdle()
            click("pin_setup_submit")
        }
    }

    // Most flows skip Android's permission dialogs so they can test their own feature directly.
    // The dedicated permission test runs first on a fresh install and does not call this helper.
    private fun grantFeaturePermissions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        listOf(
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
        ).forEach { permission ->
            instrumentation.uiAutomation.grantRuntimePermission(packageName, permission)
        }
        // Android 10 and newer need a separate background-location test grant.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            instrumentation.uiAutomation.grantRuntimePermission(
                packageName,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            )
        }
    }

    // Wait for one onboarding page to settle before moving to the next one. A second click handles
    // a button event that Android can drop while the keyboard or dropdown is closing under load.
    private fun advanceOnboarding(currentTag: String, nextTag: String) {
        compose.waitForIdle()
        click("onboarding_next")
        val moved = runCatching {
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag(nextTag).fetchSemanticsNodes().isNotEmpty()
            }
        }.isSuccess
        if (!moved) {
            waitFor(currentTag)
            compose.waitForIdle()
            click("onboarding_next")
        }
        waitFor(nextTag)
    }

    // Creates the backup app PIN used by private-entry tests.
    protected fun chooseAppPin() {
        waitFor("entry_knows_pin_yes")
        click("entry_knows_pin_yes")
        click("entry_choose_pin")
        waitFor(
            "pin_setup_submit"
        )
    }

    // Waits for the public cover after startup or a quick exit.
    protected fun waitCover() {
        // Add the current screen and Compose tree when the cover never appears.
        try {
            compose.waitForIdle()
            compose.waitUntil(45_000) {
                runCatching {
                    val covers = compose.onAllNodesWithTag("cover_root")
                    covers.fetchSemanticsNodes().indices.any { index ->
                        runCatching { covers[index].assertIsDisplayed() }.isSuccess
                    }
                }.getOrDefault(false)
            }
        } catch (e: Throwable) {
            val session =
                androidx.lifecycle.ViewModelProvider(compose.activity)[com.olive.app.ui.users.shared.navigation.SessionViewModel::class.java]
            val hierarchy = runCatching { compose.onRoot().printToString() }
                .getOrElse { "Compose hierarchy unavailable: ${it.message}" }
            throw AssertionError(
                "Expected cover; screen=${session.screen}, busy=${session.busy}, message=${session.message}\n$hierarchy",
                e
            )
        }
    }

    // Types the private trigger into the active cover app.
    protected fun trigger() {
        val session =
            androidx.lifecycle.ViewModelProvider(compose.activity)[com.olive.app.ui.users.shared.navigation.SessionViewModel::class.java]
        compose.runOnIdle { org.junit.Assert.assertTrue(session.account.interceptSearch("moon garden")) }
        waitFor("unlock_pin")
    }

    // Enters the test PIN on the private-entry screen.
    protected fun enterPin(pin: String) {
        pin.forEach { compose.onNodeWithTag("pin_digit_$it").performClick() }
        compose.onNodeWithTag("unlock_submit").performClick()
    }

    // Uses the private trigger and PIN to open the safe app.
    protected fun unlock(pin: String = "123456") {
        trigger()
        enterPin(pin)
        waitFor("safe_settings")
    }
}
