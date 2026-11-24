// User and safe-contact accounts: shares and renews a temporary friend invite.
package com.olive.app.ui.users.shared.messaging

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.olive.app.R
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.messaging.FriendInvite
import com.olive.app.ui.shared.OliveButton
import com.olive.app.ui.shared.OliveOutlinedButton
import com.olive.app.ui.shared.OliveTextButton
import com.olive.app.ui.shared.WalkthroughInWindowDialog
import com.olive.app.ui.users.shared.navigation.LocalSafeSystemLauncher
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lets the user copy, share, or refresh their invite and indicates when it becomes unavailable.
 *
 * @param uid The account that owns the invite.
 * @param createInvite Loads or creates an invite. A refresh requests a replacement code.
 * @param observeUnavailable Subscribes to changes that invalidate the displayed code.
 * @param onDismiss Closes the dialog.
 * @param inWindow Whether the dialog is hosted in the current window.
 */
@Composable
internal fun FriendInviteDialog(
    uid: String,
    createInvite: suspend (refresh: Boolean) -> FriendInvite,
    observeUnavailable: (String, () -> Unit) -> ChatSubscription,
    onDismiss: () -> Unit,
    inWindow: Boolean = false,
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
    LaunchedEffect(uid) { generate(false) }
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
    val bodyContent: @Composable () -> Unit = {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val message = if (unavailable) stringResource(R.string.friend_invite_unavailable) else error
            message?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            invite?.takeUnless { unavailable }?.let { value ->
                val expiry = Date(value.expiresAt)
                val expiresAt = remember(context, value.expiresAt) {
                    "${DateFormat.getMediumDateFormat(context).format(expiry)} · ${DateFormat.getTimeFormat(context).format(expiry)}"
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Column(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(stringResource(R.string.friend_invite_code), style = MaterialTheme.typography.labelLarge)
                        SelectionContainer {
                            Text(
                                value.code,
                                style = MaterialTheme.typography.headlineSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Text(
                            stringResource(R.string.friend_invite_expires, expiresAt),
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                OliveButton(
                    onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, value.link)
                        }
                        openSystem(Intent.createChooser(intent, inviteLabel))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.friend_invite_share))
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OliveOutlinedButton(onClick = { copy(value.code) }) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.friend_invite_copy_code))
                    }
                    OliveOutlinedButton(onClick = { copy(value.link) }) {
                        Icon(Icons.Outlined.Link, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.friend_invite_copy_link))
                    }
                }
            }
            HorizontalDivider()
            OliveTextButton(
                onClick = { scope.launch { generate(true) } },
                enabled = !busy,
            ) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.friend_invite_refresh))
            }
            if (invite != null && !unavailable) {
                Text(
                    stringResource(R.string.friend_invite_once),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (inWindow) {
        WalkthroughInWindowDialog(
            dialogTargetId = "friend_invite_dialog",
            title = { Text(inviteLabel) },
            onDismiss = onDismiss,
            confirmButton = { OliveTextButton(onClick = onDismiss) { Text(stringResource(R.string.friend_invite_done)) } },
            text = bodyContent,
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(inviteLabel) },
            text = bodyContent,
            confirmButton = {
                OliveTextButton(onClick = onDismiss) { Text(stringResource(R.string.friend_invite_done)) }
            },
        )
    }
}
