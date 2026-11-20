package com.olive.app.wear

import android.app.Application
import android.content.Context
import com.google.android.gms.wearable.DataMap
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], manifest = Config.NONE)
class WatchAccountSyncTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun session(uid: String, token: String, at: Long) = DataMap().apply {
        putBoolean("signedIn", uid.isNotEmpty()); putString("accountId", uid); putString("sessionId", token)
        putLong("updatedAt", at); putLong("sessionStartedAt", at)
    }
    private fun daily(uid: String, token: String, at: Long, calories: Float) = DataMap().apply {
        putString("accountId", uid); putString("sessionId", token); putString("date", LocalDate.now().toString())
        putLong("updatedAt", at); putFloat("calories", calories); putInt("phoneSteps", 1234)
    }

    @Test fun accountSwitchRestoresOnlyThatAccountsDataAndRejectsStaleSessions() {
        val now = System.currentTimeMillis() - 10000
        WatchFitnessData.session(context, session("a", "a1", now))
        WatchFitnessData.daily(context, daily("a", "a1", now + 1, 500f))
        WatchFitnessData.session(context, session("b", "b1", now + 2))
        assertEquals(0f, WatchFitnessData.snapshot(context).calories, 0f)
        WatchFitnessData.daily(context, daily("a", "a1", now + 3, 999f))
        assertEquals(0f, WatchFitnessData.snapshot(context).calories, 0f)
        WatchFitnessData.daily(context, daily("b", "b1", now + 4, 100f))
        WatchFitnessData.session(context, session("a", "a2", now + 5))
        assertEquals(500f, WatchFitnessData.snapshot(context).calories, 0f)
        WatchFitnessData.daily(context, daily("a", "a1", now + 6, 999f))
        assertEquals(500f, WatchFitnessData.snapshot(context).calories, 0f)
        WatchFitnessData.session(context, session("b", "b1", now + 2))
        assertEquals(500f, WatchFitnessData.snapshot(context).calories, 0f)
        assertFalse(matchesWatchSession("a", "a2", "a", "a1"))
    }

    @Test fun physicalStepCounterCannotTransferOldAccountStepsToNewAccount() {
        val now = System.currentTimeMillis() - 10000
        WatchFitnessData.session(context, session("first", "first1", now))
        WatchFitnessData.steps(context, 1000, now + 1, passive = false)
        WatchFitnessData.steps(context, 1020, now + 2, passive = false)
        assertEquals(20, WatchFitnessData.snapshot(context).watchSteps)
        WatchFitnessData.session(context, session("second", "second1", now + 3))
        WatchFitnessData.steps(context, 1020, now + 2, passive = false)
        assertEquals(0, WatchFitnessData.snapshot(context).watchSteps)
        WatchFitnessData.steps(context, 1025, now + 4, passive = false)
        WatchFitnessData.steps(context, 1030, now + 5, passive = false)
        assertEquals(5, WatchFitnessData.snapshot(context).watchSteps)
        WatchFitnessData.session(context, session("first", "first2", now + 6))
        assertEquals(20, WatchFitnessData.snapshot(context).watchSteps)
        WatchFitnessData.session(context, session("", "signed-out", now + 7))
        assertEquals(0, WatchTileData.read(context).steps)
        assertNull(WatchFitnessData.snapshot(context).heartRate)
    }

    @Test fun passiveAndForegroundStepCountersDoNotDoubleCountOrAcceptStaleSamples() {
        val now = System.currentTimeMillis()
        WatchFitnessData.session(context, session("watch-user", "today", now))
        WatchFitnessData.steps(context, 1450, now + 1, passive = false)
        WatchFitnessData.steps(context, 70, now + 1, passive = true)
        WatchFitnessData.steps(context, 1460, now + 2, passive = false)
        WatchFitnessData.steps(context, 85, now + 2, passive = true)
        WatchFitnessData.steps(context, 120, now + 1, passive = true)
        assertEquals(15, WatchFitnessData.snapshot(context).watchSteps)
        WatchFitnessData.steps(context, 5, now + 3, passive = true)
        WatchFitnessData.steps(context, 9, now + 4, passive = true)
        assertEquals(19, WatchTileData.read(context).steps)
    }

    @Test fun vitalsTileUsesAcceptedReadingsAndDoesNotLeakAnOlderAccountsSummary() {
        val now = System.currentTimeMillis()
        WatchFitnessData.session(context, session("card-user", "old", now))
        WatchFitnessData.heart(context, 65, now + 1)
        WatchFitnessData.heart(context, 87, now + 2)
        WatchFitnessData.heart(context, 150, now + 1)
        WatchFitnessData.heart(context, 400, now + 3)
        val tile = WatchTileData.read(context)
        assertEquals(87, tile.currentHeartRate)
        assertEquals(87, tile.peakHeartRate)
        assertEquals(65, tile.lowestHeartRate)
        assertEquals(76, tile.averageHeartRate)
        assertEquals(87, WatchFitnessData.snapshot(context).heartRateSummary.restingBpm)
        WatchFitnessData.session(context, session("different-user", "new", now + 4))
        WatchFitnessData.heart(context, 95, now + 3)
        assertNull(WatchTileData.read(context).currentHeartRate)
        assertNull(WatchTileData.read(context).averageHeartRate)
    }
}
