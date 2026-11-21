package com.olive.app.ui.users.shared.messaging

import com.olive.app.ui.shared.OliveButton as Button
import com.olive.app.ui.shared.OliveOutlinedButton as OutlinedButton
import com.olive.app.ui.shared.OliveOutlinedCard as OutlinedCard
import com.olive.app.ui.shared.OliveTextButton as TextButton
import com.olive.app.ui.shared.LocalizedIcon as Icon
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import com.olive.app.R
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.messaging.FriendInvite
import com.olive.app.ui.users.shared.navigation.SessionMessages
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.users.shared.navigation.LocalSafeSystemLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Shows the controls for sharing an invite and connecting with an existing code.
 *
 * @param session The signed-in session that creates and claims invites.
 * @param unreadOnly Optional unread-only filter state to render alongside the invite button.
 * @param onUnreadOnlyChange Optional callback invoked when the unread-only filter is toggled.
 * @param modifier Compose modifier applied to the root layout.
 */
@Composable
internal fun FriendInviteControls(
    session: SessionViewModel,
    unreadOnly: Boolean? = null,
    onUnreadOnlyChange: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var opened by remember(session.coverAccountId) { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (unreadOnly != null) Arrangement.SpaceBetween else Arrangement.End,
        ) {
            if (unreadOnly != null && onUnreadOnlyChange != null) {
                FilterChip(
                    selected = unreadOnly,
                    onClick = onUnreadOnlyChange,
                    label = { Text(stringResource(R.string.chat_unread_only)) },
                )
            }
            Button(
                onClick = { opened = true },
                enabled = !session.localMode,
                modifier = Modifier.testTag("friend_invite")
            ) {
                Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.friend_invite))
            }
        }
        InviteCodeEntry(
            messages = session.messages,
            enabled = !session.localMode,
            accountUid = session.coverAccountId,
            onConnected = { session.messages.ensureMessagingObservation() }
        )
    }
    if (opened) {
        val uid = session.coverAccountId.orEmpty()
        FriendInviteDialog(
            uid,
            createInvite = { refresh -> session.messages.createFriendInvite(uid, refresh) },
            observeUnavailable = { code, onUnavailable ->
                session.messages.observeFriendInviteUnavailable(uid, code, onUnavailable)
            },
            onDismiss = { opened = false },
        )
    }
}

/**
 * Lets someone claim an invite code and displays the result in place.
 *
 * @param messages The messaging controller that claims the code.
 * @param enabled Whether the user can enter and submit a code.
 * @param accountUid The account claiming the invite, if already known to the caller.
 * @param onConnected Runs after the code is claimed successfully.
 */
@Composable
internal fun InviteCodeEntry(
    messages: SessionMessages,
    enabled: Boolean = true,
    accountUid: String? = null,
    onConnected: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf<String?>(null) }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.friend_invite_question), style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.take(32); error = null },
                    label = { Text(stringResource(R.string.friend_invite_code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    enabled = enabled && !busy,
                    isError = error != null,
                    modifier = Modifier.weight(1f).testTag("friend_invite_code")
                )
                Button(
                    onClick = {
                        busy = true; error = null; connected = null
                        scope.launch {
                            try {
                                connected = messages.claimFriendInvite(accountUid, code)
                                code = ""
                                onConnected()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.localizedMessage
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = enabled && !busy && code.isNotBlank(),
                    modifier = Modifier.testTag("friend_invite_claim")
                ) {
                    Text(stringResource(if (busy) R.string.friend_invite_connecting else R.string.friend_invite_connect))
                }
            }
            connected?.let {
                Text(
                    stringResource(R.string.friend_invite_connected, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Lets the user copy, share, or refresh their invite and indicates when it becomes unavailable.
 *
 * @param uid The account that owns the invite.
 * @param createInvite Loads or creates an invite. A refresh requests a replacement code.
 * @param observeUnavailable Subscribes to changes that invalidate the displayed code.
 * @param onDismiss Closes the dialog.
 */
@Composable
internal fun FriendInviteDialog(
    uid: String,
    createInvite: suspend (refresh: Boolean) -> FriendInvite,
    observeUnavailable: (String, () -> Unit) -> ChatSubscription,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val openSystem = LocalSafeSystemLauncher.current
    val inviteLabel = stringResource(R.string.friend_invite)
    val scope = rememberCoroutineScope()
    var invite by remember { mutableStateOf<FriendInvite?>(null) }
    var busy by remember { mutableStateOf(true) }
    var unavailable by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /**
     * Loads an invite and updates the dialog with the invite or an error.
     *
     * @param refresh Whether to replace the current invite.
     */
    suspend fun generate(refresh: Boolean) {
        busy = true
        error = null
        invite = null
        unavailable = false
        try { invite = createInvite(refresh); unavailable = false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.localizedMessage }
        finally { busy = false }
    }
    LaunchedEffect(Unit) { generate(false) }
    LaunchedEffect(invite) {
        invite?.let { delay((it.expiresAt - System.currentTimeMillis()).coerceAtLeast(0)); unavailable = true }
    }
    DisposableEffect(uid, invite?.code) {
        val subscription = invite?.let { current ->
            observeUnavailable(current.code) {
                if (invite?.code == current.code) unavailable = true
            }
        }
        onDispose { subscription?.close() }
    }
    /**
     * Copies invite information to the system clipboard.
     *
     * @param value The invite code or link to copy.
     */
    fun copy(value: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(inviteLabel, value))
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.friend_invite)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (unavailable) Text(stringResource(R.string.friend_invite_unavailable))
            invite?.takeUnless { unavailable }?.let { value ->
                SelectionContainer { Text(value.code, style = MaterialTheme.typography.headlineSmall) }
                SelectionContainer { Text(value.link) }
                Text(stringResource(R.string.friend_invite_expires,
                    DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value.expiresAt))))
                Text(stringResource(R.string.friend_invite_once))
                TextButton(onClick = { copy(value.code) }) { Text(stringResource(R.string.friend_invite_copy_code)) }
                TextButton(onClick = { copy(value.link) }) { Text(stringResource(R.string.friend_invite_copy_link)) }
                TextButton(onClick = {
                    openSystem(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, value.link)
                    }, inviteLabel))
                }) { Text(stringResource(R.string.friend_invite_share)) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            OutlinedButton(onClick = { scope.launch { generate(true) } }, enabled = !busy) {
                Text(stringResource(R.string.friend_invite_refresh))
            }
        } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.friend_invite_done)) } })
}
