package com.olive.app.wear

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

class WatchFitnessActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val permissions = object : ExternalResource() {
        override fun before() {
            val runtimePermissions = buildList {
                add(Manifest.permission.ACTIVITY_RECOGNITION)
                if (Build.VERSION.SDK_INT >= 36) {
                    add("android.permission.health.READ_HEART_RATE")
                    add("android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND")
                } else {
                    add(Manifest.permission.BODY_SENSORS)
                    if (Build.VERSION.SDK_INT >= 33) add("android.permission.BODY_SENSORS_BACKGROUND")
                }
            }
            runtimePermissions.forEach {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
            }
        }
    }
    private val activity = ActivityScenarioRule<WatchFitnessActivity>(
        Intent(Intent.ACTION_MAIN).setClass(context, WatchFitnessActivity::class.java),
    )
    private val compose = AndroidComposeTestRule(activity) { rule ->
        var current: WatchFitnessActivity? = null
        rule.scenario.onActivity { current = it }
        checkNotNull(current)
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(permissions).around(compose)

    @Test
    fun openingAndResumingTheWatchAppKeepsTheDashboardAvailable() {
        compose.onNodeWithText("Olive Fitness").assertIsDisplayed()
        activity.scenario.moveToState(Lifecycle.State.CREATED)
        activity.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithContentDescription("Home fitness summary").assertIsDisplayed()
        compose.onNodeWithText("Vitals").assertIsDisplayed()
    }
}
