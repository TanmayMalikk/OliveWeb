package com.olive.app.ui.session

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.OpaqueRecoveryRepository
import com.olive.app.data.account.SafeContact
import com.olive.app.data.account.UserProfile
import com.olive.app.security.PinResult
import com.olive.app.support.SessionRuntimeFixture
import com.olive.app.ui.users.shared.navigation.SessionScreen
import java.security.Security
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AccountSessionAndSettingsDeepTest {
    private val validAddress = DeliveryAddress(line1 = "101 Jasper Ave", city = "Edmonton", province = "AB", postalCode = "T5J 1N8")

    @Before
    fun cleanTestEnvironment() {
        Security.removeProvider("AndroidKeyStore")
        Mockito.framework().clearInlineMocks()
    }

    private fun ensureWorkManager(app: Application) {
        if (FirebaseApp.getApps(app).isEmpty()) {
            FirebaseApp.initializeApp(
                app,
                FirebaseOptions.Builder()
                    .setProjectId("olive-test")
                    .setApplicationId("1:1234567890:android:olive")
                    .setApiKey("AIzaSyTestOnlyKey000000000000000000")
                    .setDatabaseUrl("https://olive-test-default-rtdb.firebaseio.com")
                    .build(),
            )
        }
        if (runCatching { WorkManager.getInstance(app) }.isFailure) {
            WorkManager.initialize(app, Configuration.Builder().build())
        }
    }

    @Test
    fun devicePinTriggerAndSignOutCoverLocalAndCloudPaths() = runBlocking {
        SessionRuntimeFixture(localMode = true).use { f ->
            ensureWorkManager(f.application)
            val ctrl = f.runtime.accountSession
            val profile = UserProfile(username = "fixture_owner", fullName = "Alex", dob = "1995-05-05", role = AccountRole.USER)
            f.runtime.enrollment = null
            f.runtime.state.screen = SessionScreen.DevicePin
            f.runtime.state.accountRole = AccountRole.USER
            f.runtime.pendingIdentity = AccountIdentity("owner", "fixture_owner")
            f.runtime.pendingProfile = profile

            // Invalid PIN & mismatch
            ctrl.createDevicePin("12", "12")
            assertEquals("enter_the_same_4_6_digit_pin_in_both_fields", f.runtime.state.message)

            // Valid PIN enrolls & opens Cover
            ctrl.createDevicePin("2468", "2468")
            f.await { f.runtime.enrollment != null && !f.runtime.state.busy }
            assertEquals(SessionScreen.Cover, f.runtime.state.screen)

            // saveProvisionedTrigger invalid & valid
            f.runtime.pendingIdentity = AccountIdentity("owner", "fixture_owner")
            f.runtime.pendingProfile = profile
            f.runtime.state.screen = SessionScreen.Safe
            ctrl.saveProvisionedTrigger("a")
            assertEquals("choose_a_trigger_word_or_phrase_of_3_64_characters", f.runtime.state.message)
            ctrl.saveProvisionedTrigger("olive garden")
            f.await { !f.runtime.state.busy && f.runtime.state.screen == SessionScreen.DevicePin }

            // Sign out resets session state
            f.runtime.state.screen = SessionScreen.Safe
            ctrl.signOut()
            val signOutDeadline = System.nanoTime() + 4_000_000_000L
            while ((f.runtime.enrollment != null || f.runtime.state.busy) && System.nanoTime() < signOutDeadline) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                kotlinx.coroutines.delay(15)
            }
            assertFalse(f.runtime.state.busy)
            assertEquals(SessionScreen.Cover, f.runtime.state.screen)
        }

        // Cloud signOut path
        SessionRuntimeFixture(localMode = false).use { f ->
            ensureWorkManager(f.application)
            f.runtime.state.screen = SessionScreen.Safe
            f.runtime.state.profile = UserProfile(username = "fixture_owner", role = AccountRole.USER)
            f.runtime.accountSession.signOut()
            val signOutDeadline = System.nanoTime() + 4_000_000_000L
            while ((f.runtime.enrollment != null || f.runtime.state.busy) && System.nanoTime() < signOutDeadline) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                kotlinx.coroutines.delay(15)
            }
            assertFalse(f.runtime.state.busy)
            assertEquals(SessionScreen.Cover, f.runtime.state.screen)
        }
    }

    @Test
    fun settingsCoverTriggerContactsPasswordAndPrivacyMethodsCoverBranches() = runBlocking {
        val opaqueMock = mock(OpaqueRecoveryRepository::class.java)
        SessionRuntimeFixture(localMode = false, opaqueRecoveryOverride = opaqueMock).use { f ->
            ensureWorkManager(f.application)
            val settings = f.runtime.settings
            val privacy = f.runtime.privacy
            val profile = UserProfile(
                username = "fixture_owner",
                fullName = "Alex",
                dob = "1995-05-05",
                address = validAddress,
                role = AccountRole.USER,
            )
            f.runtime.state.profile = profile
            f.runtime.state.screen = SessionScreen.Safe
            f.runtime.store.enroll(AccountIdentity("owner", "fixture_owner"), profile, "1357")

            // saveSettings validation errors + valid save + offline sync fallback
            settings.saveSettings("", "", "", "1995-05-05", "", validAddress)
            assertEquals("ui_profile_required_fields", f.runtime.state.message)
            settings.saveSettings("Alex", "", "", "1995-05-05", "", validAddress, email = "bad-email")
            assertEquals("email_invalid_message", f.runtime.state.message)

            val dummyId = AccountIdentity("owner", "fixture_owner")
            Mockito.doThrow(RuntimeException("offline")).`when`(f.accounts)
                .saveProfile(any<AccountIdentity>() ?: dummyId, any<UserProfile>() ?: profile)
            settings.saveSettings("Alex Updated", "None", "", "1995-05-05", "Note", validAddress, email = "alex@example.com", emailCommunicationConsent = true)
            f.await { !f.runtime.state.busy }
            assertTrue(f.runtime.state.pendingSync)
            assertEquals("saved_on_this_device_cloud_sync_failed_retry_when_connected", f.runtime.state.message)

            // Sync retry succeeds
            Mockito.doAnswer { }.`when`(f.accounts)
                .saveProfile(any<AccountIdentity>() ?: dummyId, any<UserProfile>() ?: profile)
            settings.sync()
            f.await { !f.runtime.state.busy }
            assertFalse(f.runtime.state.pendingSync)
            assertEquals("changes_synced", f.runtime.state.message)

            // saveCoverSettings
            f.runtime.state.screen = SessionScreen.Cover
            settings.saveCoverSettings("", "1995-05-05", validAddress)
            assertEquals("ui_profile_required_fields", f.runtime.state.message)
            settings.saveCoverSettings("Alex Cover", "1995-05-05", validAddress)
            f.await { !f.runtime.state.busy }
            assertEquals("Alex Cover", f.runtime.state.coverFullName)

            // changeCover & changeTrigger (rejected + accepted) & saveContacts
            f.runtime.state.screen = SessionScreen.Safe
            settings.changeCover(CoverType.NEWS)
            f.await { !f.runtime.state.busy }
            assertEquals(CoverType.NEWS, f.runtime.state.coverType)

            settings.changeTrigger("x", "1357")
            assertEquals("enter_a_trigger_of_3_64_characters_and_your_4_6_digit_pin", f.runtime.state.message)

            settings.changeTrigger("valid trigger phrase", "9999")
            f.await { !f.runtime.state.busy }
            assertTrue(f.runtime.state.attempts.isNotEmpty())

            settings.changeTrigger("valid trigger phrase", "1357")
            f.await { !f.runtime.state.busy }
            assertEquals("changes_synced", f.runtime.state.message)

            settings.saveContacts(listOf(SafeContact(id = "c1", name = "Sam", details = "555-0100", safeToStay = true)))
            f.await { !f.runtime.state.busy }
            assertEquals(1, f.runtime.state.profile?.contacts?.size)

            // changePassword validations + success
            settings.changePassword("", "Valid!123", "Valid!123")
            assertEquals("enter_your_current_password", f.runtime.state.message)
            settings.changePassword("oldPass!", "fixture_owner_1!", "fixture_owner_1!")
            assertEquals("password_excludes_username", f.runtime.state.message)
            settings.changePassword("oldPass!", "weak", "weak")
            assertEquals("ui_use_8_24_characters_with_a_letter_number_and_special_character", f.runtime.state.message)
            settings.changePassword("oldPass!", "Strong!1234", "Mismatch!1234")
            assertEquals("the_new_passwords_do_not_match", f.runtime.state.message)

            settings.changePassword("oldPass!", "Strong!1234", "Strong!1234")
            f.await { !f.runtime.state.busy }
            assertEquals("password_changed", f.runtime.state.message)

            // SessionPrivacy checks
            privacy.setIdleTimeoutSeconds(90)
            assertTrue(privacy.idleTimeoutSeconds() >= 60)
            privacy.noteInteraction()
            assertNull(f.runtime.state.idleCountdownSeconds)
            privacy.captureDetected("Recording")
            assertTrue(f.runtime.state.privacyBlackout)
            privacy.recordingStopped()
            assertFalse(f.runtime.state.privacyBlackout)
        }
    }
}
