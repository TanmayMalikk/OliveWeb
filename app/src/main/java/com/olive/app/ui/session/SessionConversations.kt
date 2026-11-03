// Signed-in users: opens and closes direct and support chats.
package com.olive.app.ui.session

import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.R
import android.util.Log

import androidx.core.content.edit
import com.olive.app.data.*
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

// Owns conversation selection, messages and read markers.
internal class SessionConversations(private val runtime: SessionRuntime) {
    // Starts conversation for session conversations.
    fun openConversation(conversation: ChatConversation, preserveMessages: Boolean = false) {
        with(runtime) {
            with(state) {
                if (!((screen == SessionScreen.Safe && page == SafePage.Messages) ||
                            (screen == SessionScreen.Portal && accountRole in setOf(AccountRole.STAFF, AccountRole.ADMIN)))) return
                val uid = enrollment?.uid ?: return
                if (uid !in conversation.participantIds) return
                messageSubscription?.close()
                val sameConversation = activeConversation?.id == conversation.id
                if (!preserveMessages || !sameConversation) chatMessages = emptyList()
                activeConversation =
                    conversation
                message = null
                conversationActions.markConversationRead(conversation)
                chatQuickExitHintVisible =
                    accountRole == AccountRole.USER && !chatQuickExitHintShown
                if (chatQuickExitHintVisible) chatQuickExitHintShown = true
                val token = epoch
                messagingBusy = true
                scope.launch {
                    // A open conversation error is handled here instead of closing the app.
                    try {
                        val key = secureSharing.roomKey(conversation)
                        if (epoch != token || activeConversation?.id != conversation.id) return@launch
                        messageSubscription =
                            secureChatRepository?.observeMessages(conversation.id, key, { rows ->
                                if (epoch == token && activeConversation?.id == conversation.id) chatMessages =
                                    rows
                            }, { error ->
                                if (epoch == token) message = runtime.application.getString(
                                    R.string.ui_unable_to_load_this_conversation_value, error
                                )
                            })
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) { // Do not expose key details; show the safe conversation error when encrypted room setup fails.
                        if (epoch == token) message =
                            runtime.application.getString(R.string.unable_to_open_secure_conversation)
                    } finally {
                        if (epoch == token) {
                            messagingBusy = false
                            val updated = activeConversation
                            if (messageSubscription == null && updated?.id == conversation.id &&
                                updated.updatedAt != conversation.updatedAt) {
                                openConversation(updated)
                            }
                        }
                    }
                }
            }
        }
    }

    // Session conversations: Stops conversation. It closes the active subscription or resource.
    fun closeConversation() {
        with(runtime) {
            with(state) {
                messageSubscription?.close()
                messageSubscription = null
                activeConversation = null
                chatMessages = emptyList()
                message = null
            }
        }
    }

    // Sending a message: check the active chat, encrypt for its room, then report success to the composer.
    fun sendMessage(text: String, onSent: () -> Unit = {}) {
        val state = runtime.state
        val repository = runtime.secureChatRepository ?: return
        val device = runtime.enrollment ?: return
        val conversation = state.activeConversation ?: return
        if (state.messagingBusy) return

        val sessionVersion = runtime.epoch
        state.messagingBusy = true
        state.message = null
        runtime.scope.launch {
            // If send message fails, catch the error so the app can keep running and explain the problem.
            try {
                val roomKey = runtime.secureSharing.roomKey(conversation)
                repository.send(
                    conversation.id,
                    AccountIdentity(device.uid, state.coverUsername),
                    text,
                    roomKey,
                )
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

    // Session conversations: Sends failure message. It reads or saves the matching protected record in Firebase.
    private fun sendFailureMessage(failure: Exception): Int =
        if (failure is FirebaseFirestoreException &&
            failure.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
        ) R.string.messaging_send_denied else R.string.ui_unable_to_send_the_message

    // returns the unsend message for session conversations. It checks the chat and uses its secure data before reading or saving a message.
    fun unsendMessage(chat: ChatMessage) {
        with(runtime) {
            with(state) {
                val conversation = activeConversation ?: return
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

    // Session conversations: Updates archived.
    fun setArchived(conversation: ChatConversation, archived: Boolean) {
        with(runtime) {
            val uid = enrollment?.uid ?: return
            if (uid !in conversation.participantIds) return
            val previous = state.archivedConversationIds
            state.archivedConversationIds = if (archived) {
                previous + conversation.id
            } else {
                previous - conversation.id
            }
            scope.launch {
                // If set archived fails, catch the error so the app can keep running and explain the problem.
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

    // returns the append private note for session conversations.
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
                // A append private note error is handled here instead of closing the app.
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

    // returns the mark conversation read for session conversations. It checks the chat and uses its secure data before reading or saving a message.
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
