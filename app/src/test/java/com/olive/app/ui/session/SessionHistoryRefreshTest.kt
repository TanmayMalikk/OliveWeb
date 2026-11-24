package com.olive.app.ui.session

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.CoverType
import com.olive.app.data.account.DeliveryAddress
import com.olive.app.data.account.HistoryRecord
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.UserProfile
import com.olive.app.data.news.NewsArticle
import com.olive.app.security.DeviceEnrollment
import com.olive.app.support.FirestoreTestDriver
import com.olive.app.support.SessionRuntimeFixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionHistoryRefreshTest {
    @Test
    fun newsBookmarksAreRestoredForOnlyTheEnrolledAccountAndSetByLink() = runBlocking<Unit> {
        SessionRuntimeFixture().use { fixture ->
            val runtime = fixture.runtime
            val cover = SessionCoverState(runtime)
            val story = NewsArticle("Synthetic news", "Fixture summary", "https://example.invalid/story", "Fixture", "")
            val existing = story.copy(title = "Another account", link = "https://example.invalid/another")
            runtime.coverStore.saveNews("other", listOf(existing))
            cover.setNewsBookmark(story, bookmarked = true)
            cover.setNewsBookmark(story, bookmarked = true)
            assertEquals(listOf(story), runtime.state.savedNews)
            fixture.await { runtime.coverStore.restore("owner") == listOf(story) }
            cover.setNewsBookmark(story.copy(title = "An updated headline"), bookmarked = false)
            assertTrue(runtime.state.savedNews.isEmpty())
            fixture.await { runtime.coverStore.restore("owner").isEmpty() }

            runtime.enrollment = DeviceEnrollment("other", "")
            cover.restoreCoverData("other")
            assertEquals(listOf(existing), runtime.state.savedNews)
            assertTrue(runtime.coverStore.restore("owner").isEmpty())
            runtime.enrollment = null
            cover.setNewsBookmark(story, bookmarked = true)
            assertEquals(listOf(existing), runtime.state.savedNews)
        }
    }

    @Test
    fun refreshingLoadedNotesKeepsTheirContentInsteadOfReturningToSkeletons() = runBlocking<Unit> {
        SessionRuntimeFixture().use { fixture ->
            val history = fixture.runtime.history
            val note = PrivateEntry(title = "Synthetic note", body = "Saved content")
            fixture.runtime.store.savePrivateEntry("owner", note)
            history.refreshHistory("owner")
            fixture.await { history.loadingUid == null && fixture.runtime.state.privateEntries.any { it.id == note.id } }
            history.refreshHistory("owner")
            assertNull(history.loadingUid)
            assertEquals(note.id, fixture.runtime.state.privateEntries.single().id)
        }
    }

    @Test
    fun accountSyncRefreshesTheActiveProfileAndHistoryAndIgnoresAFormerAccount() = runBlocking<Unit> {
        SessionRuntimeFixture().use { fixture ->
            val runtime = fixture.runtime
            val identity = AccountIdentity("owner", "fixture_owner")
            val profile = UserProfile("fixture_owner", "Remote fixture owner", "", "", "2000-01-01", "English", "", "Calgary",
                "synthetic-hash", coverType = CoverType.NEWS, address = DeliveryAddress(city = "Calgary"))
            val note = PrivateEntry(title = "Synced fixture entry", body = "Synthetic history")
            val db = FirestoreTestDriver()
            val marker = "accountSync/owner"
            `when`(fixture.accounts.profile(identity)).thenReturn(profile)
            `when`(fixture.accounts.historyRecords(identity)).thenReturn(listOf(
                HistoryRecord("private_entries", note.id, note.toJson().toString()),
            ))
            mockStatic(FirebaseFirestore::class.java).use { cloud ->
                cloud.`when`<FirebaseFirestore> { FirebaseFirestore.getInstance() }.thenReturn(db.db)
                runtime.accountSession.watchAccountChanges(identity)
                db.emit(marker)
                fixture.await { runtime.state.privateEntries.any { it.id == note.id } }
                assertEquals(profile, runtime.state.profile)
                assertEquals("Calgary", runtime.state.deliveryAddress.city)
                assertEquals(CoverType.NEWS, runtime.state.coverType)
                assertEquals("synthetic-hash", runtime.enrollment?.triggerHash)
                db.fail(marker, FirebaseFirestoreException("Synthetic sync failure", FirebaseFirestoreException.Code.UNAVAILABLE))
                assertEquals("Synthetic sync failure", runtime.state.message)
                runtime.enrollment = DeviceEnrollment("new-owner", "")
                db.emit(marker)
                verify(fixture.accounts, times(1)).profile(identity)
                runtime.accountSyncSubscription?.close()
                assertTrue(db.listeners.single().closed)
            }
        }
    }
}
