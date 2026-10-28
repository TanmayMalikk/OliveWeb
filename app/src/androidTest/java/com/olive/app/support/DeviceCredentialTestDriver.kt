// Drives real Android PIN prompts during safe-app security tests.
package com.olive.app

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import android.app.UiAutomation
import android.app.KeyguardManager
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*

// Drive the actual Android prompt and Keystore operation on a prepared disposable emulator.
internal class DeviceCredentialTestDriver(
    private val compose: ComposeTestRule,
    private val automation: UiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
) {
    fun requirePreparedEmulator() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val emulator = shellOutput("getprop ro.kernel.qemu").trim() == "1"
        assertTrue("Device-lock tests may only run on a disposable emulator.", emulator)
        assertTrue(
            "Prepare the emulator with test PIN 246810 before running device-lock tests.",
            keyguard.isDeviceSecure
        )
    }

    fun confirmPhonePin() {
        waitForPhonePin()
        // This PIN belongs only to the disposable emulator used by this test run.
        if (findNode(automation.rootInActiveWindow) { it.viewIdResourceName == "cred_pin_pad" } != null) {
            "246810".forEach { digit ->
                val key =
                    findNode(automation.rootInActiveWindow) { it.className?.toString() == "android.widget.Button" && it.text?.toString() == digit.toString() }
                assertTrue(
                    "Android PIN key $digit unavailable",
                    key?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                )
                automation.waitForIdle(150, 3_000)
            }
            compose.waitUntil(5_000) {
                findNode(automation.rootInActiveWindow) { it.viewIdResourceName == "com.android.systemui:id/key_enter" && it.isEnabled }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
        } else {
            shell("input text 246810")
            shell("input keyevent 66")
        }
    }

    fun waitForPhonePin() {
        compose.waitUntil(20_000) {
            val root = automation.rootInActiveWindow
            // The cover's search box is also an EditText; only accept the system credential window.
            if (root == null || root.packageName?.toString() == "com.olive.app") return@waitUntil false
            val field =
                findNode(root) { it.className?.toString() == "android.widget.EditText" || it.viewIdResourceName == "cred_pin_pad" }
            if (field != null) true else {
                findNode(root) {
                    it.text?.toString()?.contains("Use PIN", ignoreCase = true) == true
                }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                val hasPinText = findNode(root) {
                    it.text?.toString()?.contains("PIN", ignoreCase = true) == true
                } != null
                val hasDigitKey = findNode(root) {
                    it.className?.toString() == "android.widget.Button" &&
                            it.text?.toString()?.length == 1 && it.text?.toString()?.all(Char::isDigit) == true
                } != null
                hasPinText || hasDigitKey
            }
        }
        // SystemUI creates keypad nodes before its opening animation starts accepting PIN input.
        automation.waitForIdle(1_000, 5_000)
    }


    private fun findNode(
        node: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) findNode(
            node.getChild(index),
            predicate
        )?.let { return it }
        return null
    }

    fun shell(command: String) {
        shellOutput(command)
    }

    private fun shellOutput(command: String): String {
        val fd = automation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }
}
