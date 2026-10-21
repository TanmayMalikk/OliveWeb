package com.olive.app.data

import org.json.JSONObject
import java.util.UUID

internal val SAFETY_SECTIONS=listOf("destination","travel","contacts","essentials","dependants","aftercare","documents","grab")
internal val SAFETY_CHECKLIST_SECTIONS=listOf("documents","grab","destination","travel","contacts","aftercare")
internal val SAFETY_FIELD_KEYS=listOf("primary","backup").flatMap { plan -> SAFETY_SECTIONS.map { "${plan}_$it" } }

internal data class SafetyField(val value:String,val revision:Long=0,val mutationId:String?=null,
    val conflictValue:String?=null,val conflictRevision:Long?=null) {
    fun toJson():JSONObject=JSONObject().put("value",value).put("revision",revision).put("mutationId",mutationId)
        .put("conflictValue",conflictValue).put("conflictRevision",conflictRevision)
    companion object {
        fun fromJson(json:JSONObject)=SafetyField(json.getString("value"),json.optLong("revision"),
            json.optString("mutationId").takeIf { it.isNotEmpty() },json.optString("conflictValue").takeIf { json.has("conflictValue") && !json.isNull("conflictValue") },
            json.optLong("conflictRevision").takeIf { json.has("conflictRevision") && !json.isNull("conflictRevision") })
    }
}
internal data class SafetyPlan(val ownerUid:String,val ownerName:String,val fields:Map<String,SafetyField>,
    val staffUid:String="",val staffName:String="",val consultationRequested:Boolean=false,val shareAction:String="",
    val safeContactUids:List<String> = emptyList(),val safeContactNames:Map<String,String> = emptyMap()) {
    val hasEdits get()=fields.values.any { it.mutationId!=null }
    val hasConflicts get()=fields.values.any { it.conflictRevision!=null }
    val pending get()=hasEdits || shareAction.isNotEmpty()
    fun toJson():JSONObject=JSONObject().put("ownerUid",ownerUid).put("ownerName",ownerName)
        .put("fields",JSONObject().also { json -> fields.forEach { (key,field) -> json.put(key,field.toJson()) } })
        .put("staffUid",staffUid).put("staffName",staffName).put("consultationRequested",consultationRequested).put("shareAction",shareAction)
        .put("safeContactUids",org.json.JSONArray(safeContactUids)).put("safeContactNames",JSONObject(safeContactNames))
    companion object {
        fun fromJson(json:JSONObject):SafetyPlan {
            val fields=json.getJSONObject("fields")
            return SafetyPlan(json.getString("ownerUid"),json.optString("ownerName"),SAFETY_FIELD_KEYS.mapNotNull { key ->
                fields.optJSONObject(key)?.let { key to SafetyField.fromJson(it) }
            }.toMap(),json.optString("staffUid"),json.optString("staffName"),json.optBoolean("consultationRequested"),json.optString("shareAction"),
                json.optJSONArray("safeContactUids")?.let { array -> (0 until array.length()).map(array::getString) }.orEmpty(),
                json.optJSONObject("safeContactNames")?.let { names -> names.keys().asSequence().associateWith(names::getString) }.orEmpty())
        }
    }
}

// Never discard a draft because another device or staff member changed the same section.
internal fun mergeSafetyPlan(current:SafetyPlan,sent:SafetyPlan,remote:SafetyPlan):SafetyPlan {
    val fields=current.fields.mapValues { (key,local) ->
        val cloud=remote.fields[key] ?: return@mapValues local
        when {
            cloud.revision<local.revision -> local
            local.mutationId==null -> cloud.copy(mutationId=null)
            local.mutationId==cloud.mutationId -> cloud.copy(mutationId=null)
            sent.fields[key]?.mutationId!=null && sent.fields[key]?.mutationId==cloud.mutationId ->
                local.copy(revision=cloud.revision,conflictValue=null,conflictRevision=null)
            local.revision!=cloud.revision -> local.copy(conflictValue=cloud.value,conflictRevision=cloud.revision)
            else -> local
        }
    }
    return current.copy(fields=fields,staffUid=remote.staffUid,staffName=remote.staffName,consultationRequested=remote.consultationRequested,
        safeContactUids=remote.safeContactUids,safeContactNames=remote.safeContactNames)
}
internal fun SafetyField.edit(value:String)=copy(value=value,mutationId=UUID.randomUUID().toString())
// Upgrade only untouched local placeholders. User-cleared checklists, drafts and remote revisions remain intact.
internal fun safetyFieldsWithDefaults(existing:Map<String,SafetyField>,defaults:Map<String,String>):Map<String,SafetyField> {
    val fields=defaults.mapValues { SafetyField(it.value) }+existing
    return fields.mapValues { (key,field) ->
        val suggested=defaults[key]
        if(key in existing && key.substringAfter('_') in listOf("destination","travel","contacts","aftercare") &&
            field.revision==0L && field.mutationId==null && field.conflictRevision==null &&
            field.value==writeSafetyChecklist(emptyList()) && suggested!=null && readSafetyChecklist(suggested).isNotEmpty())
            field.edit(suggested) else field
    }
}
internal data class SafetyConsultation(val ownerUid:String,val ownerName:String)
internal data class SharedSafetyPlan(val ownerUid:String,val ownerName:String,val updatedAt:Long=0)
