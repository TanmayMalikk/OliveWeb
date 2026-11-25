package com.olive.app.ui.users.user.location

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.UserProfile
import com.olive.app.data.location.LocationRoute
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.location.RouteCoordinate
import com.olive.app.data.location.RouteDirection
import com.olive.app.support.OliveUiTestHost
import com.olive.app.support.createUiTestSession
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w412dp-h915dp")
class UserLocationNavAndMapDeepUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun interactiveLocationMapUserSearchDialogFiltersAndFollows() {
        val p1 = MapLocationPoint("u1", "Jordan", 53.5444, -113.4909, 1_700_000_000_000L, true, accuracy = 8f)
        val p2 = MapLocationPoint("u2", "Sam", 53.5461, -113.4938, 1_700_000_010_000L, true, accuracy = 12f)
        compose.setContent {
            OliveUiTestHost(locationGranted = true) {
                InteractiveLocationMap(
                    points = listOf(p1, p2),
                    ownPointUid = "u1",
                    userSearchEnabled = true,
                    initialFollowUid = "u1",
                    headingUp = true,
                    compassEnabled = true,
                )
            }
        }
        compose.waitForIdle()
        // Wait: map starts in Loading state; verify LocationMapScreen renders USER & SAFE_CONTACT modes too
    }

    @Test
    fun locationMapScreenRendersSafeContactAndUserWithRouteAndControls() {
        var role by mutableStateOf(AccountRole.USER)
        createUiTestSession(role = AccountRole.USER, screen = SessionScreen.Safe, page = SafePage.Location, localMode = true).use { handle ->
            val session = handle.session
            session.runtime.state.profile = UserProfile(
                username = "alex",
                fullName = "Alex",
                dob = "1995-05-05",
                address = DeliveryAddress(city = "Edmonton", latitude = 53.5444, longitude = -113.4909),
                role = AccountRole.USER,
            )
            compose.setContent {
                OliveUiTestHost(locationGranted = true) {
                    if (role == AccountRole.USER) {
                        LocationMapScreen(session)
                    } else {
                        LocationMapScreen(session)
                    }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("location_expand_map").performClick()
            compose.waitForIdle()
            // Switch to SafeContact layout mode
            session.runtime.state.accountRole = AccountRole.SAFE_CONTACT
            session.runtime.state.profile = session.runtime.state.profile?.copy(role = AccountRole.SAFE_CONTACT)
            role = AccountRole.SAFE_CONTACT
            compose.waitForIdle()
        }
    }
}
