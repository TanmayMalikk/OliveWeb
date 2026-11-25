package com.olive.app

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.CoverType
import com.olive.app.notification.OliveNotificationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class OliveActivityLifecycleTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun ensureInitialized() {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId("1:123456789012:android:abcdef123456")
                    .setApiKey("test-api-key")
                    .setProjectId("olive-test")
                    .build(),
            )
        }
        if (!WorkManager.isInitialized()) {
            WorkManager.initialize(context, Configuration.Builder().build())
        }
    }

    @Test
    fun oliveActivityLifecycleAndHelperPredicatesRunCleanly() {
        ensureInitialized()
        assertFalse(shouldBlankRecentPreview(protectionEnabled = false, sensitive = true))
        assertTrue(shouldBlankRecentPreview(protectionEnabled = true, sensitive = true))
        assertTrue(shouldApplyTaskIdentity(null, CoverType.FITNESS, AccountRole.USER))
        assertFalse(shouldApplyTaskIdentity(CoverType.NEWS to AccountRole.USER, CoverType.NEWS, AccountRole.USER))

        val launchIntent = Intent().apply {
            putExtra(OliveNotificationManager.EXTRA_INCOMING_CALL_ID, "call-1")
            putExtra(OliveNotificationManager.EXTRA_INCOMING_CALL_CALLER_ID, "caller-1")
            putExtra(OliveNotificationManager.EXTRA_INCOMING_CALL_NAME, "Sam")
        }
        val controller = Robolectric.buildActivity(OliveActivity::class.java, launchIntent)
        val activity = controller.create().start().resume().get()
        activity.onUserInteraction()
        controller.newIntent(Intent().putExtra(OliveNotificationManager.EXTRA_INCOMING_CALL_ID, "call-2"))
        activity.onUserLeaveHint()
        controller.pause().stop().destroy()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
}
