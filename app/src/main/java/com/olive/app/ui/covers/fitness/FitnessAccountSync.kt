package com.olive.app.ui.covers.fitness

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.olive.app.data.network.sha256Hex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * Returns the fitness authenticated user ID.
 *
 * @return The resulting fitness authenticated user ID string, or `null` when unavailable.
 */
internal fun fitnessAuthenticatedUid(): String? = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()

/**
 * Computes fitness account key.
 *
 * @param uid Firebase UID of the target user account.
 * @return The resulting fitness account key string.
 */
internal fun fitnessAccountKey(uid: String): String = sha256Hex(uid)

/**
 * Computes fitness preferences.
 *
 * @param context Android context used to access system services, resources, or storage.
 * @param uid Firebase UID of the target user account.
 * @param workout Whether to apply workout.
 * @return The resulting [SharedPreferences] for fitness preferences.
 */
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

/**
 * Stores fitness value on shared preferences editor.
 *
 * @param key Lookup key or cryptographic key.
 * @param value Input value to store, parse, or format, or null when not provided.
 */
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

/**
 * Computes cloud fitness key.
 *
 * @param key Lookup key or cryptographic key.
 * @return The resulting cloud fitness key.
 */
internal fun cloudFitnessKey(key: String) = key in setOf("weight_kg", "height_cm", "step_goal",
    "calorie_goal", "protein_goal", "carbohydrate_goal", "fat_goal", "water_goal_ml", "workout_history") ||
    savedFoodKeyPattern.matches(key) ||
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

    /**
     * Binds the fitness account sync.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param accountId Optional account ID supplied to [bind].
     */
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

    /**
     * Computes meal.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param owner Owner supplied to [meal].
     * @param date Date supplied to [meal].
     * @param entryId Unique identifier of the private entry or note.
     * @param entry Optional entry supplied to [meal].
     */
    fun meal(context: Context, owner: String, date: String, entryId: String, entry: String?) {
        if (owner == "guest") return
        queue(context, owner, "meal_${date}_$entryId", entry)
    }

    /**
     * Computes water.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param owner Owner supplied to [water].
     * @param date Date supplied to [water].
     * @param entryId Unique identifier of the private entry or note.
     * @param entry Entry supplied to [water].
     */
    fun water(context: Context, owner: String, date: String, entryId: String, entry: String) {
        if (owner == "guest") return
        queue(context, owner, "drink_${date}_$entryId", entry)
    }

    /**
     * Applies meal.
     *
     * @param local Local supplied to [applyMeal].
     * @param id ID supplied to [applyMeal].
     * @param entry Optional entry supplied to [applyMeal].
     */
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

    /**
     * Computes outbox.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param owner Owner supplied to [outbox].
     * @return The resulting outbox.
     */
    internal fun outbox(context: Context, owner: String) = context.getSharedPreferences("fitness_outbox_${fitnessAccountKey(owner)}", Context.MODE_PRIVATE)

    /**
     * Computes queue.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param owner Owner supplied to [queue].
     * @param key Lookup key or cryptographic key.
     * @param value Input value to store, parse, or format, or null when not provided.
     */
    private fun queue(context: Context, owner: String, key: String, value: Any?) {
        val payload = JSONObject().put("type", when (value) {
            is Int -> "int"; is Long -> "long"; is Float -> "float"; is Boolean -> "bool"; is String -> "string"; else -> "null"
        }).put("value", value ?: JSONObject.NULL).toString()
        val pending = outbox(context, owner)
        synchronized(pending) { pending.edit(commit = true) { putString(key, payload) } }
        schedule(context, owner)
    }

    /**
     * Decodes the fitness account sync.
     *
     * @param payload Payload supplied to [decode].
     * @return The resulting [Any], or `null` if unavailable.
     */
    internal fun decode(payload: String): Any? = runCatching {
        val value = JSONObject(payload)
        when (value.getString("type")) {
            "int" -> value.getInt("value"); "long" -> value.getLong("value")
            "float" -> value.getDouble("value").toFloat().takeIf(Float::isFinite); "bool" -> value.getBoolean("value")
            "string" -> value.getString("value"); else -> null
        }
    }.getOrNull()

    /**
     * Publishes the fitness account sync.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param owner Owner supplied to [publish].
     */
    internal fun publish(context: Context, owner: String) {
        if (uid != owner || !ready.value || fitnessAuthenticatedUid() != owner) return
        FitnessWatchSync.publish(context, owner, FitnessStore(context, owner).snapshot())
    }

    /**
     * Schedules the fitness account sync.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param owner Owner supplied to [schedule].
     */
    private fun schedule(context: Context, owner: String) {
        if (fitnessAuthenticatedUid() != owner) return
        val data = workDataOf("uid" to owner)
        WorkManager.getInstance(context).enqueueUniqueWork("fitness-upload-${fitnessAccountKey(owner)}",
            ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<FitnessUploadWorker>().setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }
}

internal class FitnessUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    /**
     * Executes the scheduled WorkManager background task and returns the resulting work status.
     *
     * @return WorkManager [Result] indicating success, retry, or failure.
     */
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

private const val SAVED_FOOD_PREFIX = "saved_food_"
internal val savedFoodKeyPattern = Regex("${SAVED_FOOD_PREFIX}[0-9a-f]{64}")
private const val RECENT_SEARCHES = "recent_food_searches"

internal data class SavedFood(val id: String, val food: NutritionFood?, val savedAt: Long)
internal data class RecentFoodSearch(val query: String, val barcode: Boolean)

internal class FitnessFoodLibrary(private val preferences: SharedPreferences) {
    /**
     * Starts observing saved foods.
     *
     * @param onChange Callback invoked when the value or observed state changes.
     * @return The resulting [AutoCloseable] for observe saved foods.
     */
    fun observeSavedFoods(onChange: (List<SavedFood>) -> Unit): AutoCloseable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || savedFoodKeyPattern.matches(key)) onChange(savedFoods())
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onChange(savedFoods())
        return AutoCloseable { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /**
     * Returns the saved foods.
     *
     * @return List of resulting saved foods items.
     */
    fun savedFoods(): List<SavedFood> = preferences.all.asSequence()
        .filter { (key, _) -> savedFoodKeyPattern.matches(key) }
        .mapNotNull { (_, raw) ->
            runCatching {
                val entry = JSONObject(raw as String)
                SavedFood(entry.getString("id"),
                    entry.optJSONObject("food")?.toNutritionFood(includeServingOptions = true),
                    entry.getLong("savedAt"))
            }.getOrNull()
        }
        .sortedByDescending(SavedFood::savedAt).toList()

    /**
     * Sets saved.
     *
     * @param food Food supplied to [setSaved].
     * @param saved Whether to apply saved.
     */
    fun setSaved(food: NutritionFood, saved: Boolean) {
        if (!saved) {
            remove(food.id)
            return
        }
        val entry = JSONObject().put("id", food.id).put("savedAt", System.currentTimeMillis())
        if (food.source != FoodDataSource.FATSECRET) {
            entry.put("food", food.toJson(includeServingOptions = true))
        }
        preferences.edit { putString(key(food.id), entry.toString()) }
    }

    /**
     * Removes the fitness food library.
     *
     * @param id ID supplied to [remove].
     */
    fun remove(id: String) = preferences.edit { remove(key(id)) }

    /**
     * Returns the searches.
     *
     * @return List of resulting searches items.
     */
    fun searches(): List<RecentFoodSearch> = runCatching {
        val stored = JSONArray(preferences.getString(RECENT_SEARCHES, "[]"))
        (0 until stored.length()).mapNotNull { index ->
            val entry = stored.optJSONObject(index) ?: return@mapNotNull null
            val query = entry.optString("query")
            query.takeIf(String::isNotBlank)?.let { RecentFoodSearch(it, entry.optBoolean("barcode")) }
        }
    }.getOrDefault(emptyList())

    /**
     * Records search.
     *
     * @param search Search supplied to [recordSearch].
     */
    fun recordSearch(search: RecentFoodSearch) {
        val normalized = search.copy(query = search.query.trim())
        if (normalized.query.isBlank()) return
        val updated = (listOf(normalized) + searches()).distinctBy { it.query.lowercase() to it.barcode }.take(8)
        val encoded = JSONArray().apply {
            updated.forEach { put(JSONObject().put("query", it.query).put("barcode", it.barcode)) }
        }
        preferences.edit { putString(RECENT_SEARCHES, encoded.toString()) }
    }

    /**
     * Computes key.
     *
     * @param id ID supplied to [key].
     * @return The resulting key.
     */
    private fun key(id: String) = SAVED_FOOD_PREFIX + fitnessAccountKey(id)
}
