// Tests the talk back test session behavior.
package com.olive.app

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import android.app.Activity
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assume.assumeTrue

// Restore the emulator's accessibility settings even when a service-level assertion fails.
internal class TalkBackTestSession(activity: Activity) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resolver = activity.contentResolver
    private val service = "com.google.android.marvin.talkback/.TalkBackService"
    private val permission = android.Manifest.permission.POST_NOTIFICATIONS
    private val hadNotifications =
        android.os.Build.VERSION.SDK_INT < 33 || activity.packageManager.checkPermission(
            permission, "com.google.android.marvin.talkback"
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    private val keys = listOf(
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, Settings.Secure.ACCESSIBILITY_ENABLED,
        Settings.Secure.TOUCH_EXPLORATION_ENABLED
    )
    private val previous = keys.associateWith { Settings.Secure.getString(resolver, it) }
    val focused = CopyOnWriteArrayList<String>()
    val automation: UiAutomation

    init {
        assumeTrue(
            "TalkBack must be installed", activity.packageManager.resolveService(
                Intent().setComponent(ComponentName.unflattenFromString(service)), 0
            ) != null
        )
        if (!hadNotifications) instrumentation.uiAutomation.grantRuntimePermission(
            "com.google.android.marvin.talkback",
            permission
        )
        automation =
            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                focused += event.contentDescription?.toString().orEmpty()
        }
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            Settings.Secure.putString(
                resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                (previous[Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES].orEmpty().split(':')
                    .filter(String::isNotBlank) + service).distinct().joinToString(":")
            )
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
            .use { it.readBytes() }
    }

    override fun close() {
        automation.setOnAccessibilityEventListener(null)
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            previous.forEach { (key, value) -> Settings.Secure.putString(resolver, key, value) }
        } finally {
            automation.dropShellPermissionIdentity()
        }
        val restored = instrumentation.getUiAutomation(0)
        if (!hadNotifications) restored.revokeRuntimePermission(
            "com.google.android.marvin.talkback",
            permission
        )
    }
}
