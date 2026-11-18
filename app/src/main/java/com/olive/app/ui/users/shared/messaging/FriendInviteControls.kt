package com.olive.app.ui.users.shared.messaging

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import com.google.firebase.firestore.FirebaseFirestore
import com.olive.app.R
import com.olive.app.data.messaging.FriendInvite
import com.olive.app.data.messaging.FriendInviteRepository
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.users.shared.navigation.LocalSafeSystemLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun FriendInviteControls(session: SessionViewModel) {
    var opened by remember(session.coverAccountId) { mutableStateOf(false) }
    Button(onClick = { opened = true }, enabled = !session.localMode,
        modifier = Modifier.testTag("friend_invite")) { Text(stringResource(R.string.friend_invite)) }
    InviteCodeEntry(enabled = !session.localMode, accountUid = session.coverAccountId,
        onConnected = { session.messages.ensureMessagingObservation() })
    if (opened) FriendInviteDialog(session.coverAccountId.orEmpty()) { opened = false }
}

@Composable
internal fun InviteCodeEntry(enabled: Boolean = true, accountUid: String? = null, onConnected: () -> Unit = {}) {
    val repository = remember(accountUid) { FriendInviteRepository(accountUid) }
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var connected by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.friend_invite_question), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(code, { code = it.take(32); error = null },
            label = { Text(stringResource(R.string.friend_invite_code)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
            enabled = enabled && !busy, isError = error != null,
            modifier = Modifier.fillMaxWidth().testTag("friend_invite_code"))
        Button(onClick = {
            busy = true; error = null; connected = null
            scope.launch {
                try { connected = repository.claim(code); code = ""; onConnected() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.localizedMessage }
                finally { busy = false }
            }
        }, enabled = enabled && !busy && code.isNotBlank(), modifier = Modifier.testTag("friend_invite_claim")) {
            Text(stringResource(if (busy) R.string.friend_invite_connecting else R.string.friend_invite_connect))
        }
        connected?.let { Text(stringResource(R.string.friend_invite_connected, it)) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun FriendInviteDialog(uid: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val openSystem = LocalSafeSystemLauncher.current
    val inviteLabel = stringResource(R.string.friend_invite)
    val repository = remember(uid) { FriendInviteRepository(uid) }
    val scope = rememberCoroutineScope()
    var invite by remember { mutableStateOf<FriendInvite?>(null) }
    var busy by remember { mutableStateOf(true) }
    var unavailable by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    suspend fun generate(refresh: Boolean) {
        busy = true
        error = null
        invite = null
        unavailable = false
        try { invite = repository.create(refresh); unavailable = false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.localizedMessage }
        finally { busy = false }
    }
    LaunchedEffect(Unit) { generate(false) }
    LaunchedEffect(invite) {
        invite?.let { delay((it.expiresAt - System.currentTimeMillis()).coerceAtLeast(0)); unavailable = true }
    }
    DisposableEffect(uid, invite?.code) {
        val listener = FirebaseFirestore.getInstance().collection("friendInviteOwners").document(uid)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot != null && invite != null &&
                    (snapshot.getString("code") != invite?.code || snapshot.getString("claimedBy").orEmpty().isNotBlank())) unavailable = true
            }
        onDispose { listener.remove() }
    }
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
