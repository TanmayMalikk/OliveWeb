package com.olive.app.ui.session
import com.olive.app.R

import androidx.core.content.edit
import com.olive.app.data.*
import com.olive.app.ui.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

// Owns conversation selection, messages and read markers.
internal class SessionConversations(private val runtime:SessionRuntime) {
    fun openConversation(conversation: ChatConversation) {
        with(runtime) {
            with(state) {
                if(screen!=SessionScreen.Safe || page!=SafePage.Messages) return
                val uid=enrollment?.uid ?: return
                if(uid !in conversation.participantIds) return
                messageSubscription?.close();chatMessages=emptyList();activeConversation=conversation;message=null
                conversationActions.markConversationRead(conversation)
                chatQuickExitHintVisible=accountRole==AccountRole.USER
                val token=epoch
                messagingBusy=true
                scope.launch {
                    try {
                        val key=secureSharing.roomKey(conversation)
                        if(epoch!=token || activeConversation?.id!=conversation.id) return@launch
                        messageSubscription=secureChatRepository?.observeMessages(conversation.id,key,{ rows ->
                            if(epoch==token && activeConversation?.id==conversation.id) chatMessages=rows
                        },{ error -> if(epoch==token) message=runtime.application.getString(
                            R.string.ui_unable_to_load_this_conversation_value,error) })
                    } catch(error:CancellationException) { throw error }
                    catch(_:Exception) { if(epoch==token) message=runtime.application.getString(R.string.unable_to_open_secure_conversation) }
                    finally { if(epoch==token) messagingBusy=false }
                }
            }
        }
    }

    fun closeConversation() {
        with(runtime) {
            with(state) {
                messageSubscription?.close();messageSubscription=null
                activeConversation=null;chatMessages=emptyList();message=null
            }
        }
    }

    fun sendMessage(text: String, onSent: ()->Unit = {}) {
        with(runtime) {
            with(state) {
                val repository=secureChatRepository ?: return
                val device=enrollment ?: return
                val conversation=activeConversation ?: return
                if(messagingBusy) return
                val token=epoch
                messagingBusy=true;message=null
                scope.launch {
                    try {
                        val key=secureSharing.roomKey(conversation)
                        repository.send(conversation.id,AccountIdentity(device.uid,coverUsername),text,key)
                        if(epoch==token && activeConversation?.id==conversation.id) onSent()
                    }
                    catch(error: CancellationException) { throw error }
                    catch(error: Exception) { if(epoch==token) message=runtime.application.getString(R.string.ui_unable_to_send_the_message) }
                    finally { if(epoch==token) messagingBusy=false }
                }
            }
        }
    }

    fun unsendMessage(chat: ChatMessage) {
        with(runtime) {
            with(state) {
                val conversation=activeConversation ?: return
                messaging.runMessagingAction("Unable to unsend this message.") { repository,device,_ ->
                    secureSharing.revokeIfShared(chat,conversation,device.uid)
                    secureChatRepository?.unsend(conversation.id,chat,device.uid)
                        ?: repository.unsend(conversation.id,chat,device.uid)
                }
            }
        }
    }

    fun dismissChatQuickExitHint() {
        with(runtime) {
            with(state) {
                chatQuickExitHintVisible=false
            }
        }
    }

    fun readMessageKey(uid:String,conversationId:String): String {
        with(runtime) {
            with(state) {
                return "read_message_${uid}_$conversationId"
            }
        }
    }

    fun markConversationRead(conversation:ChatConversation) {
        with(runtime) {
            with(state) {
                val uid=enrollment?.uid ?: return
                if(conversation.lastMessageId.isBlank()) return
                val key=conversationActions.readMessageKey(uid,conversation.id)
                if(messageReadState.getString(key,null)==conversation.lastMessageId) return
                messageReadState.edit { putString(key,conversation.lastMessageId) }
                readMessageVersion++
            }
        }
    }
}
