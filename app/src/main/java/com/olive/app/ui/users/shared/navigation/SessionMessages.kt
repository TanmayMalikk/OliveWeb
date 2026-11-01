// Users, safe contacts, staff, and admins: keeps message screen state and chat actions together.
package com.olive.app.ui.users.shared.navigation

import com.olive.app.data.ChatConversation
import com.olive.app.data.ChatMessage
import com.olive.app.data.DirectoryUser
import com.olive.app.data.Friend
import com.olive.app.data.FriendRelationship
import com.olive.app.data.FriendRequest
import com.olive.app.data.PrivateEntry
import com.olive.app.data.SafetyDocument
import com.olive.app.ui.session.SessionRuntime

internal class SessionMessages(private val runtime: SessionRuntime) {
    val userSearchResults get() = runtime.state.userSearchResults
    val friends get() = runtime.state.friends
    val incomingFriendRequests get() = runtime.state.incomingFriendRequests
    val outgoingFriendRequests get() = runtime.state.outgoingFriendRequests
    val conversations get() = runtime.state.conversations
    val archivedConversationIds get() = runtime.state.archivedConversationIds
    val privateContactNotes get() = runtime.state.privateContactNotes
    val activeConversation get() = runtime.state.activeConversation
    val chatQuickExitHintVisible get() = runtime.state.chatQuickExitHintVisible
    val chatMessages get() = runtime.state.chatMessages
    val busy get() = runtime.state.messagingBusy
    val needsReconnect get() = runtime.state.messagingNeedsReconnect
    val pendingActionCount get() = runtime.pendingMessageActionCount

    fun ensureMessagingObservation(showFeedback: Boolean = false) =
        runtime.messaging.ensureMessagingObservation(showFeedback)

    fun reconnectMessaging(password: String, onConnected: () -> Unit = {}) =
        runtime.messaging.reconnectMessaging(password, onConnected)

    fun searchUsers(query: String) = runtime.friendActions.searchUsers(query)
    fun relationshipFor(uid: String): FriendRelationship = runtime.friendActions.relationshipFor(uid)
    fun sendFriendRequest(user: DirectoryUser) = runtime.friendActions.sendFriendRequest(user)
    fun acceptFriendRequest(request: FriendRequest) = runtime.friendActions.acceptFriendRequest(request)
    fun declineFriendRequest(request: FriendRequest) = runtime.friendActions.declineFriendRequest(request)
    fun cancelFriendRequest(request: FriendRequest) = runtime.friendActions.cancelFriendRequest(request)
    fun startConversation(friend: Friend) = runtime.friendActions.startConversation(friend)

    fun openConversation(conversation: ChatConversation) =
        runtime.conversationActions.openConversation(conversation)

    fun closeConversation() = runtime.conversationActions.closeConversation()
    fun setConversationArchived(conversation: ChatConversation, archived: Boolean) =
        runtime.conversationActions.setArchived(conversation, archived)

    fun appendPrivateContactNote(target: DirectoryUser, text: String, onSaved: () -> Unit = {}) =
        runtime.conversationActions.appendPrivateNote(target, text, onSaved)

    fun sendMessage(text: String, onSent: () -> Unit = {}) =
        runtime.conversationActions.sendMessage(text, onSent)

    fun retryMessage(chat: ChatMessage) = runtime.conversationActions.retryMessage(chat)
    fun unsendMessage(chat: ChatMessage) = runtime.conversationActions.unsendMessage(chat)
    fun dismissChatQuickExitHint() = runtime.conversationActions.dismissChatQuickExitHint()

    fun sharePrivateEntryInChat(entry: PrivateEntry) = runtime.secureSharing.share(entry)
    fun shareSafetyDocumentInChat(document: SafetyDocument) = runtime.secureSharing.share(document)
    fun shareSafetyPlanInChat() = runtime.secureSharing.shareSafetyPlan()
    fun openSharedPrivateEntry(entryId: String) = runtime.secureSharing.open(entryId)
    fun closeSharedPrivateEntry() = runtime.secureSharing.close()

    fun requestAdminHelp(conversationId: String) = runtime.staffSupport.requestAdmin(conversationId)
    fun closeSupportChat(conversationId: String) = runtime.staffSupport.close(conversationId)
}
