// All signed-in roles: stores device activity state.
package com.olive.app.security

import com.olive.app.data.*
import org.json.JSONArray
import org.json.JSONObject

// Encrypted, account-scoped activity and private-entry storage; no authentication responsibilities.
internal class DeviceActivityStore(private val activityVault: EncryptedVault) {
    // These histories stay encrypted on this device under the current account ID.
    fun recordSettings(uid: String, event: SettingsEvent) {
        activityVault.update { append(it, uid, "settingsHistory", event.toJson()) }
    }

    // Adds one setting change to the phone's encrypted history.
    fun settingsHistory(uid: String): List<SettingsEvent> =
        rows(uid, "settingsHistory").map(SettingsEvent::fromJson)

    // Records trigger for device activity store.
    fun recordTrigger(uid: String, event: TriggerEvent) {
        activityVault.update { append(it, uid, "triggerHistory", event.toJson()) }
    }

    // Loads discreet-trigger history from encrypted phone storage.
    fun triggerHistory(uid: String): List<TriggerEvent> =
        rows(uid, "triggerHistory").map(TriggerEvent::fromJson).map { event ->
            // Hide old first-try Accepted rows too; the trigger itself still stays in history.
            if (event.lastResult == "Accepted" && event.pinAttempts == 1) event.copy(
                pinAttempts = 0,
                lastResult = "No PIN attempt"
            ) else event
        }

    // Records trigger attempt for device activity store.
    fun recordTriggerAttempt(uid: String, id: String, accepted: Boolean) {
        activityVault.update { root ->
            val events = root.optJSONObject("triggerHistory")?.optJSONArray(uid) ?: return@update
            for (index in 0 until events.length()) {
                val item = events.getJSONObject(index)
                // Only do this when item opt string id is id.
                if (item.optString("id") == id) {
                    // A clean first-try unlock is routine; only show Accepted after this trigger already had a wrong PIN.
                    if (accepted && item.optInt("pinAttempts") == 0) break
                    item.put("pinAttempts", item.optInt("pinAttempts") + 1)
                    item.put("lastResult", if (accepted) "Accepted" else "Incorrect PIN")
                    root.child("historySynced").child(uid).put("triggers/$id", false)
                    break
                }
            }
        }
    }

    // Records checkout for device activity store.
    fun recordCheckout(uid: String, event: CheckoutEvent) {
        activityVault.update { append(it, uid, "checkoutHistory", event.toJson()) }
    }

    // Loads food checkout history from encrypted phone storage.
    fun checkoutHistory(uid: String): List<CheckoutEvent> =
        rows(uid, "checkoutHistory").map(CheckoutEvent::fromJson)

    // Adds a private-entry event to encrypted device activity history.
    fun savePrivateEntry(uid: String, entry: PrivateEntry) {
        activityVault.update { root ->
            val byAccount = root.child("privateEntries")
            val entries = byAccount.optJSONArray(uid) ?: JSONArray().also { byAccount.put(uid, it) }
            val index = (0 until entries.length()).firstOrNull {
                entries.getJSONObject(it).optString("id") == entry.id
            }
            if (index == null) entries.put(entry.toJson()) else entries.put(index, entry.toJson())
            root.child("historySynced").child(uid).put("private_entries/${entry.id}", false)
        }
    }

    // Loads private notes and incident records from encrypted phone storage.
    fun privateEntries(uid: String): List<PrivateEntry> =
        rows(uid, "privateEntries").map(PrivateEntry::fromJson)
            .filter { it.purgedAt == null }

    // After the configured retention period, erase private content but keep a tiny sync tombstone so another device cannot resurrect it.
    fun purgeExpiredPrivateEntries(uid: String, now: Long = System.currentTimeMillis()): Boolean {
        var changed = false
        activityVault.update { root ->
            val entries = root.optJSONObject("privateEntries")?.optJSONArray(uid) ?: return@update
            for (index in 0 until entries.length()) {
                val entry = PrivateEntry.fromJson(entries.getJSONObject(index))
                // Permanently delete trashed entries only after their recovery timer ends.
                if (entry.purgedAt == null && entry.trashExpired(now)) {
                    val tombstone = entry.copy(
                        updatedAt = now,
                        kind = PrivateEntryKind.Note,
                        title = "",
                        body = "",
                        pinned = false,
                        edits = emptyList(),
                        purgedAt = now
                    )
                    entries.put(index, tombstone.toJson())
                    root.child("historySynced").child(uid).put("private_entries/${entry.id}", false)
                    changed = true
                }
            }
        }
        return changed
    }

    // Loads activity records that still need cloud sync.
    fun pendingHistory(uid: String): List<HistoryRecord> {
        val root = activityVault.read()
        val synced = root.optJSONObject("historySynced")?.optJSONObject(uid)
        return listOf(
            "settings" to "settingsHistory",
            "triggers" to "triggerHistory",
            "checkouts" to "checkoutHistory",
            "private_entries" to "privateEntries"
        )
            .flatMap { (category, section) ->
                val rows = root.optJSONObject(section)?.optJSONArray(uid) ?: JSONArray()
                (0 until rows.length()).map { rows.getJSONObject(it) }
                    .filter { synced?.optBoolean("$category/${it.getString("id")}") != true }
                    .map { HistoryRecord(category, it.getString("id"), it.toString()) }
            }
    }

    // Updates the saved history synced used by app security.
    fun markHistorySynced(uid: String, record: HistoryRecord) {
        activityVault.update { root ->
            val section = when (record.category) {
                "settings" -> "settingsHistory"
                "triggers" -> "triggerHistory"
                "checkouts" -> "checkoutHistory"
                "private_entries" -> "privateEntries"
                else -> return@update
            }
            val rows = root.optJSONObject(section)?.optJSONArray(uid) ?: return@update
            val current = (0 until rows.length()).map { rows.getJSONObject(it) }
                .firstOrNull { it.optString("id") == record.id }
            if (current?.toString() == record.json)
                root.child("historySynced").child(uid).put("${record.category}/${record.id}", true)
        }
    }

    // Merges cloud history into the encrypted phone copy.
    fun mergeHistory(uid: String, records: List<HistoryRecord>) {
        activityVault.update { root ->
            val sections = mapOf(
                "settings" to "settingsHistory",
                "triggers" to "triggerHistory",
                "checkouts" to "checkoutHistory",
                "private_entries" to "privateEntries"
            )
            records.forEach { record ->
                val section = sections[record.category] ?: return@forEach
                val byAccount = root.child(section)
                val rows =
                    byAccount.optJSONArray(uid) ?: JSONArray().also { byAccount.put(uid, it) }
                val incoming = JSONObject(record.json)
                val existing = (0 until rows.length()).firstOrNull {
                    rows.getJSONObject(it).optString("id") == record.id
                }
                val localIsNewer = record.category == "triggers" && existing != null &&
                        rows.getJSONObject(existing)
                            .optInt("pinAttempts") > incoming.optInt("pinAttempts")
                var privateNeedsUpload = false
                if (existing == null) rows.put(incoming)
                else if (record.category == "triggers" && !localIsNewer) rows.put(
                    existing,
                    incoming
                )
                else if (record.category == "private_entries") {
                    // A purge tombstone wins by timestamp and never pulls old note text or edit history back in.
                    val local = PrivateEntry.fromJson(rows.getJSONObject(existing))
                    val cloud = PrivateEntry.fromJson(incoming)
                    val newest = when {
                        local.purgedAt != null -> local
                        cloud.purgedAt != null -> cloud
                        local.updatedAt > cloud.updatedAt -> local
                        else -> cloud
                    }
                    val edits = if (newest.purgedAt != null) emptyList() else
                        (local.edits + cloud.edits).distinctBy { it.id }.sortedBy { it.timestamp }
                    val merged = newest.copy(edits = edits)
                    rows.put(existing, merged.toJson())
                    privateNeedsUpload =
                        (local.purgedAt != null && cloud.purgedAt == null) || local.updatedAt > cloud.updatedAt ||
                                (newest.purgedAt == null && edits.any { localEdit -> cloud.edits.none { it.id == localEdit.id } })
                }
                if (!localIsNewer && !privateNeedsUpload) root.child("historySynced").child(uid)
                    .put("${record.category}/${record.id}", true)
            }
        }
    }

    // Adds one security event to the correct account and history section.
    private fun append(root: JSONObject, uid: String, section: String, value: JSONObject) {
        val byAccount = root.child(section)
        val entries = byAccount.optJSONArray(uid) ?: JSONArray().also { byAccount.put(uid, it) }
        entries.put(value)
        val category = when (section) {
            "settingsHistory" -> "settings"
            "triggerHistory" -> "triggers"
            "checkoutHistory" -> "checkouts"
            "privateEntries" -> "private_entries"
            else -> error("Unknown private storage section")
        }
        root.child("historySynced").child(uid).put("$category/${value.getString("id")}", false)
    }

    // Reads one saved history list from the encrypted vault.
    private fun rows(uid: String, section: String): List<JSONObject> {
        val entries = activityVault.read().optJSONObject(section)?.optJSONArray(uid) ?: JSONArray()
        return (0 until entries.length()).map { entries.getJSONObject(it) }.asReversed()
    }
}
