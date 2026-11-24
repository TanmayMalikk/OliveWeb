package com.olive.app.ui.session

import com.olive.app.data.account.*
import com.olive.app.support.SessionRuntimeFixture
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OrganizationAccountActionsTest {
    private val staff = AdminAccountSummary("staff", "staff_fixture", AccountRole.STAFF, "Fixture Staff", "staff@example.test", "", true, false)
    private val totals = AccountCounts(users = 2, staff = 1, admins = 1, openTickets = 3)
    private fun fixture() = SessionRuntimeFixture(AccountRole.ADMIN, dispatcher = Dispatchers.Default).also { f ->
        runBlocking {
            `when`(f.admin.accounts()).thenAnswer { Thread.sleep(20); listOf(staff) }
            `when`(f.admin.staffAccounts()).thenAnswer { Thread.sleep(20); listOf(staff) }
            `when`(f.admin.summary()).thenAnswer { Thread.sleep(20); totals }
        }
    }

    @Test
    fun adminLoadsIndependentDatasetsWithoutBlockingThePortal() = runBlocking<Unit> {
        fixture().use { f ->
            f.runtime.organization.refreshAdminSummary()
            f.await { f.runtime.state.adminCounts == totals }
            assertFalse(f.runtime.state.busy)
            assertEquals(totals, f.runtime.state.adminCounts)
            verify(f.admin, never()).accounts()
            f.runtime.organization.refreshAdminAccounts()
            f.await { f.runtime.adminPreloader.accountsReady }
            assertFalse(f.runtime.state.busy)
            assertEquals(listOf(staff), f.runtime.state.adminAccounts)
            f.runtime.organization.refreshAdminStaff()
            f.await { f.runtime.adminPreloader.staffReady }
            assertFalse(f.runtime.state.busy)
            verify(f.admin).staffAccounts()
        }
    }

    @Test
    fun managedAccountValidationBlocksInvalidCredentialsBeforeCreatingAnAccount() = runBlocking<Unit> {
        fixture().use { f ->
            val invalid = AdminAccountDraft("short", "Secret!1234", AccountRole.STAFF)
            f.runtime.organization.createManagedAccount(invalid)
            assertTrue(f.runtime.state.message.orEmpty().contains("username"))
            f.runtime.organization.createManagedAccount(invalid.copy(username = "staff_fixture", password = "staff_fixture!123"))
            assertEquals("password_excludes_username", f.runtime.state.message)
            f.runtime.organization.createManagedAccount(invalid.copy(username = "staff_fixture", password = "bad"))
            assertTrue(f.runtime.state.message.orEmpty().contains("special_character"))
            verifyNoInteractions(f.admin)
            val valid = invalid.copy(username = "staff_fixture")
            f.runtime.organization.createManagedAccount(valid)
            f.await { !f.runtime.state.busy }
            verify(f.admin).create(valid)
            assertEquals(totals, f.runtime.state.adminCounts)
            assertEquals(listOf(staff), f.runtime.state.adminAccounts)
        }
    }

    @Test
    fun protectedAccountsCannotBeDeletedAndDeletionRefreshesTheDirectory() = runBlocking<Unit> {
        fixture().use { f ->
            f.runtime.organization.deleteManagedAccount(staff.copy(protected = true))
            verify(f.admin, never()).delete("staff")
            f.runtime.organization.deleteManagedAccount(staff)
            f.await { !f.runtime.state.busy }
            verify(f.admin).delete("staff")
            assertEquals(listOf(staff), f.runtime.state.adminAccounts)
            assertEquals(totals, f.runtime.state.adminCounts)
        }
    }

    @Test
    fun organizationProfileValidationAndUpdatesPreserveRoleAndCacheTheProfile() = runBlocking<Unit> {
        fixture().use { f ->
            f.runtime.organization.updateOrganizationProfile("", "2000-01-01", "invalid", "")
            assertTrue(f.runtime.state.message.orEmpty().contains("real_email"))
            val updated = UserProfile("fixture_owner", "Fixture Admin", "", "", "2000-01-01", "English", "", "Alberta", "", role = AccountRole.ADMIN, email = "admin@example.test")
            `when`(f.admin.completeOrganizationProfile("Fixture Admin", "2000-01-01", "admin@example.test", "")).thenReturn(updated)
            f.runtime.organization.updateOrganizationProfile(" Fixture Admin ", "2000-01-01", "admin@example.test", "")
            f.await { !f.runtime.state.busy }
            assertEquals(updated, f.runtime.state.profile)
            assertEquals("Fixture Admin", f.runtime.state.coverFullName)
            assertEquals("ui_profile_updated", f.runtime.state.message)
        }
    }

    @Test
    fun organizationEnrollmentRejectsInvalidEmailThenOpensTheStaffPortalAfterSaving() = runBlocking<Unit> {
        SessionRuntimeFixture(AccountRole.STAFF).use { fixture ->
            val session = fixture.runtime
            val identity = AccountIdentity("new-staff", "fixture_staff")
            val completed = UserProfile("fixture_staff", "Fixture Staff", "", "", "2000-01-01", "English", "", "Alberta", "",
                role = AccountRole.STAFF, email = "fixture@example.test")
            session.enrollment = null
            session.pendingIdentity = identity
            session.state.screen = SessionScreen.OrganizationProfileSetup
            session.screenHistory += SessionScreen.ReturningCredentials
            session.organization.saveOrganizationProfile("Fixture Staff", "2000-01-01", "invalid", "")
            assertEquals(SessionScreen.OrganizationProfileSetup, session.state.screen)
            assertTrue(session.state.message.orEmpty().contains("real_email"))
            verifyNoInteractions(fixture.admin)
            `when`(fixture.admin.completeOrganizationProfile("Fixture Staff", "2000-01-01", "fixture@example.test", "5550100"))
                .thenReturn(completed)
            session.organization.saveOrganizationProfile(" Fixture Staff ", "2000-01-01", " fixture@example.test ", " 5550100 ")
            fixture.await { !session.state.busy }
            assertEquals(SessionScreen.Portal, session.state.screen)
            assertEquals(identity.uid, session.enrollment?.uid)
            assertEquals(completed, session.state.profile)
            assertTrue(session.screenHistory.isEmpty())
        }
    }
}
