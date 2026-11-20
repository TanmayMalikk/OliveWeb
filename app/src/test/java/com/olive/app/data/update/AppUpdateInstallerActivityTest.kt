package com.olive.app.data.update

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import com.olive.app.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppUpdateInstallerActivityTest {
    private val release = AppReleaseManifest(
        packageName = "com.olive.app",
        versionCode = BuildConfig.VERSION_CODE.toLong() + 1,
        versionName = "Next release",
        minimumSupportedVersionCode = 1,
        apkUrl = "https://yourolivebranch.ca/get/fixture.apk",
        sha256 = "a".repeat(64),
        sizeBytes = 123L,
        publishedAt = "",
        mandatory = false,
        releaseNotes = emptyList(),
    )

    @Test
    fun absentMalformedAndAlreadyInstalledReleasesCloseImmediately() {
        val reports = listOf(null, "{invalid", release.copy(versionCode = BuildConfig.VERSION_CODE.toLong()).toJson())
        reports.forEach { manifest ->
            Robolectric.buildActivity(AppUpdateInstallerActivity::class.java, intent(manifest)).use {
                assertTrue(it.create().get().isFinishing)
            }
        }
    }

    @Test
    fun restoringTheInstallerPreservesTheOngoingSystemPrompt() {
        val restoring = Bundle().apply { putBoolean("installing", true) }
        Robolectric.buildActivity(AppUpdateInstallerActivity::class.java, intent(release.toJson())).use {
            it.create(restoring)
            assertFalse(it.get().isFinishing)
            val saved = Bundle()
            it.saveInstanceState(saved)
            assertTrue(saved.getBoolean("installing"))
        }
    }

    @Test
    fun installerPermissionTargetsOnlyTheRunningPackage() {
        val context = RuntimeEnvironment.getApplication()
        val settings = installUpdatesPermissionIntent(context)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.action)
        assertEquals("package:${context.packageName}", settings.dataString)
    }

    private fun intent(manifest: String?) = Intent().apply {
        if (manifest != null) putExtra(AppUpdateInstallerActivity.EXTRA_RELEASE_JSON, manifest)
    }
}
