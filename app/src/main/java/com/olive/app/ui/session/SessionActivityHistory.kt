// Signed-in users: records safe-app activity for the account.
package com.olive.app.ui.session

import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

// Owns activity records and history synchronization.
internal class SessionHistory(private val runtime: SessionRuntime) {
    // Records settings interaction for session activity history.
    fun recordSettingsInteraction(area: String, action: String) {
        with(runtime) {
            with(state) {
                if (area == "Olive Branch") return
                val uid = enrollment?.uid ?: return
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        store.recordSettings(
                            uid,
                            SettingsEvent(area = area, action = action)
                        )
                        queueHistorySync()
                    }
                }
            }
        }
    }

    // Session activity history: Refreshes history.
    fun refreshHistory(uid: String) {
        with(runtime) {
            with(state) {
                scope.launch {
                    val (histories, cleanupNeeded) = withContext(Dispatchers.IO) {
                        val purged = store.purgeExpiredPrivateEntries(uid)
                        Triple(
                            store.settingsHistory(uid),
                            store.triggerHistory(uid),
                            store.checkoutHistory(uid)
                        ) to purged
                    }
                    val entries = withContext(Dispatchers.IO) { store.privateEntries(uid) }
                    // refresh history checks whether enrollment uid is uid before continuing.
                    if (enrollment?.uid == uid) {
                        settingsHistory =
                            histories.first.filter { it.area != "Privacy" && (it.area != "Olive Branch" || it.changes.isNotEmpty()) }
                        privacyHistory = histories.first.filter { it.area == "Privacy" }
                        triggerHistory = histories.second
                        checkoutHistory =
                            histories.third
                        privateEntries = entries
                    }
                    if (cleanupNeeded) queueHistorySync()
                }
            }
        }
    }

    // Opens the private activity page with the requested history category selected.
    fun showActivityCategory(value: ActivityCategory) {
        with(runtime) {
            with(state) {
                if (screen == SessionScreen.Safe) {
                    activityCategory = value
                    page = SafePage.Activity
                    message = null
                }
            }
        }
    }

    // Session activity history: returns the activity back.
    fun activityBack() {
        with(runtime) {
            with(state) {
                if (activityCategory != null) activityCategory = null else navigation.back()
            }
        }
    }

    // Downloads account activity, merges it with local records, and refreshes the selected history page.
    fun loadCloudHistory(uid: String, username: String) {
        with(runtime) {
            scope.launch {
                // Paint the local cover first; cloud reconciliation does not belong on the launch critical path.
                delay(1_000.milliseconds)
                val identity = AccountIdentity(uid, username)
                // Messaging registration failing must never block the existing profile/history sync path.
                runCatching { messaging.registerMessagingIdentity(identity) }
                runCatching {
                    accounts.profile(identity) // Reading once upgrades the existing profile object to readable JSON.
                    val records = accounts.historyRecords(identity)
                    withContext(Dispatchers.IO) { store.mergeHistory(uid, records) }
                    refreshHistory(uid)
                }
                queueHistorySync()
            }
        }
    }

    // Session activity history: returns the queue history sync. It waits for the set time and cancels the timer when the feature closes.
    fun queueHistorySync() {
        with(runtime) {
            with(state) {
                if (localMode || enrollment == null) return
                historySyncJob?.cancel()
                val uid = enrollment!!.uid
                val username = coverUsername
                historySyncJob = scope.launch {
                    delay(750.milliseconds)
                    historySyncMutex.withLock {
                        // Session activity history: Keeps queue history sync active while enrollment? uid equals uid.
                        while (enrollment?.uid == uid) {
                            val pending = withContext(Dispatchers.IO) { store.pendingHistory(uid) }
                            if (pending.isEmpty()) break
                            for (record in pending) {
                                // If queue history sync fails, catch the error so the app can keep running and explain the problem.
                                try {
                                    accounts.saveHistoryRecord(
                                        AccountIdentity(uid, username),
                                        record
                                    )
                                    withContext(Dispatchers.IO) {
                                        store.markHistorySynced(
                                            uid,
                                            record
                                        )
                                    }
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) { // Local history is already saved, so a failed cloud copy waits for the next refresh.
                                    return@withLock
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
