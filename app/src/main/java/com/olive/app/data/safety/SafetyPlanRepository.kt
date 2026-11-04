// Safe-app users and support staff: loads and saves safety-plan edits.
package com.olive.app.data

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.json.JSONObject
import kotlinx.coroutines.tasks.await

// Callable Functions verify current Auth roles and sharing on every read and write; no plaintext Firestore disk cache.
internal class SafetyPlanRepository(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance("northamerica-northeast1"),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    // Calls a protected safety-plan function and returns its JSON response.
    private suspend fun call(name: String, data: Map<String, Any> = emptyMap()): JSONObject {
        val result = functions.getHttpsCallable(name).call(data).await().data as? Map<*, *>
            ?: error("Invalid response")
        return JSONObject(result)
    }

    // Watches a plan that Firestore rules have already approved for this signed-in account.
    fun observe(
        ownerUid: String,
        onUpdate: (SafetyPlan) -> Unit,
        onError: (Exception) -> Unit,
    ): ListenerRegistration = firestore.collection("safetyPlans").document(ownerUid)
        .addSnapshotListener { snapshot, error ->
            if (error != null) {
                onError(error)
                return@addSnapshotListener
            }
            val data = snapshot?.data ?: return@addSnapshotListener
            try {
                onUpdate(SafetyPlan.fromJson(JSONObject(data)))
            } catch (failure: Exception) {
                onError(failure)
            }
        }

    // Sends changed safety-plan fields and returns the merged server version.
    suspend fun sync(plan: SafetyPlan): SafetyPlan {
        val changes =
            plan.fields.filterValues { it.mutationId != null && it.conflictRevision == null }
                .map { (key, field) ->
                    mapOf(
                        "key" to key,
                        "value" to field.value,
                        "revision" to field.revision,
                        "mutationId" to checkNotNull(field.mutationId)
                    )
                }
        val result = call(
            "safetyPlanSync",
            mapOf(
                "ownerUid" to plan.ownerUid,
                "defaults" to plan.fields.mapValues { it.value.value },
                "changes" to changes
            )
        )
        return SafetyPlan.fromJson(result.getJSONObject("plan"))
    }

    // Opens a new plan-consult ticket and shares the current safety plan with it.
    suspend fun consult(action: String) = SafetyPlan.fromJson(
        call(
            "safetyPlanConsult",
            mapOf("action" to action)
        ).getJSONObject("plan")
    )

    // Shares the safety plan with the selected trusted contacts.
    suspend fun shareContacts(friends: List<Friend>) =
        SafetyPlan.fromJson(call("safetyPlanShareContacts", mapOf("contacts" to friends.map {
            mapOf("uid" to it.uid, "friendshipId" to it.friendshipId, "name" to it.username)
        })).getJSONObject("plan"))

    // Saves which trusted contact should receive safety-plan support access.
    suspend fun setSupportContact(uid: String) {
        call("safetySupportContact", mapOf("safeContactUid" to uid))
    }

    // Loads safety plans that other users shared with this trusted contact.
    suspend fun sharedWithMe(): List<SharedSafetyPlan> {
        val plans = call("safetyPlansSharedWithMe").getJSONArray("plans")
        return (0 until plans.length()).map { index ->
            val row = plans.getJSONObject(index)
            SharedSafetyPlan(
                row.getString("ownerUid"),
                row.optString("ownerName"),
                row.optLong("updatedAt")
            )
        }
    }

    // Loads open and archived plan-consult tickets for this user.
    suspend fun consultations(): Pair<Boolean, List<SafetyConsultation>> {
        val result = call("safetyPlanConsultations")
        val plans = result.getJSONArray("plans")
        return result.getBoolean("available") to (0 until plans.length()).map { index ->
            val row = plans.getJSONObject(index)
            SafetyConsultation(
                row.getString("ownerUid"),
                row.optString("ownerName")
            )
        }
    }

    // Finds the staff member with the fewest active assignments.
    suspend fun available(value: Boolean) {
        call("safetyPlanAvailability", mapOf("available" to value))
    }
}
