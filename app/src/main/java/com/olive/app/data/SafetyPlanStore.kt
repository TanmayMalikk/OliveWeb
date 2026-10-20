package com.olive.app.data

import android.content.Context
import com.olive.app.security.EncryptedVault
import com.olive.app.security.child

// Separate encrypted vaults isolate local university accounts from Firebase accounts and each signed-in actor's cache.
internal class SafetyPlanStore(context:Context,localMode:Boolean) {
    private val vault=EncryptedVault(context,if(localMode) "safety-demo" else "safety-firebase")
    fun read(actorUid:String,ownerUid:String)=vault.read().optJSONObject(actorUid)?.optJSONObject(ownerUid)?.let(SafetyPlan::fromJson)
    fun ensure(actorUid:String,ownerUid:String,ownerName:String,defaults:Map<String,String>):SafetyPlan {
        vault.update { root ->
            val actor=root.child(actorUid)
            val existing=actor.optJSONObject(ownerUid)?.let(SafetyPlan::fromJson)
            val fields=safetyFieldsWithDefaults(existing?.fields.orEmpty(),defaults)
            actor.put(ownerUid,(existing?.copy(fields=fields) ?: SafetyPlan(ownerUid,ownerName,fields)).toJson())
        }
        return checkNotNull(read(actorUid,ownerUid))
    }
    fun update(actorUid:String,ownerUid:String,change:(SafetyPlan)->SafetyPlan):SafetyPlan {
        var updated:SafetyPlan?=null
        vault.update { root ->
            val actor=root.child(actorUid)
            val old=actor.optJSONObject(ownerUid) ?: error("Plan has not been loaded")
            updated=change(SafetyPlan.fromJson(old))
            actor.put(ownerUid,checkNotNull(updated).toJson())
        }
        return checkNotNull(updated)
    }
    fun write(actorUid:String,ownerUid:String,value:SafetyPlan) {
        require(value.ownerUid==ownerUid)
        vault.update { root -> root.child(actorUid).put(ownerUid,value.toJson()) }
    }
    fun all(actorUid:String):List<SafetyPlan> {
        val actor=vault.read().optJSONObject(actorUid) ?: return emptyList()
        return actor.keys().asSequence().mapNotNull { actor.optJSONObject(it)?.let(SafetyPlan::fromJson) }.toList()
    }
    fun remove(actorUid:String,ownerUid:String) { vault.update { it.optJSONObject(actorUid)?.remove(ownerUid) } }
}
