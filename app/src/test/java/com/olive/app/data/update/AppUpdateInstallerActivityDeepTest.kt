package com.olive.app.data.update

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.olive.app.BuildConfig
import com.olive.app.R
import com.olive.app.support.OliveUiTestHost
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class AppUpdateInstallerActivityDeepTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun appUpdateInstallerContentCoversAllStagesAndButtons() {
        var stage by mutableStateOf(AppUpdateStage.Preparing)
        var permissions = 0
        var retries = 0
        var cancels = 0

        compose.setContent {
            OliveUiTestHost(privateDesign = false) {
                AppUpdateInstallerContent(
                    version = "9.9.9",
                    stage = stage,
                    onPermission = { permissions++ },
                    onRetry = { retries++ },
                    onCancel = { cancels++ },
                )
            }
        }
        compose.onNodeWithText(context.getString(R.string.app_update_preparing)).assertExists()

        stage = AppUpdateStage.Permission
        compose.onNodeWithText(context.getString(R.string.app_permissions_install)).performClick()

        stage = AppUpdateStage.Failed
        compose.onNodeWithText(context.getString(R.string.retry)).performClick()

        stage = AppUpdateStage.Installing
        compose.onNodeWithText(context.getString(R.string.app_update_installing)).assertExists()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()

        assertEquals(1, permissions)
        assertEquals(1, retries)
        assertEquals(1, cancels)
    }

    @Test
    fun appUpdateInstallerActivityHandlesMissingManifestAndCachedApk() {
        val emptyController = Robolectric.buildActivity(AppUpdateInstallerActivity::class.java, Intent()).setup()
        assertTrue(emptyController.get().isFinishing)

        val apkBytes = "fake-apk-payload".toByteArray()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }
        val nextCode = BuildConfig.VERSION_CODE.toLong() + 50L
        val cachedDir = File(context.filesDir, "updates").apply { mkdirs() }
        File(cachedDir, "olive-release-$nextCode.apk").writeBytes(apkBytes)

        val manifest = AppReleaseManifest(
            packageName = "com.olive.app",
            versionCode = nextCode,
            versionName = "2.5.0",
            minimumSupportedVersionCode = 1L,
            apkUrl = "https://yourolivebranch.ca/app.apk",
            sha256 = sha256,
            sizeBytes = apkBytes.size.toLong(),
        )
        val intent = Intent(context, AppUpdateInstallerActivity::class.java)
            .putExtra(AppUpdateInstallerActivity.EXTRA_RELEASE_JSON, manifest.toJson())
        val controller = Robolectric.buildActivity(AppUpdateInstallerActivity::class.java, intent).setup()
        val outState = Bundle()
        controller.saveInstanceState(outState)
        assertNotNull(installUpdatesPermissionIntent(context))
    }
}
