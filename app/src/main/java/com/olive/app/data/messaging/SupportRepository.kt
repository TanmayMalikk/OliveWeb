// Keeps regular support tickets separate from safety-plan consultations.
package com.olive.app.data

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

internal class SupportRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("northamerica-northeast1"),
) {
    private suspend fun call(name: String, data: Map<String, Any>): JSONObject {
        val result = functions.getHttpsCallable(name).call(data).await().data as? Map<*, *>
            ?: error("Invalid response")
        return JSONObject(result)
    }

    suspend fun request(action: String): StaffSupportTicket {
        val ticket = call("staffSupportRequest", mapOf("action" to action)).getJSONObject("ticket")
        return StaffSupportTicket(
            requested = ticket.optBoolean("requested"),
            staffUid = ticket.optString("staffUid"),
            conversationId = ticket.optString("conversationId"),
        )
    }

    suspend fun requestAdmin(conversationId: String) {
        call("safetySupportAdmin", mapOf("conversationId" to conversationId))
    }

    suspend fun close(conversationId: String) {
        call("staffSupportClose", mapOf("conversationId" to conversationId))
    }
}

internal data class StaffSupportTicket(
    val requested: Boolean,
    val staffUid: String,
    val conversationId: String,
)
