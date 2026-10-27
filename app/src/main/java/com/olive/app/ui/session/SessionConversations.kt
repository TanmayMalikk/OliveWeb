// Contains the session conversations code for this part of Olive.
package com.olive.app.ui.session

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.R

import androidx.core.content.edit
import com.olive.app.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

// Owns conversation selection, messages and read markers.
internal class SessionConversations(private val runtime: SessionRuntime) {
    // Opens conversation.
    fun openConversation(conversation: ChatConversation) {
        with(runtime) {
            with(state) {
                if (!((screen == SessionScreen.Safe && page == SafePage.Messages) ||
                            (screen == SessionScreen.Portal && accountRole in setOf(AccountRole.STAFF, AccountRole.ADMIN)))) return
                val uid = enrollment?.uid ?: return
                if (uid !in conversation.participantIds) return
                messageSubscription?.close()
                chatMessages = emptyList()
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
                    } catch (_: Exception) {
                        if (epoch == token) message =
                            runtime.application.getString(R.string.unable_to_open_secure_conversation)
                    } finally {
                        if (epoch == token) messagingBusy = false
                    }
                }
            }
        }
    }

    // Closes conversation.
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

    // Sends message.
    fun sendMessage(text: String, onSent: () -> Unit = {}) {
        with(runtime) {
            with(state) {
                val repository = secureChatRepository ?: return
                val device = enrollment ?: return
                val conversation = activeConversation ?: return
                if (messagingBusy) return
                val token = epoch
                messagingBusy = true
                message = null
                scope.launch {
                    try {
                        val key = secureSharing.roomKey(conversation)
                        repository.send(
                            conversation.id,
                            AccountIdentity(device.uid, coverUsername),
                            text,
                            key
                        )
                        if (epoch == token && activeConversation?.id == conversation.id) onSent()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        if (epoch == token) message =
                            runtime.application.getString(R.string.ui_unable_to_send_the_message)
                    } finally {
                        if (epoch == token) messagingBusy = false
                    }
                }
            }
        }
    }

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

    fun dismissChatQuickExitHint() {
        with(runtime) {
            with(state) {
                chatQuickExitHintVisible = false
            }
        }
    }

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
                try {
                    withContext(Dispatchers.IO) {
                        store.setConversationArchived(uid, conversation.id, archived)
                    }
                } catch (_: Exception) {
                    state.archivedConversationIds = previous
                    state.message = runtime.application.getString(R.string.unable_to_update_archived_chat)
                }
            }
        }
    }

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

    // Reads message key.
    fun readMessageKey(uid: String, conversationId: String) = "read_message_${uid}_$conversationId"

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
