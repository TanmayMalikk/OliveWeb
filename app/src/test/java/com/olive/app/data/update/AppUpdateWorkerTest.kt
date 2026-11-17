package com.olive.app.data.update

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import androidx.work.ListenableWorker.Result
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
            verify(clients.constructed().single(), never()).download(previous)
            assertTrue(notifications.allNotifications.isEmpty())
        }

        val stagedFile = File(context.filesDir, "synthetic-release.apk")
        mockConstruction(AppUpdateClient::class.java) { client, _ ->
            `when`(client.fetchLatest()).thenReturn(release)
            `when`(client.download(release)).thenReturn(stagedFile)
        }.use { clients ->
            assertEquals(Result.success(), worker(context).doWork())
            verify(clients.constructed().single()).download(release)
            val notification = notifications.allNotifications.single()
            assertEquals(OliveNotificationManager.CHANNEL_APP_UPDATES, notification.channelId)
            val launch = shadowOf(notification.contentIntent).savedIntent
            assertEquals(AppUpdateInstallerActivity::class.java.name, launch.component?.className)
            assertEquals(release, AppReleaseManifest.fromJson(launch.getStringExtra(AppUpdateInstallerActivity.EXTRA_RELEASE_JSON)!!))
        }
    }

    @Test
    fun anInterruptedDownloadRetriesButIntegrityErrorsAndExhaustedAttemptsDoNotNotify() = runBlocking<Unit> {
        val context = notificationContext()
        mockConstruction(AppUpdateClient::class.java) { client, _ ->
            `when`(client.fetchLatest()).thenReturn(release)
            doAnswer { throw IOException("Synthetic interrupted download") }.`when`(client).download(release)
        }.use {
            assertEquals(Result.retry(), worker(context, attempts = 1).doWork())
            assertEquals(Result.failure(), worker(context, attempts = 3).doWork())
        }
        mockConstruction(AppUpdateClient::class.java) { client, _ ->
            `when`(client.fetchLatest()).thenReturn(release)
            doThrow(IllegalStateException("Synthetic checksum mismatch")).`when`(client).download(release)
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

    private fun worker(context: Context, attempts: Int = 0): AppUpdateWorker {
        val parameters = mock(WorkerParameters::class.java)
        val tasks = mock(TaskExecutor::class.java)
        `when`(parameters.backgroundExecutor).thenReturn(Executor(Runnable::run))
        `when`(parameters.taskExecutor).thenReturn(tasks)
        `when`(parameters.runAttemptCount).thenReturn(attempts)
        return AppUpdateWorker(context, parameters)
    }
}
