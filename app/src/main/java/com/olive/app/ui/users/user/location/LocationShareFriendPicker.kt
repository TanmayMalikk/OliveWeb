package com.olive.app.ui.users.user.location

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.messaging.Friend
import com.olive.app.ui.shared.oliveScrollbar

@Composable
internal fun LocationShareFriendPicker(
    accountId: String,
    friends: List<Friend>,
    contactNames: Map<String, String>,
    selected: Set<String>,
    enabled: Boolean,
    onSelectionChange: (String, Boolean) -> Unit,
) {
    if (friends.isEmpty()) {
        Text(stringResource(R.string.location_no_friends), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    var query by rememberSaveable(accountId) { mutableStateOf("") }
    val search = query.trim().removePrefix("@")
    val matches = friends.filter { friend ->
        friend.username.contains(search, ignoreCase = true) ||
            contactNames[friend.uid].orEmpty().contains(search, ignoreCase = true)
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it.take(120) },
        modifier = Modifier.fillMaxWidth().testTag("location_friend_search"),
        label = { Text(stringResource(R.string.location_search_friends)) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        singleLine = true,
    )
    if (matches.isEmpty()) {
        Text(stringResource(R.string.location_no_matching_friends), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(search) { listState.scrollToItem(0) }
    val rowHeight = 56.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
    LazyColumn(
        modifier = Modifier.fillMaxWidth().height(rowHeight * minOf(3, matches.size))
            .oliveScrollbar(listState).testTag("location_friend_list"),
        state = listState,
    ) {
        items(matches, key = { it.uid }) { friend ->
            val checked = friend.uid in selected
            val contactName = contactNames[friend.uid]?.takeIf(String::isNotBlank)
            Row(
                Modifier.fillMaxWidth().height(rowHeight).testTag("location_friend_${friend.uid}")
                    .toggleable(checked, enabled = enabled, role = Role.Checkbox) {
                        onSelectionChange(friend.uid, it)
                    }.padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                }
                Column(Modifier.weight(1f)) {
                    Text(contactName ?: friend.username, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (contactName != null) Text(
                        "@${friend.username}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
