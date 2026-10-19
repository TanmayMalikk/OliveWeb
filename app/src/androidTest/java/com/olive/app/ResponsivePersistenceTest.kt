package com.olive.app

import androidx.test.core.app.ApplicationProvider
import com.olive.app.data.AccountIdentity
import com.olive.app.data.AccountRole
import com.olive.app.data.CoverStateStore
import com.olive.app.data.CoverType
import com.olive.app.data.FoodCoverState
import com.olive.app.data.NewsArticle
import com.olive.app.data.NewsFeedCacheStore
import com.olive.app.data.NewsScope
import com.olive.app.data.UserProfile
import com.olive.app.security.DeviceStore
import com.olive.app.security.EncryptedVault
import com.olive.app.ui.covers.calculator.CalculatorHistoryEntry
import com.olive.app.ui.covers.calculator.CalculatorHistoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class ResponsivePersistenceTest {
    private val context get()=ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun startupSessionRestoresEnrollmentAndProfileTogether() {
        val suffix=UUID.randomUUID().toString()
        val store=DeviceStore(EncryptedVault(context,"responsive-session-$suffix"),
            EncryptedVault(context,"responsive-activity-$suffix"))
        val identity=AccountIdentity("uid-$suffix","responsive_user")
        val profile=UserProfile(
            username=identity.username,fullName="Responsive User",affectedNames="Responsive User",
            abuserName="Test Person",dob="2000-01-02",language="English",situation="Testing",
            region="Alberta",triggerHash="saved-trigger",coverType=CoverType.NEWS,role=AccountRole.USER,
        )
        store.enroll(identity,profile,"1234")

        val restored=store.currentSession()

        assertNotNull(restored)
        assertEquals(identity.uid,restored!!.enrollment.uid)
        assertEquals(profile,restored.profile)
    }

    @Test fun allCoverStateRestoresFromOneSnapshot() {
        val store=CoverStateStore(EncryptedVault(context,"responsive-cover-${UUID.randomUUID()}"))
        val uid="cover-user"
        val food=FoodCoverState(mapOf("dish-1" to 2),setOf("dish-1"),mapOf("dish-1" to setOf("Olives")))
        val article=NewsArticle("Cached headline","Summary","https://news.google.com/example","Source","Today")
        store.saveFood(uid,food)
        store.saveNews(uid,listOf(article))

        val restored=store.restore(uid)

        assertEquals(food,restored.first)
        assertEquals(listOf(article),restored.second)
    }

    @Test fun newsCacheSurvivesAStoreRecreationAndSeparatesScopes() {
        context.getSharedPreferences("daily_digest_feed_cache",android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val article=NewsArticle("Immediate headline","Cached summary","https://news.google.com/cached","Daily Digest","Today")
        NewsFeedCacheStore(context).save("English",NewsScope.LOCAL,"Calgary, Alberta",listOf(article))

        val restored=NewsFeedCacheStore(context).load("English",NewsScope.LOCAL)

        assertEquals("Calgary, Alberta",restored?.place)
        assertEquals(listOf(article),restored?.articles)
        assertNull(NewsFeedCacheStore(context).load("English",NewsScope.GLOBAL))
    }

    @Test fun calculatorHistorySurvivesAStoreRecreationAndClearRemovesIt() {
        val accountId="calculator-${UUID.randomUUID()}"
        val entries=listOf(CalculatorHistoryEntry("2+3","5"),CalculatorHistoryEntry("1e10","1E10"))
        CalculatorHistoryStore(context,accountId).save(entries)

        assertEquals(entries,CalculatorHistoryStore(context,accountId).load())
        CalculatorHistoryStore(context,accountId).save(emptyList())
        assertEquals(emptyList<CalculatorHistoryEntry>(),CalculatorHistoryStore(context,accountId).load())
    }
}
