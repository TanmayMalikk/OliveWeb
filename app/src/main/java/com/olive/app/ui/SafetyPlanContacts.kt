package com.olive.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.*
import java.util.UUID

@Composable internal fun SafetyPlanContactPicker(items:List<SafetyChecklistItem>,contacts:List<SafeContact>,editable:Boolean,
    onItemsChange:(List<SafetyChecklistItem>)->Unit,onContactsChange:((List<SafeContact>)->Unit)?) {
    val selected=items.mapNotNull(::readSafetyPlanContact)
    val selectedIds=selected.mapTo(mutableSetOf()) { it.id }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var details by remember { mutableStateOf("") }
    Text(stringResource(R.string.safety_selected_contacts,selected.size),style=MaterialTheme.typography.labelMedium,color=Muted)
    if(editable) {
        Text(stringResource(R.string.safety_contact_picker_help),style=MaterialTheme.typography.bodySmall,color=Muted)
        if(contacts.isEmpty()) Text(stringResource(R.string.no_contacts_yet_adding_one_is_optional),color=Muted)
        contacts.forEach { contact -> key(contact.id) {
            val selectionDescription=stringResource(R.string.safety_include_contact,contact.name)
            val safeStayDescription=stringResource(R.string.safe_to_stay_for,contact.name)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(checked=contact.id in selectedIds,onCheckedChange={ checked ->
                            val remaining=items.filterNot { readSafetyPlanContact(it)?.id==contact.id }
                            onItemsChange(if(checked) remaining+safetyPlanContactItem(contact) else remaining)
                        },modifier=Modifier.testTag("safety_contact_${contact.id}").semantics { contentDescription=selectionDescription })
                        Column(Modifier.weight(1f)) {
                            Text(contact.name,style=MaterialTheme.typography.titleSmall)
                            Text(contact.details,style=MaterialTheme.typography.bodySmall,color=Muted)
                        }
                    }
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(stringResource(R.string.safe_to_stay),style=MaterialTheme.typography.labelLarge,modifier=Modifier.weight(1f))
                        Switch(checked=contact.safeToStay,onCheckedChange={ enabled ->
                            onContactsChange?.invoke(contacts.map { if(it.id==contact.id) it.copy(safeToStay=enabled) else it })
                        },modifier=Modifier.testTag("safety_contact_stay_${contact.id}")
                            .semantics { contentDescription=safeStayDescription })
                    }
                }
            }
        } }
        if(!adding) OutlinedButton(onClick={adding=true},modifier=Modifier.fillMaxWidth().testTag("safety_add_contact")) {
            Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(6.dp));Text(stringResource(R.string.add_a_contact))
        } else OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.add_a_contact),style=MaterialTheme.typography.titleMedium,
                    modifier=Modifier.semantics { heading() })
                Field(stringResource(R.string.contact_name),name,"safety_contact_name") { name=it.take(120) }
                Field(stringResource(R.string.phone_or_email),details,"safety_contact_details") { details=it.take(200) }
                Button(onClick={
                    val contact=SafeContact(UUID.randomUUID().toString(),name.trim(),details.trim())
                    onContactsChange?.invoke(contacts+contact)
                    onItemsChange(items+safetyPlanContactItem(contact))
                    name="";details="";adding=false
                },enabled=name.isNotBlank() && details.isNotBlank(),modifier=Modifier.fillMaxWidth().testTag("safety_save_contact")) {
                    Text(stringResource(R.string.safety_save_and_select_contact))
                }
                TextButton(onClick={name="";details="";adding=false},modifier=Modifier.testTag("safety_cancel_contact")) {
                    Text(stringResource(R.string.cancel_edit))
                }
            }
        }
    } else if(selected.isEmpty()) Text(stringResource(R.string.safety_no_contacts_selected),color=Muted)
    else selected.forEach { contact ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(contact.name,style=MaterialTheme.typography.titleSmall)
                Text(contact.details,style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}
