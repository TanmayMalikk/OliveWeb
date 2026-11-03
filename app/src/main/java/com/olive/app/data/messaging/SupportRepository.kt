// Users and staff: saves support tickets separately from plan consultations.
package com.olive.app.data

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

internal class SupportRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("northamerica-northeast1"),
) {
    // Calls the matching backend service for messaging.
    private suspend fun call(name: String, data: Map<String, Any>): JSONObject {
        val result = functions.getHttpsCallable(name).call(data).await().data as? Map<*, *>
            ?: error("Invalid response")
        return JSONObject(result)
    }

    // Requests help for messaging. It checks the chat and uses its secure data before reading or saving a message.
    suspend fun requestHelp(): StaffSupportTicket = updateRequest("request")

    // Messaging: returns the cancel request.
    suspend fun cancelRequest(): StaffSupportTicket = updateRequest("stop")

    // Messaging: Updates request.
    private suspend fun updateRequest(action: String): StaffSupportTicket {
        val ticket = call("staffSupportRequest", mapOf("action" to action)).getJSONObject("ticket")
        return StaffSupportTicket(
            requested = ticket.optBoolean("requested"),
            staffUid = ticket.optString("staffUid"),
            conversationId = ticket.optString("conversationId"),
        )
    }

    // Requests admin for messaging. It checks the chat and uses its secure data before reading or saving a message.
    suspend fun requestAdmin(conversationId: String) {
        call("safetySupportAdmin", mapOf("conversationId" to conversationId))
    }

    // Closes the active feature for messaging. It checks the chat and uses its secure data before reading or saving a message.
    suspend fun close(conversationId: String) {
        call("staffSupportClose", mapOf("conversationId" to conversationId))
    }
}

internal data class StaffSupportTicket(
    val requested: Boolean,
    val staffUid: String,
    val conversationId: String,
)
