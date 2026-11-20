package com.olive.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.R
import com.olive.app.data.account.*
import com.olive.app.data.location.LocationSharingDataSource
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.messaging.Friend
import com.olive.app.security.DeviceEnrollment
import com.olive.app.security.EncryptedVault
import com.olive.app.ui.session.SessionDataSources
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.user.dashboard.LocalBackgroundLocationPermissionRequest
import com.olive.app.ui.users.user.location.LocationMapScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import javax.crypto.SecretKey

class LocationWorkspaceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun text(id: Int) = instrumentation.targetContext.getString(id)

    private class Locations : LocationSharingDataSource {
        var savedEnabled = false
        var attemptedLive = false
        override fun staticSharingEnabled(uid: String) = savedEnabled
        override suspend fun setStaticSharing(identity: AccountIdentity, profile: UserProfile, friends: List<Friend>, enabled: Boolean) {
            check(identity.uid == "map-fixture")
            savedEnabled = enabled
        }
        override suspend fun setLiveRecipients(ownerUid: String, friends: List<Friend>): SecretKey? {
            attemptedLive = true
            return null
        }
        override fun observeStaticContacts(currentUid: String, friends: List<Friend>, onUpdate: (List<MapLocationPoint>) -> Unit) =
            ChatSubscription {}
        override fun observeLiveFriends(currentUid: String, friends: List<Friend>, onUpdate: (List<MapLocationPoint>) -> Unit): ChatSubscription {
            onUpdate(listOf(
                MapLocationPoint("first", "Synthetic live sender", 53.0, -113.0, 100, true),
                MapLocationPoint("second", "Synthetic last known sender", 53.1, -113.1, 100, false),
            ))
            return ChatSubscription {}
        }
    }

    private fun session(role: AccountRole, locations: Locations): SessionViewModel {
        val app = compose.activity.application
        val sources = SessionDataSources(LocalAccountRepository(EncryptedVault(app, "map-test-accounts")),
            null, null, null, null, null, null, locations, null, null, null)
        lateinit var session: SessionViewModel
        instrumentation.runOnMainSync {
            session = ViewModelProvider(compose.activity, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = SessionViewModel(app, sources, false) as T
            })[SessionViewModel::class.java]
        }
        compose.waitUntil(15000) {
            var ready = false
            instrumentation.runOnMainSync { ready = !session.busy }
            ready
        }
        instrumentation.runOnMainSync {
            session.runtime.enrollment = DeviceEnrollment("map-fixture", "", role)
            session.runtime.state.accountRole = role
            session.runtime.state.coverUsername = "map_fixture"
            session.runtime.state.profile = UserProfile("map_fixture", "Synthetic map user", "", "", "2000-01-01",
                "English", "", "Edmonton", "", address = DeliveryAddress(latitude = 53.5, longitude = -113.5), role = role)
            session.runtime.state.friends = listOf(Friend("friendship", "first", "Synthetic friend"))
            session.runtime.pinAuthorizedUid = "map-fixture"
            session.runtime.pinAuthorizedEpoch = session.runtime.epoch
            session.runtime.state.screen = SessionScreen.Safe
            session.runtime.state.page = SafePage.Location
        }
        return session
    }

    @Test
    fun sharingRequiresSelectingAFriendAndDeniedLocationNeverGrantsAccess() {
        val locations = Locations()
        val session = session(AccountRole.USER, locations)
        var backgroundRequests = 0
        compose.setContent {
            OliveTheme {
                CompositionLocalProvider(LocalBackgroundLocationPermissionRequest provides { backgroundRequests++; false }) {
                    LocationMapScreen(session)
                }
            }
        }
        val start = compose.onNodeWithTag("start_location_sharing")
        start.performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("background_location").performScrollTo().performClick()
        compose.onNodeWithTag("background_location_continue").performClick()
        compose.runOnIdle { assertEquals(1, backgroundRequests) }
        compose.onNodeWithTag("location_friend_first").performScrollTo().performClick()
        start.performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("location_duration_10_seconds").performClick()
        compose.onNodeWithText(text(R.string.location_permission_required)).assertExists()
        compose.runOnIdle { assertFalse(locations.attemptedLive) }
    }

    @Test
    fun safeContactSeesLiveSendersAndCanExpandTheMapAndChangeSavedLocationSharing() {
        val locations = Locations()
        val session = session(AccountRole.SAFE_CONTACT, locations)
        compose.setContent { OliveTheme { LocationMapScreen(session) } }
        compose.onNodeWithText("Synthetic live sender").assertExists()
        compose.onNodeWithTag("location_navigate").assertIsEnabled()
        compose.onNodeWithTag("location_expand_map").performClick()
        compose.onNodeWithTag("full_screen_location_map").assertIsDisplayed()
        compose.onNodeWithTag("exit_full_screen_navigation").performClick()
        compose.onNodeWithTag("location_expand_map").assertIsDisplayed()
        compose.onNodeWithTag("share_saved_location").assertIsOff().performClick()
        compose.onNodeWithTag("share_saved_location").assertIsOn()
        compose.runOnIdle { assertTrue(locations.savedEnabled) }
    }
}
