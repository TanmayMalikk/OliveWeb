// Signed-in users: opens and closes direct and support chats.
package com.olive.app.ui.session

import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.R
import android.util.Log
import androidx.core.content.edit
import com.olive.app.security.EncryptionUtils
import com.google.firebase.firestore.FirebaseFirestoreException
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.ChatCacheException
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.ChatMessage
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
    private var keyListeners = emptyList<com.google.firebase.firestore.ListenerRegistration>()
    private val observedKeys = mutableMapOf<String, String>()
    private var keyParticipants = emptyMap<String, String>()
    private var keyVersion = 0
    private var openingChat: Job? = null
    // Opens a conversation after loading its participants and encryption access.
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
                    keyListeners.forEach { it.remove() }
                    observedKeys.clear()
                    keyParticipants = conversation.participantUsernames
                    keyVersion = 0
                    val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    val sources = listOf(
                        firestore.collection("conversations").document(conversation.id).collection("deviceKeys")
                            .document("$uid--${KeyManager.deviceId()}"),
                    ) + conversation.participantUsernames.values.distinct().map {
                        firestore.collection("messageDirectory").document(it.trim().lowercase())
                    }
                    val directories = conversation.participantUsernames.values.distinct().map {
                        firestore.collection("messageDirectory").document(it.trim().lowercase()).collection("devices")
                    }
                    keyListeners = sources.map { reference ->
                        reference.addSnapshotListener { snapshot, failure ->
                            if (failure != null || snapshot == null || epoch != observationEpoch ||
                                enrollment?.uid != uid || activeConversation?.id != conversation.id) return@addSnapshotListener
                            val fingerprint = snapshot.getString("wrappedKey") ?: snapshot.getString("publicKey") ?: return@addSnapshotListener
                            if (observedKeys.put(reference.path, fingerprint) != fingerprint) {
                                keyVersion++
                                if (activeRoomKey == null && !messagingBusy) openConversation(checkNotNull(activeConversation))
                            }
                        }
                    } + directories.map { directory ->
                        directory.addSnapshotListener { snapshot, failure ->
                            if (failure != null || snapshot == null || epoch != observationEpoch ||
                                enrollment?.uid != uid || activeConversation?.id != conversation.id) return@addSnapshotListener
                            val fingerprint = snapshot.documents.joinToString("|") {
                                "${it.id}:${it.getString("publicKey")}:${it.getString("mgf1Digest")}"
                            }
                            if (observedKeys.put(directory.path, fingerprint) == fingerprint) return@addSnapshotListener
                            val availableKey = activeRoomKey ?: return@addSnapshotListener
                            scope.launch {
                                try {
                                    if (epoch == observationEpoch && enrollment?.uid == uid &&
                                        activeConversation?.participantIds == conversation.participantIds) {
                                        secureChatRepository?.shareWithRegisteredDevices(conversation, availableKey)
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    // The next directory update or conversation open retries device approval.
                                }
                            }
                        }
                    }
                }
                if (!preserveMessages || !sameConversation) {
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
                if (conversation.type == "SUPPORT" && conversation.participantIds.size < 2) {
                    activeRoomKey = null
                    messagingBusy = false
                    return
                }
                messagingBusy = true
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

    fun stopKeyObservation() {
        openingChat?.cancel()
        openingChat = null
        keyListeners.forEach { it.remove() }
        keyListeners = emptyList()
        keyParticipants = emptyMap()
        observedKeys.clear()
    }

    // Session conversations: Stops conversation. It closes the active subscription or resource.
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

    // Sending a message: check the active chat, encrypt for its room, then report success to the composer.
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

    // Adds a readable system message when a conversation action fails.
    private fun sendFailureMessage(failure: Exception): Int =
        if (failure is FirebaseFirestoreException &&
            failure.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
        ) R.string.messaging_send_denied else R.string.ui_unable_to_send_the_message

    // Retries only the failed encrypted queue head so later messages cannot overtake it.
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

    // Discards only the permanently failed queue head, then resumes later ordered deliveries.
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

    // Removes message from the signed-in session.
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

    // Session conversations: Stops chat quick exit hint.
    fun dismissChatQuickExitHint() {
        with(runtime) {
            with(state) {
                chatQuickExitHintVisible = false
            }
        }
    }

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
                        scope.launch { publishMessages(conversation.id, ownerUid, roomKey, null, token) }
                    }
                },
                { error ->
                    if (epoch == token && remoteChatMessages.isEmpty()) {
                        state.message = application.getString(
                            R.string.ui_unable_to_load_this_conversation_value,
                            error,
                        )
                    }
                },
                { encrypted ->
                    scope.launch {
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

    // Archives or restores one chat for every participant.
    fun setArchived(conversation: ChatConversation, archived: Boolean) {
        with(runtime) {
            val uid = enrollment?.uid ?: return
            if (uid !in conversation.participantIds) return
            if (conversation.closedAt > 0L) {
                val previous = state.restoredClosedConversationIds
                state.restoredClosedConversationIds = if (archived) {
                    previous - conversation.id
                } else {
                    previous + conversation.id
                }
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            store.setClosedConversationRestored(uid, conversation.id, !archived)
                        }
                    } catch (_: Exception) { // Put the completed chat back where it was if this phone could not save the choice.
                        state.restoredClosedConversationIds = previous
                        state.message = runtime.application.getString(R.string.unable_to_update_archived_chat)
                    }
                }
                return
            }
            val previous = state.archivedConversationIds
            state.archivedConversationIds = if (archived) {
                previous + conversation.id
            } else {
                previous - conversation.id
            }
            scope.launch {
                // Turn an unexpected set archived failure into a readable message.
                try {
                    withContext(Dispatchers.IO) {
                        store.setConversationArchived(uid, conversation.id, archived)
                    }
                } catch (_: Exception) { // Restore the local archive state because the device could not persist the requested change.
                    state.archivedConversationIds = previous
                    state.message = runtime.application.getString(R.string.unable_to_update_archived_chat)
                }
            }
        }
    }

    // Keeps the private note on this device for the signed-in session.
    fun appendPrivateNote(target: DirectoryUser, text: String, onSaved: () -> Unit = {}) {
        with(runtime) {
            val uid = enrollment?.uid ?: return
            if (target.uid == uid || text.isBlank() || text.length > 2_000) return
            val organizationAccount = state.profile?.role in setOf(AccountRole.STAFF, AccountRole.ADMIN)
            if (!organizationAccount && state.friends.none { it.uid == target.uid }) {
                state.message = runtime.application.getString(R.string.private_note_friends_only)
                return
            }
            state.messagingBusy = true
            scope.launch {
                // Use the fallback when append private note is unavailable.
                try {
                    withContext(Dispatchers.IO) {
                        store.appendPrivateContactNote(uid, target.uid, target.username, text)
                        store.privateContactNotes(uid)
                    }.also { state.privateContactNotes = it }
                    onSaved()
                } catch (error: Exception) {
                    state.message = error.localizedMessage
                        ?: runtime.application.getString(R.string.unable_to_save_private_contact_note)
                } finally {
                    state.messagingBusy = false
                }
            }
        }
    }

    // Builds the device-storage key that tracks the last message read in one conversation.
    fun readMessageKey(uid: String, conversationId: String) = "read_message_${uid}_$conversationId"

    // Updates the saved conversation read used by the signed-in session.
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
