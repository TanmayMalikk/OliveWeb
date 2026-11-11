// Safe contacts and organization accounts: caches authorized safety-plan list metadata encrypted on-device.
package com.olive.app.data

import android.content.Context
import com.olive.app.security.EncryptedVault
import com.olive.app.security.JsonVault
import com.olive.app.security.child
import org.json.JSONArray
import org.json.JSONObject

internal data class SafetyAccessIndex(
    val available: Boolean = true,
    val consultations: List<SafetyConsultation> = emptyList(),
    val sharedPlans: List<SharedSafetyPlan> = emptyList(),
)

internal class SafetyAccessIndexStore(private val vault: JsonVault) {
    constructor(context: Context) : this(EncryptedVault(context.applicationContext, "safety-access-index"))

    fun read(uid: String): SafetyAccessIndex {
        requireUid(uid)
        val value = runCatching { vault.read().optJSONObject(ACCOUNTS)?.optJSONObject(uid) }
            .getOrNull() ?: return SafetyAccessIndex()
        return runCatching {
            SafetyAccessIndex(
                available = value.optBoolean(AVAILABLE, true),
                consultations = value.optJSONArray(CONSULTATIONS).rows { row ->
                    SafetyConsultation(row.getString("ownerUid"), row.optString("ownerName"))
                },
                sharedPlans = value.optJSONArray(SHARED).rows { row ->
                    SharedSafetyPlan(
                        row.getString("ownerUid"),
                        row.optString("ownerName"),
                        row.optLong("updatedAt"),
                    )
                },
            )
        }.getOrElse {
            purge(uid)
            SafetyAccessIndex()
        }
    }

    fun replaceConsultations(uid: String, available: Boolean, values: List<SafetyConsultation>) {
        requireUid(uid)
        vault.update { root -> root.child(ACCOUNTS).child(uid)
            .put(AVAILABLE, available)
            .put(CONSULTATIONS, JSONArray(values.distinctBy { it.ownerUid }.map { value ->
                JSONObject().put("ownerUid", value.ownerUid).put("ownerName", value.ownerName)
            })) }
    }

    fun replaceShared(uid: String, values: List<SharedSafetyPlan>) {
        requireUid(uid)
        vault.update { root -> root.child(ACCOUNTS).child(uid)
            .put(SHARED, JSONArray(values.distinctBy { it.ownerUid }.map { value ->
                JSONObject().put("ownerUid", value.ownerUid).put("ownerName", value.ownerName)
                    .put("updatedAt", value.updatedAt)
            })) }
    }

    fun purge(uid: String) {
        requireUid(uid)
        runCatching { vault.update { it.optJSONObject(ACCOUNTS)?.remove(uid) } }
    }

    private fun requireUid(uid: String) = require(uid.isNotBlank() && uid.length <= 128)
    private fun <T> JSONArray?.rows(parse: (JSONObject) -> T): List<T> = this?.let { values ->
        (0 until values.length()).mapNotNull { index -> runCatching { parse(values.getJSONObject(index)) }.getOrNull() }
    }.orEmpty()

    private companion object {
        const val ACCOUNTS = "accounts"
        const val AVAILABLE = "available"
        const val CONSULTATIONS = "consultations"
        const val SHARED = "sharedPlans"
    }
}

