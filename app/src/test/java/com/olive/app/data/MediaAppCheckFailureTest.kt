package com.olive.app.data

import com.olive.app.data.media.isRetryableAppCheckFailure
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaAppCheckFailureTest {
    @Test
    fun `throttled token exchange remains retryable`() {
        assertTrue(isRetryableAppCheckFailure(Exception("Too many attempts.")))
    }

    @Test
    fun `wrapped network failures remain retryable`() {
        assertTrue(isRetryableAppCheckFailure(Exception("App Check failed", IOException("offline"))))
        assertTrue(isRetryableAppCheckFailure(SocketTimeoutException("timeout")))
    }

    @Test
    fun `attestation rejection still requires user action`() {
        assertFalse(isRetryableAppCheckFailure(Exception("App attestation failed.")))
    }
}
