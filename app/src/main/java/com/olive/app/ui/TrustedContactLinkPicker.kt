package com.olive.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.Friend

// Linking is always explicit; names, phone numbers and email addresses are never used to infer an account.
@Composable internal fun TrustedContactLinkPicker(friends:List<Friend>,selectedUid:String,unavailableUids:Set<String>,
    enabled:Boolean,tag:String,onSelected:(String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected=friends.firstOrNull { it.uid==selectedUid }
    val choices=friends.filter { it.uid==selectedUid || it.uid !in unavailableUids }
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.trusted_link_title),style=MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.trusted_link_help),style=MaterialTheme.typography.bodySmall,color=Muted)
        if(friends.isEmpty()) Text(stringResource(R.string.trusted_link_no_friends),style=MaterialTheme.typography.bodySmall,color=Muted)
        else Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick={expanded=true},enabled=enabled && choices.isNotEmpty(),
                modifier=Modifier.fillMaxWidth().testTag(tag)) {
                Icon(Icons.Outlined.Link,null);Spacer(Modifier.width(8.dp))
                Text(when {
                    selected!=null -> stringResource(R.string.trusted_link_to,selected.username)
                    selectedUid.isNotBlank() -> stringResource(R.string.trusted_link_unavailable)
                    else -> stringResource(R.string.trusted_link_none)
                },Modifier.weight(1f))
            }
            DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
                choices.forEach { friend -> DropdownMenuItem(
                    text={Text("@${friend.username}")},
                    onClick={expanded=false;onSelected(friend.uid)},
                    leadingIcon={Icon(Icons.Outlined.Link,null)}
                ) }
            }
        }
        if(selectedUid.isNotBlank()) TextButton(onClick={onSelected("")},enabled=enabled,
            modifier=Modifier.align(Alignment.End).testTag("${tag}_unlink")) {
            Icon(Icons.Outlined.LinkOff,null);Spacer(Modifier.width(6.dp));Text(stringResource(R.string.trusted_unlink))
        }
    }
}
