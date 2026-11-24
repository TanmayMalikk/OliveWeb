package com.olive.app.ui.session

import com.olive.app.data.account.*
import com.olive.app.security.DeviceEnrollment
import com.olive.app.support.SessionRuntimeFixture
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class AdminDataPreloaderTest {
    private val staff = AdminAccountSummary("staff", "synthetic_staff", AccountRole.STAFF,
        "Synthetic Staff", "staff@example.invalid", "", true, false)
    private val user = staff.copy(uid = "user", username = "synthetic_user", role = AccountRole.USER)

    @Test
    fun backgroundReadsDoNotBlockPortalAndRapidVisitsReuseIndependentLists() = runBlocking<Unit> {
        SessionRuntimeFixture(AccountRole.ADMIN, dispatcher = Dispatchers.Default).use { f ->
            `when`(f.admin.summary()).thenReturn(AccountCounts(users = 1, staff = 1))
            `when`(f.admin.accounts()).thenReturn(listOf(user, staff))
            `when`(f.admin.staffAccounts()).thenReturn(listOf(staff))
            val loader = f.runtime.adminPreloader
            loader.preload()
            assertFalse(f.runtime.state.busy)
            f.await { loader.accountsReady && loader.staffReady && !loader.loadingAccounts && !loader.loadingStaff }
            repeat(3) { loader.accounts(); loader.staff() }
            assertEquals(listOf(user, staff), loader.accounts)
            assertEquals(listOf(staff), loader.staff)
            assertEquals(listOf(user, staff), f.runtime.state.adminAccounts)
            verify(f.admin, times(1)).accounts()
            verify(f.admin, times(1)).staffAccounts()
        }
    }

    @Test
    fun manualRefreshShowsSkeletonsEvenWhenJoiningABackgroundRefresh() = runBlocking<Unit> {
        for (staffDataset in listOf(false, true)) {
            SessionRuntimeFixture(AccountRole.ADMIN, dispatcher = Dispatchers.Default).use { f ->
                `when`(f.admin.accounts()).thenReturn(listOf(user))
                `when`(f.admin.staffAccounts()).thenReturn(listOf(staff))
                val loader = f.runtime.adminPreloader
                loader.accounts(); loader.staff()
                f.await { loader.accountsReady && loader.staffReady && !loader.loadingAccounts && !loader.loadingStaff }
                val started = CountDownLatch(1)
                val release = CountDownLatch(1)
                val pending = org.mockito.stubbing.Answer {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    if (staffDataset) listOf(staff) else listOf(user)
                }
                if (staffDataset) `when`(f.admin.staffAccounts()).thenAnswer(pending)
                else `when`(f.admin.accounts()).thenAnswer(pending)
                try {
                    if (staffDataset) loader.staff(force = true) else loader.accounts(force = true)
                    assertTrue(started.await(5, TimeUnit.SECONDS))
                    assertFalse(if (staffDataset) loader.showStaffSkeleton else loader.showAccountsSkeleton)
                    if (staffDataset) f.runtime.organization.refreshAdminStaff() else f.runtime.organization.refreshAdminAccounts()
                    assertTrue(if (staffDataset) loader.showStaffSkeleton else loader.showAccountsSkeleton)
                    assertTrue(if (staffDataset) loader.staffReady else loader.accountsReady)
                    assertFalse(f.runtime.state.busy)
                } finally { release.countDown() }
                f.await { !loader.loadingAccounts && !loader.loadingStaff }
                assertFalse(loader.showAccountsSkeleton)
                assertFalse(loader.showStaffSkeleton)
                if (staffDataset) verify(f.admin, times(2)).staffAccounts()
                else verify(f.admin, times(2)).accounts()
            }
        }
    }

    @Test
    fun emptyResultsAreCachedAndRefreshFailureRetainsLoadedData() = runBlocking<Unit> {
        SessionRuntimeFixture(AccountRole.ADMIN, dispatcher = Dispatchers.Default).use { f ->
            `when`(f.admin.accounts()).thenReturn(emptyList())
            val loader = f.runtime.adminPreloader
            loader.accounts()
            f.await { loader.accountsReady && !loader.loadingAccounts }
            loader.accounts()
            verify(f.admin, times(1)).accounts()
            `when`(f.admin.accounts()).thenAnswer { throw IOException("Synthetic outage") }
            f.runtime.organization.refreshAdminAccounts()
            f.await { loader.accountsError != null && !loader.loadingAccounts }
            assertTrue(loader.accountsReady)
            assertTrue(loader.accounts.isEmpty())
            assertFalse(loader.showAccountsSkeleton)
            assertFalse(f.runtime.state.busy)
        }
    }

    @Test
    fun switchingAccountsClearsRowsAndRejectsTheOldRequest() = runBlocking<Unit> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        SessionRuntimeFixture(AccountRole.ADMIN, dispatcher = Dispatchers.Default).use { f ->
            `when`(f.admin.accounts()).thenAnswer {
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
                finished.countDown()
                listOf(user)
            }
            val loader = f.runtime.adminPreloader
            try {
                loader.accounts()
                assertTrue(started.await(5, TimeUnit.SECONDS))
                loader.accounts()
                verify(f.admin, times(1)).accounts()
                f.runtime.enrollment = DeviceEnrollment("new-admin", "", AccountRole.ADMIN)
                assertTrue(loader.accounts.isEmpty())
                assertFalse(loader.accountsReady)
            } finally { release.countDown() }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            delay(100)
            assertTrue(loader.accounts.isEmpty())
            assertTrue(f.runtime.state.adminAccounts.isEmpty())
        }
    }
}
