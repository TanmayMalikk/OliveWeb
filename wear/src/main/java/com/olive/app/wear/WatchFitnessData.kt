package com.olive.app.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.android.gms.wearable.*
import java.time.LocalDate
import java.security.MessageDigest

internal object WatchFitnessData {
    const val SESSION = "/olive/account/session"
    const val DAILY = "/olive/fitness/daily"
    const val ACTIVITY = "/olive/fitness/watch-activity"
    /**
     * Computes preferences.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @return The resulting preferences.
     */
    fun prefs(context: Context) = context.getSharedPreferences("watch_fitness", Context.MODE_PRIVATE)
    private val accountFields = setOf("step_date", "steps_today", "active_steps", "passive_steps", "heart_rate_date",
        "heart_rate_latest", "heart_rate_peak", "heart_rate_lowest", "heart_rate_total", "heart_rate_samples",
        "heart_rate_resting_total", "heart_rate_resting_samples", "phone_steps", "phone_date", "step_goal",
        "calorie_goal", "protein_goal", "carbohydrate_goal", "fat_goal", "calories", "protein", "carbohydrates",
        "fat", "fiber", "sugar", "sodium")

    /**
     * Computes cache.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param uid Firebase UID of the target user account.
     * @return The resulting [SharedPreferences] instance.
     */
    private fun cache(context: Context, uid: String): SharedPreferences {
        val key = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray()).joinToString("") { "%02x".format(it) }
        return context.getSharedPreferences("watch_account_$key", Context.MODE_PRIVATE)
    }
    /**
     * Stores the watch fitness data on shared preferences editor.
     *
     * @param key Lookup key or cryptographic key.
     * @param value Input value to store, parse, or format, or null when not provided.
     */
    private fun SharedPreferences.Editor.put(key: String, value: Any?) {
        when (value) {
            is String -> putString(key, value); is Int -> putInt(key, value)
            is Long -> putLong(key, value); is Float -> putFloat(key, value)
            is Boolean -> putBoolean(key, value)
        }
    }
    /**
     * Saves the watch fitness data.
     *
     * @param context Android context used to access system services, resources, or storage.
     */
    private fun save(context: Context) {
        val local = prefs(context)
        val uid = local.getString("active_account_id", null) ?: return
        cache(context, uid).edit(commit = true) { local.all.filterKeys { it in accountFields }.forEach { (key, value) -> put(key, value) } }
    }

    /**
     * Computes session.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param data Data supplied to [session].
     * @return `true` if session, or `false` otherwise.
     */
    @Synchronized fun session(context: Context, data: DataMap): Boolean {
        val local = prefs(context)
        val updated = data.getLong("updatedAt")
        if (updated < local.getLong("session_updated_at", 0L)) return false
        val uid = data.getString("accountId").orEmpty().takeIf { data.getBoolean("signedIn") }
        val token = data.getString("sessionId").orEmpty()
        if (token.isBlank()) return false
        val changed = uid != local.getString("active_account_id", null) || token != local.getString("sessionId", null)
        if (changed) save(context)
        local.edit(commit = true) {
            if (changed) {
                accountFields.forEach(::remove)
                remove("sensor_total"); remove("passive_raw"); remove("passive_date")
                remove("last_active_at"); remove("last_passive_at"); remove("last_heart_at")
                remove("phone_updated_at"); remove("_heart_steps")
                if (uid != null) cache(context, uid).all.forEach { (key, value) -> if (key in accountFields) put(key, value) }
            }
            if (uid == null) remove("active_account_id") else putString("active_account_id", uid)
            putString("sessionId", token)
            putLong("session_started_at", data.getLong("sessionStartedAt"))
            putLong("session_updated_at", updated)
        }
        if (changed) refreshTiles(context, force = true)
        return changed
    }

    /**
     * Computes daily.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param data Data supplied to [daily].
     */
    @Synchronized fun daily(context: Context, data: DataMap) {
        val local = prefs(context)
        if (!matchesWatchSession(local.getString("active_account_id", null), local.getString("sessionId", null),
                data.getString("accountId"), data.getString("sessionId"))) return
        if (data.getString("date") != LocalDate.now().toString() || data.getLong("updatedAt") < local.getLong("phone_updated_at", 0L)) return
        local.edit(commit = true) {
            putString("phone_date", data.getString("date"))
            putInt("phone_steps", data.getInt("phoneSteps").coerceIn(0, 500_000))
            putLong("phone_updated_at", data.getLong("updatedAt"))
            mapOf("step_goal" to "stepGoal", "calorie_goal" to "calorieGoal", "protein_goal" to "proteinGoal",
                "carbohydrate_goal" to "carbohydrateGoal", "fat_goal" to "fatGoal").forEach { (key, field) -> putInt(key, data.getInt(field).coerceAtLeast(1)) }
            listOf("calories", "protein", "carbohydrates", "fat", "fiber", "sugar", "sodium").forEach { key ->
                putFloat(key, data.getFloat(key).takeIf { it.isFinite() && it >= 0f } ?: 0f)
            }
        }
        save(context)
    }

    /**
     * Computes accepts.
     *
     * @param local Local supplied to [accepts].
     * @param timestamp Epoch timestamp in milliseconds.
     * @return The resulting accepts.
     */
    private fun accepts(local: SharedPreferences, timestamp: Long) = local.contains("active_account_id") &&
        timestamp >= local.getLong("session_started_at", Long.MAX_VALUE) &&
        java.time.Instant.ofEpochMilli(timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == LocalDate.now()

    /**
     * Computes steps.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param raw Raw supplied to [steps].
     * @param timestamp Epoch timestamp in milliseconds.
     * @param passive Whether to apply passive.
     */
    @Synchronized fun steps(context: Context, raw: Int, timestamp: Long, passive: Boolean) {
        val local = prefs(context)
        if (!accepts(local, timestamp) || raw < 0) return
        val lastAtKey = if (passive) "last_passive_at" else "last_active_at"
        if (timestamp <= local.getLong(lastAtKey, 0L)) return
        val today = LocalDate.now().toString()
        val todayCounts = local.getString("step_date", null) == today
        val rawKey = if (passive) "passive_raw" else "sensor_total"
        val previous = if (!passive || local.getString("passive_date", null) == today) local.getInt(rawKey, -1) else -1
        val key = if (passive) "passive_steps" else "active_steps"
        val baseline = if (todayCounts) local.getInt(key, local.getInt("steps_today", 0)) else 0
        val count = (baseline + if (previous in 0..raw) raw - previous else 0).coerceIn(0, 500_000)
        val other = if (todayCounts) local.getInt(if (passive) "active_steps" else "passive_steps", 0) else 0
        local.edit(commit = true) {
            putString("step_date", today); putString("passive_date", today)
            putLong(lastAtKey, timestamp); putInt(rawKey, raw)
            putInt(key, count); putInt("steps_today", maxOf(count, other))
        }
        save(context)
    }

    /**
     * Computes heart.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param bpm BPM supplied to [heart].
     * @param timestamp Epoch timestamp in milliseconds.
     */
    @Synchronized fun heart(context: Context, bpm: Int, timestamp: Long) {
        val local = prefs(context)
        if (!accepts(local, timestamp) || bpm !in 30..240 || timestamp <= local.getLong("last_heart_at", 0L)) return
        val current = snapshot(context).heartRateSummary
        val steps = snapshot(context).watchSteps
        val summary = current.record(bpm, resting = steps == local.getInt("_heart_steps", -1))
        local.edit(commit = true) {
            putString("heart_rate_date", LocalDate.now().toString()); putInt("heart_rate_latest", bpm)
            putLong("last_heart_at", timestamp); putInt("_heart_steps", steps)
            putInt("heart_rate_peak", summary.peakBpm); putInt("heart_rate_lowest", summary.lowestBpm)
            putLong("heart_rate_total", summary.totalBpm); putInt("heart_rate_samples", summary.sampleCount)
            putLong("heart_rate_resting_total", summary.restingTotalBpm); putInt("heart_rate_resting_samples", summary.restingSampleCount)
        }
        save(context)
    }

    /**
     * Computes snapshot.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @return The resulting [WatchFitnessState] instance.
     */
    fun snapshot(context: Context): WatchFitnessState {
        val local = prefs(context)
        if (!local.contains("active_account_id")) return WatchFitnessState(heartRateStatus = "Open Olive on your phone")
        val today = LocalDate.now().toString()
        val phoneToday = local.getString("phone_date", null) == today
        val heartToday = local.getString("heart_rate_date", null) == today
        return WatchFitnessState(
            watchSteps = if (local.getString("step_date", null) == today) local.getInt("steps_today", 0) else 0,
            phoneSteps = if (phoneToday) local.getInt("phone_steps", 0) else 0,
            stepGoal = local.getInt("step_goal", 8000), calorieGoal = local.getInt("calorie_goal", 2000),
            heartRate = if (heartToday) local.getInt("heart_rate_latest", 0).takeIf { it in 30..240 } else null,
            heartRateStatus = if (System.currentTimeMillis() - local.getLong("last_heart_at", 0L) < 60_000L) "Live" else "Last reading",
            heartRateSummary = if (!heartToday) WatchHeartRateSummary() else WatchHeartRateSummary(
                local.getInt("heart_rate_peak", 0), local.getInt("heart_rate_lowest", 0), local.getLong("heart_rate_total", 0),
                local.getInt("heart_rate_samples", 0), local.getLong("heart_rate_resting_total", 0), local.getInt("heart_rate_resting_samples", 0)),
            calories = if (phoneToday) local.getFloat("calories", 0f) else 0f,
            protein = if (phoneToday) local.getFloat("protein", 0f) else 0f, proteinGoal = local.getInt("protein_goal", 120),
            carbohydrates = if (phoneToday) local.getFloat("carbohydrates", 0f) else 0f, carbohydrateGoal = local.getInt("carbohydrate_goal", 250),
            fat = if (phoneToday) local.getFloat("fat", 0f) else 0f, fatGoal = local.getInt("fat_goal", 65),
            fiber = if (phoneToday) local.getFloat("fiber", 0f) else 0f, sugar = if (phoneToday) local.getFloat("sugar", 0f) else 0f,
            sodium = if (phoneToday) local.getFloat("sodium", 0f) else 0f,
            phoneConnected = local.contains("active_account_id"), updatedAt = local.getLong("phone_updated_at", 0),
        )
    }

    private var lastTileUpdate = 0L
    /**
     * Refreshes tiles.
     *
     * @param context Android context used to access system services, resources, or storage.
     * @param force Whether to apply force.
     */
    private fun refreshTiles(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastTileUpdate < 15_000L) return
        lastTileUpdate = now
        runCatching {
            val updater = androidx.wear.tiles.TileService.getUpdater(context)
            updater.requestUpdate(OliveActivityTileService::class.java)
            updater.requestUpdate(OliveVitalsTileService::class.java)
        }
    }
    /**
     * Publishes the watch fitness data.
     *
     * @param context Android context used to access system services, resources, or storage.
     */
    fun publish(context: Context) {
        refreshTiles(context)
        val local = prefs(context)
        val owner = local.getString("active_account_id", null) ?: return
        val state = snapshot(context)
        val request = PutDataMapRequest.create(ACTIVITY).apply {
            dataMap.putString("accountId", owner); dataMap.putString("sessionId", local.getString("sessionId", "").orEmpty())
            dataMap.putString("date", LocalDate.now().toString())
            dataMap.putInt("watchSteps", state.watchSteps); dataMap.putInt("heartRate", state.heartRate ?: 0)
            dataMap.putLong("updatedAt", System.currentTimeMillis())
            dataMap.putLong("heartRateAt", local.getLong("last_heart_at", 0L))
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request)
    }
}

/**
 * Computes matches watch session.
 *
 * @param activeUid Optional active user ID supplied to [matchesWatchSession].
 * @param activeSession Optional active session supplied to [matchesWatchSession].
 * @param incomingUid Optional incoming user ID supplied to [matchesWatchSession].
 * @param incomingSession Optional incoming session supplied to [matchesWatchSession].
 * @return The resulting matches watch session.
 */
internal fun matchesWatchSession(activeUid: String?, activeSession: String?, incomingUid: String?, incomingSession: String?) =
    !activeUid.isNullOrBlank() && !activeSession.isNullOrBlank() && activeUid == incomingUid && activeSession == incomingSession
