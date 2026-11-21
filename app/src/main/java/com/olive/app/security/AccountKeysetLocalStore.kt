package com.olive.app.security

import org.json.JSONObject

/** Account-scoped local copies of encrypted keyset material; neither entry contains a raw ARK. */
internal class AccountKeysetLocalStore(private val vault: JsonVault) {
    internal data class Saved(
        val envelope: AccountKeysetEnvelope,
        val deviceRecord: AccountRecoveryKeyDeviceRecord,
    )

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

    fun save(value: Saved) {
        require(value.envelope.accountId == value.deviceRecord.accountId) { "Account keyset record mismatch" }
        vault.update { root ->
            root.child(SECTION).put(value.envelope.accountId, JSONObject().apply {
                put("envelope", value.envelope.toJson())
                put("deviceRecord", value.deviceRecord.toJson())
            })
        }
    }

    /** Removes every local ciphertext envelope and device-protected ARK record for this account. */
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
