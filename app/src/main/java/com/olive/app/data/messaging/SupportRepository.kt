// Users and staff: saves support tickets separately from plan consultations.
package com.olive.app.data

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

internal class SupportRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("northamerica-northeast1"),
) {
    // Calls a protected support-ticket function and checks its response.
    private suspend fun call(name: String, data: Map<String, Any>): JSONObject {
        val result = functions.getHttpsCallable(name).call(data).await().data as? Map<*, *>
            ?: error("Invalid response")
        return JSONObject(result)
    }

    // Creates a new support ticket without sharing the safety plan.
    suspend fun requestHelp(): StaffSupportTicket = updateRequest("request")

    // Returns true when the user still has a staff request that can be cancelled.
    suspend fun cancelRequest(): StaffSupportTicket = updateRequest("stop")

    // Updates one support ticket without replacing its existing chat history.
    private suspend fun updateRequest(action: String): StaffSupportTicket {
        val ticket = call("staffSupportRequest", mapOf("action" to action)).getJSONObject("ticket")
        return StaffSupportTicket(
            requested = ticket.optBoolean("requested"),
            staffUid = ticket.optString("staffUid"),
            conversationId = ticket.optString("conversationId"),
        )
    }

    // Adds an admin to a support ticket that needs escalation.
    suspend fun requestAdmin(conversationId: String) {
        call("safetySupportAdmin", mapOf("conversationId" to conversationId))
    }

    // Closes the support ticket and archives it for every participant.
    suspend fun close(conversationId: String) {
        call("staffSupportClose", mapOf("conversationId" to conversationId))
    }
}

internal data class StaffSupportTicket(
    val requested: Boolean,
    val staffUid: String,
    val conversationId: String,
)
