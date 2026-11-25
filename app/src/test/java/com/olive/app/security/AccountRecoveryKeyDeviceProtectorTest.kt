package com.olive.app.security

import com.olive.app.support.TestAndroidKeyStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
class AccountRecoveryKeyDeviceProtectorTest {

    @Test
    fun `protect unlock and forget manage app-managed keystore record without device auth`() {
        TestAndroidKeyStore.install().use {
            val protector = AccountRecoveryKeyDeviceProtector()
            val accountId = "account-a"
            val ark = AccountRecoveryKey.generate()
            val expectedRaw = ark.encodedCopy()

            val record = protector.protect(accountId, ark)

            assertEquals(AccountRecoveryKeyDeviceRecord.VERSION_APP_MANAGED, record.version)
            assertEquals(accountId, record.accountId)

            val unlocked = protector.unlock(record)
            val unlockedRaw = unlocked.encodedCopy()
            assertArrayEquals(expectedRaw, unlockedRaw)

            expectedRaw.fill(0)
            unlockedRaw.fill(0)

            protector.forget(record)

            assertThrows(IllegalStateException::class.java) {
                protector.unlock(record)
            }
        }
    }

    @Test
    fun `unlock rejects legacy v1 device auth record and forget deletes legacy alias`() {
        TestAndroidKeyStore.install().use {
            val protector = AccountRecoveryKeyDeviceProtector()
            val accountId = "account-legacy"
            val protectionId = UUID.randomUUID().toString()
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(accountId.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val legacyAlias = "olive.account.ark.v1.$hash.$protectionId"

            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val generated = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            store.setKeyEntry(legacyAlias, generated, null, null)
            assertNotNull(store.getKey(legacyAlias, null))

            val legacyRecord = AccountRecoveryKeyDeviceRecord(
                accountId = accountId,
                protectionId = protectionId,
                ivBase64 = Base64.getEncoder().encodeToString(ByteArray(12) { 9 }),
                ciphertextBase64 = Base64.getEncoder().encodeToString(ByteArray(48) { 7 }),
                version = AccountRecoveryKeyDeviceRecord.VERSION_DEVICE_AUTH,
            )

            assertThrows(IllegalArgumentException::class.java) {
                protector.unlock(legacyRecord)
            }
            assertTrue(store.containsAlias(legacyAlias))

            protector.forget(legacyRecord)

            assertFalse(store.containsAlias(legacyAlias))
        }
    }
}
