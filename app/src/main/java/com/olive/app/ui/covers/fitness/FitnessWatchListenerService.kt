// Fitness cover users: receives the paired Watch6 daily step total for the active account.
package com.olive.app.ui.covers.fitness

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

class FitnessWatchListenerService : WearableListenerService() {
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
            FitnessStore(applicationContext, accountId).recordWatchActivity(
                watchSteps = watchData.getInt("watchSteps"),
                heartRate = watchData.getInt("heartRate"),
                watchUpdatedAt = watchData.getLong("updatedAt"),
            )
        }
    }
}
