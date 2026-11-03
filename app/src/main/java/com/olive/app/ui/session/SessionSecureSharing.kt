// Signed-in users: controls who can receive shared safety items.
package com.olive.app.ui.session

import com.olive.app.R
import com.olive.app.data.AccountIdentity
import com.olive.app.data.ChatConversation
import com.olive.app.data.ChatMessage
import com.olive.app.data.PrivateEntry
import com.olive.app.data.PrivateEntryKind
import com.olive.app.data.SafetyDocument
import com.olive.app.ui.users.user.location.LocationMapConfiguration
import com.olive.app.ui.users.user.location.LocationMapController
import com.olive.app.security.KeyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.PublicKey

/** Coordinates encrypted conversations and private-entry sharing without exposing keys to UI code. */
internal class SessionSecureSharing(private val runtime: SessionRuntime) {
    // Keeps the saved room key name in one place for the signed-in session.
    suspend fun roomKey(conversation: ChatConversation): javax.crypto.SecretKey {
        with(runtime) {
            val device = enrollment ?: error("No active account")
            val repository = messagingRepository ?: error("Messaging unavailable")
            val secure = secureChatRepository ?: error("Secure messaging unavailable")
            // Use the fallback when room key is unavailable.
            try {
                val peers = conversation.participantIds.filter { it != device.uid }.map { uid ->
                    val username = conversation.participantUsernames[uid]
                        ?: error("Conversation participant unavailable")
                    repository.searchUsers(username, device.uid).firstOrNull()
                        ?: error("Every participant needs to open the updated app before secure messages can be sent.")
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
                return secure.ensureRoomKey(conversation, device.uid, keys)
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
                            entry.id,
                            device.uid,
                            entry.title,
                            entry.body,
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
                val documents = safetyDocumentRepository ?: return
                val chat = secureChatRepository ?: return
                if (messagingBusy || document.ownerUid != device.uid) return
                val token = epoch
                messagingBusy = true
                message = null
                scope.launch {
                    val newlyGrantedRecipients = mutableListOf<String>()
                    // Turn an unexpected share failure into a readable message.
                    try {
                        conversation.participantIds.filter { it != device.uid }.forEach { recipient ->
                            if (recipient !in document.allowedUsers) newlyGrantedRecipients += recipient
                            val username = conversation.participantUsernames[recipient]
                                ?: error("Recipient username is unavailable")
                            documents.grantAccess(document, device.uid, recipient, username)
                        }
                        chat.send(
                            conversation.id, AccountIdentity(device.uid, coverUsername),
                            "SHARED_MEDIA:${document.id}:${document.name}", roomKey(conversation)
                        )
                        if (epoch == token) message =
                            runtime.application.getString(R.string.documents_shared_in_chat)
                    } catch (error: CancellationException) {
                        newlyGrantedRecipients.forEach { recipient ->
                            runCatching {
                                documents.revokeAccess(document.id, device.uid, recipient)
                            }
                        }
                        throw error
                    } catch (_: Exception) { // Revoke any access granted before a later recipient or chat update failed.
                        newlyGrantedRecipients.forEach { recipient ->
                            runCatching {
                                documents.revokeAccess(document.id, device.uid, recipient)
                            }
                        }
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
                if (accountRole != com.olive.app.data.AccountRole.USER || messagingBusy ||
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
                if (profile.role != com.olive.app.data.AccountRole.USER || messagingBusy) return
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
                    onError = { message = it })
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
