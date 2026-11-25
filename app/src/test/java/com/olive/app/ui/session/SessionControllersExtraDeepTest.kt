package com.olive.app.ui.session

import android.app.Application
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.HistoryRecord
import com.olive.app.data.account.UserProfile
import com.olive.app.support.SessionRuntimeFixture
import com.olive.app.ui.users.shared.navigation.SessionScreen
import java.security.Security
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SessionControllersExtraDeepTest {
    private val validAddress = DeliveryAddress(line1 = "101 Jasper Ave", city = "Edmonton", province = "AB", postalCode = "T5J 1N8")

    @Before
    fun cleanTestEnvironment() {
        Security.removeProvider("AndroidKeyStore")
        Mockito.framework().clearInlineMocks()
    }

    @Test
    fun accountSessionControllerSaveSetupProfileCoversValidationsAndBothRoles() = runBlocking {
        SessionRuntimeFixture(role = AccountRole.USER, localMode = true).use { f ->
            val ctrl = f.runtime.accountSession
            val identity = AccountIdentity("setup-u1", "setup_user")
            f.runtime.state.screen = SessionScreen.ProfileSetup
            f.runtime.pendingIdentity = identity
            f.runtime.state.accountRole = AccountRole.USER

            // Validation failures
            ctrl.saveSetupProfile(UserProfile(username = "setup_user", role = AccountRole.SAFE_CONTACT))
            assertTrue(f.runtime.state.message?.isNotBlank() == true)

            ctrl.saveSetupProfile(UserProfile(username = "setup_user", fullName = "", role = AccountRole.USER))
            assertTrue(f.runtime.state.message?.isNotBlank() == true)

            ctrl.saveSetupProfile(
                UserProfile(
                    username = "setup_user",
                    fullName = "Jordan",
                    dob = "1995-05-05",
                    language = "en",
                    address = validAddress,
                    email = "not-an-email",
                    role = AccountRole.USER,
                ),
            )
            assertTrue(f.runtime.state.message?.isNotBlank() == true)

            ctrl.saveSetupProfile(
                UserProfile(
                    username = "setup_user",
                    fullName = "Jordan",
                    dob = "1995-05-05",
                    language = "en",
                    address = validAddress,
                    email = "jordan@example.com",
                    emailCommunicationConsent = false,
                    role = AccountRole.USER,
                ),
            )
            assertTrue(f.runtime.state.message?.isNotBlank() == true)

            // Valid USER profile moves to DevicePin
            ctrl.saveSetupProfile(
                UserProfile(
                    username = "setup_user",
                    fullName = "Jordan",
                    dob = "1995-05-05",
                    language = "en",
                    address = validAddress,
                    email = "jordan@example.com",
                    emailCommunicationConsent = true,
                    role = AccountRole.USER,
                ),
            )
            f.await { !f.runtime.state.busy && f.runtime.state.screen == SessionScreen.DevicePin }
            assertNotNull(f.runtime.pendingProfile)

            // Valid SAFE_CONTACT enrolls immediately to Safe
            val scIdentity = AccountIdentity("setup-sc1", "sc_user")
            f.runtime.state.screen = SessionScreen.ProfileSetup
            f.runtime.state.accountRole = AccountRole.SAFE_CONTACT
            f.runtime.pendingIdentity = scIdentity
            ctrl.saveSetupProfile(
                UserProfile(
                    username = "sc_user",
                    fullName = "Sam Contact",
                    dob = "1990-01-01",
                    language = "en",
                    address = validAddress,
                    role = AccountRole.SAFE_CONTACT,
                ),
            )
            f.await { !f.runtime.state.busy && f.runtime.enrollment != null && f.runtime.state.screen == SessionScreen.Safe }
            assertEquals(AccountRole.SAFE_CONTACT, f.runtime.state.accountRole)
        }
    }

    @Test
    fun loadCloudHistoryAndQueueHistorySyncCoverSyncAndReconnectBranches() = runBlocking {
        SessionRuntimeFixture(role = AccountRole.USER, localMode = false, dispatcher = Dispatchers.Default).use { f ->
            val identity = AccountIdentity("owner", "fixture_owner")
            val profile = UserProfile(username = "fixture_owner", fullName = "Alex", dob = "1995-05-05", address = validAddress, role = AccountRole.USER)
            f.runtime.enrollment = f.runtime.store.enroll(identity, profile, "2468")
            f.runtime.state.profile = profile
            f.runtime.state.coverUsername = "fixture_owner"

            val record = HistoryRecord("rec-1", "settings", "{}")
            `when`(f.accounts.authenticatedUid()).thenReturn("owner")
            `when`(f.accounts.profile(identity)).thenReturn(profile)
            `when`(f.accounts.historyRecords(identity)).thenReturn(listOf(record))

            f.runtime.history.loadCloudHistory("owner", "fixture_owner")
            f.await(timeoutMillis = 3_500) { true }

            // Exercise SessionMessaging.runMessagingAction reconnect error branch
            `when`(f.accounts.authenticatedUid()).thenReturn("different-uid")
            f.runtime.state.screen = SessionScreen.Safe
            f.runtime.messagingActions.runMessagingAction("fail") { _, _, _ -> }
            assertTrue(f.runtime.state.messagingNeedsReconnect)
        }
    }
}
