package com.olive.app.ui.session

import com.olive.app.data.account.*
import com.olive.app.security.*
import com.olive.app.support.SessionRuntimeFixture
import com.olive.app.ui.users.shared.navigation.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionSettingsPersistenceTest {
    private val identity=AccountIdentity("owner","fixture_owner")
    private val profile=UserProfile("fixture_owner","Synthetic owner","","","2000-01-01","English","","Edmonton",InputPolicy.triggerHash("moon garden"),address=DeliveryAddress(city="Edmonton"))
    private fun fixture()=SessionRuntimeFixture().also { f ->
        f.runtime.enrollment=f.runtime.store.enroll(identity,profile,"123456")
        f.runtime.state.profile=profile
        f.runtime.state.page=SafePage.Settings
    }
    @Test
    fun failedCloudSaveKeepsTheEditedProfileLocallyAndAnExplicitRetryClearsThePendingMarker()=runBlocking<Unit> {
        fixture().use { f ->
            val changed=profile.copy(fullName="Revised owner")
            `when`(f.accounts.saveProfile(identity,changed)).thenAnswer { error("Synthetic offline failure") }
            f.runtime.settings.saveSafeProfile(changed)
            f.await { !f.runtime.state.busy }
            assertEquals(changed,f.runtime.store.profile("owner"))
            assertTrue(f.runtime.state.pendingSync)
            assertTrue(f.runtime.store.pendingSync("owner"))
            doAnswer { Unit }.`when`(f.accounts).saveProfile(identity,changed)
            f.runtime.settings.sync()
            f.await { !f.runtime.state.busy }
            assertFalse(f.runtime.state.pendingSync)
            assertFalse(f.runtime.store.pendingSync("owner"))
            assertEquals("changes_synced",f.runtime.state.message)
        }
    }
    @Test
    fun coverAndContactChangesPersistUnderTheCurrentAccountAndCoverSettingsRemainEditableAfterExit()=runBlocking<Unit> {
        fixture().use { f ->
            f.runtime.settings.changeCover(CoverType.GAME)
            f.await { !f.runtime.state.busy }
            assertEquals(CoverType.GAME,f.runtime.store.profile("owner").coverType)
            assertEquals(CoverType.GAME,f.runtime.state.coverType)
            val contact=SafeContact("trusted","Synthetic friend","555-0100",true,"friend")
            f.runtime.settings.saveContacts(listOf(contact))
            f.await { !f.runtime.state.busy }
            assertEquals(listOf(contact),f.runtime.store.profile("owner").contacts)
            f.runtime.state.screen=SessionScreen.Cover
            f.runtime.settings.saveCoverSettings("Updated cover name","2000-01-01",DeliveryAddress(city="Calgary"))
            f.await { !f.runtime.state.busy }
            assertEquals("Updated cover name",f.runtime.state.coverFullName)
            assertEquals("Calgary",f.runtime.state.deliveryAddress.city)
            assertEquals(CoverType.GAME,f.runtime.store.profile("owner").coverType)
        }
    }
    @Test
    fun passwordValidationBlocksMismatchesAndTheSuccessfulChangeRecordsOnlyHiddenAuditValues()=runBlocking<Unit> {
        fixture().use { f ->
            f.runtime.settings.changePassword("","NewSecret!123","NewSecret!123")
            assertEquals("enter_your_current_password",f.runtime.state.message)
            f.runtime.settings.changePassword("old","NewSecret!123","different")
            assertEquals("the_new_passwords_do_not_match",f.runtime.state.message)
            f.runtime.settings.changePassword("old","NewSecret!123","NewSecret!123")
            f.await { !f.runtime.state.busy }
            verify(f.accounts).changePassword(identity,"old","NewSecret!123")
            assertEquals("password_changed",f.runtime.state.message)
            val history=f.runtime.store.settingsHistory("owner")
            assertTrue(history.any { it.action=="Changed account password" })
            assertFalse(history.any { it.toJson().toString().contains("NewSecret") })
        }
    }
    @Test
    fun replacingThePrivateEntryPinRequiresTheExistingPinAndSavesTheNewMethod()=runBlocking<Unit> {
        fixture().use { f ->
            f.runtime.entrySettings.savePin("123456","987654","different",true)
            assertEquals("the_new_pins_do_not_match",f.runtime.state.message)
            f.runtime.entrySettings.savePin("123456","987654","987654",true)
            f.await { !f.runtime.state.busy }
            assertEquals("entry_settings_saved",f.runtime.state.message)
            assertEquals(SafeEntryMethod.APP_PIN,f.runtime.enrollment?.entryMethod)
            assertTrue(f.runtime.store.verify("owner","123456") is PinResult.Rejected)
            assertTrue(f.runtime.store.verify("owner","987654") is PinResult.Accepted)
        }
    }

    @Test
    fun aTriggerChangeRequiresTheCurrentPinAndPersistsOnlyTheVerifiedPhraseHash() = runBlocking<Unit> {
        fixture().use { fixture ->
            val session = fixture.runtime
            val replacement = "synthetic replacement phrase"
            session.settings.changeTrigger("x", "123456")
            assertTrue(session.state.message.orEmpty().contains("trigger"))
            session.settings.changeTrigger(replacement, "654321")
            fixture.await { !session.state.busy }
            assertFalse(InputPolicy.matchesTrigger(replacement, session.store.profile("owner").triggerHash))
            assertEquals(1, session.state.attempts.size)
            session.settings.changeTrigger(replacement, "123456")
            fixture.await { !session.state.busy }
            val hash = session.store.profile("owner").triggerHash
            assertTrue(InputPolicy.matchesTrigger(replacement, hash))
            assertEquals(hash, session.enrollment?.triggerHash)
            assertNotEquals(replacement, hash)
            assertFalse(InputPolicy.matchesTrigger("moon garden", hash))
        }
    }
}
