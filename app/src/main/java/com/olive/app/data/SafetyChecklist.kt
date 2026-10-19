package com.olive.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class SafetyChecklistItem(val id:String,val text:String,val checked:Boolean=false)
internal data class SafetyPlanContact(val id:String,val name:String,val details:String)
private const val SAFETY_CONTACT_PREFIX="trusted-contact:"

internal fun safetyPlanContactItem(contact:SafeContact)=SafetyChecklistItem(
    SAFETY_CONTACT_PREFIX+contact.id,
    JSONObject().put("name",contact.name).put("details",contact.details).toString())
internal fun readSafetyPlanContact(item:SafetyChecklistItem):SafetyPlanContact? {
    if(!item.id.startsWith(SAFETY_CONTACT_PREFIX)) return null
    return runCatching {
        val json=JSONObject(item.text)
        SafetyPlanContact(item.id.removePrefix(SAFETY_CONTACT_PREFIX),json.getString("name"),json.getString("details"))
    }.getOrNull()
}

// Checklist rows remain inside each existing section's revision, so offline/staff conflict handling stays compatible.
internal fun readSafetyChecklist(raw:String,legacyExtras:List<String> = emptyList()):List<SafetyChecklistItem> {
    val saved=runCatching {
        val json=JSONObject(raw)
        require(json.optInt("oliveSafetyChecklist")==1)
        val rows=json.getJSONArray("items")
        require(rows.length()<=80)
        (0 until rows.length()).map { index ->
            val row=rows.getJSONObject(index)
            SafetyChecklistItem(row.getString("id"),row.getString("text"),row.getBoolean("checked"))
        }.also { items -> require(items.map { it.id }.distinct().size==items.size) }
    }.getOrNull()
    if(saved!=null) return saved
    // Older free-text notes are retained verbatim; document suggestions are added only on this first conversion.
    return (listOf(raw).filter(String::isNotBlank)+legacyExtras).mapIndexed { index,text ->
        SafetyChecklistItem(UUID.nameUUIDFromBytes("$index:$text".toByteArray(Charsets.UTF_8)).toString(),text)
    }
}
internal fun writeSafetyChecklist(items:List<SafetyChecklistItem>):String = JSONObject().put("oliveSafetyChecklist",1)
    .put("items",JSONArray().also { rows -> items.forEach { item ->
        rows.put(JSONObject().put("id",item.id).put("text",item.text).put("checked",item.checked))
    } }).toString()
internal fun safetyChecklistPreview(raw:String)=readSafetyChecklist(raw).joinToString("\n") {
    readSafetyPlanContact(it)?.let { contact -> "• ${contact.name}: ${contact.details}" }
        ?: ((if(it.checked) "☑ " else "☐ ")+it.text)
}
