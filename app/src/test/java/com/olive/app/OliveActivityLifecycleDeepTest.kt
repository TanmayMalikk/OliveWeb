package com.olive.app

import android.app.Application
import android.content.Intent
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.CoverType
import com.olive.app.notification.OliveNotificationManager
import com.olive.app.support.TestAndroidKeyStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.security.Security

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class OliveActivityLifecycleDeepTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var keysHandle: AutoCloseable? = null

    @Before
    fun setUp() {
        Security.removeProvider("AndroidKeyStore")
        keysHandle = TestAndroidKeyStore.install()
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("1:123:android:abc")
                    .setApiKey("test-key")
                    .setProjectId("test-proj")
                    .build(),
            )
        }
        runCatching { WorkManager.getInstance(context) }.onFailure {
            WorkManager.initialize(context, Configuration.Builder().build())
        }
    }

    @After
    fun tearDown() {
        keysHandle?.close()
        Security.removeProvider("AndroidKeyStore")
    }

    @Test
    fun helperPredicatesAndLauncherWorkCorrectly() {
        assertTrue(shouldBlankRecentPreview(protectionEnabled = true, sensitive = true))
        assertFalse(shouldBlankRecentPreview(protectionEnabled = true, sensitive = false))
        assertFalse(shouldBlankRecentPreview(protectionEnabled = false, sensitive = true))

        assertTrue(shouldApplyTaskIdentity(null, CoverType.FITNESS, AccountRole.USER))
        assertFalse(shouldApplyTaskIdentity(CoverType.NEWS to AccountRole.USER, CoverType.NEWS, AccountRole.USER))
        assertTrue(shouldApplyTaskIdentity(CoverType.NEWS to AccountRole.USER, CoverType.FITNESS, AccountRole.USER))

        assertTrue(shouldRequestNotifications(enabled = true, asked = false, granted = false))
        assertFalse(shouldRequestNotifications(enabled = true, asked = true, granted = false))
        assertFalse(shouldRequestNotifications(enabled = true, asked = false, granted = true))

        assertTrue(shouldOpenLocationSettings(asked = true, fineRationale = false, coarseRationale = false))
        assertFalse(shouldOpenLocationSettings(asked = false, fineRationale = false, coarseRationale = false))
        assertFalse(shouldOpenLocationSettings(asked = true, fineRationale = true, coarseRationale = false))

        val launcher = Robolectric.buildActivity(OliveLauncher::class.java).setup()
        assertTrue(launcher.get().isFinishing)
    }

    @Test
    fun oliveActivityLifecycleCreatesTransitionsAndTearsDownCleanly() {
        val intent = Intent(context, OliveActivity::class.java)
            .putExtra(OliveNotificationManager.EXTRA_INCOMING_CALL_ID, "call-1")
            .putExtra(OliveNotificationManager.EXTRA_INCOMING_CALL_CALLER_ID, "caller-1")
            .putExtra(OliveNotificationManager.EXTRA_INCOMING_CALL_NAME, "Sam")

        val controller = Robolectric.buildActivity(OliveActivity::class.java, intent)
            .create()
            .start()
            .resume()
        val activity = controller.get()
        activity.onUserInteraction()
        activity.onUserLeaveHint()
        controller.newIntent(Intent(context, OliveActivity::class.java))
        controller.pause().stop().destroy()
    }
}
