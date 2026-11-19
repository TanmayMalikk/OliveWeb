package com.olive.app.ui.covers.fitness

import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

internal data class CoachTurn(val role: String, val text: String)
internal data class CoachReply(val text: String, val goals: Map<String, Int>)
internal data class CoachMembership(val active: Boolean, val canManage: Boolean)
internal data class CoachPayment(val publishableKey: String, val clientSecret: String)

// No access to the Safe App session or to any account profile repository.
internal class FitnessCoachClient(private val uid: String) {
    private suspend fun call(name: String, input: Map<String, Any>): Map<*, *> {
        check(FirebaseAuth.getInstance().currentUser?.uid == uid)
        val result = FirebaseFunctions.getInstance("northamerica-northeast1")
            .getHttpsCallable(name).call(input).await().data
        check(FirebaseAuth.getInstance().currentUser?.uid == uid)
        return result as? Map<*, *> ?: error("Invalid coach response")
    }

    suspend fun membership(): CoachMembership {
        val data = call("fitnessAiBilling", mapOf("action" to "status"))
        return CoachMembership(data["active"] == true, data["canManage"] == true)
    }

    suspend fun billingPortal(): Uri {
        val data = call("fitnessAiBilling", mapOf("action" to "portal"))
        val uri = Uri.parse(data["url"] as? String ?: error("Missing billing URL"))
        check(uri.scheme == "https" && uri.host == "billing.stripe.com" && uri.userInfo == null)
        return uri
    }

    suspend fun paymentSheet(): CoachPayment {
        val data = call("fitnessAiBilling", mapOf("action" to "paymentSheet"))
        val key = data["publishableKey"] as? String ?: error("Missing publishable key")
        val secret = data["clientSecret"] as? String ?: error("Missing payment intent")
        check(key.startsWith("pk_") && secret.startsWith("pi_") && "_secret_" in secret)
        return CoachPayment(key, secret)
    }

    suspend fun send(
        text: String,
        turns: List<CoachTurn>,
        profile: FitnessProfile,
        history: List<FitnessHistoryDay>,
    ): CoachReply {
        val data = call("fitnessAiCoach", mapOf(
            "message" to text,
            "conversation" to turns.takeLast(4).map { mapOf("role" to it.role, "text" to it.text) },
            "goals" to coachGoals(profile),
            "history" to history.takeLast(14).map {
                mapOf("date" to it.date.toString(), "steps" to it.steps, "calories" to it.caloriesEaten,
                    "protein" to it.proteinGrams, "carbohydrates" to it.carbohydrateGrams, "fat" to it.fatGrams)
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

internal fun coachGoals(profile: FitnessProfile) = mapOf(
    "steps" to profile.stepGoal, "calories" to profile.calorieGoal, "protein" to profile.proteinGoal,
    "carbohydrates" to profile.carbohydrateGoal, "fat" to profile.fatGoal, "waterMl" to profile.waterGoalMl,
)

internal fun FitnessProfile.withCoachGoals(goals: Map<String, Int>) = copy(
    stepGoal = goals["steps"]?.takeIf { it in 500..100_000 } ?: stepGoal,
    calorieGoal = goals["calories"]?.takeIf { it in 500..10_000 } ?: calorieGoal,
    proteinGoal = goals["protein"]?.takeIf { it in 10..1_000 } ?: proteinGoal,
    carbohydrateGoal = goals["carbohydrates"]?.takeIf { it in 10..2_000 } ?: carbohydrateGoal,
    fatGoal = goals["fat"]?.takeIf { it in 10..1_000 } ?: fatGoal,
    waterGoalMl = goals["waterMl"]?.takeIf { it in 250..10_000 } ?: waterGoalMl,
)

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
