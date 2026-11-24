package com.olive.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.olive.app.R
import com.olive.app.security.DeviceEnrollment
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.users.user.dashboard.LocalLocationPermissionRequest
import com.olive.app.ui.users.user.notes.PrivateNotes
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class IncidentLocationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun deniedLocationPermissionKeepsIncidentEditableWithManualCoordinates() {
        val activity = compose.activity
        lateinit var session: SessionViewModel
        compose.runOnUiThread {
            session = ViewModelProvider(activity)[SessionViewModel::class.java]
        }
        compose.waitUntil(15_000) { !session.busy }
        compose.runOnUiThread {
            session.runtime.enrollment = DeviceEnrollment(UUID.randomUUID().toString(), "")
            session.runtime.state.screen = SessionScreen.Safe
            session.runtime.state.page = SafePage.Notes
        }
        val attempts = AtomicInteger()
        compose.setContent {
            OliveTheme {
                CompositionLocalProvider(LocalLocationPermissionRequest provides { attempts.incrementAndGet(); false }) {
                    PrivateNotes(session)
                }
            }
        }
        compose.onNodeWithTag("new_private_entry").performClick()
        compose.onNodeWithTag("private_kind_incident").performClick()
        val editor = compose.onNodeWithTag("private_editor_list")
        val field = hasTestTag("incident_location")
        editor.performScrollToNode(field)
        compose.onNodeWithTag("incident_location_pin").performClick()
        val error = hasText(activity.getString(R.string.incident_location_lookup_failed))
        compose.waitUntil(10_000) { attempts.get() == 1 }
        editor.performScrollToNode(error)
        compose.onNode(error).assertIsDisplayed()
        editor.performScrollToNode(field)
        compose.onNode(field).performTextReplacement("53.5, -113.5")
        compose.onNode(field).assertTextContains("53.5, -113.5")
        assertEquals(1, attempts.get())
    }
}
