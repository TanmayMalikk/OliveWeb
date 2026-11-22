package com.olive.app.ui.covers.fitness

import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

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
