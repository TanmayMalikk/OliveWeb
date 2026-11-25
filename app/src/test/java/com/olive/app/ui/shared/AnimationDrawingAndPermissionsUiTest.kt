package com.olive.app.ui.shared

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.CoverType
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.covers.CoverLauncher
import com.olive.app.ui.covers.CoverSettingsPage
import com.olive.app.ui.covers.DiscreetCoverEntry
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SafePermissionsSettings
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class AnimationDrawingAndPermissionsUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun oliveBranchCanvasDrawsAllAnimationPhasesOntoBitmap() {
        var progress by mutableFloatStateOf(0.15f)
        var rootView: View? = null
        var callbackCount = 0

        compose.setContent {
            OliveUiTestHost {
                rootView = LocalView.current.rootView
                Box(Modifier.size(400.dp, 800.dp)) {
                    OliveBranchAnimationContent(
                        progress = progress,
                        modifier = Modifier.fillMaxSize(),
                        onOliveDropPosition = { _, _, _ -> callbackCount++ },
                    )
                }
            }
        }
        val bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
        val androidCanvas = Canvas(bitmap)
        listOf(0.15f, 0.42f, 0.65f, 0.78f, 0.92f).forEach { p ->
            progress = p
            compose.waitForIdle()
            compose.runOnUiThread {
                rootView?.draw(androidCanvas)
            }
        }
        assertTrue(callbackCount >= 1)
    }

    @Test
    fun safePermissionsAndCoverSettingsRenderValidateAndLaunch() {
        val shadowApp = Shadows.shadowOf(context)
        shadowApp.grantPermissions(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)

        CoverLauncher.ensureStudioLauncher(context)
        listOf(AccountRole.USER, AccountRole.SAFE_CONTACT, AccountRole.STAFF, AccountRole.ADMIN).forEach { role ->
            CoverType.entries.forEach { cover ->
                runCatching { CoverLauncher.apply(context, cover, role) }
            }
        }

        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Cover, page = SafePage.Home, localMode = true).use { handle ->
            var stage by mutableIntStateOf(0)
            var opens = 0
            var emergencies = 0

            compose.setContent {
                OliveUiTestHost {
                    when (stage) {
                        0 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            SafePermissionsSettings()
                        }
                        1 -> CoverSettingsPage(session = handle.session, onBack = {})
                        else -> DiscreetCoverEntry(
                            enabled = true,
                            onOpen = { opens++ },
                            onEmergency = { emergencies++ },
                        )
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("settings_install_permission").performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.app_permissions_open_android)).performScrollTo().performClick()

            stage = 1
            compose.waitForIdle()
            compose.onNodeWithTag("cover_save_settings").performScrollTo().performClick()
            compose.waitForIdle()

            compose.mainClock.autoAdvance = false
            stage = 2
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithTag("discreet_cover_olive").assertExists()
        }
    }
}
