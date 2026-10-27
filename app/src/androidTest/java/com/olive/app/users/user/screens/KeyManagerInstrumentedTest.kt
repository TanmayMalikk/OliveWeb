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
    @Test
    fun currentOaepContractWrapsAndUnwrapsAesKey() {
        val original = EncryptionUtils.generateSymmetricKey()

        val wrapped = KeyManager.wrapKey(original, KeyManager.getOrCreatePublicKey())
        val restored = KeyManager.unwrapKey(wrapped, KeyManager.CURRENT_KEY_WRAP_VERSION)

        assertArrayEquals(original.encoded, restored.encoded)
    }
}
