// Contains the activity history code for this part of Olive.
package com.olive.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

// Groups the data and actions for history record.
data class HistoryRecord(val category: String, val id: String, val json: String)

// Keep the machine timestamp for sorting and add an ISO value humans can read in Firebase Storage.
private fun JSONObject.putReadableTime(name: String, value: Long) =
    put(name, value).put("${name}Iso", Instant.ofEpochMilli(value).toString())

// Groups the data and actions for private entry kind.
enum class PrivateEntryKind { Note, Incident }

// TESTING: 10 seconds; change to 30.days.inWholeMilliseconds for 30 days.
val PRIVATE_ENTRY_TRASH_RETENTION_MS = 10.seconds.inWholeMilliseconds

// Groups the data and actions for private entry edit.
data class PrivateEntryEdit(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val changes: List<FieldChange>,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).putReadableTime("timestamp", timestamp)
        .put("changes", JSONArray().also { rows -> changes.forEach { rows.put(it.toJson()) } })

    companion object {
        fun fromJson(value: JSONObject): PrivateEntryEdit {
            val rows = value.optJSONArray("changes") ?: JSONArray()
            return PrivateEntryEdit(
                value.optString("id", UUID.randomUUID().toString()), value.getLong("timestamp"),
                (0 until rows.length()).map { FieldChange.fromJson(rows.getJSONObject(it)) })
        }
    }
}

// Notes and incident records get their own timestamped account objects and never enter cover data.
data class PrivateEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val updatedAt: Long = timestamp,
    val kind: PrivateEntryKind? = null,
    val title: String,
    val body: String,
    val pinned: Boolean = false,
    val edits: List<PrivateEntryEdit> = emptyList(),
    val deletedAt: Long? = null,
    val purgedAt: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).putReadableTime("timestamp", timestamp)
        .putReadableTime("updatedAt", updatedAt)
        .put("title", title).put("body", body).put("pinned", pinned)
        .put("edits", JSONArray().also { rows -> edits.forEach { rows.put(it.toJson()) } })
        .also {
            if (kind != null) it.put("kind", kind.name)
            if (deletedAt != null) it.putReadableTime("deletedAt", deletedAt)
            if (purgedAt != null) it.putReadableTime("purgedAt", purgedAt)
        }

    companion object {
        fun fromJson(value: JSONObject): PrivateEntry {
            val edits = value.optJSONArray("edits") ?: JSONArray()
            return PrivateEntry(
                id = value.getString("id"),
                timestamp = value.getLong("timestamp"),
                updatedAt = value.optLong("updatedAt", value.getLong("timestamp")),
                kind = value.optString("kind").takeIf(String::isNotBlank)
                    ?.let { runCatching { PrivateEntryKind.valueOf(it) }.getOrNull() },
                title = value.optString("title"),
                body = value.getString("body"),
                pinned = value.optBoolean("pinned"),
                edits = (0 until edits.length()).map {
                    PrivateEntryEdit.fromJson(
                        edits.getJSONObject(
                            it
                        )
                    )
                }
                    .mapNotNull { edit ->
                        edit.copy(changes = edit.changes.filterNot { it.field == "Pinned" })
                            .takeIf { it.changes.isNotEmpty() }
                    },
                deletedAt = value.optLong("deletedAt").takeIf { it > 0L },
                purgedAt = value.optLong("purgedAt").takeIf { it > 0L },
            )
        }
    }
}

fun PrivateEntry.trashExpired(now: Long = System.currentTimeMillis()) =
    deletedAt?.let { now - it >= PRIVATE_ENTRY_TRASH_RETENTION_MS } == true

fun PrivateEntry.trashDaysRemaining(now: Long = System.currentTimeMillis()): Int {
    val deleted = deletedAt ?: return 0
    val remaining = (PRIVATE_ENTRY_TRASH_RETENTION_MS - (now - deleted)).coerceAtLeast(0L)
    return ((remaining + 86_399_999L) / 86_400_000L).toInt()
}

fun PrivateEntry.trashSecondsRemaining(now: Long = System.currentTimeMillis()): Int {
    val deleted = deletedAt ?: return 0
    return ((PRIVATE_ENTRY_TRASH_RETENTION_MS - (now - deleted)).coerceAtLeast(0L) + 999L).div(
        1_000L
    ).toInt()
}

// Groups the data and actions for field change.
data class FieldChange(val field: String, val before: String, val after: String) {
    fun toJson(): JSONObject =
        JSONObject().put("field", field).put("before", before).put("after", after)

    companion object {
        fun fromJson(value: JSONObject) = FieldChange(
            value.getString("field"),
            value.optString("before"),
            value.optString("after")
        )
    }
}

// Private account activity lives in the encrypted device vault; secrets are described, never copied in.
data class SettingsEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val area: String,
    val action: String,
    val changes: List<FieldChange> = emptyList(),
) {
    fun toJson(): JSONObject =
        JSONObject().put("id", id).putReadableTime("timestamp", timestamp).put("area", area)
            .put("action", action)
            .put("changes", JSONArray().also { rows -> changes.forEach { rows.put(it.toJson()) } })

    companion object {
        fun fromJson(value: JSONObject): SettingsEvent {
            val rows = value.optJSONArray("changes") ?: JSONArray()
            return SettingsEvent(
                value.getString("id"),
                value.getLong("timestamp"),
                value.getString("area"),
                value.getString("action"),
                (0 until rows.length()).map { FieldChange.fromJson(rows.getJSONObject(it)) })
        }
    }
}

// Groups the data and actions for trigger event.
data class TriggerEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val source: String,
    val pinAttempts: Int = 0,
    val lastResult: String = "No PIN attempt",
) {
    fun toJson(): JSONObject =
        JSONObject().put("id", id).putReadableTime("timestamp", timestamp).put("source", source)
            .put("pinAttempts", pinAttempts).put("lastResult", lastResult)

    companion object {
        fun fromJson(value: JSONObject) = TriggerEvent(
            value.getString("id"),
            value.getLong("timestamp"),
            value.getString("source"),
            value.optInt("pinAttempts"),
            value.optString("lastResult", "No PIN attempt")
        )
    }
}

// Groups the data and actions for order line.
data class OrderLine(val food: String, val quantity: Int) {
    fun toJson(): JSONObject = JSONObject().put("food", food).put("quantity", quantity)

    companion object {
        fun fromJson(value: JSONObject) =
            OrderLine(value.getString("food"), value.getInt("quantity"))
    }
}

// Groups the data and actions for checkout event.
data class CheckoutEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val foods: List<OrderLine>,
    val totalCents: Int,
    val error: String,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).putReadableTime("timestamp", timestamp)
        .put("totalCents", totalCents)
        .put("error", error)
        .put("foods", JSONArray().also { rows -> foods.forEach { rows.put(it.toJson()) } })

    companion object {
        fun fromJson(value: JSONObject): CheckoutEvent {
            val rows = value.optJSONArray("foods") ?: JSONArray()
            return CheckoutEvent(
                value.getString("id"),
                value.getLong("timestamp"),
                (0 until rows.length()).map { OrderLine.fromJson(rows.getJSONObject(it)) },
                value.getInt("totalCents"),
                value.getString("error")
            )
        }
    }
}

// Changes are recorded field by field so the history can show old and new values clearly.
fun profileChanges(before: UserProfile, after: UserProfile): List<FieldChange> = buildList {
    fun changed(field: String, old: String, new: String) {
        if (old != new) add(FieldChange(field, old, new))
    }
    changed("Full name", before.fullName, after.fullName)
    changed("People experiencing abuse", before.affectedNames, after.affectedNames)
    changed("Abuser's name", before.abuserName, after.abuserName)
    changed("Date of birth", before.dob, after.dob)
    changed("Language", before.language, after.language)
    changed("Situation", before.situation, after.situation)
    changed("City", before.address.city, after.address.city)
    changed("Street address", before.address.line1, after.address.line1)
    changed("Apartment / unit", before.address.line2, after.address.line2)
    changed("Province / state", before.address.province, after.address.province)
    changed("Postal / ZIP", before.address.postalCode, after.address.postalCode)
    changed("Country", before.address.country, after.address.country)
    changed("Cover app", before.coverType.title, after.coverType.title)
    if (before.triggerHash != after.triggerHash) add(
        FieldChange(
            "Trigger word",
            "Hidden",
            "Changed"
        )
    )
    if (before.contacts != after.contacts) {
        fun summary(contacts: List<SafeContact>) = contacts.joinToString("; ") {
            "${it.name}: ${it.details}${if (it.safeToStay) " (Safe to stay)" else ""}${if (it.linkedUid.isNotBlank()) " (Linked chat account)" else ""}"
        }
        add(FieldChange("Safe contacts", summary(before.contacts), summary(after.contacts)))
    }
}
