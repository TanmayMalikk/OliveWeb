package com.olive.app.ui.users.shared.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olive.app.data.messaging.ChatAccessRecoveryResult
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.RecoveredChatAccess
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.security.UnlockedAccountKeyset
import com.olive.app.ui.users.user.documents.MediaAccessRecoveryResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal enum class EncryptedAccessRecoveryNotice { SUCCESS, PARTIAL, FAILED }

internal data class EncryptedAccessRecoveryState(
    val ownerUid: String? = null,
    val promptVisible: Boolean = false,
    val scanning: Boolean = false,
    val restoring: Boolean = false,
    val mediaCount: Int = 0,
    val chatCount: Int = 0,
    val notice: EncryptedAccessRecoveryNotice? = null,
    val noticeMessage: String? = null,
)

/** Coordinates one explicit account unlock across independent media and chat key recovery. */
internal class EncryptedAccessRecoveryController(
    private val scope: CoroutineScope,
    private val deviceId: () -> String,
    private val unlock: suspend (String) -> UnlockedAccountKeyset,
    private val findChats: suspend (List<ChatConversation>, String) -> List<ChatConversation>,
    private val restoreMedia: suspend (
        List<SafetyDocument>,
        String,
        UnlockedAccountKeyset,
    ) -> MediaAccessRecoveryResult,
    private val restoreChats: suspend (
        List<ChatConversation>,
        String,
        UnlockedAccountKeyset,
    ) -> ChatAccessRecoveryResult,
    private val rememberMedia: suspend (String, List<SafetyDocument>) -> Unit,
    private val rememberChat: (String, RecoveredChatAccess, String, Long) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val canRestore: suspend (String) -> Boolean = { true },
    private val failureMessage: (Throwable) -> String? = { null },
) {
    var state by mutableStateOf(EncryptedAccessRecoveryState())
        private set

    private var evaluation: Job? = null
    private var restoration: Job? = null
    private var generation = 0L
    private var evaluatedSignature: String? = null
    private var dismissedSignature: String? = null
    private var mediaCandidates = emptyList<SafetyDocument>()
    private var chatCandidates = emptyList<ChatConversation>()

    /**
     * Evaluates server-authorized recovery candidates without opening ciphertext payloads.
     *
     * @param ownerUid Active Firebase account identifier.
     * @param media Recoverable media candidates already filtered for the current device.
     * @param conversations Conversations currently visible to the account.
     */
    fun evaluate(
        ownerUid: String,
        media: List<SafetyDocument>,
        conversations: List<ChatConversation>,
    ) {
        if (state.restoring && state.ownerUid == ownerUid) return
        val signature = buildString {
            append(ownerUid)
            append('|')
            media.sortedBy(SafetyDocument::id).forEach {
                append(it.id).append(':').append(it.createdAt).append(':').append(it.ciphertextSha256).append(',')
            }
            append('|')
            conversations.sortedBy(ChatConversation::id).forEach {
                append(it.id).append(':').append(it.updatedAt).append(',')
            }
        }
        if (signature == evaluatedSignature && state.ownerUid == ownerUid) return
        evaluatedSignature = signature
        generation++
        val token = generation
        evaluation?.cancel()
        restoration?.cancel()
        mediaCandidates = media.distinctBy(SafetyDocument::id)
        chatCandidates = emptyList()
        state = EncryptedAccessRecoveryState(ownerUid = ownerUid, scanning = true)
        evaluation = scope.launch {
            val chats = try {
                findChats(conversations, ownerUid)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (token != generation || state.ownerUid != ownerUid) return@launch
            chatCandidates = chats.distinctBy(ChatConversation::id)
            val hasCandidates = mediaCandidates.isNotEmpty() || chatCandidates.isNotEmpty()
            val recoverable = if (!hasCandidates) false else try {
                canRestore(ownerUid)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                true
            }
            if (token != generation || state.ownerUid != ownerUid) return@launch
            val visible = hasCandidates && recoverable
            state = state.copy(
                scanning = false,
                promptVisible = visible && dismissedSignature != signature,
                mediaCount = mediaCandidates.size,
                chatCount = chatCandidates.size,
            )
        }
    }

    /** Hides only the current candidate set; a later access change may prompt again. */
    fun dismiss() {
        dismissedSignature = evaluatedSignature
        state = state.copy(promptVisible = false)
    }

    /**
     * Uses one foreground account-keyset unlock to restore all current candidates.
     *
     * Successful media and chat wrappers are retained independently so one corrupt item cannot
     * discard the other successful recoveries.
     */
    fun restore() {
        val ownerUid = state.ownerUid ?: return
        if (!state.promptVisible || state.restoring) return
        val token = generation
        val media = mediaCandidates
        val chats = chatCandidates
        state = state.copy(promptVisible = false, restoring = true, notice = null, noticeMessage = null)
        restoration = scope.launch {
            try {
                val keyset = unlock(ownerUid)
                val mediaResult = if (media.isEmpty()) {
                    MediaAccessRecoveryResult(emptyList(), emptySet(), emptySet())
                } else restoreMedia(media, ownerUid, keyset)
                if (mediaResult.documents.isNotEmpty()) {
                    rememberMedia(ownerUid, mediaResult.documents)
                }
                val chatResult = if (chats.isEmpty()) {
                    ChatAccessRecoveryResult(emptyList(), emptySet())
                } else restoreChats(chats, ownerUid, keyset)
                val restoredAt = now()
                val currentDeviceId = deviceId()
                chatResult.recovered.forEach { access ->
                    rememberChat(ownerUid, access, currentDeviceId, restoredAt)
                }
                if (token != generation || state.ownerUid != ownerUid) return@launch
                val failed = mediaResult.failedIds.isNotEmpty() ||
                    mediaResult.pendingSyncIds.isNotEmpty() || chatResult.failedIds.isNotEmpty()
                state = state.copy(
                    restoring = false,
                    notice = if (failed) EncryptedAccessRecoveryNotice.PARTIAL
                    else EncryptedAccessRecoveryNotice.SUCCESS,
                    noticeMessage = null,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (token == generation && state.ownerUid == ownerUid) {
                    state = state.copy(
                        restoring = false,
                        notice = EncryptedAccessRecoveryNotice.FAILED,
                        noticeMessage = failureMessage(failure)?.takeIf { it.isNotBlank() },
                    )
                }
            }
        }
    }

    /** Clears transient messaging after it has been announced by the UI. */
    fun clearNotice() {
        state = state.copy(notice = null, noticeMessage = null)
    }
}
