// Fitness cover users: sends today's goals and nutrition totals to a paired Wear OS watch.
package com.olive.app.ui.covers.fitness

import android.content.Context
import androidx.core.content.edit
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

internal object FitnessWatchSync {
    const val DAILY_DATA_PATH = "/olive/fitness/daily"
    const val WATCH_ACTIVITY_PATH = "/olive/fitness/watch-activity"
    const val ACCOUNT_SESSION_PATH = "/olive/account/session"
    const val SYNC_PREFERENCES = "fitness_watch_sync"
    const val ACTIVE_ACCOUNT_ID = "active_account_id"

    // Tells the paired watch which phone account owns future fitness readings.
    fun bindAccount(context: Context, accountId: String?) {
        val safeAccountId = accountId.orEmpty().trim()
        context.getSharedPreferences(SYNC_PREFERENCES, Context.MODE_PRIVATE).edit {
            if (safeAccountId.isBlank()) remove(ACTIVE_ACCOUNT_ID)
            else putString(ACTIVE_ACCOUNT_ID, safeAccountId)
        }
        val request = PutDataMapRequest.create(ACCOUNT_SESSION_PATH).apply {
            dataMap.putBoolean("signedIn", safeAccountId.isNotBlank())
            dataMap.putString("accountId", safeAccountId)
            dataMap.putLong("updatedAt", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request)
    }

    // Sends the latest phone fitness summary to the paired Wear OS app.
    fun publish(context: Context, accountId: String, snapshot: FitnessSnapshot) {
        if (accountId.isBlank() || accountId == "guest") return
        context.getSharedPreferences(SYNC_PREFERENCES, Context.MODE_PRIVATE).edit {
            putString(ACTIVE_ACCOUNT_ID, accountId)
        }
        val nutrition = snapshot.nutrition
        val request = PutDataMapRequest.create(DAILY_DATA_PATH).apply {
            dataMap.putString("accountId", accountId)
            dataMap.putLong("updatedAt", System.currentTimeMillis())
            dataMap.putInt("phoneSteps", snapshot.stepsToday.coerceAtLeast(0))
            dataMap.putInt("stepGoal", snapshot.profile.stepGoal)
            dataMap.putInt("calorieGoal", snapshot.profile.calorieGoal)
            dataMap.putInt("proteinGoal", snapshot.profile.proteinGoal)
            dataMap.putInt("carbohydrateGoal", snapshot.profile.carbohydrateGoal)
            dataMap.putInt("fatGoal", snapshot.profile.fatGoal)
            dataMap.putFloat("calories", nutrition.calories.coerceAtLeast(0f))
            dataMap.putFloat("protein", nutrition.proteinGrams.coerceAtLeast(0f))
            dataMap.putFloat("carbohydrates", nutrition.carbohydrateGrams.coerceAtLeast(0f))
            dataMap.putFloat("fat", nutrition.fatGrams.coerceAtLeast(0f))
            dataMap.putFloat("fiber", nutrition.fiberGrams.coerceAtLeast(0f))
            dataMap.putFloat("sugar", nutrition.sugarGrams.coerceAtLeast(0f))
            dataMap.putFloat("sodium", nutrition.sodiumMilligrams.coerceAtLeast(0f))
        }.asPutDataRequest().setUrgent()

        Wearable.getDataClient(context).putDataItem(request)
    }
}
