package com.olive.app.ui.session

import androidx.work.impl.WorkManagerImpl
import com.google.firebase.auth.FirebaseAuth
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.UserProfile
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.safety.SafetyAccessIndexStore
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.data.safety.SharedSafetyPlan
import com.olive.app.support.SessionRuntimeFixture
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionSignOutPrivacyTest {
    @Test
    fun signOutClosesObserversAndPurgesOnlyTheDepartingAccountsOfflineIndexes() = runBlocking<Unit> {
        SessionRuntimeFixture().use { fixture ->
            val runtime = fixture.runtime
            val owner = "owner"
            val other = "different-account"
            val identity = AccountIdentity(owner, "fixture_owner")
            val profile = UserProfile("fixture_owner", "Synthetic name", "", "", "2000-01-01", "English", "", "", "")
            runtime.enrollment = runtime.store.enroll(identity, profile, "123456")
            runtime.state.profile = profile
            runtime.state.coverFullName = profile.fullName
            runtime.state.coverType = CoverType.NEWS
            runtime.state.deliveryAddress = DeliveryAddress(city = "Synthetic city")
            var closed = false
            runtime.accountSyncSubscription = ChatSubscription { closed = true }
            val access = SafetyAccessIndexStore(fixture.application)
            for (uid in listOf(owner, other)) {
                access.replaceShared(uid, listOf(SharedSafetyPlan("shared-user", "Synthetic shared user")))
                runtime.chatCache.replaceIndex(uid, listOf(
                    ChatConversation("chat", "pair", listOf(uid), emptyMap()),
                ), 1000)
                runtime.documentIndex.replace(uid, listOf(
                    SafetyDocument("file", uid, "synthetic/file", "Synthetic document", "text/plain", listOf(uid)),
                ), 1000)
            }
            val work = mock(WorkManagerImpl::class.java)
            WorkManagerImpl.setDelegate(work)
            val auth = mock(FirebaseAuth::class.java)
            try {
                mockStatic(FirebaseAuth::class.java).use { sdk ->
                    sdk.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
                    `when`(fixture.accounts.authenticatedUid()).thenReturn(null)
                    runtime.accountSession.signOut()
                    fixture.await { !runtime.state.busy }
                    verify(fixture.accounts).signOut()
                    assertTrue(closed)
                    assertNull(runtime.accountSyncSubscription)
                    assertNull(runtime.enrollment)
                    assertNull(runtime.store.currentSession())
                    assertNull(runtime.pinAuthorizedUid)
                    assertEquals(SessionScreen.Cover, runtime.state.screen)
                    assertEquals(CoverType.FITNESS, runtime.state.coverType)
                    assertEquals("", runtime.state.coverFullName)
                    assertEquals(DeliveryAddress(), runtime.state.deliveryAddress)
                    assertTrue(access.read(owner).sharedPlans.isEmpty())
                    assertTrue(runtime.chatCache.conversations(owner).isEmpty())
                    assertTrue(runtime.documentIndex.read(owner).documents.isEmpty())
                    assertEquals("shared-user", access.read(other).sharedPlans.single().ownerUid)
                    assertEquals("chat", runtime.chatCache.conversations(other).single().id)
                    assertEquals("file", runtime.documentIndex.read(other).documents.single().id)
                }
            } finally {
                WorkManagerImpl.setDelegate(null)
            }
        }
    }
}
