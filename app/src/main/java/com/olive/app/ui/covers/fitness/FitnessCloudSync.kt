// Fitness cover users: syncs the signed-in account's 14-day fitness summary for the web viewer.
package com.olive.app.ui.covers.fitness

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

internal object FitnessCloudSync {
    private const val MIN_SYNC_INTERVAL_MS = 30_000L
    private var lastUploadAt = 0L
    private var lastPayload = ""

    // Uploads only changed summaries and never more than once every 30 seconds.
    suspend fun upload(accountId: String, snapshot: FitnessSnapshot, history: List<FitnessHistoryDay>) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        if (accountId != user.uid) return
        val payload = summaryJson(snapshot, history).toString()
        val now = System.currentTimeMillis()
        if (payload == lastPayload || now - lastUploadAt < MIN_SYNC_INTERVAL_MS) return
        val bytes = payload.toByteArray(Charsets.UTF_8)
        try {
            FirebaseStorage.getInstance().reference
                .child("accounts/${user.uid}/fitness/summary.json")
                .putBytes(
                    bytes,
                    StorageMetadata.Builder().setContentType("application/json")
                        .setCustomMetadata("schemaVersion", "1").build(),
                ).await()
            lastPayload = payload
            lastUploadAt = now
        } finally {
            bytes.fill(0)
        }
    }

    private fun summaryJson(snapshot: FitnessSnapshot, history: List<FitnessHistoryDay>) = JSONObject().apply {
        put("updatedAt", System.currentTimeMillis())
        put("stepGoal", snapshot.profile.stepGoal)
        put("calorieGoal", snapshot.profile.calorieGoal)
        put("proteinGoal", snapshot.profile.proteinGoal)
        put("carbohydrateGoal", snapshot.profile.carbohydrateGoal)
        put("fatGoal", snapshot.profile.fatGoal)
        put("watchConnected", snapshot.watchConnected)
        put("days", JSONArray(history.map { day ->
            JSONObject().apply {
                put("date", day.date.toString())
                put("steps", day.steps)
                put("distanceKm", day.distanceKm.toDouble())
                put("caloriesBurned", day.caloriesBurned.toDouble())
                put("caloriesEaten", day.caloriesEaten.toDouble())
                put("proteinGrams", day.proteinGrams.toDouble())
                put("carbohydrateGrams", day.carbohydrateGrams.toDouble())
                put("fatGrams", day.fatGrams.toDouble())
            }
        }))
        put("vitals", JSONArray(snapshot.heartRateHistory.map { vitals ->
            JSONObject().apply {
                put("date", vitals.date.toString())
                put("peakBpm", vitals.peakBpm)
                put("lowestBpm", vitals.lowestBpm)
                put("restingBpm", vitals.restingBpm ?: JSONObject.NULL)
                put("averageBpm", vitals.averageBpm)
                put("sampleCount", vitals.sampleCount)
                put("lastUpdatedAt", vitals.lastUpdatedAt)
            }
        }))
    }
}
