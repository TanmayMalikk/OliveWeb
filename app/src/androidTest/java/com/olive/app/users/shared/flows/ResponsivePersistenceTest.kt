// Tests restored accounts, chats, notes, covers, and news after restart.
package com.olive.app

import androidx.test.core.app.ApplicationProvider
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountRole
import com.olive.app.data.cover.CoverStateStore
import com.olive.app.data.account.CoverType
import com.olive.app.data.news.NewsArticle
import com.olive.app.data.news.NewsFeedCacheStore
import com.olive.app.data.news.NewsScope
import com.olive.app.data.account.UserProfile
import com.olive.app.data.messaging.PrivateContactNote
import com.olive.app.security.DeviceStore
import com.olive.app.security.EncryptedVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class ResponsivePersistenceTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    // Checks the startup session restores enrollment and profile together step with the test's sample data.
    @Test
    fun startupSessionRestoresEnrollmentAndProfileTogether() {
        val suffix = UUID.randomUUID().toString()
        val store = DeviceStore(
            EncryptedVault(context, "responsive-session-$suffix"),
            EncryptedVault(context, "responsive-activity-$suffix")
        )
        val identity = AccountIdentity("uid-$suffix", "responsive_user")
        val profile = UserProfile(
            username = identity.username,
            fullName = "Responsive User",
            affectedNames = "Responsive User",
            abuserName = "Test Person",
            dob = "2000-01-02",
            language = "English",
            situation = "Testing",
            region = "Alberta",
            triggerHash = "saved-trigger",
            coverType = CoverType.NEWS,
            role = AccountRole.USER,
        )
        store.enroll(identity, profile, "1234")

        val restored = store.currentSession()

        assertNotNull(restored)
        assertEquals(identity.uid, restored!!.enrollment.uid)
        assertEquals(profile, restored.profile)
    }

    // Checks the archived chats and private contact notes stay separate for each account step with the test's sample data.
    @Test
    fun archivedChatsAndPrivateContactNotesStaySeparateForEachAccount() {
        val suffix = UUID.randomUUID().toString()
        val store = DeviceStore(
            EncryptedVault(context, "responsive-private-social-$suffix"),
            EncryptedVault(context, "responsive-private-social-activity-$suffix")
        )
        store.setConversationArchived("viewer-a", "chat-1", true)
        store.appendPrivateContactNote("viewer-a", "person-1", "Taylor", "First detail")
        store.appendPrivateContactNote("viewer-a", "person-1", "Taylor", "Second detail")

        assertEquals(setOf("chat-1"), store.archivedConversationIds("viewer-a"))
        assertEquals(
            "First detail\nSecond detail",
            store.privateContactNotes("viewer-a")["person-1"]?.body
        )
        assertEquals(emptySet<String>(), store.archivedConversationIds("viewer-b"))
        assertEquals(emptyMap<String, PrivateContactNote>(), store.privateContactNotes("viewer-b"))

        store.setConversationArchived("viewer-a", "chat-1", false)
        assertEquals(emptySet<String>(), store.archivedConversationIds("viewer-a"))
    }

    // Checks saved news cover state restores from one snapshot step with the test's sample data.
    @Test
    fun newsCoverStateRestoresFromOneSnapshot() {
        val store =
            CoverStateStore(EncryptedVault(context, "responsive-cover-${UUID.randomUUID()}"))
        val uid = "cover-user"
        val article = NewsArticle(
            "Cached headline",
            "Summary",
            "https://news.google.com/example",
            "Source",
            "Today"
        )
        store.saveNews(uid, listOf(article))

        val restored = store.restore(uid)

        assertEquals(listOf(article), restored)
    }

    // Checks the news cache survives astore recreation and separates scopes step with the test's sample data.
    @Test
    fun newsCacheSurvivesAStoreRecreationAndSeparatesScopes() {
        context.getSharedPreferences(
            "daily_digest_feed_cache",
            android.content.Context.MODE_PRIVATE
        ).edit().clear().commit()
        val article = NewsArticle(
            "Immediate headline",
            "Cached summary",
            "https://news.google.com/cached",
            "Daily Digest",
            "Today"
        )
        NewsFeedCacheStore(context).save(
            "English",
            NewsScope.LOCAL,
            "Calgary, Alberta",
            listOf(article)
        )

        val restored = NewsFeedCacheStore(context).load("English", NewsScope.LOCAL)

        assertEquals("Calgary, Alberta", restored?.place)
        assertEquals(listOf(article), restored?.articles)
        assertNull(NewsFeedCacheStore(context).load("English", NewsScope.GLOBAL))
    }

}
