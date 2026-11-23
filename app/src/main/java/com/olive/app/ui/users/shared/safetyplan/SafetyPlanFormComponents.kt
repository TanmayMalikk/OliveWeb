// All safe-app roles: draws editable safety-plan fields.
package com.olive.app.ui.users.shared.safetyplan

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveIconButton as IconButton
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveOutlinedCard as OutlinedCard
import com.olive.app.ui.shared.OliveTextButton as TextButton
import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.account.SafeContact
import com.olive.app.data.account.SafetyPlanIntake
import com.olive.app.data.messaging.Friend
import com.olive.app.data.safety.SAFETY_CHECKLIST_SECTIONS
import com.olive.app.data.safety.SafetyChecklistItem
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.data.safety.SafetyPlan
import com.olive.app.data.safety.mergeTailoredChecklistItems
import com.olive.app.data.safety.readSafetyChecklist
import com.olive.app.data.safety.readSafetyPlanContact
import com.olive.app.data.safety.safetyChecklistAttachments
import com.olive.app.data.safety.safetyPlanContactItem
import com.olive.app.data.safety.safetyPlanDefaultsFromIntake
import com.olive.app.data.safety.safetyPlanSectionPrompts
import com.olive.app.data.safety.writeSafetyChecklist
import com.olive.app.ui.session.SessionSafetyPlans
import com.olive.app.ui.shared.*
import com.olive.app.ui.users.shared.navigation.*
import java.util.UUID

/**
 * Composes the safety checklist progress UI element.
 *
 * @param prepared Prepared supplied to [SafetyChecklistProgress].
 * @param total Total supplied to [SafetyChecklistProgress].
 * @param summary Whether to apply summary.
 */
@Composable
internal fun SafetyChecklistProgress(prepared: Int, total: Int, summary: Boolean = false) {
    val fraction = if (total > 0) prepared.toFloat() / total else 0f
    val count = stringResource(R.string.safety_prepared_count, prepared, total)
    val progressLabel = stringResource(R.string.a11y_safety_progress, prepared, total)
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .then(if (summary) Modifier.testTag("safety_checklist_progress") else Modifier)
            .clearAndSetSemantics {
                contentDescription = progressLabel
                stateDescription = "${(fraction * 100).toInt()}%"
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (summary) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.safety_checklist_progress),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() }
                )
                Text(count, style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
        } else {
            Text(count, style = MaterialTheme.typography.labelMedium, color = Muted)
        }
        if (summary || total > 0) LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(if (summary) 10.dp else 6.dp)
                .border(1.dp, OliveDivider, CircleShape),
            color = OliveGreen,
            trackColor = SageGreen,
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
        )
    }
}

/**
 * Sections share one continuous note surface. Each heading and checkbox remains separately navigable with TalkBack.
 *
 * @param plan Safety plan data model.
 * @param fieldKey Field key supplied to [SafetyChecklistSection].
 * @param onEdit Callback invoked when edit changes or occurs.
 * @param onSyncNow Callback invoked when sync now changes or occurs.
 * @param contacts Collection of contact records.
 * @param contactsEditable Whether to apply contacts editable.
 * @param onContactsChange Callback invoked when contacts change changes or occurs.
 * @param editable Whether to apply editable.
 * @param attachmentsEditable Whether to apply attachments editable.
 * @param onUploadDocument Callback invoked when upload document changes or occurs.
 * @param documents Collection of documents values to process.
 * @param onOpenDocument Callback invoked when open document changes or occurs.
 */
@Composable
internal fun SafetyChecklistSection(
    plan: SafetyPlan, fieldKey: String, onEdit: (String, String) -> Unit,
    onSyncNow: () -> Unit = {},
    contacts: List<SafeContact> = emptyList(),
    contactsEditable: Boolean = false, onContactsChange: ((List<SafeContact>) -> Unit)? = null,
    editable: Boolean = true, attachmentsEditable: Boolean = editable,
    onUploadDocument: ((String) -> Unit)? = null, documents: List<SafetyDocument> = emptyList(),
    onOpenDocument: ((SafetyDocument) -> Unit)? = null,
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
        if (section != "contacts") SafetyChecklistProgress(items.count { it.checked }, items.size)
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
            if (attachmentsEditable) Text(
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
                        onSyncNow()
                    },
                    onText = { value -> update(items.map { if (it.id == item.id) it.copy(text = value) else it }) },
                    onRemove = {
                        update(items.filterNot { it.id == item.id })
                        onSyncNow()
                    },
                    onSyncNow = onSyncNow,
                    attachments = safetyChecklistAttachments(fieldKey, item, documents),
                    attachmentsEditable = attachmentsEditable,
                    onOpenAttachment = onOpenDocument,
                    onRemoveAttachment = { document ->
                        update(items.map {
                            if (it.id == item.id) it.copy(
                                attachmentIds = it.attachmentIds - document.id,
                                excludedAttachmentIds =
                                    (it.excludedAttachmentIds + document.id).distinct().takeLast(80),
                            ) else it
                        })
                        onSyncNow()
                    },
                    onUploadDocument = onUploadDocument?.let { upload -> { upload(item.id) } },
                )
            }
        }
        if (section != "contacts" && editable) {
            val addDescription = stringResource(R.string.safety_add_to_section, title)
            TextButton(
                onClick = {
                    update(items + SafetyChecklistItem(UUID.randomUUID().toString(), ""))
                    onSyncNow()
                },
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
    }
}

/**
 * Shared safe app safety plan form components shows this checklist row view so the user can view its details and use its actions.
 *
 * @param item Item supplied to [ChecklistRow].
 * @param heading Heading supplied to [ChecklistRow].
 * @param sectionKey Section key supplied to [ChecklistRow].
 * @param index Zero-based item index.
 * @param editable Whether to apply editable.
 * @param onChecked Callback invoked when checked changes or occurs.
 * @param onText Callback invoked when text changes or occurs.
 * @param onRemove Callback invoked when remove changes or occurs.
 * @param onSyncNow Callback invoked when sync now changes or occurs.
 * @param attachments Collection of attachments values to process.
 * @param onRemoveAttachment Callback invoked when remove attachment changes or occurs.
 * @param attachmentsEditable Whether to apply attachments editable.
 * @param onOpenAttachment Callback invoked when open attachment changes or occurs.
 * @param onUploadDocument Callback invoked when upload document changes or occurs.
 */
@Composable
private fun ChecklistRow(
    item: SafetyChecklistItem, heading: String, sectionKey: String, index: Int, editable: Boolean,
    onChecked: (Boolean) -> Unit, onText: (String) -> Unit, onRemove: () -> Unit,
    onSyncNow: () -> Unit,
    attachments: List<SafetyDocument>, onRemoveAttachment: (SafetyDocument) -> Unit,
    attachmentsEditable: Boolean,
    onOpenAttachment: ((SafetyDocument) -> Unit)?,
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
                    .semantics { contentDescription = heading }
                    .onFocusChanged { if (!it.isFocused) onSyncNow() }
                    .keepVisibleWhileTyping(),
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
            if (sectionKey.substringAfter('_') == "documents" && attachmentsEditable && onUploadDocument != null) {
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
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { onOpenAttachment?.invoke(document) },
                        enabled = onOpenAttachment != null,
                        modifier = Modifier.weight(1f)
                            .testTag("safety_attachment_${item.id}_${document.id}"),
                    ) {
                        Icon(Icons.Outlined.Attachment, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            document.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                        )
                    }
                    if (attachmentsEditable) IconButton(
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

/**
 * Lets the user choose which trusted contacts may view this safety plan.
 *
 * @param items Collection of items values to process.
 * @param contacts Collection of contact records.
 * @param editable Whether to apply editable.
 * @param onItemsChange Callback invoked when items change changes or occurs.
 * @param onContactsChange Callback invoked when contacts change changes or occurs.
 */
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
            var showContactErrors by remember { mutableStateOf(false) }
            var contactShake by remember { mutableIntStateOf(0) }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.add_a_contact),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
                Field(
                    stringResource(R.string.contact_name),
                    name,
                    "safety_contact_name",
                    error = if (showContactErrors && name.isBlank()) stringResource(R.string.ui_name_required) else null,
                    shakeTrigger = contactShake,
                ) {
                    name = it.take(120)
                    showContactErrors = false
                }
                Field(
                    stringResource(R.string.phone_or_email),
                    details,
                    "safety_contact_details",
                    error = if (showContactErrors && details.isBlank()) stringResource(R.string.enter_a_name_and_a_phone_number_or_email) else null,
                    shakeTrigger = contactShake,
                ) {
                    details = it.take(200)
                    showContactErrors = false
                }
                Button(
                    onClick = {
                        if (name.isBlank() || details.isBlank()) {
                            showContactErrors = true
                            contactShake += 1
                        } else {
                            val contact =
                                SafeContact(UUID.randomUUID().toString(), name.trim(), details.trim())
                            onContactsChange?.invoke(contacts + contact)
                            onItemsChange(items + safetyPlanContactItem(contact))
                            name = ""
                            details = ""
                            showContactErrors = false
                            adding = false
                        }
                    },
                    enabled = true,
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


/**
 * Adaptable prompts informed by PHAC safety-planning guidance and the user's onboarding questionnaire;
 * all starting text remains editable. https://www.canada.ca/en/public-health/services/health-promotion/stop-family-violence/plan-your-safety.html.
 *
 * @param context Android context used to access system services, resources, or storage.
 * @param hasAffectedPeople Whether affected family members were listed during personal setup.
 * @param intake Onboarding questionnaire selections used to tailor documents and subsections.
 * @return Map containing the resulting safety defaults entries.
 */
internal fun safetyDefaults(
    context: Context,
    hasAffectedPeople: Boolean = false,
    intake: SafetyPlanIntake = SafetyPlanIntake(),
): Map<String, String> = safetyPlanDefaultsFromIntake(context, intake, hasAffectedPeople)

/**
 * Adds newly relevant tailored checklist items to both primary and backup plans without altering
 * or removing the user's existing items, checkmarks, or attachments.
 *
 * @param context Android context used to resolve localized checklist strings.
 * @param controller Active safety-plan controller managing edits and sync.
 * @param intake Updated questionnaire selections.
 * @param hasAffectedPeople Whether affected family members were listed in the user's profile.
 */
internal fun applySafetyIntakeToPlan(
    context: Context,
    controller: SessionSafetyPlans,
    intake: SafetyPlanIntake,
    hasAffectedPeople: Boolean,
) {
    val activePlan = controller.plan ?: return
    var changed = false
    listOf("primary", "backup").forEach { planPrefix ->
        SAFETY_CHECKLIST_SECTIONS.filterNot { it == "contacts" }.forEach { section ->
            val key = "${planPrefix}_$section"
            val existing = activePlan.fields[key]?.value ?: return@forEach
            val tailored = safetyPlanSectionPrompts(context, section, intake, hasAffectedPeople)
            val updated = mergeTailoredChecklistItems(existing, tailored)
            if (updated != existing) {
                controller.edit(key, updated)
                changed = true
            }
        }
    }
    if (changed) controller.flushEdits()
}

/**
 * Formats the safety section label shown by the safety plan.
 *
 * @param section Section supplied to [safetySectionLabel].
 * @return The resulting safety section label.
 */
internal fun safetySectionLabel(section: String) = when (section) {
    "destination" -> R.string.safety_destination
    "travel" -> R.string.safety_travel
    "contacts" -> R.string.safety_contacts
    "documents" -> R.string.safety_documents_heading
    "grab" -> R.string.safety_grab_heading
    "finances" -> R.string.safety_finances_heading
    "essentials" -> R.string.safety_essentials
    "dependants" -> R.string.safety_dependants
    else -> R.string.safety_aftercare
}

/**
 * Returns the safety section hint used by the safety plan.
 *
 * @param section Section supplied to [safetySectionHint].
 * @return The resulting safety section hint.
 */
internal fun safetySectionHint(section: String) = when (section) {
    "destination" -> R.string.safety_destination_hint
    "travel" -> R.string.safety_travel_hint
    "contacts" -> R.string.safety_contacts_hint
    "aftercare" -> R.string.safety_aftercare_hint
    else -> null
}

/**
 * Linking is always explicit; names, phone numbers and email addresses are never used to infer an account.
 *
 * @param friends List of friend records.
 * @param selectedUid Selected user ID supplied to [TrustedContactLinkPicker].
 * @param unavailableUids Collection of unavailable uids values to process.
 * @param enabled Whether this feature or control is currently enabled.
 * @param tag Tag supplied to [TrustedContactLinkPicker].
 * @param onSelected Callback invoked when selected changes or occurs.
 */
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
