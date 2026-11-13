// Signed-in users: controls who can receive shared safety items.
package com.olive.app.ui.session

import com.olive.app.R
import com.olive.app.data.account.AccountIdentity
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.ChatMessage
import com.olive.app.data.account.PrivateEntry
import com.olive.app.data.account.PrivateEntryKind
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.ui.users.user.location.LocationMapConfiguration
import com.olive.app.ui.users.user.location.LocationMapController
import com.olive.app.security.KeyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.PublicKey
import com.olive.app.ui.users.shared.navigation.*
import android.app.Application
import androidx.compose.runtime.*
import com.olive.app.data.account.AccountRole
import com.olive.app.data.messaging.Friend
import com.olive.app.data.messaging.MessageSendWorker
import com.olive.app.data.messaging.OutgoingAttachment
import com.olive.app.data.messaging.isDurableChatAttachment
import com.olive.app.data.messaging.outgoingRoomKey
import com.olive.app.data.messaging.prepareOutgoingMessage
import com.olive.app.security.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope


/** Coordinates encrypted conversations and private-entry sharing without exposing keys to UI code. */
internal class SessionSecureSharing(private val runtime: SessionRuntime) {
    // Keeps the saved room key name in one place for the signed-in session.
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

    // Shares the selected item for session secure sharing.
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
                        val peers = conversation.participantIds.filter { it != device.uid }.map { uid ->
                            val name = conversation.participantUsernames[uid]
                                ?: error("Participant unavailable")
                            repository.searchUsers(name, device.uid).firstOrNull()
                                ?: error("Recipient unavailable")
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

    // Shares the selected item for session secure sharing.
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
                        if (epoch == token) message =
                            runtime.application.getString(R.string.message_delivery_queued)
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

    // Sends safety plan for session secure sharing.
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

    // Session secure sharing: Sends live location.
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

    // Opens the requested item for session secure sharing.
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

    // Removes if shared from the signed-in session.
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

    // Session secure sharing: Closes the active feature.
    fun close() {
        runtime.state.viewedSharedEntry = null
    }
}
