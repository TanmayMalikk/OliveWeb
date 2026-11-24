package com.olive.app.security

import org.json.JSONObject

/** Account-scoped local copies of encrypted keyset material; neither entry contains a raw ARK. */
internal class AccountKeysetLocalStore(private val vault: JsonVault) {
    internal data class Saved(
        val envelope: AccountKeysetEnvelope,
        val deviceRecord: AccountRecoveryKeyDeviceRecord,
    )

    /**
     * Reads the local encrypted keyset envelope and device record for [accountId], if present.
     *
     * @param accountId The account ID that owns the key or record.
     * @return The stored keyset envelope and recovery record, or null when unavailable or unreadable.
     */
    fun read(accountId: String): Saved? {
        val value = vault.read().optJSONObject(SECTION)?.optJSONObject(accountId) ?: return null
        return try {
            Saved(
                AccountKeysetEnvelope.fromJson(value.getJSONObject("envelope")),
                AccountRecoveryKeyDeviceRecord.fromJson(value.getJSONObject("deviceRecord")),
            ).also {
                check(it.envelope.accountId == accountId && it.deviceRecord.accountId == accountId) {
                    "Stored account keyset belongs to another account"
                }
            }
        } catch (failure: Exception) {
            throw AccountKeysetException("Stored account keyset is invalid", failure)
        }
    }

    /**
     * Persists a validated encrypted keyset envelope and device-protected ARK record into the local vault.
     *
     * @param value The paired account envelope and device-protected recovery key record.
     */
    fun save(value: Saved) {
        require(value.envelope.accountId == value.deviceRecord.accountId) { "Account keyset record mismatch" }
        vault.update { root ->
            root.child(SECTION).put(value.envelope.accountId, JSONObject().apply {
                put("envelope", value.envelope.toJson())
                put("deviceRecord", value.deviceRecord.toJson())
            })
        }
    }

    /**
     * Removes every local ciphertext envelope and device-protected ARK record for this account.
     *
     * @param accountId The account ID that owns the key or record.
     * @return The device protection record that was removed, or null when there was no record.
     */
    fun purge(accountId: String): AccountRecoveryKeyDeviceRecord? {
        var removed: AccountRecoveryKeyDeviceRecord? = null
        vault.update { root ->
            val entries = root.optJSONObject(SECTION) ?: return@update
            entries.optJSONObject(accountId)?.let { value ->
                removed = runCatching {
                    AccountRecoveryKeyDeviceRecord.fromJson(value.getJSONObject("deviceRecord"))
                }.getOrNull()
            }
            entries.remove(accountId)
        }
        return removed
    }

    private companion object {
        const val SECTION = "accountKeysets"
    }
}
