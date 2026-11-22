package com.olive.app.ui.covers.fitness

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.work.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import com.olive.app.data.network.nonNegativeFloat
import com.olive.app.data.network.sha256Hex
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

internal data class CoachTurn(val role: String, val text: String)
internal data class CoachReply(val text: String, val goals: Map<String, Int>)
internal data class CoachMembership(
    val active: Boolean,
    val canManage: Boolean,
    val currentPeriodEndMs: Long? = null,
)
internal data class CoachPayment(val publishableKey: String, val clientSecret: String)

private const val COACH_MEMBERSHIP_ACTIVE_KEY = "coach_membership_active"
private const val COACH_MEMBERSHIP_MANAGE_KEY = "coach_membership_can_manage"
private const val COACH_MEMBERSHIP_PERIOD_END_KEY = "coach_membership_period_end_ms"

/**
 * Returns cached active Coach membership until its current period end or renewal timestamp.
 *
 * @param preferences Account-scoped fitness SharedPreferences instance.
 * @param nowMillis Current wall-clock time in milliseconds since epoch.
 * @return The cached active [CoachMembership], or `null` if uncached or expired.
 */
internal fun cachedActiveCoachMembership(
    preferences: SharedPreferences,
    nowMillis: Long = System.currentTimeMillis(),
): CoachMembership? {
    if (!preferences.getBoolean(COACH_MEMBERSHIP_ACTIVE_KEY, false)) return null
    val periodEndMs = preferences.getLong(COACH_MEMBERSHIP_PERIOD_END_KEY, 0L)
    if (periodEndMs <= nowMillis) return null
    return CoachMembership(
        active = true,
        canManage = preferences.getBoolean(COACH_MEMBERSHIP_MANAGE_KEY, false),
        currentPeriodEndMs = periodEndMs,
    )
}

/**
 * Stores active Coach membership and expiration timestamp, or clears cache if inactive or expired.
 *
 * @param preferences Account-scoped fitness SharedPreferences instance.
 * @param membership Latest membership returned by billing status.
 * @param nowMillis Current wall-clock time in milliseconds since epoch.
 */
internal fun saveCoachMembershipCache(
    preferences: SharedPreferences,
    membership: CoachMembership,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val periodEndMs = membership.currentPeriodEndMs
    if (membership.active && periodEndMs != null && periodEndMs > nowMillis) {
        preferences.edit {
            putBoolean(COACH_MEMBERSHIP_ACTIVE_KEY, true)
            putBoolean(COACH_MEMBERSHIP_MANAGE_KEY, membership.canManage)
            putLong(COACH_MEMBERSHIP_PERIOD_END_KEY, periodEndMs)
        }
    } else {
        clearCoachMembershipCache(preferences)
    }
}

/**
 * Clears any locally cached AI Coach subscription state from SharedPreferences.
 *
 * @param preferences Account-scoped fitness SharedPreferences instance.
 */
internal fun clearCoachMembershipCache(preferences: SharedPreferences) {
    preferences.edit {
        remove(COACH_MEMBERSHIP_ACTIVE_KEY)
        remove(COACH_MEMBERSHIP_MANAGE_KEY)
        remove(COACH_MEMBERSHIP_PERIOD_END_KEY)
    }
}

/**
 * Returns true when a cached active subscription is still within its billing period and does not need rechecking.
 *
 * @param membership Cached or in-memory membership snapshot.
 * @param forceRefresh True when returning from billing management or an explicit user refresh.
 * @param nowMillis Current wall-clock time in milliseconds since epoch.
 * @return `true` if remote status lookup can be skipped until period end, `false` otherwise.
 */
internal fun shouldSkipCoachStatusCheck(
    membership: CoachMembership?,
    forceRefresh: Boolean,
    nowMillis: Long = System.currentTimeMillis(),
): Boolean {
    val periodEndMs = membership?.currentPeriodEndMs ?: return false
    return !forceRefresh && membership.active && nowMillis < periodEndMs
}

internal const val FITNESS_COACH_HISTORY_DAYS = 14

internal data class FitnessCoachDay(
    val date: LocalDate,
    val steps: Int?,
    val nutrition: NutritionTotals?,
    val foodNames: List<String>,
)

internal interface FitnessCoachService {
    /**
     * Returns the membership.
     *
     * @return The resulting [CoachMembership] instance.
     */
    suspend fun membership(): CoachMembership
    /**
     * Returns the billing portal.
     *
     * @return The resulting [Uri] for billing portal.
     */
    suspend fun billingPortal(): Uri
    /**
     * Returns the payment sheet.
     *
     * @return The resulting [CoachPayment] for payment sheet.
     */
    suspend fun paymentSheet(): CoachPayment
    /**
     * Sends the fitness coach service.
     *
     * @param text Text string to display, parse, or send.
     * @param turns Collection of turns values to process.
     * @param profile Account profile details.
     * @param history Collection of history values to process.
     * @return The resulting [CoachReply] instance.
     */
    suspend fun send(
        text: String,
        turns: List<CoachTurn>,
        profile: FitnessProfile,
        history: List<FitnessCoachDay>,
    ): CoachReply
}

// No access to the Safe App session or to any account profile repository.
internal class FitnessCoachClient(
    private val uid: String,
    private val functions: () -> FirebaseFunctions,
) : FitnessCoachService {
    constructor(uid: String) : this(uid, { FirebaseFunctions.getInstance("northamerica-northeast1") })

    /**
     * Invokes the fitness coach client.
     *
     * @param name Field, key, or display name.
     * @param input Input stream, matrix, or value to process.
     * @return Map containing the resulting call entries.
     */
    private suspend fun call(name: String, input: Map<String, Any>): Map<*, *> {
        check(FirebaseAuth.getInstance().currentUser?.uid == uid)
        val result = functions()
            .getHttpsCallable(name).call(input).await().data
        check(FirebaseAuth.getInstance().currentUser?.uid == uid)
        return result as? Map<*, *> ?: error("Invalid coach response")
    }

    /**
     * Returns the membership.
     *
     * @return The resulting [CoachMembership] instance.
     */
    override suspend fun membership(): CoachMembership {
        val data = call("fitnessAiBilling", mapOf("action" to "status"))
        val periodEndMs = (data["currentPeriodEndMs"] as? Number)?.toLong()?.takeIf { it > 0L }
        return CoachMembership(data["active"] == true, data["canManage"] == true, periodEndMs)
    }

    /**
     * Returns the billing portal.
     *
     * @return The resulting [Uri] for billing portal.
     */
    override suspend fun billingPortal(): Uri {
        val data = call("fitnessAiBilling", mapOf("action" to "portal"))
        val uri = Uri.parse(data["url"] as? String ?: error("Missing billing URL"))
        check(uri.scheme == "https" && uri.host == "billing.stripe.com" && uri.userInfo == null)
        return uri
    }

    /**
     * Returns the payment sheet.
     *
     * @return The resulting [CoachPayment] for payment sheet.
     */
    override suspend fun paymentSheet(): CoachPayment {
        val data = call("fitnessAiBilling", mapOf("action" to "paymentSheet"))
        val key = data["publishableKey"] as? String ?: error("Missing publishable key")
        val secret = data["clientSecret"] as? String ?: error("Missing payment intent")
        check(key.startsWith("pk_") && secret.startsWith("pi_") && "_secret_" in secret)
        return CoachPayment(key, secret)
    }

    /**
     * Sends the fitness coach client.
     *
     * @param text Text string to display, parse, or send.
     * @param turns Collection of turns values to process.
     * @param profile Account profile details.
     * @param history Collection of history values to process.
     * @return The resulting [CoachReply] instance.
     */
    override suspend fun send(
        text: String,
        turns: List<CoachTurn>,
        profile: FitnessProfile,
        history: List<FitnessCoachDay>,
    ): CoachReply {
        val data = call("fitnessAiCoach", mapOf(
            "message" to text,
            "conversation" to turns.takeLast(5).map { mapOf("role" to it.role, "text" to it.text) },
            "goals" to coachGoals(profile),
            "history" to history.takeLast(FITNESS_COACH_HISTORY_DAYS).map { day ->
                buildMap {
                    put("date", day.date.toString())
                    day.steps?.let { put("steps", it) }
                    day.nutrition?.let {
                        put("calories", it.calories)
                        put("protein", it.proteinGrams)
                        put("carbohydrates", it.carbohydrateGrams)
                        put("fat", it.fatGrams)
                    }
                    if (day.foodNames.isNotEmpty()) put("foodNames", day.foodNames)
                }
            },
        ))
        val goals = (data["goals"] as? Map<*, *>)?.entries.orEmpty().mapNotNull { (key, value) ->
            val name = key as? String ?: return@mapNotNull null
            val number = (value as? Number)?.toInt() ?: return@mapNotNull null
            if (name in coachGoals(profile)) name to number else null
        }.toMap()
        return CoachReply(data["reply"] as? String ?: error("Missing coaching reply"), goals)
    }
}

/**
 * Computes coach goals.
 *
 * @param profile Account profile details.
 * @return The resulting coach goals.
 */
internal fun coachGoals(profile: FitnessProfile) = mapOf(
    "steps" to profile.stepGoal, "calories" to profile.calorieGoal, "protein" to profile.proteinGoal,
    "carbohydrates" to profile.carbohydrateGoal, "fat" to profile.fatGoal, "waterMl" to profile.waterGoalMl,
)

/**
 * Computes with coach goals for this fitness profile.
 *
 * @param goals Key-value mapping of goals entries.
 */
internal fun FitnessProfile.withCoachGoals(goals: Map<String, Int>) = copy(
    stepGoal = goals["steps"]?.takeIf { it in 500..100_000 } ?: stepGoal,
    calorieGoal = goals["calories"]?.takeIf { it in 500..10_000 } ?: calorieGoal,
    proteinGoal = goals["protein"]?.takeIf { it in 10..1_000 } ?: proteinGoal,
    carbohydrateGoal = goals["carbohydrates"]?.takeIf { it in 10..2_000 } ?: carbohydrateGoal,
    fatGoal = goals["fat"]?.takeIf { it in 10..1_000 } ?: fatGoal,
    waterGoalMl = goals["waterMl"]?.takeIf { it in 250..10_000 } ?: waterGoalMl,
)

/**
 * Computes automatic coach goals.
 *
 * @param before Before supplied to [automaticCoachGoals].
 * @param current Current supplied to [automaticCoachGoals].
 * @param proposed Key-value mapping of proposed entries.
 * @param lastUpdate Last update supplied to [automaticCoachGoals].
 * @param today Today supplied to [automaticCoachGoals].
 * @return Map containing the resulting automatic coach goals entries.
 */
internal fun automaticCoachGoals(
    before: FitnessProfile,
    current: FitnessProfile,
    proposed: Map<String, Int>,
    lastUpdate: String,
    today: LocalDate,
): Map<String, Int> {
    val original = coachGoals(before)
    val currentGoals = coachGoals(current)
    val validated = coachGoals(current.withCoachGoals(proposed))
    return currentGoals.mapNotNull { (name, value) ->
        val target = validated.getValue(name)
        val allowed = name == "steps" ||
            (lastUpdate != today.toString() && abs(target - value) <= max(1, value / 10))
        if (original[name] == value && target != value && allowed) {
            name to target
        } else null
    }.toMap()
}

internal interface FoodNutritionDataSource {
    /**
     * Searches the food nutrition data source.
     *
     * @param query Search query string entered by the user.
     * @param page Page supplied to [search].
     * @return The resulting [FoodSearchResult] instance.
     */
    suspend fun search(query: String, page: Int): FoodSearchResult
    /**
     * Computes suggestions.
     *
     * @param query Search query string entered by the user.
     * @return List of resulting suggestions items.
     */
    suspend fun suggestions(query: String): List<String>
    /**
     * Looks up barcode.
     *
     * @param barcode Barcode supplied to [lookupBarcode].
     * @return The resulting [FoodSearchResult] for lookup barcode.
     */
    suspend fun lookupBarcode(barcode: String): FoodSearchResult
    /**
     * Computes food by ID.
     *
     * @param id ID supplied to [foodById].
     * @return The resulting [NutritionFood], or `null` if unavailable.
     */
    suspend fun foodById(id: String): NutritionFood? = null
}

internal class FoodNutritionRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val signedIn: () -> Boolean = { runCatching { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null }.getOrDefault(false) },
    functions: () -> FirebaseFunctions = {
        FirebaseFunctions.getInstance("northamerica-northeast1")
    },
) : FoodNutritionDataSource {
    // Manual entry and scanning can open before Firebase is available.
    private val remoteFunctions by lazy(functions)
    /**
     * Both sources are independent. A failure in one source does not hide results from the other.
     *
     * @param query Search query string entered by the user.
     * @return The resulting [FoodSearchResult] instance.
     */
    suspend fun search(query: String): FoodSearchResult = search(query, 0)

    /**
     * Searches the target.
     *
     * @param query Search query string entered by the user.
     * @param page Page supplied to [search].
     * @return The resulting [FoodSearchResult] instance.
     */
    override suspend fun search(query: String, page: Int): FoodSearchResult = search(query, page, "")

    /**
     * Searches the target.
     *
     * @param query Search query string entered by the user.
     * @param page Page supplied to [search].
     * @param foodType Food type supplied to [search].
     * @return The resulting [FoodSearchResult] instance.
     */
    suspend fun search(query: String, page: Int = 0, foodType: String): FoodSearchResult = coroutineScope {
        val cleanQuery = query.trim().take(80)
        if (cleanQuery.length < 2) return@coroutineScope FoodSearchResult()

        val searches = listOf(
            async { providerResult { searchFatSecret(cleanQuery, page, foodType) } },
            async { providerResult {
                if (foodType.isBlank()) searchOpenFoodFacts(cleanQuery, page) else FoodSearchResult()
            } },
        ).awaitAll()
        searches.forEachIndexed { index, search ->
            search.exceptionOrNull()?.let { error ->
                val provider = if (index == 0) "FatSecret" else "Open Food Facts"
                Log.w("FitnessFoodSearch", "$provider search failed", error)
            }
        }
        val foods = searches.flatMap { it.getOrDefault(FoodSearchResult()).foods }
            .distinctBy { food ->
                food.barcode.takeIf(String::isNotBlank)
                    ?: "${food.name.lowercase()}|${food.brand.lowercase()}"
            }
            .take(40)
        FoodSearchResult(
            foods = foods,
            providerFailures = searches.count { it.isFailure } * if (foodType.isBlank()) 1 else 2,
            hasMore = searches.any { it.getOrNull()?.hasMore == true },
            needsSignIn = !signedIn() && searches.any {
                (it.exceptionOrNull() as? com.google.firebase.functions.FirebaseFunctionsException)?.code ==
                    com.google.firebase.functions.FirebaseFunctionsException.Code.UNAUTHENTICATED
            },
            needsIpApproval = searches.any {
                (it.exceptionOrNull() as? com.google.firebase.functions.FirebaseFunctionsException)?.code ==
                    com.google.firebase.functions.FirebaseFunctionsException.Code.FAILED_PRECONDITION
            },
        )
    }

    /**
     * Computes suggestions.
     *
     * @param query Search query string entered by the user.
     * @return List of resulting suggestions items.
     */
    override suspend fun suggestions(query: String): List<String> {
        val response = remoteFunctions.getHttpsCallable("nutritionFoodSearch")
            .call(mapOf("query" to query.trim().take(80), "action" to "suggest")).await()
            .data as? Map<*, *> ?: return emptyList()
        return (response["suggestions"] as? List<*>).orEmpty().filterIsInstance<String>().take(4)
    }

    /**
     * Looks up barcode.
     *
     * @param barcode Barcode supplied to [lookupBarcode].
     * @return The resulting [FoodSearchResult] for lookup barcode.
     */
    override suspend fun lookupBarcode(barcode: String): FoodSearchResult = try {
        fatSecret(mapOf("barcode" to barcode.trim()))
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        FoodSearchResult(providerFailures = 2, needsIpApproval =
            (error as? com.google.firebase.functions.FirebaseFunctionsException)?.code ==
                com.google.firebase.functions.FirebaseFunctionsException.Code.FAILED_PRECONDITION)
    }

    /**
     * Searches fat secret.
     *
     * @param query Search query string entered by the user.
     * @param page Page supplied to [searchFatSecret].
     * @param foodType Food type supplied to [searchFatSecret].
     * @return The resulting search fat secret.
     */
    private suspend fun searchFatSecret(query: String, page: Int, foodType: String) =
        fatSecret(mapOf("query" to query, "page" to page, "foodType" to foodType))

    /**
     * Computes food by ID.
     *
     * @param id ID supplied to [foodById].
     * @return The resulting [NutritionFood], or `null` if unavailable.
     */
    override suspend fun foodById(id: String): NutritionFood? {
        val foodId = id.removePrefix("fatsecret:").substringBefore(':')
        val food = fatSecret(mapOf("action" to "detail", "foodId" to foodId)).foods.firstOrNull() ?: return null
        if (food.id == id) return food
        return food.servingOptions.firstOrNull { it.id == id }?.copy(servingOptions = food.servingOptions)
    }

    /**
     * Computes fat secret.
     *
     * @param parameters WorkManager parameters supplied for this worker execution.
     * @return The resulting [FoodSearchResult] for fat secret.
     */
    private suspend fun fatSecret(parameters: Map<String, Any>): FoodSearchResult {
        val response = remoteFunctions.getHttpsCallable("nutritionFoodSearch")
            .call(parameters).await().data as? Map<*, *> ?: error("Missing food response")
        return FoodSearchResult(
            foods = (response["foods"] as? List<*>).orEmpty().mapNotNull { raw ->
                parseFatSecretFood(raw as? Map<*, *> ?: return@mapNotNull null)
            },
            hasMore = response["hasMore"] == true,
        )
    }

    /**
     * Searches Open Food Facts when packaged-food results are needed.
     *
     * @param query Search query string entered by the user.
     * @param page Page supplied to [searchOpenFoodFacts].
     * @return The resulting [FoodSearchResult] for search open food facts.
     */
    private suspend fun searchOpenFoodFacts(query: String, page: Int): FoodSearchResult =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
            val url = "https://world.openfoodfacts.org/cgi/search.pl" +
                    "?search_terms=$encoded&search_simple=1&action=process&json=1&page_size=20" +
                    "&page=${page + 1}&fields=code,product_name,brands,nutriments"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "OliveBranchSchoolProject/1.0 (yourolivebranch.ca)")
                .build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Open Food Facts returned ${response.code}" }
                val body = response.body.string()
                val payload = JSONObject(body)
                val products = payload.optJSONArray("products") ?: return@use FoodSearchResult()
                val foods = buildList {
                    for (index in 0 until products.length()) {
                        parseOpenFoodFactsProduct(products.optJSONObject(index))?.let(::add)
                    }
                }
                FoodSearchResult(foods = foods, hasMore = (page + 1) * 20 < payload.optInt("count"))
            }
        }
}

internal data class FoodSearchResult(
    val foods: List<NutritionFood> = emptyList(),
    val providerFailures: Int = 0,
    val hasMore: Boolean = false,
    val needsIpApproval: Boolean = false,
    val needsSignIn: Boolean = false,
)

/**
 * Converts a FatSecret result into the shared nutrition model.
 *
 * @param value Input value to store, parse, or format.
 * @return The resulting [NutritionFood], or `null` if unavailable.
 */
internal fun parseFatSecretFood(value: Map<*, *>): NutritionFood? {
    val name = value["name"]?.toString()?.trim().orEmpty()
    if (name.isBlank()) return null
    return NutritionFood(
        id = "fatsecret:${value["id"]}",
        name = name,
        brand = value["brand"]?.toString().orEmpty(),
        servingDescription = value["servingDescription"]?.toString()
            ?.takeIf(String::isNotBlank) ?: "1 serving",
        calories = value.nonNegativeFloat("calories"),
        proteinGrams = value.nonNegativeFloat("proteinGrams"),
        carbohydrateGrams = value.nonNegativeFloat("carbohydrateGrams"),
        fatGrams = value.nonNegativeFloat("fatGrams"),
        source = FoodDataSource.FATSECRET,
        barcode = value["barcode"]?.toString().orEmpty(),
        saturatedFatGrams = value.nonNegativeFloat("saturatedFatGrams"),
        transFatGrams = value.nonNegativeFloat("transFatGrams"),
        fiberGrams = value.nonNegativeFloat("fiberGrams"),
        sugarGrams = value.nonNegativeFloat("sugarGrams"),
        sodiumMilligrams = value.nonNegativeFloat("sodiumMilligrams"),
        cholesterolMilligrams = value.nonNegativeFloat("cholesterolMilligrams"),
        potassiumMilligrams = value.nonNegativeFloat("potassiumMilligrams"),
        servingOptions = (value["servings"] as? List<*>).orEmpty().mapNotNull { raw ->
            val serving = raw as? Map<*, *> ?: return@mapNotNull null
            parseFatSecretFood(value.filterKeys { it != "servings" } + serving +
                mapOf("id" to "${value["id"]}:${serving["servingId"]}"))
        },
    )
}

/**
 * Fitness cover: Converts open food facts product.
 *
 * @param product Optional product supplied to [parseOpenFoodFactsProduct].
 * @return The resulting [NutritionFood], or `null` if unavailable.
 */
private fun parseOpenFoodFactsProduct(product: JSONObject?): NutritionFood? {
    product ?: return null
    val name = product.optString("product_name").trim()
    if (name.isBlank()) return null
    val nutrients = product.optJSONObject("nutriments") ?: JSONObject()
    if (!nutrients.hasCoreNutrition()) return null
    return NutritionFood(
        id = "openfoodfacts:${product.optString("code", name)}",
        name = name,
        brand = product.optString("brands").substringBefore(',').trim(),
        // OFF nutrients below are per 100 g, never per the product's arbitrary serving size.
        servingDescription = "100 g",
        calories = nutrients.nonNegativeFloat("energy-kcal_100g"),
        proteinGrams = nutrients.nonNegativeFloat("proteins_100g"),
        carbohydrateGrams = nutrients.nonNegativeFloat("carbohydrates_100g"),
        fatGrams = nutrients.nonNegativeFloat("fat_100g"),
        source = FoodDataSource.OPEN_FOOD_FACTS,
        barcode = product.optString("code"),
        saturatedFatGrams = nutrients.nonNegativeFloat("saturated-fat_100g"),
        transFatGrams = nutrients.nonNegativeFloat("trans-fat_100g"),
        fiberGrams = nutrients.nonNegativeFloat("fiber_100g"),
        sugarGrams = nutrients.nonNegativeFloat("sugars_100g"),
        sodiumMilligrams = nutrients.nonNegativeFloat("sodium_100g") * 1_000f,
        cholesterolMilligrams = nutrients.nonNegativeFloat("cholesterol_100g") * 1_000f,
        potassiumMilligrams = nutrients.nonNegativeFloat("potassium_100g") * 1_000f,
    )
}

/**
 * Keeps only foods that include calories and the three main macros.
 *
 * @return `true` if has core nutrition, or `false` otherwise.
 */
private fun JSONObject.hasCoreNutrition() =
    has("energy-kcal_100g") && has("proteins_100g") &&
            has("carbohydrates_100g") && has("fat_100g")

/**
 * Cancellation belongs to the screen lifecycle, not to provider fallback.
 *
 * @param block Callback or suspending lambda executed within this scope.
 * @return Outcome [Result] wrapping the provider result result or failure.
 */
private suspend fun <T> providerResult(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (error: kotlinx.coroutines.CancellationException) {
    throw error
} catch (error: Exception) {
    Result.failure(error)
}
