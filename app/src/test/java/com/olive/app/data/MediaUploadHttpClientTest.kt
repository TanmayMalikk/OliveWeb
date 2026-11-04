package com.olive.app.data

import com.olive.app.data.media.mediaUploadHttpClient
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUploadHttpClientTest {
    @Test
    fun uploadTimeoutsAllowSlowCiphertextStreamsWithinTicketLifetime() {
        val client=mediaUploadHttpClient()

        assertEquals(TimeUnit.SECONDS.toMillis(30).toInt(),client.connectTimeoutMillis)
        assertEquals(TimeUnit.MINUTES.toMillis(1).toInt(),client.readTimeoutMillis)
        assertEquals(TimeUnit.MINUTES.toMillis(5).toInt(),client.writeTimeoutMillis)
        assertEquals(TimeUnit.MINUTES.toMillis(9).toInt(),client.callTimeoutMillis)
        assertTrue(client.retryOnConnectionFailure)
        assertTrue(client.callTimeoutMillis < TimeUnit.MINUTES.toMillis(10))
    }
}
