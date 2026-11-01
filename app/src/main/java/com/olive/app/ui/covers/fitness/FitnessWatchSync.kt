// Fitness cover users: sends today's goals and nutrition totals to a paired Wear OS watch.
package com.olive.app.ui.covers.fitness

import android.content.Context
import androidx.core.content.edit
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

internal object FitnessWatchSync {
    const val DAILY_DATA_PATH = "/olive/fitness/daily"
    const val WATCH_ACTIVITY_PATH = "/olive/fitness/watch-activity"
    const val SYNC_PREFERENCES = "fitness_watch_sync"
    const val ACTIVE_ACCOUNT_ID = "active_account_id"

    // returns the publish for fitness cover. It reads the value saved on this device.
    fun publish(context: Context, accountId: String, snapshot: FitnessSnapshot) {
        context.getSharedPreferences(SYNC_PREFERENCES, Context.MODE_PRIVATE).edit {
            putString(ACTIVE_ACCOUNT_ID, accountId)
        }
        val nutrition = snapshot.nutrition
        val request = PutDataMapRequest.create(DAILY_DATA_PATH).apply {
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
