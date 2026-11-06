// All safe-app roles: draws editable safety-plan fields.
package com.olive.app.ui.users.shared.safetyplan

import com.olive.app.ui.shared.*
import com.olive.app.ui.users.shared.navigation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Attachment
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.olive.app.data.*
import com.olive.app.data.SafetyChecklistItem
import com.olive.app.data.writeSafetyChecklist
import com.olive.app.R
import java.util.UUID
import android.content.Context
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import com.olive.app.data.Friend



// Sections share one continuous note surface. Each heading and checkbox remains separately navigable with TalkBack.
@Composable
internal fun SafetyChecklistSection(
    plan: SafetyPlan,
    fieldKey: String,
    onEdit: (String, String) -> Unit,
    onResolve: (String, Boolean) -> Unit,
    contacts: List<SafeContact> = emptyList(),
    contactsEditable: Boolean = false,
    onContactsChange: ((List<SafeContact>) -> Unit)? = null,
    editable: Boolean = true,
    onUploadDocument: ((String) -> Unit)? = null,
    documents: List<SafetyDocument> = emptyList(),
) {
    val field = plan.fields[fieldKey] ?: return
    val items = remember(field.value) { readSafetyChecklist(field.value) }
    val section = fieldKey.substringAfter('_')
    val title = stringResource(safetySectionLabel(section))
    var full by remember(fieldKey) { mutableStateOf(false) }
    val update: (List<SafetyChecklistItem>) -> Unit = { changed ->
        val encoded = writeSafetyChecklist(changed)
        full = encoded.length > 4000 || changed.size > 80
        if (!full) onEdit(fieldKey, encoded)
    }
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            title, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.testTag("safety_heading_$fieldKey").semantics { heading() })
        if (section != "contacts") Text(
            stringResource(R.string.safety_prepared_count, items.count { it.checked }, items.size),
            style = MaterialTheme.typography.labelMedium, color = Muted
        )
        safetySectionHint(section)?.let {
            Text(
                stringResource(it),
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
        }
        if (section == "contacts") SafetyPlanContactPicker(
            items,
            contacts,
            contactsEditable,
            update,
            onContactsChange
        )
        // Run this part only when section is documents.
        if (section == "documents") {
            Text(
                stringResource(R.string.safety_document_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            Text(
                stringResource(R.string.safety_document_upload_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
        }
        if (section != "contacts") items.forEachIndexed { index, item ->
            key(item.id) {
                ChecklistRow(
                    item, title, fieldKey, index, editable,
                    onChecked = { checked ->
                        update(items.map {
                            if (it.id == item.id) it.copy(
                                checked = checked
                            ) else it
                        })
                    },
                    onText = { value -> update(items.map { if (it.id == item.id) it.copy(text = value) else it }) },
                    onRemove = { update(items.filterNot { it.id == item.id }) },
                    attachments = safetyChecklistAttachments(fieldKey, item, documents),
                    onRemoveAttachment = { document ->
                        update(items.map {
                            if (it.id == item.id) it.copy(
                                excludedAttachmentIds =
                                    (it.excludedAttachmentIds + document.id).distinct().takeLast(80),
                            ) else it
                        })
                    },
                    onUploadDocument = onUploadDocument?.let { upload -> { upload(item.id) } },
                )
            }
        }
        if (section != "contacts" && editable) {
            val addDescription = stringResource(R.string.safety_add_to_section, title)
            TextButton(
                onClick = { update(items + SafetyChecklistItem(UUID.randomUUID().toString(), "")) },
                modifier = Modifier.testTag("safety_add_$fieldKey")
                    .semantics { contentDescription = addDescription }) {
                Icon(
                    Icons.Outlined.Add,
                    null
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.safety_add_item))
            }
        }
        if (full) AccessibleMessage(stringResource(R.string.safety_checklist_full))
        if (field.conflictRevision != null && editable) {
            AccessibleMessage(
                stringResource(R.string.safety_conflict),
                modifier = Modifier.testTag("safety_conflict_$fieldKey")
            )
            Text(
                stringResource(
                    R.string.safety_synced_version,
                    safetyChecklistPreview(field.conflictValue.orEmpty())
                )
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    onResolve(
                        fieldKey,
                        true
                    )
                }) { Text(stringResource(R.string.safety_keep_mine)) }
                OutlinedButton(onClick = {
                    onResolve(
                        fieldKey,
                        false
                    )
                }) { Text(stringResource(R.string.safety_use_synced)) }
            }
        }
    }
}

// shared safe app safety plan form components shows this checklist row view so the user can view its details and use its actions.
@Composable
private fun ChecklistRow(
    item: SafetyChecklistItem, heading: String, sectionKey: String, index: Int, editable: Boolean,
    onChecked: (Boolean) -> Unit, onText: (String) -> Unit, onRemove: () -> Unit,
    attachments: List<SafetyDocument>, onRemoveAttachment: (SafetyDocument) -> Unit,
    onUploadDocument: (() -> Unit)?,
) {
    val empty = stringResource(R.string.safety_empty_item)
    val name = item.text.ifBlank { empty }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Checkbox(
            checked = item.checked, onCheckedChange = if (editable) onChecked else null,
            modifier = Modifier.testTag("safety_check_${sectionKey}_$index")
                .semantics { contentDescription = name })
        Column(Modifier.weight(1f)) {
            BasicTextField(
                value = item.text,
                onValueChange = onText,
                readOnly = !editable,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (item.checked) Muted else Ink,
                    textDecoration = if (item.checked) TextDecoration.LineThrough else null
                ),
                cursorBrush = SolidColor(OliveGreen),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 12.dp)
                    .testTag(if (index == 0) "safety_$sectionKey" else "safety_${sectionKey}_${item.id}")
                    .semantics { contentDescription = heading }.keepVisibleWhileTyping(),
                decorationBox = { input ->
                    Box {
                        if (item.text.isEmpty()) Text(
                            empty,
                            color = Muted
                        )
                        input()
                    }
                })
            // Editable document rows can open the attachment picker.
            if (sectionKey.substringAfter('_') == "documents" && editable && onUploadDocument != null) {
                val uploadDescription = stringResource(R.string.safety_upload_for, name)
                OutlinedButton(
                    onClick = onUploadDocument,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.testTag("safety_upload_${sectionKey}_$index")
                        .semantics { contentDescription = uploadDescription }) {
                    Text(
                        stringResource(R.string.safety_upload_document),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            attachments.forEach { document ->
                Row(
                    Modifier.fillMaxWidth().testTag("safety_attachment_${item.id}_${document.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Attachment, null, Modifier.size(18.dp), tint = OliveGreen)
                    Spacer(Modifier.width(8.dp))
                    Text(document.name, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f), maxLines = 2)
                    if (editable) IconButton(
                        onClick = { onRemoveAttachment(document) },
                        modifier = Modifier.testTag("safety_remove_attachment_${document.id}"),
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            stringResource(R.string.safety_remove_attachment, document.name),
                        )
                    }
                }
            }
        }
        // Editing controls are hidden from read-only safety-plan viewers.
        if (editable) {
            val removeLabel = stringResource(R.string.safety_remove_item, name)
            IconButton(
                onClick = onRemove,
                modifier = Modifier.testTag("safety_remove_${sectionKey}_$index")
            ) {
                Icon(Icons.Outlined.Close, removeLabel, Modifier.size(18.dp))
            }
        }
    }
}

// Lets the user choose which trusted contacts may view this safety plan.
@Composable
private fun SafetyPlanContactPicker(
    items: List<SafetyChecklistItem>,
    contacts: List<SafeContact>,
    editable: Boolean,
    onItemsChange: (List<SafetyChecklistItem>) -> Unit,
    onContactsChange: ((List<SafeContact>) -> Unit)?
) {
    val selected = items.mapNotNull(::readSafetyPlanContact)
    val selectedIds = selected.mapTo(mutableSetOf()) { it.id }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    Text(
        stringResource(R.string.safety_selected_contacts, selected.size),
        style = MaterialTheme.typography.labelMedium,
        color = Muted
    )
    // Only the plan owner can change linked trusted contacts.
    if (editable) {
        Text(
            stringResource(R.string.safety_contact_picker_help),
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
        if (contacts.isEmpty()) Text(
            stringResource(R.string.no_contacts_yet_adding_one_is_optional),
            color = Muted
        )
        contacts.forEach { contact ->
            key(contact.id) {
                val selectionDescription =
                    stringResource(R.string.safety_include_contact, contact.name)
                val safeStayDescription = stringResource(R.string.safe_to_stay_for, contact.name)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = contact.id in selectedIds,
                                onCheckedChange = { checked ->
                                    val remaining =
                                        items.filterNot { readSafetyPlanContact(it)?.id == contact.id }
                                    onItemsChange(
                                        if (checked) remaining + safetyPlanContactItem(
                                            contact
                                        ) else remaining
                                    )
                                },
                                modifier = Modifier.testTag("safety_contact_${contact.id}")
                                    .semantics { contentDescription = selectionDescription })
                            Column(Modifier.weight(1f)) {
                                Text(contact.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    contact.details,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Muted
                                )
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.safe_to_stay),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = contact.safeToStay, onCheckedChange = { enabled ->
                                    onContactsChange?.invoke(contacts.map {
                                        if (it.id == contact.id) it.copy(
                                            safeToStay = enabled
                                        ) else it
                                    })
                                }, modifier = Modifier.testTag("safety_contact_stay_${contact.id}")
                                    .semantics { contentDescription = safeStayDescription })
                        }
                    }
                }
            }
        }
        if (!adding) OutlinedButton(
            onClick = { adding = true },
            modifier = Modifier.fillMaxWidth().testTag("safety_add_contact")
        ) {
            Icon(
                Icons.Outlined.Add,
                null
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.add_a_contact))
        } else OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.add_a_contact),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
                Field(stringResource(R.string.contact_name), name, "safety_contact_name") {
                    name = it.take(120)
                }
                Field(
                    stringResource(R.string.phone_or_email),
                    details,
                    "safety_contact_details"
                ) { details = it.take(200) }
                Button(
                    onClick = {
                        val contact =
                            SafeContact(UUID.randomUUID().toString(), name.trim(), details.trim())
                        onContactsChange?.invoke(contacts + contact)
                        onItemsChange(items + safetyPlanContactItem(contact))
                        name = ""
                        details = ""
                        adding = false
                    },
                    enabled = name.isNotBlank() && details.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().testTag("safety_save_contact")
                ) {
                    Text(stringResource(R.string.safety_save_and_select_contact))
                }
                TextButton(
                    onClick = {
                        name = ""
                        details = ""
                        adding = false
                    },
                    modifier = Modifier.testTag("safety_cancel_contact")
                ) {
                    Text(stringResource(R.string.cancel_edit))
                }
            }
        }
    } else if (selected.isEmpty()) Text(
        stringResource(R.string.safety_no_contacts_selected),
        color = Muted
    )
    else selected.forEach { contact ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(contact.name, style = MaterialTheme.typography.titleSmall)
                Text(contact.details, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// All safe-app roles: shows who can view a shared safety plan.


// Adaptable prompts informed by PHAC safety-planning guidance; all starting text remains editable.
// https://www.canada.ca/en/public-health/services/health-promotion/stop-family-violence/plan-your-safety.html
internal fun safetyDefaults(context: Context): Map<String, String> = buildMap {
    listOf("primary", "backup").forEach { plan ->
        com.olive.app.data.SAFETY_SECTIONS.forEach { section ->
            val prompts = when (section) {
                "documents" -> safetyDocumentSuggestions(context)
                "grab" -> context.resources.getStringArray(R.array.safety_grab_checklist).toList()
                "destination" -> context.resources.getStringArray(R.array.safety_destination_checklist)
                    .toList()

                "travel" -> context.resources.getStringArray(R.array.safety_travel_checklist)
                    .toList()

                "contacts" -> context.resources.getStringArray(R.array.safety_contacts_checklist)
                    .toList()

                "aftercare" -> context.resources.getStringArray(R.array.safety_aftercare_checklist)
                    .toList()

                else -> emptyList()
            }
            put(
                "${plan}_$section",
                writeSafetyChecklist(prompts.map {
                    SafetyChecklistItem(
                        UUID.randomUUID().toString(),
                        it
                    )
                })
            )
        }
    }
}

// Loads the safety document suggestions needed by the safety plan.
private fun safetyDocumentSuggestions(context: Context) =
    context.resources.getStringArray(R.array.safety_document_checklist).toList()

// Formats the safety section label shown by the safety plan.
internal fun safetySectionLabel(section: String) = when (section) {
    "destination" -> R.string.safety_destination
    "travel" -> R.string.safety_travel
    "contacts" -> R.string.safety_contacts
    "documents" -> R.string.safety_documents_heading
    "grab" -> R.string.safety_grab_heading
    "essentials" -> R.string.safety_essentials
    "dependants" -> R.string.safety_dependants
    else -> R.string.safety_aftercare
}

// Returns the safety section hint used by the safety plan.
internal fun safetySectionHint(section: String) = when (section) {
    "destination" -> R.string.safety_destination_hint
    "travel" -> R.string.safety_travel_hint
    "contacts" -> R.string.safety_contacts_hint
    "aftercare" -> R.string.safety_aftercare_hint
    else -> null
}

// Linking is always explicit; names, phone numbers and email addresses are never used to infer an account.
@Composable
internal fun TrustedContactLinkPicker(
    friends: List<Friend>, selectedUid: String, unavailableUids: Set<String>,
    enabled: Boolean, tag: String, onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = friends.firstOrNull { it.uid == selectedUid }
    val choices = friends.filter { it.uid == selectedUid || it.uid !in unavailableUids }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.trusted_link_title),
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            stringResource(R.string.trusted_link_help),
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
        if (friends.isEmpty()) Text(
            stringResource(R.string.trusted_link_no_friends),
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
        else Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true }, enabled = enabled && choices.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().testTag(tag)
            ) {
                Icon(Icons.Outlined.Link, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    // Adds or removes the selected trusted contact from the safety plan.
                    when {
                        selected != null -> stringResource(
                            R.string.trusted_link_to,
                            selected.username
                        )

                        selectedUid.isNotBlank() -> stringResource(R.string.trusted_link_unavailable)
                        else -> stringResource(R.string.trusted_link_none)
                    }, Modifier.weight(1f)
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { friend ->
                    DropdownMenuItem(
                        text = { Text("@${friend.username}") },
                        onClick = {
                            expanded = false
                            onSelected(friend.uid)
                        },
                        leadingIcon = { Icon(Icons.Outlined.Link, null) }
                    )
                }
            }
        }
        if (selectedUid.isNotBlank()) TextButton(
            onClick = { onSelected("") }, enabled = enabled,
            modifier = Modifier.align(Alignment.End).testTag("${tag}_unlink")
        ) {
            Icon(
                Icons.Outlined.LinkOff,
                null
            )
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.trusted_unlink))
        }
    }
}
