// Fitness cover users: syncs the signed-in account's 14-day fitness summary for the web viewer.
package com.olive.app.ui.covers.fitness

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.olive.app.data.AccountSyncRepository
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

internal object FitnessCloudSync {
    private const val MIN_SYNC_INTERVAL_MS = 30_000L
    private val lastUploadAtByAccount = ConcurrentHashMap<String, Long>()
    private val lastPayloadByAccount = ConcurrentHashMap<String, String>()

    // Uploads only changed summaries and never more than once every 30 seconds.
    suspend fun upload(accountId: String, snapshot: FitnessSnapshot, history: List<FitnessHistoryDay>) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        if (accountId != user.uid) return
        val summary = summaryJson(snapshot, history)
        val payloadSignature = summary.toString()
        val now = System.currentTimeMillis()
        val lastUploadAt = lastUploadAtByAccount[accountId] ?: 0L
        if (
            payloadSignature == lastPayloadByAccount[accountId] ||
            now - lastUploadAt < MIN_SYNC_INTERVAL_MS
        ) return
        summary.put("updatedAt", now)
        val payload = summary.toString()
        val bytes = payload.toByteArray(Charsets.UTF_8)
        // A upload error is handled here instead of closing the app.
        try {
            FirebaseStorage.getInstance().reference
                .child("accounts/${user.uid}/fitness/summary.json")
                .putBytes(
                    bytes,
                    StorageMetadata.Builder().setContentType("application/json")
                        .setCustomMetadata("schemaVersion", "1").build(),
                ).await()
            AccountSyncRepository().notifyChanged(accountId)
            lastPayloadByAccount[accountId] = payloadSignature
            lastUploadAtByAccount[accountId] = now
        } catch (_: Exception) {
            // Fitness sync: a temporary network or Firebase failure must not close the cover app.
        } finally {
            bytes.fill(0)
        }
    }

    // returns the summary json for fitness cover.
    private fun summaryJson(snapshot: FitnessSnapshot, history: List<FitnessHistoryDay>) = JSONObject().apply {
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
