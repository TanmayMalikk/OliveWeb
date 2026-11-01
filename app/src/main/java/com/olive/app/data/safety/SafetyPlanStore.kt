// Safe-app users and support staff: keeps safety-plan drafts on the device.
package com.olive.app.data

import android.content.Context
import com.olive.app.security.EncryptedVault
import com.olive.app.security.child

// Separate encrypted vaults isolate local university accounts from Firebase accounts and each signed-in actor's cache.
internal class SafetyPlanStore(context: Context, localMode: Boolean) {
    private val vault = EncryptedVault(context, if (localMode) "safety-demo" else "safety-firebase")
    // Reads the saved value for safety.
    fun read(actorUid: String, ownerUid: String) =
        vault.read().optJSONObject(actorUid)?.optJSONObject(ownerUid)?.let(SafetyPlan::fromJson)

    // returns the ensure for safety.
    fun ensure(
        actorUid: String,
        ownerUid: String,
        ownerName: String,
        defaults: Map<String, String>
    ): SafetyPlan {
        vault.update { root ->
            val actor = root.child(actorUid)
            val existing = actor.optJSONObject(ownerUid)?.let(SafetyPlan::fromJson)
            val fields = safetyFieldsWithDefaults(existing?.fields.orEmpty(), defaults)
            actor.put(
                ownerUid,
                (existing?.copy(fields = fields) ?: SafetyPlan(
                    ownerUid,
                    ownerName,
                    fields
                )).toJson()
            )
        }
        return checkNotNull(read(actorUid, ownerUid))
    }

    // Safety: Updates the feature state.
    fun update(actorUid: String, ownerUid: String, change: (SafetyPlan) -> SafetyPlan): SafetyPlan {
        var updated: SafetyPlan? = null
        vault.update { root ->
            val actor = root.child(actorUid)
            val old = actor.optJSONObject(ownerUid) ?: error("Plan has not been loaded")
            val next = change(SafetyPlan.fromJson(old))
            updated = next
            actor.put(ownerUid, next.toJson())
        }
        return checkNotNull(updated)
    }

    // Safety: Writes the new value. It writes the value to the correct Firebase path.
    fun write(actorUid: String, ownerUid: String, value: SafetyPlan) {
        require(value.ownerUid == ownerUid)
        vault.update { root -> root.child(actorUid).put(ownerUid, value.toJson()) }
    }

    // Safety: returns the all.
    fun all(actorUid: String): List<SafetyPlan> {
        val actor = vault.read().optJSONObject(actorUid) ?: return emptyList()
        return actor.keys().asSequence()
            .mapNotNull { actor.optJSONObject(it)?.let(SafetyPlan::fromJson) }.toList()
    }

    // Removes the selected item for safety.
    fun remove(actorUid: String, ownerUid: String) {
        vault.update { it.optJSONObject(actorUid)?.remove(ownerUid) }
    }
}
