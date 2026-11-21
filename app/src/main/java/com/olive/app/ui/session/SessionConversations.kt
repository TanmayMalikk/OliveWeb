// Signed-in users: opens and closes direct and support chats.
package com.olive.app.ui.session

import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.R
import android.util.Log
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.olive.app.data.network.hasValidatedInternetConnection
import kotlinx.coroutines.delay
import com.olive.app.security.EncryptionUtils
import com.google.firebase.firestore.FirebaseFirestoreException
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.ChatCacheException
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.ChatMessage
import com.olive.app.data.messaging.ChatSubscription
import com.olive.app.data.messaging.DirectoryUser
import com.olive.app.data.messaging.MessageDeliveryStatus
import com.olive.app.data.messaging.MessageSendWorker
import com.olive.app.data.messaging.OutgoingMessage
import com.olive.app.data.messaging.canDiscardFailedMessage
import com.olive.app.data.messaging.canManuallyRetryMessage
import com.olive.app.data.messaging.prepareOutgoingMessage
import com.olive.app.security.KeyManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration.Companion.milliseconds


// Owns conversation selection, messages and read markers.
internal class SessionConversations(private val runtime: SessionRuntime) {
    private val pendingArchiveWrites = mutableSetOf<Pair<String, String>>()
    private var keySubscription: ChatSubscription? = null
    private val observedKeys = mutableMapOf<String, String>()
    private var keyParticipants = emptyMap<String, String>()
    private var keyVersion = 0
    private var openingChat: Job? = null
    private var messageLoadTimeout: Job? = null
    var loadingConversation by mutableStateOf(false)
        private set

    /**
     * Cancels the loading timeout and clears the conversation's loading indicator.
     */
    private fun finishLoadingConversation() {
        messageLoadTimeout?.cancel()
        messageLoadTimeout = null
        loadingConversation = false
    }
    /**
     * Opens a direct, group, or support chat for a signed-in participant and starts loading its encrypted history.
     *
     * @param conversation The chat to open, including its current participants and ticket details.
     * @param preserveMessages Keeps existing messages and the room key when reopening the same chat.
     */
    fun openConversation(conversation: ChatConversation, preserveMessages: Boolean = false) {
        with(runtime) {
            with(state) {
                if (!((screen == SessionScreen.Safe && page == SafePage.Messages) ||
                            (screen == SessionScreen.Portal && accountRole in setOf(AccountRole.STAFF, AccountRole.ADMIN)))) return
                val uid = enrollment?.uid ?: return
                if (uid !in conversation.participantIds) return
                openingChat?.cancel()
                messageSubscription?.close()
                messageSubscription = null
                messageOutboxObservation?.cancel()
                messageOutboxObservation = null
                val sameConversation = activeConversation?.id == conversation.id
                if (!sameConversation || keyParticipants != conversation.participantUsernames) {
                    val observationEpoch = epoch
                    keySubscription?.close()
                    observedKeys.clear()
                    keyParticipants = conversation.participantUsernames
                    keyVersion = 0
                    keySubscription = secureChatRepository?.observeKeyChanges(
                        conversation,
                        uid,
                        onKey = keyChanged@{ path, fingerprint ->
                            if (epoch != observationEpoch || enrollment?.uid != uid) return@keyChanged
                            val selected = activeConversation?.takeIf { it.id == conversation.id } ?: return@keyChanged
                            if (observedKeys.put(path, fingerprint) != fingerprint) {
                                keyVersion++
                                if (activeRoomKey == null && !messagingBusy) openConversation(selected)
                            }
                        },
                        onDevices = devicesChanged@{ path, fingerprint ->
                            if (epoch != observationEpoch || enrollment?.uid != uid ||
                                activeConversation?.id != conversation.id ||
                                observedKeys.put(path, fingerprint) == fingerprint
                            ) return@devicesChanged
                            val availableKey = activeRoomKey ?: return@devicesChanged
                            scope.launch {
                                try {
                                    if (epoch == observationEpoch && enrollment?.uid == uid &&
                                        activeConversation?.id == conversation.id &&
                                        activeConversation?.participantIds == conversation.participantIds
                                    ) secureChatRepository?.shareWithRegisteredDevices(conversation, availableKey)
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    // The next directory update or conversation open retries device approval.
                                }
                            }
                        },
                    )
                }
                if (!preserveMessages || !sameConversation) {
                    finishLoadingConversation()
                    chatMessages = emptyList()
                    remoteChatMessages = emptyList()
                    activeRoomKey = null
                }
                activeConversation =
                    conversation
                message = null
                conversationActions.markConversationRead(conversation)
                chatQuickExitHintVisible =
                    accountRole == AccountRole.USER && !chatQuickExitHintShown
                if (chatQuickExitHintVisible) chatQuickExitHintShown = true
                val token = epoch
                val initialKeyVersion = keyVersion
                if (secureChatRepository == null || (conversation.type == "SUPPORT" && conversation.participantIds.size < 2)) {
                    activeRoomKey = null
                    messagingBusy = false
                    return
                }
                messagingBusy = true
                if (!preserveMessages || !sameConversation) {
                    loadingConversation = true
                    messageLoadTimeout = scope.launch {
                        delay(12_000.milliseconds)
                        if (epoch == token && activeConversation?.id == conversation.id && loadingConversation) {
                            loadingConversation = false
                            if (chatMessages.isEmpty()) message = application.getString(R.string.unable_to_open_secure_conversation)
                        }
                    }
                }
                openingChat = scope.launch {
                    try {
                        val secure = checkNotNull(secureChatRepository)
                        var establishedKey = false
                        val key = try {
                            withTimeoutOrNull(8_000.milliseconds) {
                                val onlineKey = secureSharing.roomKey(conversation)
                                val wrapped = secure.wrappedRoomKey(conversation.id, uid)
                                    ?: error("Wrapped room key unavailable")
                                withContext(Dispatchers.IO) {
                                    chatCache.saveAccess(
                                        uid,
                                        conversation,
                                        wrapped,
                                        KeyManager.deviceId(),
                                        System.currentTimeMillis(),
                                    )
                                }
                                onlineKey
                            } ?: error("Online room key timed out")
                        } catch (onlineFailure: Exception) {
                            if (onlineFailure is CancellationException) throw onlineFailure
                            if (onlineFailure is FirebaseFirestoreException &&
                                onlineFailure.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                            ) throw onlineFailure
                            val cached = withContext(Dispatchers.IO) {
                                chatCache.open(
                                    uid,
                                    conversation.id,
                                    KeyManager.deviceId(),
                                    System.currentTimeMillis(),
                                )
                            }
                            val offlineKey = KeyManager.unwrapKey(cached.wrappedRoomKey)
                            val cachedMessages = withContext(Dispatchers.Default) {
                                secure.decryptMessages(cached.messages, offlineKey)
                            }
                            if (epoch == token && activeConversation?.id == conversation.id) {
                                remoteChatMessages = cachedMessages
                            }
                            offlineKey
                        }
                        if (epoch != token || activeConversation?.id != conversation.id) return@launch
                        activeRoomKey = key
                        startMessageObservation(conversation, uid, key, token)
                        if (establishedKey) launch {
                            try {
                                secure.shareWithRegisteredDevices(conversation, key)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // Reading an established chat does not depend on approving other devices.
                            }
                        }
                        publishMessages(conversation.id, uid, key, null, token)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) { // Do not expose key details; show the safe conversation error when encrypted room setup fails.
                        if (epoch == token && activeConversation?.id == conversation.id) finishLoadingConversation()
                        if (epoch == token) message =
                            runtime.application.getString(R.string.unable_to_open_secure_conversation)
                    } finally {
                        if (epoch == token && activeConversation?.id == conversation.id &&
                            kotlinx.coroutines.currentCoroutineContext()[Job]?.isCancelled != true) {
                            messagingBusy = false
                            val updated = activeConversation
                            if (messageSubscription == null && updated?.id == conversation.id &&
                                (updated.updatedAt != conversation.updatedAt ||
                                  (activeRoomKey == null && keyVersion != initialKeyVersion))) {
                                openConversation(updated)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Cancels the pending open and stops listening for the current chat's key and device changes.
     */
    fun stopKeyObservation() {
        finishLoadingConversation()
        openingChat?.cancel()
        openingChat = null
        keySubscription?.close()
        keySubscription = null
        keyParticipants = emptyMap()
        observedKeys.clear()
    }

    /**
     * Closes the active chat, stops its listeners, and clears its messages and room key from the session.
     */
    fun closeConversation() {
        stopKeyObservation()
        with(runtime) {
            with(state) {
                messageSubscription?.close()
                messageSubscription = null
                messageOutboxObservation?.cancel()
                messageOutboxObservation = null
                activeConversation = null
                activeRoomKey = null
                chatMessages = emptyList()
                remoteChatMessages = emptyList()
                messagingBusy = false
                message = null
            }
        }
    }

    /**
     * Encrypts a message for the active chat and saves it to the device's outbox for delivery.
     *
     * @param text The message text from the composer.
     * @param onSent Runs after the message enters the outbox if the same account and chat are still active.
     */
    fun sendMessage(text: String, onSent: () -> Unit = {}) {
        val state = runtime.state
        runtime.secureChatRepository ?: return
        val device = runtime.enrollment ?: return
        val conversation = state.activeConversation ?: return
        if (state.messagingBusy) return

        val sessionVersion = runtime.epoch
        state.messagingBusy = true
        state.message = null
        runtime.scope.launch {
            // Turn an unexpected send message failure into a readable message.
            try {
                val roomKey = runtime.activeRoomKey ?: runtime.secureSharing.roomKey(conversation)
                val outgoing = withContext(Dispatchers.Default) {
                    prepareOutgoingMessage(
                        conversation.id,
                        AccountIdentity(device.uid, state.coverUsername),
                        text,
                        roomKey,
                    )
                }
                val queued = withContext(Dispatchers.IO) { runtime.messageOutbox.write(outgoing) }
                MessageSendWorker.enqueue(runtime.application, queued)
                if (runtime.epoch == sessionVersion && state.activeConversation?.id == conversation.id) {
                    onSent()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e("OliveMessaging", "Encrypted message send failed", failure)
                if (runtime.epoch == sessionVersion) {
                    state.message = runtime.application.getString(sendFailureMessage(failure))
                }
            } finally {
                if (runtime.epoch == sessionVersion) state.messagingBusy = false
            }
        }
    }

    /**
     * Chooses the message to show when encryption or queueing fails.
     *
     * @param failure The exception raised while preparing the outgoing message.
     * @return The string resource ID for an access denial or a general send failure.
     */
    private fun sendFailureMessage(failure: Exception): Int =
        if (failure is FirebaseFirestoreException &&
            failure.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
        ) R.string.messaging_send_denied else R.string.ui_unable_to_send_the_message

    /**
     * Retries a message if it is eligible for retry and is first in the signed-in account's chat outbox.
     *
     * @param chat The visible message whose delivery should be retried.
     */
    fun retryMessage(chat: ChatMessage) {
        if (!canManuallyRetryMessage(chat.deliveryStatus)) return
        val uid = runtime.enrollment?.uid ?: return
        runtime.scope.launch {
            val outgoing = withContext(Dispatchers.IO) {
                runtime.messageOutbox.get(chat.id)?.takeIf { candidate ->
                    candidate.ownerUid == uid &&
                            runtime.messageOutbox.head(uid, candidate.conversationId)?.id == candidate.id
                }?.let {
                    runtime.messageOutbox.update(it.id) { current ->
                        current.copy(
                            status = MessageDeliveryStatus.QUEUED,
                            attemptCount = 0,
                            failureCode = null,
                        )
                    }
                }
            } ?: return@launch
            MessageSendWorker.retryNow(runtime.application, outgoing)
        }
    }

    /**
     * Removes a permanently failed message at the front of its chat outbox and schedules the following message.
     *
     * @param chat The failed message selected for deletion.
     */
    fun discardFailedMessage(chat: ChatMessage) {
        if (!canDiscardFailedMessage(chat.deliveryStatus)) return
        val uid = runtime.enrollment?.uid ?: return
        runtime.scope.launch {
            val removed = withContext(Dispatchers.IO) {
                runtime.messageOutbox.get(chat.id)?.takeIf { candidate ->
                    candidate.ownerUid == uid && canDiscardFailedMessage(candidate.status) &&
                            runtime.messageOutbox.head(uid, candidate.conversationId)?.id == candidate.id
                }?.also { runtime.messageOutbox.remove(uid, it.id) }
            } ?: return@launch
            runtime.messageOutbox.head(uid, removed.conversationId)?.let {
                MessageSendWorker.enqueue(runtime.application, it)
            }
        }
    }

    /**
     * Revokes any attachment access granted through a sent message and requests that the message be unsent.
     *
     * @param chat The sent message in the active chat.
     */
    fun unsendMessage(chat: ChatMessage) {
        with(runtime) {
            with(state) {
                val conversation = activeConversation ?: return
                if (chat.deliveryStatus != MessageDeliveryStatus.SENT) return
                messaging.runMessagingAction("Unable to unsend this message.") { _, device, _ ->
                    secureSharing.revokeIfShared(chat, conversation, device.uid)
                    checkNotNull(secureChatRepository) { "Secure messaging unavailable" }
                        .unsend(conversation.id, chat, device.uid)
                }
            }
        }
    }

    /**
     * Hides the quick-exit hint in the current chat.
     */
    fun dismissChatQuickExitHint() {
        with(runtime) {
            with(state) {
                chatQuickExitHintVisible = false
            }
        }
    }

    /**
     * Merges server messages with locally queued messages and removes acknowledged outbox entries.
     *
     * @param conversationId The chat whose timeline is being updated.
     * @param ownerUid The signed-in account that owns the outbox.
     * @param roomKey The encryption key used to read pending messages.
     * @param suppliedOutgoing The current outbox snapshot, or null to read it from storage.
     * @param token The session version used to ignore results from an earlier sign-in.
     */
    private suspend fun publishMessages(
        conversationId: String,
        ownerUid: String,
        roomKey: javax.crypto.SecretKey,
        suppliedOutgoing: List<OutgoingMessage>?,
        token: Long,
    ) {
        val outgoing = suppliedOutgoing ?: withContext(Dispatchers.IO) {
            runtime.messageOutbox.all(ownerUid)
        }
        if (runtime.epoch != token || runtime.state.activeConversation?.id != conversationId) return
        val remote = runtime.remoteChatMessages
        val remoteIds = remote.mapTo(mutableSetOf(), ChatMessage::id)
        withContext(Dispatchers.IO) {
            outgoing.filter { it.status == MessageDeliveryStatus.SENT && it.id in remoteIds }
                .forEach { runtime.messageOutbox.remove(ownerUid, it.id) }
        }
        val local = withContext(Dispatchers.Default) {
            outgoing.asSequence().filter {
                it.conversationId == conversationId && it.id !in remoteIds
            }.mapNotNull { pending ->
                runCatching {
                    ChatMessage(
                        id = pending.id,
                        senderUid = pending.ownerUid,
                        senderUsername = pending.senderUsername,
                        body = EncryptionUtils.decryptText(pending.ciphertext, roomKey),
                        sentAt = pending.clientSentAt,
                        deliveryStatus = pending.status,
                        deliveryFailureCode = pending.failureCode,
                    )
                }.getOrNull()
            }.toList()
        }
        if (runtime.epoch == token && runtime.state.activeConversation?.id == conversationId) {
            runtime.state.chatMessages = (remote + local)
                .distinctBy(ChatMessage::id)
                .sortedWith(compareBy(ChatMessage::sentAt, ChatMessage::id))
        }
    }

    /**
     * Listens to server messages and the device outbox so the timeline updates while the chat stays open.
     *
     * @param conversation The chat whose messages should be observed.
     * @param ownerUid The signed-in account that owns the outbox.
     * @param roomKey The encryption key used to read messages.
     * @param token The session version captured when the chat was opened.
     */
    private fun startMessageObservation(
        conversation: ChatConversation,
        ownerUid: String,
        roomKey: javax.crypto.SecretKey,
        token: Long,
    ) {
        with(runtime) {
            val secure = secureChatRepository ?: return
            MessageSendWorker.enqueuePending(application, ownerUid)
            messageOutboxObservation?.cancel()
            messageOutboxObservation = scope.launch {
                messageOutbox.observe(ownerUid).collect { outgoing ->
                    publishMessages(conversation.id, ownerUid, roomKey, outgoing, token)
                }
            }
            messageSubscription?.close()
            messageSubscription = secure.observeMessages(
                conversation.id,
                roomKey,
                { rows ->
                    if (epoch == token && state.activeConversation?.id == conversation.id) {
                        remoteChatMessages = rows
                        scope.launch {
                            publishMessages(conversation.id, ownerUid, roomKey, null, token)
                            if (epoch == token && state.activeConversation?.id == conversation.id &&
                                !hasValidatedInternetConnection(application)) finishLoadingConversation()
                        }
                    }
                },
                { error ->
                    if (epoch == token && state.activeConversation?.id == conversation.id) finishLoadingConversation()
                    if (epoch == token && remoteChatMessages.isEmpty()) {
                        state.message = application.getString(
                            R.string.ui_unable_to_load_this_conversation_value,
                            error,
                        )
                    }
                },
                { encrypted ->
                    scope.launch {
                        if (epoch == token && state.activeConversation?.id == conversation.id) {
                            publishMessages(conversation.id, ownerUid, roomKey, null, token)
                            if (epoch == token && state.activeConversation?.id == conversation.id) finishLoadingConversation()
                        }
                        try {
                            val wrapped = secure.wrappedRoomKey(conversation.id, ownerUid)
                                ?: throw ChatCacheException("Wrapped room key unavailable")
                            val confirmedKey = KeyManager.unwrapKey(wrapped)
                            withContext(Dispatchers.IO) {
                                chatCache.saveAccess(
                                    ownerUid,
                                    state.activeConversation?.takeIf { it.id == conversation.id }
                                        ?: conversation,
                                    wrapped,
                                    KeyManager.deviceId(),
                                    System.currentTimeMillis(),
                                    requireIndexed = true,
                                )
                                chatCache.saveMessages(
                                    ownerUid,
                                    conversation.id,
                                    encrypted,
                                    System.currentTimeMillis(),
                                )
                            }
                            if (epoch == token && state.activeConversation?.id == conversation.id &&
                                activeRoomKey?.encoded?.let(confirmedKey.encoded::contentEquals) != true
                            ) {
                                activeRoomKey = confirmedKey
                                startMessageObservation(conversation, ownerUid, confirmedKey, token)
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // A transient revalidation failure never extends the offline lease.
                        }
                    }
                },
            )
        }
    }

    /**
     * Saves the signed-in account's archive or restore preference for one chat on this device.
     *
     * @param conversation The chat to move between the active and archived lists.
     * @param archived True to archive the chat, or false to restore it to the active list.
     * @param onSaved Runs after the device saves the requested state for the active account.
     */
    fun setArchived(conversation: ChatConversation, archived: Boolean, onSaved: () -> Unit = {}) {
        with(runtime) {
            val uid = enrollment?.uid ?: return
            if (uid !in conversation.participantIds) return
            val id = conversation.id
            val closed = conversation.closedAt > 0L
            val wasArchived = if (closed) id !in state.restoredClosedConversationIds
                else id in state.archivedConversationIds
            val operation = uid to id
            if (wasArchived == archived || !pendingArchiveWrites.add(operation)) return
            val archiveEpoch = epoch
            fun showArchived(value: Boolean) {
                if (closed) {
                    val restored = state.restoredClosedConversationIds
                    state.restoredClosedConversationIds = if (value) restored - id else restored + id
                } else {
                    val archivedIds = state.archivedConversationIds
                    state.archivedConversationIds = if (value) archivedIds + id else archivedIds - id
                }
            }
            showArchived(archived)
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        if (closed) store.setClosedConversationRestored(uid, id, !archived)
                        else store.setConversationArchived(uid, id, archived)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (epoch == archiveEpoch && enrollment?.uid == uid) {
                        showArchived(wasArchived)
                        state.message = application.getString(R.string.unable_to_update_archived_chat)
                    }
                    return@launch
                } finally {
                    pendingArchiveWrites.remove(operation)
                }
                if (epoch == archiveEpoch && enrollment?.uid == uid) onSaved()
            }
        }
    }

    /**
     * Saves one private note about a contact for the signed-in account on this device.
     *
     * @param target The contact the note is about.
     * @param text The replacement note body. A blank body removes the saved note.
     * @param onSaved Runs after the saved notes are loaded into the same active account's session.
     */
    fun savePrivateNote(target: DirectoryUser, text: String, onSaved: () -> Unit = {}) {
        with(runtime) {
            val uid = enrollment?.uid ?: return
            if (target.uid == uid || text.length > 20_000 || state.messagingBusy) return
            val organizationAccount = state.profile?.role in setOf(AccountRole.STAFF, AccountRole.ADMIN)
            if (!organizationAccount && state.friends.none { it.uid == target.uid }) {
                state.message = runtime.application.getString(R.string.private_note_friends_only)
                return
            }
            val noteEpoch = epoch
            state.messagingBusy = true
            scope.launch {
                // Replace only this owner's note for this person.
                try {
                    val notes = withContext(Dispatchers.IO) {
                        store.savePrivateContactNote(uid, target.uid, target.username, text)
                        store.privateContactNotes(uid)
                    }
                    if (enrollment?.uid == uid && epoch == noteEpoch) {
                        state.privateContactNotes = notes
                        onSaved()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (enrollment?.uid == uid && epoch == noteEpoch) state.message = error.localizedMessage
                        ?: runtime.application.getString(R.string.unable_to_save_private_contact_note)
                } finally {
                    if (enrollment?.uid == uid && epoch == noteEpoch) state.messagingBusy = false
                }
            }
        }
    }

    /**
     * Builds the device preference key for an account's read marker in a chat.
     *
     * @param uid The account that owns the read marker.
     * @param conversationId The chat the read marker belongs to.
     * @return The preference key used to store the last read message ID.
     */
    fun readMessageKey(uid: String, conversationId: String) = "read_message_${uid}_$conversationId"

    /**
     * Records the chat's latest message as read for the signed-in account.
     *
     * @param conversation The chat and its latest known message ID.
     */
    fun markConversationRead(conversation: ChatConversation) {
        with(runtime) {
            with(state) {
                val uid = enrollment?.uid ?: return
                if (conversation.lastMessageId.isBlank()) return
                val key = conversationActions.readMessageKey(uid, conversation.id)
                if (messageReadState.getString(key, null) == conversation.lastMessageId) return
                messageReadState.edit { putString(key, conversation.lastMessageId) }
                readMessageVersion++
            }
        }
    }
}
