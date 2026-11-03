// Tests Android key wrapping for encrypted user documents.
package com.olive.app.users.user.screens

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.olive.app.security.EncryptionUtils
import com.olive.app.security.KeyManager
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.security.SecureRandom

/** Verifies the real Android Keystore provider, which host-side JVM tests cannot emulate. */
@RunWith(AndroidJUnit4::class)
class KeyManagerInstrumentedTest {
    // Checks the current oaep contract wraps and unwraps aes key step with the test's sample data.
    @Test
    fun currentOaepContractWrapsAndUnwrapsAesKey() {
        val original = EncryptionUtils.generateSymmetricKey()

        val wrapped = KeyManager.wrapKey(original, KeyManager.getOrCreatePublicKey())
        val restored = KeyManager.unwrapKey(wrapped, KeyManager.CURRENT_KEY_WRAP_VERSION)

        assertArrayEquals(original.encoded, restored.encoded)
    }

    @Test
    fun legacyRecordCanOpenKeyRewrappedWithCurrentProfile() {
        val original = EncryptionUtils.generateSymmetricKey()
        val wrapped = KeyManager.wrapKey(original, KeyManager.getOrCreatePublicKey())

        val restored = KeyManager.unwrapKey(wrapped, 1)

        assertArrayEquals(original.encoded, restored.encoded)
    }

    @Test
    fun incompatibleDeviceCopyFallsBackToAccountWrappedKey() {
        val original = EncryptionUtils.generateSymmetricKey()
        val accountWrapped = KeyManager.wrapKey(original, KeyManager.getOrCreatePublicKey())
        val invalidDeviceCopy = ByteArray(256).also(SecureRandom()::nextBytes).let {
            android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
        }

        val restored = KeyManager.unwrapFirst(
            listOf(invalidDeviceCopy, accountWrapped),
            KeyManager.CURRENT_KEY_WRAP_VERSION,
        )

        assertArrayEquals(original.encoded, restored.encoded)
    }
}
