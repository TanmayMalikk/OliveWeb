package com.olive.app.ui.session

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class PasskeyRecoveryStageTest {
    @Test
    fun `successful stages return their result across repeated calls`() = runBlocking {
        repeat(100) { value ->
            assertEquals(value, atPasskeyRecoveryStage(PasskeyRecoveryStage.SAVE) { value })
        }
    }

    @Test
    fun `different failure classes retain their stage and cause`() {
        for (failure in listOf(IOException("network"), IllegalStateException("state"))) {
            val thrown = assertThrows(PasskeyRecoveryStageException::class.java) {
                runBlocking {
                    atPasskeyRecoveryStage(PasskeyRecoveryStage.VERIFY_CREATED_PASSKEY) { throw failure }
                }
            }
            assertEquals(PasskeyRecoveryStage.VERIFY_CREATED_PASSKEY, thrown.stage)
            assertSame(failure, thrown.cause)
        }
    }

    @Test
    fun `cancellation is never converted into a recoverable setup failure`() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                atPasskeyRecoveryStage(PasskeyRecoveryStage.SAVE) { throw CancellationException("leave") }
            }
        }
    }
}
