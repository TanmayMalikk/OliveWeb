package com.olive.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.*
import java.util.UUID

// Sections share one continuous note surface. Each heading and checkbox remains separately navigable with TalkBack.
@Composable internal fun SafetyChecklistSection(plan:SafetyPlan,fieldKey:String,onEdit:(String,String)->Unit,
    onResolve:(String,Boolean)->Unit,contacts:List<SafeContact> = emptyList(),contactsEditable:Boolean=false,
    onContactsChange:((List<SafeContact>)->Unit)?=null) {
    val field=plan.fields[fieldKey] ?: return
    val items=remember(field.value) { readSafetyChecklist(field.value) }
    val section=fieldKey.substringAfter('_')
    val title=stringResource(safetySectionLabel(section))
    var full by remember(fieldKey) { mutableStateOf(false) }
    val update:(List<SafetyChecklistItem>)->Unit={ changed ->
        val encoded=writeSafetyChecklist(changed)
        full=encoded.length>4000 || changed.size>80
        if(!full) onEdit(fieldKey,encoded)
    }
    Column(Modifier.padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(title,style=MaterialTheme.typography.titleLarge,
            modifier=Modifier.testTag("safety_heading_$fieldKey").semantics { heading() })
        if(section!="contacts") Text(stringResource(R.string.safety_prepared_count,items.count { it.checked },items.size),
            style=MaterialTheme.typography.labelMedium,color=Muted)
        safetySectionHint(section)?.let { Text(stringResource(it),style=MaterialTheme.typography.bodySmall,color=Muted) }
        if(section=="contacts") SafetyPlanContactPicker(items,contacts,contactsEditable,update,onContactsChange)
        if(section=="documents") {
            Text(stringResource(R.string.safety_document_hint),style=MaterialTheme.typography.bodySmall,color=Muted)
            Text(stringResource(R.string.safety_document_upload_hint),style=MaterialTheme.typography.bodySmall,color=Muted)
        }
        if(section!="contacts") items.forEachIndexed { index,item -> key(item.id) {
            ChecklistRow(item,title,fieldKey,index,
                onChecked={ checked -> update(items.map { if(it.id==item.id) it.copy(checked=checked) else it }) },
                onText={ value -> update(items.map { if(it.id==item.id) it.copy(text=value) else it }) },
                onRemove={update(items.filterNot { it.id==item.id })})
        } }
        if(section!="contacts") {
            val addDescription=stringResource(R.string.safety_add_to_section,title)
            TextButton(onClick={update(items+SafetyChecklistItem(UUID.randomUUID().toString(),""))},
                modifier=Modifier.testTag("safety_add_$fieldKey").semantics { contentDescription=addDescription }) {
                Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(6.dp));Text(stringResource(R.string.safety_add_item))
            }
        }
        if(full) AccessibleMessage(stringResource(R.string.safety_checklist_full))
        if(field.conflictRevision!=null) {
            AccessibleMessage(stringResource(R.string.safety_conflict),modifier=Modifier.testTag("safety_conflict_$fieldKey"))
            Text(stringResource(R.string.safety_synced_version,safetyChecklistPreview(field.conflictValue.orEmpty())))
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={onResolve(fieldKey,true)}) { Text(stringResource(R.string.safety_keep_mine)) }
                OutlinedButton(onClick={onResolve(fieldKey,false)}) { Text(stringResource(R.string.safety_use_synced)) }
            }
        }
    }
}

@Composable private fun ChecklistRow(item:SafetyChecklistItem,heading:String,sectionKey:String,index:Int,
    onChecked:(Boolean)->Unit,onText:(String)->Unit,onRemove:()->Unit) {
    val empty=stringResource(R.string.safety_empty_item)
    val name=item.text.ifBlank { empty }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Top) {
        Checkbox(checked=item.checked,onCheckedChange=onChecked,
            modifier=Modifier.testTag("safety_check_${sectionKey}_$index").semantics { contentDescription=name })
        Column(Modifier.weight(1f)) {
            BasicTextField(value=item.text,onValueChange=onText,textStyle=MaterialTheme.typography.bodyLarge.copy(
                color=if(item.checked) Muted else Ink,textDecoration=if(item.checked) TextDecoration.LineThrough else null),
                cursorBrush=SolidColor(OliveGreen),keyboardOptions=KeyboardOptions(autoCorrectEnabled=false),
                modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).padding(vertical=12.dp)
                    .testTag(if(index==0) "safety_$sectionKey" else "safety_${sectionKey}_${item.id}")
                    .semantics { contentDescription=heading }.keepVisibleWhileTyping(),
                decorationBox={ input -> Box { if(item.text.isEmpty()) Text(empty,color=Muted);input() } })
            if(sectionKey.substringAfter('_')=="documents") {
                val uploadDescription=stringResource(R.string.safety_upload_for,name)
                OutlinedButton(onClick={},enabled=false,contentPadding=PaddingValues(horizontal=12.dp,vertical=4.dp),
                    modifier=Modifier.testTag("safety_upload_${sectionKey}_$index").semantics { contentDescription=uploadDescription }) {
                    Text(stringResource(R.string.safety_upload_document),style=MaterialTheme.typography.labelMedium)
                }
            }
        }
        val removeLabel=stringResource(R.string.safety_remove_item,name)
        IconButton(onClick=onRemove,modifier=Modifier.testTag("safety_remove_${sectionKey}_$index")) {
            Icon(Icons.Outlined.Close,removeLabel,Modifier.size(18.dp))
        }
    }
}
