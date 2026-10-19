package com.olive.app.ui

import android.content.Context
import com.olive.app.R
import com.olive.app.data.SafetyChecklistItem
import com.olive.app.data.writeSafetyChecklist
import java.util.UUID

// Adaptable prompts informed by PHAC safety-planning guidance; all starting text remains editable.
// https://www.canada.ca/en/public-health/services/health-promotion/stop-family-violence/plan-your-safety.html
internal fun safetyDefaults(context:Context):Map<String,String> = buildMap {
    listOf("primary","backup").forEach { plan ->
        com.olive.app.data.SAFETY_SECTIONS.forEach { section ->
            val prompts=when(section) {
                "documents" -> safetyDocumentSuggestions(context)
                "grab" -> context.resources.getStringArray(R.array.safety_grab_checklist).toList()
                "destination" -> context.resources.getStringArray(R.array.safety_destination_checklist).toList()
                "travel" -> context.resources.getStringArray(R.array.safety_travel_checklist).toList()
                "contacts" -> context.resources.getStringArray(R.array.safety_contacts_checklist).toList()
                "aftercare" -> context.resources.getStringArray(R.array.safety_aftercare_checklist).toList()
                else -> emptyList()
            }
            put("${plan}_$section",writeSafetyChecklist(prompts.map { SafetyChecklistItem(UUID.randomUUID().toString(),it) }))
        }
    }
}
internal fun safetyDocumentSuggestions(context:Context)=context.resources.getStringArray(R.array.safety_document_checklist).toList()
internal fun safetySectionLabel(section:String)=when(section) {
    "destination" -> R.string.safety_destination;"travel" -> R.string.safety_travel;"contacts" -> R.string.safety_contacts
    "documents" -> R.string.safety_documents_heading;"grab" -> R.string.safety_grab_heading
    "essentials" -> R.string.safety_essentials;"dependants" -> R.string.safety_dependants;else -> R.string.safety_aftercare
}
internal fun safetySectionHint(section:String)=when(section) {
    "destination" -> R.string.safety_destination_hint
    "travel" -> R.string.safety_travel_hint
    "contacts" -> R.string.safety_contacts_hint
    "aftercare" -> R.string.safety_aftercare_hint
    else -> null
}
