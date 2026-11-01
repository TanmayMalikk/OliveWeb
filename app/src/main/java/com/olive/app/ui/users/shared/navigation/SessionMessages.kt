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

    // returns the ensure messaging observation for shared safe app session messages.
    fun ensureMessagingObservation(showFeedback: Boolean = false) =
        runtime.messaging.ensureMessagingObservation(showFeedback)

    // returns the reconnect messaging for shared safe app session messages.
    fun reconnectMessaging(password: String, onConnected: () -> Unit = {}) =
        runtime.messaging.reconnectMessaging(password, onConnected)

    // Finds users for shared safe app session messages.
    fun searchUsers(query: String) = runtime.friendActions.searchUsers(query)
    // Shared safe app session messages: Finds the relationship with the selected account.
    fun relationshipFor(uid: String): FriendRelationship = runtime.friendActions.relationshipFor(uid)
    // Sends friend request for shared safe app session messages.
    fun sendFriendRequest(user: DirectoryUser) = runtime.friendActions.sendFriendRequest(user)
    // Shared safe app session messages: Accepts friend request.
    fun acceptFriendRequest(request: FriendRequest) = runtime.friendActions.acceptFriendRequest(request)
    // Declines friend request for shared safe app session messages.
    fun declineFriendRequest(request: FriendRequest) = runtime.friendActions.declineFriendRequest(request)
    // Shared safe app session messages: returns the cancel friend request.
    fun cancelFriendRequest(request: FriendRequest) = runtime.friendActions.cancelFriendRequest(request)
    // Starts conversation for shared safe app session messages.
    fun startConversation(friend: Friend) = runtime.friendActions.startConversation(friend)

    // Shared safe app session messages: Starts conversation.
    fun openConversation(conversation: ChatConversation) =
        runtime.conversationActions.openConversation(conversation)

    // Shared safe app session messages: Stops conversation.
    fun closeConversation() = runtime.conversationActions.closeConversation()
    // Updates conversation archived for shared safe app session messages.
    fun setConversationArchived(conversation: ChatConversation, archived: Boolean) =
        runtime.conversationActions.setArchived(conversation, archived)

    // returns the append private contact note for shared safe app session messages. It checks the chat and uses its secure data before reading or saving a message.
    fun appendPrivateContactNote(target: DirectoryUser, text: String, onSaved: () -> Unit = {}) =
        runtime.conversationActions.appendPrivateNote(target, text, onSaved)

    // Sends message for shared safe app session messages.
    fun sendMessage(text: String, onSent: () -> Unit = {}) =
        runtime.conversationActions.sendMessage(text, onSent)

    // returns the unsend message for shared safe app session messages.
    fun unsendMessage(chat: ChatMessage) = runtime.conversationActions.unsendMessage(chat)
    // Shared safe app session messages: Stops chat quick exit hint.
    fun dismissChatQuickExitHint() = runtime.conversationActions.dismissChatQuickExitHint()

    // Shared safe app session messages: Sends private entry in chat. It checks the chat and uses its secure data before reading or saving a message.
    fun sharePrivateEntryInChat(entry: PrivateEntry) = runtime.secureSharing.share(entry)
    // Sends safety document in chat for shared safe app session messages. It checks the chat and uses its secure data before reading or saving a message.
    fun shareSafetyDocumentInChat(document: SafetyDocument) = runtime.secureSharing.share(document)
    // Shared safe app session messages: Sends safety plan in chat. It checks the chat and uses its secure data before reading or saving a message.
    fun shareSafetyPlanInChat() = runtime.secureSharing.shareSafetyPlan()
    // Starts shared private entry for shared safe app session messages. It checks the chat and uses its secure data before reading or saving a message.
    fun openSharedPrivateEntry(entryId: String) = runtime.secureSharing.open(entryId)
    // Shared safe app session messages: Stops shared private entry. It closes the active subscription or resource.
    fun closeSharedPrivateEntry() = runtime.secureSharing.close()

    // Requests admin help for shared safe app session messages. It checks the chat and uses its secure data before reading or saving a message.
    fun requestAdminHelp(conversationId: String) = runtime.staffSupport.requestAdmin(conversationId)
    // Shared safe app session messages: Stops support chat. It closes the active subscription or resource.
    fun closeSupportChat(conversationId: String) = runtime.staffSupport.close(conversationId)
}
