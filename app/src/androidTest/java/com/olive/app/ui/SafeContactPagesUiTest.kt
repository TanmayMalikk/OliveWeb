package com.olive.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.BuildConfig
import com.olive.app.R
import com.olive.app.data.account.*
import com.olive.app.security.DeviceEnrollment
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.users.safecontact.SafeContactArea
import com.olive.app.ui.users.shared.navigation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SafeContactPagesUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun session():SessionViewModel {
        check(!BuildConfig.FIREBASE_CONFIGURED)
        lateinit var session:SessionViewModel
        instrumentation.runOnMainSync { session=ViewModelProvider(compose.activity)[SessionViewModel::class.java] }
        compose.waitUntil(15000) { var ready=false;instrumentation.runOnMainSync { ready=!session.busy };ready }
        instrumentation.runOnMainSync {
            session.runtime.enrollment=DeviceEnrollment("synthetic-contact","",AccountRole.SAFE_CONTACT)
            session.runtime.state.accountRole=AccountRole.SAFE_CONTACT
            session.runtime.state.coverUsername="contact_fixture"
            session.runtime.state.profile=UserProfile("contact_fixture","Synthetic Contact","","","2000-01-01","English","","Edmonton","",role=AccountRole.SAFE_CONTACT)
            session.runtime.state.screen=SessionScreen.Safe
            session.runtime.state.page=SafePage.Home
        }
        return session
    }
    @Test
    fun safeContactCanOpenOwnProfileAndItsCategorizedSettingsThenReturnHome() {
        val session=session()
        compose.setContent { OliveTheme { SafeContactArea(session,{}) } }
        compose.onNodeWithTag("safe_contact_profile").performClick()
        compose.onNodeWithText("contact_fixture").assertExists()
        compose.onNodeWithText("2000-01-01").assertExists()
        compose.onNodeWithTag("safe_contact_edit_profile").performScrollTo().performClick()
        compose.onNodeWithTag("settings_tab_permissions").performClick()
        compose.onNodeWithTag("settings_install_permission").assertIsDisplayed()
        compose.onNodeWithTag("settings_tab_password").performClick()
        compose.onNodeWithTag("settings_tab_profile").performClick()
        compose.onNodeWithTag("settings_full_name").assertTextContains("Synthetic Contact")
        compose.runOnIdle { session.navigation.back() }
        compose.onNodeWithTag("safe_contact_profile_back").performClick()
        compose.onNodeWithTag("safe_contact_profile").assertExists()
    }
    @Test
    fun sharedIncidentPreviewShowsItsFieldsAndCloseReturnsToTheContactDashboard() {
        val session=session()
        compose.runOnIdle { session.runtime.state.viewedSharedEntry=PrivateEntry(title="Synthetic incident",body="Synthetic details",kind=PrivateEntryKind.Incident,
            incidentDate="2025-05-10",incidentTime="13:20",incidentLocation="Synthetic address") }
        compose.setContent { OliveTheme { CompositionLocalProvider(LocalQuickExit provides {}) { SafeContactArea(session,{}) } } }
        compose.onNodeWithText("Synthetic incident").assertExists()
        compose.onNodeWithText("Synthetic details").assertExists()
        compose.onNodeWithText("Synthetic address").assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.close_shared_entry)).performClick()
        compose.runOnIdle { assertNull(session.viewedSharedEntry) }
        compose.onNodeWithTag("safe_contact_profile").assertExists()
    }
    @Test
    fun sharedNotePreviewKeepsItsTitleAndBodyDistinctFromIncidentFields() {
        val session=session()
        compose.runOnIdle { session.runtime.state.viewedSharedEntry=PrivateEntry(title="Synthetic note",body="Synthetic note body") }
        compose.setContent { OliveTheme { SafeContactArea(session,{}) } }
        compose.onNodeWithText("Synthetic note").assertExists()
        compose.onNodeWithText("Synthetic note body").assertExists()
        compose.onNodeWithContentDescription(context.getString(R.string.close_shared_entry)).performClick()
        compose.onNodeWithText("Synthetic note body").assertDoesNotExist()
    }
}
