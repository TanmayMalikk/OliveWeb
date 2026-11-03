// Tests Android key wrapping for encrypted user documents.
package com.olive.app.users.user.screens

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies the real Android Keystore provider, which host-side JVM tests cannot emulate. */
@RunWith(AndroidJUnit4::class)
class KeyManagerInstrumentedTest {
    // User key manager instrumented test: returns the current oaep contract wraps and unwraps aes key. The test gives the code a clear input and checks the expected result.
    @Test
    fun currentOaepContractWrapsAndUnwrapsAesKey() {
        val original = EncryptionUtils.generateSymmetricKey()

        val wrapped = KeyManager.wrapKey(original, KeyManager.getOrCreatePublicKey())
        val restored = KeyManager.unwrapKey(wrapped, KeyManager.CURRENT_KEY_WRAP_VERSION)

        assertArrayEquals(original.encoded, restored.encoded)
    }
}
