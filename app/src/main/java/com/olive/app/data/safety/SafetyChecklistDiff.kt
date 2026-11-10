package com.olive.app.data

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
