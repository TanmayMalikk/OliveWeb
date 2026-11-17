package com.olive.app.ui.covers.fitness

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal fun fitnessAuthenticatedUid(): String? = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()

internal fun fitnessAccountKey(uid: String): String = MessageDigest.getInstance("SHA-256")
    .digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }

internal fun fitnessPreferences(context: Context, uid: String, workout: Boolean = false): SharedPreferences {
    val prefix = if (workout) "fitness_workout_" else "fitness_cover_"
    val preferences = context.getSharedPreferences(prefix + fitnessAccountKey(uid), Context.MODE_PRIVATE)
    if (!preferences.contains("_migrated")) {
        val legacyName = prefix + if (workout) uid.hashCode().toUInt().toString(16) else uid.hashCode().toString()
        val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        // Record who claimed the legacy cache; a hash collision cannot migrate it into a second account.
        if (legacy.getString("_owner", uid) == uid) {
            legacy.edit(commit = true) { putString("_owner", uid) }
            preferences.edit(commit = true) {
                legacy.all.filterKeys { !it.startsWith("_") }.forEach { (key, value) -> putFitnessValue(key, value) }
                putBoolean("_migrated", true)
            }
        } else preferences.edit(commit = true) { putBoolean("_migrated", true) }
    }
    return preferences
}

private fun SharedPreferences.Editor.putFitnessValue(key: String, value: Any?) {
    when (value) {
        is String -> putString(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is Boolean -> putBoolean(key, value)
        null -> remove(key)
    }
}

internal fun cloudFitnessKey(key: String) = key in setOf("weight_kg", "height_cm", "step_goal",
    "calorie_goal", "protein_goal", "carbohydrate_goal", "fat_goal", "water_goal_ml", "workout_history") ||
    Regex("""(?:activity_steps|heart_rate_[a-z_]+)_\d{4}-\d{2}-\d{2}""").matches(key)

// The cloud is account-owned; the preferences are an offline cache and durable per-document outbox.
internal object FitnessAccountSync {
    val ready = MutableStateFlow(true)
    val failed = MutableStateFlow(false)
    private var uid: String? = null
    private var registration: ListenerRegistration? = null
    private var applying = false
    private var preferences: SharedPreferences? = null
    private var workouts: SharedPreferences? = null
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun bind(context: Context, accountId: String?) {
        if (uid == accountId && registration != null) return
        val previous = uid
        if (previous != null && previous != accountId) {
            WorkManager.getInstance(context).cancelUniqueWork("fitness-health-${fitnessAccountKey(previous)}")
            WorkManager.getInstance(context).cancelUniqueWork("fitness-upload-${fitnessAccountKey(previous)}")
        }
        registration?.remove()
        listener?.let { preferences?.unregisterOnSharedPreferenceChangeListener(it); workouts?.unregisterOnSharedPreferenceChangeListener(it) }
        registration = null
        uid = accountId
        ready.value = accountId == null
        failed.value = false
        FitnessWatchSync.bindAccount(context, accountId)
        if (accountId == null) return
        val owner = accountId
        val local = fitnessPreferences(context, owner)
        val workout = fitnessPreferences(context, owner, workout = true)
        preferences = local
        workouts = workout
        ready.value = outbox(context, owner).getBoolean("_hydrated", false) ||
            local.all.keys.any { cloudFitnessKey(it) || it.startsWith("foods_") }
        listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (uid != owner || applying || key == null || !cloudFitnessKey(key)) return@OnSharedPreferenceChangeListener
            queue(context, owner, key, prefs.all[key])
            publish(context, owner)
        }.also { local.registerOnSharedPreferenceChangeListener(it); workout.registerOnSharedPreferenceChangeListener(it) }
        val ref = FirebaseFirestore.getInstance().collection("users").document(owner).collection("fitness")
        registration = ref.addSnapshotListener { snapshot, error ->
            if (uid != owner || fitnessAuthenticatedUid() != owner) return@addSnapshotListener
            if (error != null) { failed.value = true; ready.value = true; return@addSnapshotListener }
            if (snapshot == null || snapshot.metadata.isFromCache) return@addSnapshotListener
            applying = true
            try {
                val pending = outbox(context, owner)
                val remoteIds = snapshot.documents.map { it.id }.toSet()
                // First sync migrates only local keys absent from the server. Existing cloud data wins.
                if (!pending.getBoolean("_hydrated", false)) {
                    for (prefs in listOf(local, workout)) prefs.all.filterKeys(::cloudFitnessKey).forEach { (key, value) ->
                        if (key !in remoteIds && !pending.contains(key)) queue(context, owner, key, value)
                    }
                    local.all.filterKeys { it.startsWith("foods_") }.forEach { (key, value) ->
                        val date = key.removePrefix("foods_")
                        runCatching { JSONArray(value as String) }.getOrNull()?.let { entries ->
                            for (i in 0 until entries.length()) {
                                val entry = entries.getJSONObject(i)
                                val id = "meal_${date}_${entry.getString("logId")}"
                                if (id !in remoteIds && !pending.contains(id)) queue(context, owner, id, entry.toString())
                            }
                        }
                    }
                }
                snapshot.documents.forEach { doc ->
                    if (pending.contains(doc.id) && !doc.id.startsWith("activity_steps_")) return@forEach
                    val payload = doc.getString("payload") ?: return@forEach
                    val value = decode(payload)
                    if (doc.id.startsWith("meal_") || doc.id.startsWith("drink_")) {
                        applyMeal(local, doc.id, value as? String)
                    } else if (cloudFitnessKey(doc.id)) {
                        val prefs = if (doc.id == "workout_history") workout else local
                        // Step totals never decrease due to two devices publishing in a different order.
                        val incoming = if (doc.id.startsWith("activity_steps_") && value is Int)
                            maxOf(value, prefs.getInt(doc.id, 0)) else value
                        prefs.edit(commit = true) { putFitnessValue(doc.id, incoming) }
                        if (incoming != value) queue(context, owner, doc.id, incoming)
                    }
                }
                pending.edit(commit = true) { putBoolean("_hydrated", true) }
                ready.value = true
                failed.value = false
            } finally { applying = false }
            publish(context, owner)
            schedule(context, owner)
        }
        schedule(context, owner)
        FitnessHealthConnect.schedule(context, owner)
    }

    fun meal(context: Context, owner: String, date: String, entryId: String, entry: String?) {
        if (owner == "guest") return
        queue(context, owner, "meal_${date}_$entryId", entry)
    }

    fun water(context: Context, owner: String, date: String, entryId: String, entry: String) {
        if (owner == "guest") return
        queue(context, owner, "drink_${date}_$entryId", entry)
    }

    private fun applyMeal(local: SharedPreferences, id: String, entry: String?) {
        val drink = id.startsWith("drink_")
        val prefix = if (drink) "drink_" else "meal_"
        val date = id.removePrefix(prefix).take(10)
        val entryId = id.removePrefix("${prefix}${date}_")
        val key = if (drink) "water_$date" else "foods_$date"
        val existing = runCatching { JSONArray(local.getString(key, "[]")) }.getOrDefault(JSONArray())
        val next = JSONArray()
        for (i in 0 until existing.length()) {
            val value = existing.getJSONObject(i)
            if (value.optString("logId") != entryId) next.put(value)
        }
        if (entry != null) runCatching { JSONObject(entry) }.getOrNull()?.let(next::put)
        local.edit(commit = true) { putString(key, next.toString()) }
    }

    internal fun outbox(context: Context, owner: String) = context.getSharedPreferences("fitness_outbox_${fitnessAccountKey(owner)}", Context.MODE_PRIVATE)

    private fun queue(context: Context, owner: String, key: String, value: Any?) {
        val payload = JSONObject().put("type", when (value) {
            is Int -> "int"; is Long -> "long"; is Float -> "float"; is Boolean -> "bool"; is String -> "string"; else -> "null"
        }).put("value", value ?: JSONObject.NULL).toString()
        val pending = outbox(context, owner)
        synchronized(pending) { pending.edit(commit = true) { putString(key, payload) } }
        schedule(context, owner)
    }

    internal fun decode(payload: String): Any? = runCatching {
        val value = JSONObject(payload)
        when (value.getString("type")) {
            "int" -> value.getInt("value"); "long" -> value.getLong("value")
            "float" -> value.getDouble("value").toFloat().takeIf(Float::isFinite); "bool" -> value.getBoolean("value")
            "string" -> value.getString("value"); else -> null
        }
    }.getOrNull()

    internal fun publish(context: Context, owner: String) {
        if (uid != owner || !ready.value || fitnessAuthenticatedUid() != owner) return
        FitnessWatchSync.publish(context, owner, FitnessStore(context, owner).snapshot())
    }

    private fun schedule(context: Context, owner: String) {
        if (fitnessAuthenticatedUid() != owner) return
        val data = workDataOf("uid" to owner)
        WorkManager.getInstance(context).enqueueUniqueWork("fitness-upload-${fitnessAccountKey(owner)}",
            ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<FitnessUploadWorker>().setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}

internal class FitnessUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val owner = inputData.getString("uid") ?: return Result.failure()
        if (fitnessAuthenticatedUid() != owner) return Result.success()
        val pending = FitnessAccountSync.outbox(applicationContext, owner)
        if (!pending.getBoolean("_hydrated", false)) return Result.retry()
        try {
            // Preserve newer edits made while an earlier value is being uploaded.
            for ((key, raw) in pending.all.filterKeys { !it.startsWith("_") }) {
                if (fitnessAuthenticatedUid() != owner) return Result.success()
                val payload = raw as? String ?: continue
                FirebaseFirestore.getInstance().collection("users").document(owner).collection("fitness").document(key)
                    .set(mapOf("schemaVersion" to 1, "payload" to payload, "updatedAt" to FieldValue.serverTimestamp())).await()
                synchronized(pending) {
                    if (pending.getString(key, null) == payload) pending.edit(commit = true) { remove(key) }
                }
            }
            if (fitnessAuthenticatedUid() == owner) {
                val store = FitnessStore(applicationContext, owner)
                FitnessCloudSync.upload(owner, store.snapshot(), store.history())
            }
            return if (pending.all.keys.any { !it.startsWith("_") }) Result.retry() else Result.success()
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { return Result.retry() }
    }
}
