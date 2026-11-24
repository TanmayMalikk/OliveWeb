package com.olive.app.security

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64
import java.util.UUID

class AccountKeysetLocalStoreTest {
    @Test
    fun `keysets remain isolated by account and purge only the selected account`() {
        val store = AccountKeysetLocalStore(FakeVault())
        val first = saved("account-a")
        val second = saved("account-b")

        store.save(first)
        store.save(second)

        assertEquals(first.envelope.keyId, store.read("account-a")?.envelope?.keyId)
        assertEquals(second.envelope.keyId, store.read("account-b")?.envelope?.keyId)
        assertEquals(first.deviceRecord, store.purge("account-a"))
        assertNull(store.read("account-a"))
        assertEquals(second.envelope.keyId, store.read("account-b")?.envelope?.keyId)
    }

    @Test
    fun `mismatched stored account records fail closed`() {
        val store = AccountKeysetLocalStore(FakeVault())
        val envelope = AccountKeysetEnvelope.create("account-a", AccountRecoveryKey.generate())
        val record = deviceRecord("account-b")

        assertThrows(IllegalArgumentException::class.java) {
            store.save(AccountKeysetLocalStore.Saved(envelope, record))
        }
    }

    private fun saved(accountId: String) = AccountKeysetLocalStore.Saved(
        AccountKeysetEnvelope.create(accountId, AccountRecoveryKey.generate()),
        deviceRecord(accountId),
    )

    private fun deviceRecord(accountId: String) = AccountRecoveryKeyDeviceRecord(
        accountId,
        UUID.randomUUID().toString(),
        Base64.getEncoder().encodeToString(ByteArray(12)),
        Base64.getEncoder().encodeToString(ByteArray(17)),
    )

    private class FakeVault : JsonVault {
        private var root = JSONObject()
        override fun read() = JSONObject(root.toString())
        override fun update(change: (JSONObject) -> Unit) = change(root)
        override fun clear() { root = JSONObject() }
    }
}
