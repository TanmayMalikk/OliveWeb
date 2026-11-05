// Fitness cover users: receives the paired Watch6 daily step total for the active account.
package com.olive.app.ui.covers.fitness

import com.google.firebase.auth.FirebaseAuth
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

internal class FitnessWatchListenerService : WearableListenerService() {
    // Saves fitness data received from the connected Wear OS watch.
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED ||
                event.dataItem.uri.path != FitnessWatchSync.WATCH_ACTIVITY_PATH
            ) return@forEach

            val accountId = getSharedPreferences(
                FitnessWatchSync.SYNC_PREFERENCES,
                MODE_PRIVATE,
            ).getString(FitnessWatchSync.ACTIVE_ACCOUNT_ID, null) ?: return@forEach
            val watchData = DataMapItem.fromDataItem(event.dataItem).dataMap
            val watchAccountId = watchData.getString("accountId").orEmpty()
            val signedInAccountId = runCatching {
                FirebaseAuth.getInstance().currentUser?.uid
            }.getOrNull()
            // Ignore delayed readings from a watch session owned by another account.
            if (watchAccountId != accountId || signedInAccountId != accountId) return@forEach
            FitnessStore(applicationContext, accountId).recordWatchActivity(
                watchSteps = watchData.getInt("watchSteps"),
                heartRate = watchData.getInt("heartRate"),
                watchUpdatedAt = watchData.getLong("updatedAt"),
            )
        }
    }
}
