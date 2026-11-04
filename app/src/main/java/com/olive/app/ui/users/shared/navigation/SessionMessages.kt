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

    // Starts chat listeners when the current screen needs messaging.
    fun ensureMessagingObservation(showFeedback: Boolean = false) =
        runtime.messaging.ensureMessagingObservation(showFeedback)

    // Reconnects chat listeners after a network or account change.
    fun reconnectMessaging(password: String, onConnected: () -> Unit = {}) =
        runtime.messaging.reconnectMessaging(password, onConnected)

    // Searches the account directory for an exact username.
    fun searchUsers(query: String) = runtime.friendActions.searchUsers(query)
    // Returns the current friend relationship with this account.
    fun relationshipFor(uid: String): FriendRelationship = runtime.friendActions.relationshipFor(uid)
    // Sends a friend request to the selected account.
    fun sendFriendRequest(user: DirectoryUser) = runtime.friendActions.sendFriendRequest(user)
    // Accepts an incoming friend request.
    fun acceptFriendRequest(request: FriendRequest) = runtime.friendActions.acceptFriendRequest(request)
    // Declines an incoming friend request.
    fun declineFriendRequest(request: FriendRequest) = runtime.friendActions.declineFriendRequest(request)
    // Returns true when the outgoing friend request may still be cancelled.
    fun cancelFriendRequest(request: FriendRequest) = runtime.friendActions.cancelFriendRequest(request)
    // Opens an existing conversation by its ID.
    fun startConversation(friend: Friend) = runtime.friendActions.startConversation(friend)

    // Creates or opens a direct conversation with this account.
    fun openConversation(conversation: ChatConversation) =
        runtime.conversationActions.openConversation(conversation)

    // Closes the current conversation and returns to the chat list.
    fun closeConversation() = runtime.conversationActions.closeConversation()
    // Archives or restores the selected conversation for this account.
    fun setConversationArchived(conversation: ChatConversation, archived: Boolean) =
        runtime.conversationActions.setArchived(conversation, archived)

    // Saves a private note about this friend or supported user.
    fun appendPrivateContactNote(target: DirectoryUser, text: String, onSaved: () -> Unit = {}) =
        runtime.conversationActions.appendPrivateNote(target, text, onSaved)

    // Encrypts and sends the typed message to the current conversation.
    fun sendMessage(text: String, onSent: () -> Unit = {}) =
        runtime.conversationActions.sendMessage(text, onSent)

    // Retries the failed encrypted queue head without bypassing message order.
    fun retryMessage(chat: ChatMessage) = runtime.conversationActions.retryMessage(chat)
    // Un-sends a message when the signed-in account is allowed to remove it.
    fun unsendMessage(chat: ChatMessage) = runtime.conversationActions.unsendMessage(chat)
    // Dismisses the quick-exit tip shown inside a chat.
    fun dismissChatQuickExitHint() = runtime.conversationActions.dismissChatQuickExitHint()

    // Encrypts a private note and shares it with the current chat.
    fun sharePrivateEntryInChat(entry: PrivateEntry) = runtime.secureSharing.share(entry)
    // Gives the current chat encrypted access to a safety document.
    fun shareSafetyDocumentInChat(document: SafetyDocument) = runtime.secureSharing.share(document)
    // Shares the safety plan and starts a separate plan-consult ticket.
    fun shareSafetyPlanInChat() = runtime.secureSharing.shareSafetyPlan()
    // Opens a private note that was shared in this conversation.
    fun openSharedPrivateEntry(entryId: String) = runtime.secureSharing.open(entryId)
    // Closes the shared-note viewer and releases its live listener.
    fun closeSharedPrivateEntry() = runtime.secureSharing.close()

    // Escalates this support ticket by adding an available admin.
    fun requestAdminHelp(conversationId: String) = runtime.staffSupport.requestAdmin(conversationId)
    // Closes the support ticket and archives it for every participant.
    fun closeSupportChat(conversationId: String) = runtime.staffSupport.close(conversationId)
}
