package com.olive.app.data.update

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import androidx.work.ListenableWorker.Result
import androidx.work.Data
import androidx.work.workDataOf
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import com.olive.app.BuildConfig
import com.olive.app.notification.OliveNotificationManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpdateWorkerTest {
    private val release = AppReleaseManifest(
        "com.olive.app", BuildConfig.VERSION_CODE.toLong() + 1, "Synthetic release", 1,
        "https://yourolivebranch.ca/fixture.apk", "1".repeat(64), 20, "", false, listOf("Fixture note"),
    )

    @Test
    fun onlyANewVerifiedApkCreatesAnInstallerNotificationWithTheExactRelease() = runBlocking<Unit> {
        val context = notificationContext()
        val manager = context.getSystemService(NotificationManager::class.java)
        val notifications = shadowOf(manager)
        val previous = release.copy(versionCode = BuildConfig.VERSION_CODE.toLong())
        mockConstruction(AppUpdateClient::class.java) { client, _ ->
            `when`(client.fetchLatest()).thenReturn(previous)
        }.use { clients ->
            assertEquals(Result.success(), worker(context).doWork())
            verify(clients.constructed().single(), never()).prepare(previous)
            assertTrue(notifications.allNotifications.isEmpty())
        }

        val stagedFile = File(context.filesDir, "synthetic-release.apk")
        mockConstruction(AppUpdateClient::class.java) { client, _ ->
            `when`(client.fetchLatest()).thenReturn(release)
            `when`(client.prepare(release)).thenReturn(stagedFile)
        }.use { clients ->
            assertEquals(Result.success(), worker(context).doWork())
            verify(clients.constructed().single()).prepare(release)
            val notification = notifications.allNotifications.single()
            assertEquals(OliveNotificationManager.CHANNEL_APP_UPDATES, notification.channelId)
            val launch = shadowOf(notification.contentIntent).savedIntent
            assertEquals(AppUpdateInstallerActivity::class.java.name, launch.component?.className)
            assertEquals(release, AppReleaseManifest.fromJson(launch.getStringExtra(AppUpdateInstallerActivity.EXTRA_RELEASE_JSON)!!))
        }
    }

    @Test
    fun checkOnlyOffersTheReleaseBeforeTheApkIsDownloaded() = runBlocking<Unit> {
        val context = notificationContext()
        val data = workDataOf(AppUpdateWorker.CHECK_ONLY to true)
        mockConstruction(AppUpdateClient::class.java) { client, _ ->
            `when`(client.fetchLatest()).thenReturn(release)
        }.use { clients ->
            assertEquals(Result.success(), worker(context, data = data).doWork())
            verify(clients.constructed().single(), never()).prepare(release)
        }
        assertEquals(release, AppUpdateStore(context).offer()?.release)
    }

    @Test
    fun minimizationPersistsAcrossSessionsUntilANewerRelease() {
        val context = notificationContext()
        val installed = BuildConfig.VERSION_CODE.toLong()
        val store = AppUpdateStore(context)
        store.publish(release.copy(mandatory = true))
        assertTrue(checkNotNull(store.offer()).required)
        store.dismiss(checkNotNull(store.offer()))
        assertTrue(checkNotNull(AppUpdateStore(context).offer()).dismissed)
        store.publish(release.copy(releaseNotes = listOf("Same version with revised notes")))
        assertTrue(checkNotNull(store.offer()).dismissed)

        val nextRelease = release.copy(versionCode = release.versionCode + 1)
        store.publish(nextRelease)
        assertFalse(checkNotNull(store.offer()).dismissed)
        store.publish(nextRelease.copy(minimumSupportedVersionCode = installed + 1))
        assertTrue(checkNotNull(store.offer()).required)
        assertFalse(checkNotNull(store.offer()).dismissed)
        assertNull(AppUpdateStore(context, installedVersion = nextRelease.versionCode).offer())

        val offers = mutableListOf<AppUpdateOffer?>()
        val subscription = store.observe { offers += it }
        store.publish(nextRelease.copy(releaseNotes = listOf("Live observer update")))
        assertTrue(offers.size >= 2)
        subscription.close()
    }

    @Test
    fun anInterruptedDownloadRetriesButIntegrityErrorsAndExhaustedAttemptsDoNotNotify() = runBlocking<Unit> {
        val context = notificationContext()
        mockConstruction(AppUpdateClient::class.java) { client, _ ->
            `when`(client.fetchLatest()).thenReturn(release)
            doAnswer { throw IOException("Synthetic interrupted download") }.`when`(client).prepare(release)
        }.use {
            assertEquals(Result.retry(), worker(context, attempts = 1).doWork())
            assertEquals(Result.failure(), worker(context, attempts = 3).doWork())
        }
        mockConstruction(AppUpdateClient::class.java) { client, _ ->
            `when`(client.fetchLatest()).thenReturn(release)
            doThrow(IllegalStateException("Synthetic checksum mismatch")).`when`(client).prepare(release)
        }.use {
            assertEquals(Result.failure(), worker(context).doWork())
        }
        assertTrue(shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.isEmpty())
    }

    private fun notificationContext(): Context {
        val app = RuntimeEnvironment.getApplication()
        val resources = spy(app.resources)
        doReturn("Synthetic release notification").`when`(resources).getString(anyInt())
        return object : ContextWrapper(app) {
            override fun getApplicationContext() = this
            override fun getResources() = resources
        }
    }

    private fun worker(context: Context, attempts: Int = 0, data: Data = Data.EMPTY): AppUpdateWorker {
        val parameters = mock(WorkerParameters::class.java)
        val tasks = mock(TaskExecutor::class.java)
        `when`(parameters.backgroundExecutor).thenReturn(Executor(Runnable::run))
        `when`(parameters.taskExecutor).thenReturn(tasks)
        `when`(parameters.runAttemptCount).thenReturn(attempts)
        `when`(parameters.inputData).thenReturn(data)
        return AppUpdateWorker(context, parameters)
    }
}
