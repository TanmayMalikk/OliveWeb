package com.olive.app.data

import androidx.work.WorkRequest
import com.olive.app.data.work.DurableWorkPolicy
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class DurableWorkPolicyTest {
    @Test
    fun `high priority delivery uses WorkManager minimum backoff`() {
        assertEquals(10L, DurableWorkPolicy.INITIAL_BACKOFF_SECONDS)
        assertEquals(
            TimeUnit.MILLISECONDS.toSeconds(WorkRequest.MIN_BACKOFF_MILLIS),
            DurableWorkPolicy.INITIAL_BACKOFF_SECONDS,
        )
    }
}
