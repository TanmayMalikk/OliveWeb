// Signed-in users: controls who can receive shared safety items.
package com.olive.app.ui.session

import com.google.firebase.firestore.ListenerRegistration
import com.olive.app.R
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.ChatMessage
import com.olive.app.data.messaging.Friend
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.ui.users.user.location.LocationMapConfiguration
import com.olive.app.ui.users.user.location.LocationMapController
import com.olive.app.security.KeyManager
import com.olive.app.ui.users.shared.navigation.SessionScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.PublicKey
import java.io.File
import com.olive.app.data.media.PendingVoiceMessage
import com.olive.app.ui.users.user.documents.MediaTransferController
import androidx.compose.runtime.*
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.MessageSendWorker
import com.olive.app.data.messaging.OutgoingAttachment
import com.olive.app.data.messaging.isDurableChatAttachment
import com.olive.app.data.messaging.outgoingRoomKey
import com.olive.app.data.messaging.prepareOutgoingMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope


/** Coordinates encrypted conversations and private-entry sharing without exposing keys to UI code. */
internal class SessionSecureSharing(private val runtime: SessionRuntime) {
    private var sharedEntriesSubscription: ListenerRegistration? = null
    private var sharedEntriesOwner: Pair<String, Long>? = null
    private var lastSyncedSharedSignature: String? = null
    /**
     * Keeps the saved room key name in one place for the signed-in session.
     *
     * @param conversation Conversation metadata including participants and ticket status.
     * @return The resulting [SecretKey] for room key.
     */
    suspend fun roomKey(conversation: ChatConversation): javax.crypto.SecretKey {
        with(runtime) {
            val device = enrollment ?: error("No active account")
            val repository = messagingRepository ?: error("Messaging unavailable")
            val secure = secureChatRepository ?: error("Secure messaging unavailable")
            messaging.registerMessagingIdentity(AccountIdentity(device.uid, state.coverUsername))
            val prepared = if (conversation.type == "SUPPORT") {
                conversation.copy(participantUsernames = repository.prepareTicketParticipantNames(conversation.id))
            } else conversation
            // Use the fallback when room key is unavailable.
            try {
                val peers = coroutineScope {
                    prepared.participantIds.filter { it != device.uid }.map { uid ->
                        async {
                            val username = prepared.participantUsernames[uid] ?: error("Conversation participant unavailable")
                            repository.searchUsers(username, device.uid).firstOrNull { it.uid == uid }
                                ?: error("Every participant needs to open the updated app before secure messages can be sent.")
                        }
                    }.awaitAll()
                }
                require(peers.all { it.publicKey.isNotBlank() }) {
                    "Every participant needs to open the updated app before secure messages can be sent."
                }
                val keys: Map<String, PublicKey> = withContext(Dispatchers.Default) {
                    buildMap {
                        put(device.uid, KeyManager.getOrCreatePublicKey())
                        peers.forEach { put(it.uid, KeyManager.decodePublicKey(it.publicKey)) }
                    }
                }
                return secure.ensureRoomKey(prepared, device.uid, keys)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (keySharingFailure: Exception) {
                // Existing members can still read history while a newly added member finishes key setup.
                return secure.existingRoomKey(conversation.id, device.uid) ?: throw keySharingFailure
            }
        }
    }

    /**
     * Recovers an authorized room key outside the short online/cache timeout.
     *
     * @param conversation The chat and its current participants.
     * @return The symmetric key authorized for decrypting the conversation.
     */
    internal suspend fun recoverRoomKey(conversation: ChatConversation): javax.crypto.SecretKey {
        with(runtime) {
            val device = enrollment ?: error("No active account")
            val secure = secureChatRepository ?: error("Secure messaging unavailable")
            val keyset = settings.unlockAccountKeysetForMedia(device.uid)
            val recovered = secure.existingRoomKey(conversation.id, device.uid, keyset)
                ?: error("Account room key unavailable")
            // Rewrap for this device so later opens use the normal device-key path.
            try {
                secure.shareWithRegisteredDevices(conversation, recovered)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Recovery can open this session even if durable device approval must retry later.
            }
            return recovered
        }
    }

    /**
     * Shares the selected item for session secure sharing.
     *
     * @param entry Entry supplied to [share].
     */
    fun share(entry: PrivateEntry) {
        with(runtime) {
            with(state) {
                val conversation = activeConversation ?: return
                val device = enrollment ?: return
                val documents = secureDocumentRepository ?: return
                val chat = secureChatRepository ?: return
                val repository = messagingRepository ?: return
                if (messagingBusy) return
                val token = epoch
                messagingBusy = true
                message = null
                scope.launch {
                    // Turn an unexpected share failure into a readable message.
                    try {
                        val prepared = if (conversation.type == "SUPPORT") {
                            conversation.copy(participantUsernames = repository.prepareTicketParticipantNames(conversation.id))
                        } else conversation
                        val peers = coroutineScope {
                            prepared.participantIds.filter { it != device.uid }.map { uid ->
                                async {
                                    val name = prepared.participantUsernames[uid]
                                        ?: error("Participant unavailable")
                                    repository.searchUsers(name, device.uid).firstOrNull { it.uid == uid }
                                        ?: error("Recipient unavailable")
                                }
                            }.awaitAll()
                        }
                        require(peers.all { it.publicKey.isNotBlank() })
                        val recipients = withContext(Dispatchers.Default) {
                            buildMap {
                                put(device.uid, KeyManager.getOrCreatePublicKey())
                                peers.forEach { put(it.uid, KeyManager.decodePublicKey(it.publicKey)) }
                            }
                        }
                        documents.saveMultiRecipientEntry(
                            entry,
                            device.uid,
                            recipients
                        )
                        val prefix =
                            if (entry.kind == PrivateEntryKind.Incident) "SHARED_INCIDENT" else "SHARED_NOTE"
                        chat.send(
                            conversation.id, AccountIdentity(device.uid, coverUsername),
                            "$prefix:${entry.id}:${
                                entry.title.ifBlank {
                                    runtime.application.getString(
                                        R.string.untitled_note
                                    )
                                }
                            }",
                            roomKey(conversation)
                        )
                        if (epoch == token) message =
                            runtime.application.getString(R.string.private_entry_shared)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) { // Hide encryption details and report only that the private entry could not be shared.
                        if (epoch == token) message =
                            runtime.application.getString(R.string.unable_to_share_private_entry)
                    } finally {
                        if (epoch == token) messagingBusy = false
                    }
                }
            }
        }
    }

    /**
     * Shares the selected item for session secure sharing.
     *
     * @param document Safety document metadata record.
     */
    fun share(document: SafetyDocument) {
        with(runtime) {
            with(state) {
                val conversation = activeConversation ?: return
                val device = enrollment ?: return
                safetyDocumentRepository ?: return
                if (messagingBusy || !isDurableChatAttachment(document, device.uid)) return
                val token = epoch
                messagingBusy = true
                message = null
                scope.launch {
                    // Turn an unexpected share failure into a readable message.
                    try {
                        val key = outgoingRoomKey(activeRoomKey) { roomKey(conversation) }
                        val outgoing = prepareOutgoingMessage(
                            conversation.id, AccountIdentity(device.uid, coverUsername),
                            "SHARED_MEDIA:${document.id}:${document.name}", key
                        ).copy(attachment = OutgoingAttachment(mediaId = document.id))
                        val queued = withContext(Dispatchers.IO) { messageOutbox.write(outgoing) }
                        MessageSendWorker.enqueue(application, queued)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        if (epoch == token) message =
                            runtime.application.getString(R.string.documents_share_failed)
                    } finally {
                        if (epoch == token) messagingBusy = false
                    }
                }
            }
        }
    }

    var preparingVoiceConversationId by mutableStateOf<String?>(null)
        private set

    /**
     * Sends voice.
     *
     * @param file Local file to read, hash, process, or write.
     * @param metadata Metadata supplied to [sendVoice].
     * @param transfers Transfers supplied to [sendVoice].
     * @return `true` if send voice, or `false` otherwise.
     */
    fun sendVoice(file: File, metadata: String, transfers: MediaTransferController): Boolean = with(runtime) {
        with(state) {
            val conversation = activeConversation ?: return false
            val device = enrollment ?: return false
            if (preparingVoiceConversationId != null || conversation.closedAt != 0L ||
                conversation.participantIds.size < 2
            ) return false
            val token = epoch
            val identity = AccountIdentity(device.uid, coverUsername)
            val openedKey = activeRoomKey
            preparingVoiceConversationId = conversation.id
            message = null
            scope.launch {
                try {
                    val key = outgoingRoomKey(openedKey) { roomKey(conversation) }
                    check(enrollment?.uid == identity.uid)
                    val wrappedKey = withContext(Dispatchers.IO) {
                        KeyManager.wrapKey(key, KeyManager.getOrCreatePublicKey())
                    }
                    transfers.queueVoiceMessage(file, identity, PendingVoiceMessage(
                        conversation.id, identity.username, wrappedKey, metadata,
                    ))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (epoch == token && activeConversation?.id == conversation.id) {
                        message = application.getString(R.string.voice_send_failed)
                    }
                } finally {
                    file.delete()
                    preparingVoiceConversationId = null
                }
            }
            true
        }
    }

    /**
     * Sends safety plan for session secure sharing.
     */
    fun shareSafetyPlan() {
        with(runtime) {
            with(state) {
                val conversation = activeConversation ?: return
                val device = enrollment ?: return
                val chat = secureChatRepository ?: return
                if (accountRole != AccountRole.USER || messagingBusy ||
                    conversation.type != "SUPPORT") return
                val token = epoch
                messagingBusy = true
                message = null
                scope.launch {
                    // Use the fallback when share safety plan is unavailable.
                    try {
                        chat.send(
                            conversation.id,
                            AccountIdentity(device.uid, coverUsername),
                            "SHARED_SAFETY_PLAN:${device.uid}:Safety plan",
                            roomKey(conversation),
                        )
                        if (epoch == token) message = runtime.application.getString(R.string.safety_plan_shared_in_chat)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) { // Leave the safety plan unchanged and show the shared sync error.
                        if (epoch == token) message = runtime.application.getString(R.string.safety_sync_error)
                    } finally {
                        if (epoch == token) messagingBusy = false
                    }
                }
            }
        }
    }

    /**
     * Session secure sharing: Sends live location.
     *
     * @param durationMillis Duration or timestamp for duration, in milliseconds.
     * @param controller Controller supplied to [shareLiveLocation].
     * @param requestPermission Function used to transform or provide request permission.
     */
    fun shareLiveLocation(
        durationMillis: Long,
        controller: LocationMapController,
        requestPermission: suspend () -> Boolean
    ) {
        with(runtime) {
            with(state) {
                val conversation = activeConversation ?: return
                val device = enrollment ?: return
                val profile = profile ?: return
                if (profile.role != AccountRole.USER || messagingBusy) return
                val peerUid = conversation.participantIds.firstOrNull { it != device.uid } ?: return
                val friend = friends.firstOrNull { it.uid == peerUid } ?: return
                val configuration = LocationMapConfiguration(
                    AccountIdentity(device.uid, coverUsername),
                    profile,
                    listOf(friend),
                    localMode,
                    application.getString(R.string.you),
                    application.getString(R.string.your_saved_location)
                )
                controller.startSharingWith(
                    configuration,
                    listOf(friend),
                    durationMillis,
                    requestPermission,
                    application.getString(R.string.location_permission_required),
                    application.getString(R.string.location_start_failed),
                    onError = { message = it },
                    onStarted = { expiresAt ->
                        // Posts a normal encrypted timeline item after location sharing has actually started.
                        conversationActions.sendMessage("SHARED_LOCATION:$expiresAt")
                    },
                )
            }
        }
    }

    /**
     * Opens the requested item for session secure sharing.
     *
     * @param entryId Unique identifier of the private entry or note.
     */
    fun open(entryId: String) {
        with(runtime) {
            with(state) {
                val uid = enrollment?.uid ?: return
                val repository = secureDocumentRepository ?: return
                if (busy) return
                val token = epoch
                busy = true
                message = null
                viewedSharedEntry = null
                scope.launch {
                    // Turn an unexpected open failure into a readable message.
                    try {
                        val entry = repository.loadAndDecryptEntry(entryId, uid)
                        if (epoch == token) viewedSharedEntry = entry
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) { // Keep the encrypted entry closed and display a safe opening error.
                        if (epoch == token) message =
                            runtime.application.getString(R.string.unable_to_open_shared_entry)
                    } finally {
                        if (epoch == token) busy = false
                    }
                }
            }
        }
    }

    /**
     * Removes if shared from the signed-in session.
     *
     * @param message Chat message or remote payload.
     * @param conversation Conversation metadata including participants and ticket status.
     * @param currentUid Current user ID supplied to [revokeIfShared].
     */
    suspend fun revokeIfShared(
        message: ChatMessage,
        conversation: ChatConversation,
        currentUid: String
    ) {
        val parts = message.body.split(":", limit = 3)
        if (parts.firstOrNull() == "SHARED_MEDIA") {
            val entryId = parts.getOrNull(1) ?: return
            conversation.participantIds.filter { it != currentUid }.forEach { recipient ->
                runtime.safetyDocumentRepository?.revokeAccess(entryId, currentUid, recipient)
            }
            return
        }
        if (parts.firstOrNull() !in setOf("SHARED_NOTE", "SHARED_INCIDENT")) return
        val entryId = parts.getOrNull(1) ?: return
        conversation.participantIds.filter { it != currentUid }.forEach { recipient ->
            runtime.secureDocumentRepository?.revokeAccessToEntry(entryId, recipient)
        }
    }

    /**
     * Session secure sharing: Closes the active feature.
     */
    fun close() {
        runtime.state.viewedSharedEntry = null
    }

    /**
     * Observes private entries shared with the signed-in user or safe contact and tracks recipients of owned notes.
     */
    fun observeSharedEntries() {
        with(runtime) {
            if (localMode) return
            val uid = enrollment?.uid ?: return
            if (state.screen != SessionScreen.Safe ||
                state.accountRole !in setOf(AccountRole.USER, AccountRole.SAFE_CONTACT)
            ) return
            val sessionKey = uid to epoch
            if (sharedEntriesOwner == sessionKey && sharedEntriesSubscription != null) return
            sharedEntriesSubscription?.remove()
            sharedEntriesOwner = sessionKey
            sharedEntriesSubscription = secureDocumentRepository?.observeUserSharedEntries(
                currentUid = uid,
                onUpdate = { snapshot ->
                    if (enrollment?.uid == uid && epoch == sessionKey.second && state.screen == SessionScreen.Safe) {
                        state.sharedPrivateEntries = snapshot.sharedWithMe
                        state.sharedEntryRecipients = snapshot.ownedSharedRecipients
                    }
                },
            )
        }
    }

    /**
     * Stops the live shared-notes listener and resets conversation share-sync state.
     */
    fun stopSharedEntriesObservation() {
        sharedEntriesSubscription?.remove()
        sharedEntriesSubscription = null
        sharedEntriesOwner = null
        lastSyncedSharedSignature = null
    }

    /**
     * Shares or revokes a private note directly with selected accepted friends from the Notes screen.
     *
     * @param entry The private entry to save, display, or share.
     * @param selectedFriends The accepted friends selected to receive the private entry.
     * @param onComplete Runs when the requested operation completes.
     */
    fun shareWithFriends(
        entry: PrivateEntry,
        selectedFriends: List<Friend>,
        onComplete: () -> Unit = {},
    ) {
        with(runtime) {
            with(state) {
                if (localMode || busy || selectedFriends.size > 10) return
                val device = enrollment ?: return
                val documents = secureDocumentRepository ?: return
                val repository = messagingRepository ?: return
                val token = epoch
                busy = true
                message = null
                scope.launch {
                    try {
                        messaging.registerMessagingIdentity(AccountIdentity(device.uid, coverUsername))
                        if (selectedFriends.isEmpty()) {
                            withContext(Dispatchers.IO) {
                                documents.revokeAllRecipients(entry.id, device.uid)
                            }
                        } else {
                            val peers = coroutineScope {
                                selectedFriends.filter { it.uid != device.uid }.map { friend ->
                                    async {
                                        repository.searchUsers(friend.username, device.uid)
                                            .firstOrNull { it.uid == friend.uid }
                                            ?: error("Recipient unavailable")
                                    }
                                }.awaitAll()
                            }
                            require(peers.all { it.publicKey.isNotBlank() })
                            val recipients = withContext(Dispatchers.Default) {
                                buildMap {
                                    put(device.uid, KeyManager.getOrCreatePublicKey())
                                    peers.forEach { put(it.uid, KeyManager.decodePublicKey(it.publicKey)) }
                                }
                            }
                            withContext(Dispatchers.IO) {
                                documents.updateMultiRecipientEntry(entry, device.uid, recipients)
                            }
                        }
                        if (epoch == token) {
                            sharedEntryRecipients = if (selectedFriends.isEmpty()) {
                                sharedEntryRecipients - entry.id
                            } else {
                                sharedEntryRecipients + (entry.id to selectedFriends.map { it.uid }.distinct())
                            }
                            message = application.getString(
                                if (selectedFriends.isEmpty()) R.string.safety_sharing_saved
                                else R.string.private_entry_shared
                            )
                            onComplete()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (epoch == token) {
                            message = application.getString(R.string.unable_to_share_private_entry)
                        }
                    } finally {
                        if (epoch == token) busy = false
                    }
                }
            }
        }
    }

    /**
     * Clears the cached conversation share signature so newly registered devices or participants receive keys.
     */
    fun invalidateConversationSharedSync() {
        lastSyncedSharedSignature = null
    }

    /**
     * Ensures that newly added conversation participants (such as an escalated Admin or added friend) receive
     * wrapped keys for previously shared notes, incidents, and documents in the active conversation.
     *
     * @param conversation The chat and its current participants.
     * @param messages The loaded messages used to reconcile shared files and notes.
     */
    fun syncSharedItemsForConversation(
        conversation: ChatConversation,
        messages: List<ChatMessage>,
    ) {
        with(runtime) {
            if (localMode || conversation.closedAt != 0L || conversation.participantIds.size < 2) return
            val device = enrollment ?: return
            val repository = messagingRepository ?: return
            val otherUids = conversation.participantIds.filter { it != device.uid }
            if (otherUids.isEmpty()) return
            val shares = messages.asSequence()
                .filter { !it.unsent }
                .mapNotNull { msg ->
                    val parts = msg.body.split(":", limit = 3)
                    val kind = parts.firstOrNull().orEmpty()
                    val id = parts.getOrNull(1).orEmpty()
                    if (id.isNotBlank() && kind in setOf(
                            "SHARED_NOTE", "SHARED_INCIDENT", "SHARED_MEDIA", "SHARED_VOICE",
                        )) {
                        kind to id
                    } else null
                }
                .distinct()
                .toList()
            if (shares.isEmpty()) return
            val signature = "${conversation.id}|${otherUids.sorted().joinToString(",")}|" +
                shares.joinToString(",") { "${it.first}:${it.second}" }
            if (signature == lastSyncedSharedSignature) return
            val token = epoch
            scope.launch {
                try {
                    val usernames = if (conversation.type == "SUPPORT") {
                        conversation.participantUsernames +
                            runCatching { repository.prepareTicketParticipantNames(conversation.id) }
                                .getOrDefault(emptyMap())
                    } else conversation.participantUsernames
                    val noteIds = shares
                        .filter { it.first in setOf("SHARED_NOTE", "SHARED_INCIDENT") }
                        .map { it.second }.distinct()
                    val mediaIds = shares
                        .filter { it.first in setOf("SHARED_MEDIA", "SHARED_VOICE") }
                        .map { it.second }.distinct()
                    var allParticipantsResolved = true
                    if (noteIds.isNotEmpty() && secureDocumentRepository != null) {
                        val resolvedKeys = mutableMapOf<String, PublicKey>()
                        for (uid in otherUids) {
                            val username = usernames[uid]?.takeIf(String::isNotBlank)
                            val peer = username?.let {
                                runCatching {
                                    repository.searchUsers(it, device.uid).firstOrNull { u -> u.uid == uid }
                                }.getOrNull()
                            }
                            val key = peer?.publicKey?.takeIf(String::isNotBlank)?.let {
                                runCatching { KeyManager.decodePublicKey(it) }.getOrNull()
                            }
                            if (key != null) resolvedKeys[uid] = key else allParticipantsResolved = false
                        }
                        if (resolvedKeys.isNotEmpty()) {
                            withContext(Dispatchers.IO) {
                                for (entryId in noteIds) {
                                    runCatching {
                                        secureDocumentRepository?.ensureRecipientsHaveAccess(
                                            entryId, device.uid, resolvedKeys,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (mediaIds.isNotEmpty() && safetyDocumentRepository != null) {
                        val recipientNames = buildMap {
                            for (uid in otherUids) {
                                val username = usernames[uid]?.takeIf(String::isNotBlank)
                                if (username != null) put(uid, username) else allParticipantsResolved = false
                            }
                        }
                        if (recipientNames.isNotEmpty()) {
                            withContext(Dispatchers.IO) {
                                for (mediaId in mediaIds) {
                                    runCatching {
                                        safetyDocumentRepository?.ensureChatMediaAccess(
                                            mediaId, device.uid, recipientNames,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (allParticipantsResolved && epoch == token) {
                        lastSyncedSharedSignature = signature
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                }
            }
        }
    }
}
