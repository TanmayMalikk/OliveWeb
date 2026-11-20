package com.olive.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.R
import com.olive.app.data.update.AppUpdateInstallerContent
import com.olive.app.data.update.AppUpdateStage
import com.olive.app.ui.shared.OliveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class UpdateInstallerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun updateScreenShowsTheCorrectActionForEachInstallerStage() {
        val stage = mutableStateOf(AppUpdateStage.Preparing)
        val actions = mutableListOf<String>()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            OliveTheme {
                AppUpdateInstallerContent("9.9 fixture", stage.value,
                    onPermission = { actions += "permission" }, onRetry = { actions += "retry" },
                    onCancel = { actions += "cancel" },
                )
            }
        }
        fun label(id: Int) = compose.onNodeWithText(context.getString(id))
        compose.onNodeWithText(context.getString(R.string.app_update_version, "9.9 fixture")).assertIsDisplayed()
        label(R.string.app_update_preparing).assertIsDisplayed()
        label(R.string.retry).assertDoesNotExist()
        compose.runOnIdle { stage.value = AppUpdateStage.Permission }
        label(R.string.app_update_permission).assertIsDisplayed()
        label(R.string.app_permissions_install).performClick()
        compose.runOnIdle { stage.value = AppUpdateStage.Failed }
        label(R.string.app_update_failed).assertIsDisplayed()
        label(R.string.retry).performClick()
        label(R.string.app_permissions_install).assertDoesNotExist()
        compose.runOnIdle { stage.value = AppUpdateStage.Installing }
        label(R.string.app_update_installing).assertIsDisplayed()
        label(R.string.retry).assertDoesNotExist()
        label(R.string.cancel).performClick()
        compose.runOnIdle { assertEquals(listOf("permission", "retry", "cancel"), actions) }
    }
}
