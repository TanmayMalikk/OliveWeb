package com.olive.app.data

import org.json.JSONObject

// Represents one meaningful remote checklist change without exposing unrelated rows.
internal sealed interface SafetyChecklistChange {
    val id: String

    data class Added(
        override val id: String,
        val text: String,
        val checked: Boolean,
    ) : SafetyChecklistChange

    data class Removed(
        override val id: String,
        val text: String,
    ) : SafetyChecklistChange

    data class TextChanged(
        override val id: String,
        val before: String,
        val after: String,
    ) : SafetyChecklistChange

    data class CompletionChanged(
        override val id: String,
        val text: String,
        val before: Boolean,
        val after: Boolean,
    ) : SafetyChecklistChange

    data class AttachmentsChanged(
        override val id: String,
        val text: String,
    ) : SafetyChecklistChange
}

internal data class SafetyChecklistConflictPreview(
    val item: String,
    val property: String,
    val value: String,
)

/** Shows only checklist row properties that differ between the owner's and incoming versions. */
internal fun safetyChecklistConflictPreview(
    mine: String,
    incoming: String,
    showMine: Boolean,
): List<SafetyChecklistConflictPreview>? {
    fun parse(value: String): List<SafetyChecklistItem>? = runCatching {
        require(JSONObject(value).optInt("oliveSafetyChecklist") == 1)
        readSafetyChecklist(value)
    }.getOrNull()

    val mineRows = parse(mine) ?: return null
    val incomingRows = parse(incoming) ?: return null
    val mineById = mineRows.associateBy(SafetyChecklistItem::id)
    val incomingById = incomingRows.associateBy(SafetyChecklistItem::id)
    return buildList {
        (mineById.keys + incomingById.keys).forEach { id ->
            val yourItem = mineById[id]
            val incomingItem = incomingById[id]
            when {
                yourItem == incomingItem -> Unit
                yourItem == null || incomingItem == null -> {
                    val item = yourItem ?: incomingItem ?: return@forEach
                    add(
                        SafetyChecklistConflictPreview(
                            item = item.text,
                            property = "Step",
                            value = if (showMine) {
                                if (yourItem == null) "Removed" else "Present"
                            } else {
                                if (incomingItem == null) "Removed" else "Present"
                            },
                        ),
                    )
                }
                else -> {
                    val item = if (showMine) yourItem else incomingItem
                    if (yourItem.text != incomingItem.text) add(
                        SafetyChecklistConflictPreview(item.text, "Text", item.text),
                    )
                    if (yourItem.checked != incomingItem.checked) add(
                        SafetyChecklistConflictPreview(
                            item.text,
                            "Completion",
                            if (item.checked) "Complete" else "Not complete",
                        ),
                    )
                    if (yourItem.attachmentIds != incomingItem.attachmentIds) add(
                        SafetyChecklistConflictPreview(item.text, "Attachments", "Changed"),
                    )
                    if (yourItem.excludedAttachmentIds != incomingItem.excludedAttachmentIds) add(
                        SafetyChecklistConflictPreview(item.text, "Excluded attachments", "Changed"),
                    )
                }
            }
        }
    }
}

// Finds semantic changes by stable row ID; row order alone is not a plan change.
internal fun safetyChecklistIncomingChanges(
    local: List<SafetyChecklistItem>,
    remote: List<SafetyChecklistItem>,
): List<SafetyChecklistChange> {
    val remoteById = remote.associateBy(SafetyChecklistItem::id)
    val localIds = local.mapTo(mutableSetOf(), SafetyChecklistItem::id)
    return buildList {
        local.forEach { current ->
            val incoming = remoteById[current.id]
            if (incoming == null) {
                add(SafetyChecklistChange.Removed(current.id, current.text))
            } else {
                if (current.text != incoming.text) {
                    add(SafetyChecklistChange.TextChanged(current.id, current.text, incoming.text))
                }
                if (current.checked != incoming.checked) {
                    add(
                        SafetyChecklistChange.CompletionChanged(
                            current.id,
                            incoming.text,
                            current.checked,
                            incoming.checked,
                        ),
                    )
                }
                if (current.excludedAttachmentIds != incoming.excludedAttachmentIds) {
                    add(SafetyChecklistChange.AttachmentsChanged(current.id, incoming.text))
                }
            }
        }
        remote.filterNot { it.id in localIds }.forEach { incoming ->
            add(SafetyChecklistChange.Added(incoming.id, incoming.text, incoming.checked))
        }
    }
}
