package com.olive.app.ui.users.shared.organization

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.UserProfile
import com.olive.app.data.location.AdminLocationRepository
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OrganizationSettingsComposeTest {

    @Test
    fun renderDashboardCard() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            OrganizationDashboardCard(
                icon = Icons.AutoMirrored.Outlined.ArrowForward,
                title = "Zero Badge",
                description = "Zero badge description",
                onClick = {},
                badgeCount = 0
            )
            OrganizationDashboardCard(
                icon = Icons.AutoMirrored.Outlined.ArrowForward,
                title = "Normal Badge",
                description = "Normal badge description",
                onClick = {},
                badgeCount = 5
            )
            OrganizationDashboardCard(
                icon = Icons.AutoMirrored.Outlined.ArrowForward,
                title = "Large Badge",
                description = "Large badge description",
                onClick = {},
                badgeCount = 150
            )
        }
    }

    @Test
    fun renderOrganizationSettings() {
        val app = RuntimeEnvironment.getApplication()
        val session = SessionViewModel(app, localMode = true)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        // 1. Profile is null branch
        session.runtime.state.profile = null
        activity.setContent {
            OrganizationSettings(session, onBack = {})
        }

        // 2. Profile is non-null
        val profile = UserProfile(
            username = "owner",
            fullName = "Admin Fixture",
            dob = "2000-01-01",
            email = "admin@fixture.test",
            phone = "1234567890",
            role = "ADMIN"
        )
        session.runtime.state.profile = profile
        session.runtime.state.coverAccountId = "admin_acc"
        session.runtime.state.accountRole = AccountRole.ADMIN

        activity.setContent {
            OrganizationSettings(session, onBack = {})
        }
    }

    @Test
    fun renderOrganizationProfileSetup() {
        val app = RuntimeEnvironment.getApplication()
        val session = SessionViewModel(app, localMode = true)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        session.runtime.pendingProfile = UserProfile(
            username = "owner",
            fullName = "Admin Fixture",
            dob = "2000-01-01",
            email = "admin@fixture.test",
            phone = "1234567890",
            role = "ADMIN"
        )
        session.runtime.state.accountRole = AccountRole.ADMIN

        activity.setContent {
            OrganizationProfileSetup(session)
        }
    }

    @Test
    fun renderOrganizationMap() {
        val app = RuntimeEnvironment.getApplication()
        val session = SessionViewModel(app, localMode = true)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        session.runtime.state.coverAccountId = "admin_acc"
        session.runtime.state.accountRole = AccountRole.ADMIN

        activity.setContent {
            OrganizationMap(session, onBack = {}, initialFollowUid = "point-1")
        }
    }

    @Test
    fun adminLocationMapControllerObservesPointsAndErrors() {
        val repo = mock(AdminLocationRepository::class.java)
        var onUpdateCallback: ((List<MapLocationPoint>) -> Unit)? = null
        var onErrorCallback: ((String) -> Unit)? = null
        val sub = mock(ChatSubscription::class.java)

        `when`(repo.observe(any(), any())).thenAnswer { invocation ->
            @Suppress("UNCHECKED_CAST")
            onUpdateCallback = invocation.getArgument(0) as (List<MapLocationPoint>) -> Unit
            @Suppress("UNCHECKED_CAST")
            onErrorCallback = invocation.getArgument(1) as (String) -> Unit
            sub
        }

        val controller = AdminLocationMapController(repo)

        // Non-staff/admin role ignored
        controller.open(AccountRole.USER)
        verifyNoInteractions(repo)

        // Staff/admin opens
        controller.open(AccountRole.ADMIN)
        assertTrue(controller.state.loading)

        val point = MapLocationPoint("uid1", 53.5, -113.5, 1000L, true)
        onUpdateCallback?.invoke(listOf(point))
        assertFalse(controller.state.loading)
        assertEquals(listOf(point), controller.state.points)

        onErrorCallback?.invoke("Location feed failed")
        assertEquals("Location feed failed", controller.state.error)

        controller.close()
        verify(sub).close()
        assertEquals(AdminLocationMapState(), controller.state)
    }
}
