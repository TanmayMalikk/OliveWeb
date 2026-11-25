package com.olive.app.ui

import android.content.Intent
import android.Manifest
import android.content.ContextWrapper
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.olive.app.R
import com.olive.app.BuildConfig
import com.olive.app.data.update.AppReleaseManifest
import com.olive.app.data.update.AppUpdateStore
import com.olive.app.data.update.AppUpdateInstallerActivity
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.user.dashboard.LocalBackgroundLocationPermissionRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SafePermissionsAndUpdatesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(resource: Int) = context.getString(resource)

    @Test
    fun permissionsExplainFeatureAccessAndOpenTheCorrectInstallSettings() {
        var opened: Intent? = null
        var requested = emptyList<String>()
        var backgroundRequests = 0
        val cameraAllowed = mutableStateOf(false)
        compose.setContent {
            val base = LocalContext.current
            val fixture = remember(base) {
                object : ContextWrapper(base) {
                    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                        if (permission == Manifest.permission.CAMERA) {
                            if (cameraAllowed.value) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                        }
                        else super.checkPermission(permission, pid, uid)
                }
            }
            OliveTheme {
                CompositionLocalProvider(
                    LocalContext provides fixture,
                    LocalBackgroundLocationPermissionRequest provides { backgroundRequests++; false },
                    LocalSafeSystemLauncher provides { opened = it; true },
                    LocalSafePermissionActions provides SafePermissionActions(
                        if (cameraAllowed.value) 1 else 0, { requested = it.toList(); true }, { false },
                    ),
                ) {
                    Column(Modifier.verticalScroll(rememberScrollState())) { SafePermissionsSettings() }
                }
            }
        }
        compose.onNodeWithText(text(R.string.app_permissions_install_why)).assertExists()
        compose.onNodeWithText(text(R.string.app_permissions_biometrics)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.app_permissions_why)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.app_permissions_background_location_why)).assertExists()
        compose.onNodeWithText(text(R.string.app_permissions_camera_why)).assertExists()
        compose.onNodeWithText(text(R.string.app_permissions_location_why)).assertExists()
        compose.onNodeWithTag("settings_permission_BackgroundLocation").performScrollTo().performClick()
        compose.onNodeWithTag("background_location_continue").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, backgroundRequests) }
        compose.onNodeWithTag("background_location_continue").performClick()
        compose.runOnIdle { assertEquals(1, backgroundRequests) }
        compose.onNodeWithText(text(R.string.app_permissions_health_why)).assertExists()
        val cameraStatus = compose.onNodeWithTag("settings_permission_status_Camera")
        cameraStatus.assertTextEquals(text(R.string.app_permissions_disallowed))
        compose.onNodeWithTag("settings_permission_Camera").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(Manifest.permission.CAMERA), requested)
            cameraAllowed.value = true
        }
        cameraStatus.assertTextEquals(text(R.string.app_permissions_allowed))
        compose.onNodeWithTag("settings_install_permission").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, opened?.action)
            assertEquals("package:${context.packageName}", opened?.dataString)
        }
    }

    @Test
    fun updateBadgeAppearsOnlyForAnAvailableReleaseAndOpensTheInstaller() {
        val release = AppReleaseManifest("com.olive.app", BuildConfig.VERSION_CODE.toLong() + 1, "Fixture release", 1,
            "https://yourolivebranch.ca/fixture.apk", "1".repeat(64), 20, "", false, emptyList())
        val prefs = context.getSharedPreferences("test_updates", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val fixture = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = prefs
        }
        val store = AppUpdateStore(fixture)
        val enabled = mutableStateOf(true)
        val started = mutableListOf<Intent>()
        compose.setContent {
            val lifecycle = remember {
                object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry(this).apply { currentState = Lifecycle.State.CREATED }
                }
            }
            OliveTheme {
                CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalContext provides fixture) {
                    val update = rememberSafeAppUpdate(enabled.value) {
                        started.add(it)
                        true
                    }
                    CompositionLocalProvider(LocalSafeUpdateReminder provides update) {
                        SafeAppUpdateReminder()
                    }
                }
            }
        }
        val updateButton = compose.onNodeWithTag("safe_update_reminder")
        val badge = compose.onNodeWithTag("safe_update_badge", useUnmergedTree = true)
        updateButton.assertDoesNotExist()
        badge.assertDoesNotExist()
        compose.runOnIdle { store.publish(release) }
        updateButton.assertIsDisplayed().assertHasClickAction()
        badge.assertIsDisplayed()
        updateButton.assertContentDescriptionEquals(text(R.string.ui_a_new_update_is_available))
        compose.onNodeWithText(text(R.string.ui_a_new_update_is_available)).assertDoesNotExist()
        compose.runOnIdle { enabled.value = false }
        updateButton.assertDoesNotExist()
        compose.runOnIdle { enabled.value = true }
        updateButton.performClick()
        compose.runOnIdle {
            val intent = started.single()
            assertEquals(AppUpdateInstallerActivity::class.java.name, intent.component?.className)
            assertEquals(release.versionCode, AppReleaseManifest.fromJson(
                checkNotNull(intent.getStringExtra(AppUpdateInstallerActivity.EXTRA_RELEASE_JSON)),
            ).versionCode)
            store.publish(release.copy(versionCode = release.versionCode + 1, mandatory = true))
        }
        badge.assertIsDisplayed()
        updateButton.assertContentDescriptionEquals(text(R.string.app_update_required))
        compose.onNodeWithText(text(R.string.app_update_required)).assertDoesNotExist()
        compose.runOnIdle { store.publish(release.copy(versionCode = BuildConfig.VERSION_CODE.toLong())) }
        updateButton.assertDoesNotExist()
    }
}
