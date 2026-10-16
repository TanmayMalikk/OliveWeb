package com.olive.app.ui.session

import com.olive.app.data.*
import com.olive.app.ui.*

// Searches the directory and manages friend requests.
internal class FriendActions(private val runtime:SessionRuntime) {
    fun searchUsers(query: String) {
        with(runtime) {
            with(state) {
                return messaging.runMessagingAction("Unable to search for users.") { repository,device,token ->
                        val rows=repository.searchUsers(query,device.uid)
                        if(epoch==token) {
                            userSearchResults=rows
                            message=if(rows.isEmpty()) "No user found for that exact username." else null
                        }
                    }
            }
        }
    }

    fun relationshipFor(uid: String) : FriendRelationship {
        with(runtime) {
            with(state) {
                return when {
                        friends.any { it.uid==uid } -> FriendRelationship.Friends
                        incomingFriendRequests.any { it.senderUid==uid } -> FriendRelationship.IncomingRequest
                        outgoingFriendRequests.any { it.recipientUid==uid } -> FriendRelationship.RequestSent
                        else -> FriendRelationship.AddFriend
                    }
            }
        }
    }

    fun sendFriendRequest(user: DirectoryUser) {
        with(runtime) {
            with(state) {
                return messaging.runMessagingAction("Unable to send this friend request.") { repository,device,_ ->
                        repository.sendFriendRequest(AccountIdentity(device.uid,coverUsername),user)
                    }
            }
        }
    }

    fun acceptFriendRequest(request: FriendRequest) {
        with(runtime) {
            with(state) {
                return messaging.runMessagingAction("Unable to accept this friend request.") { repository,device,_ ->
                        repository.acceptRequest(AccountIdentity(device.uid,coverUsername),request)
                    }
            }
        }
    }

    fun declineFriendRequest(request: FriendRequest) {
        with(runtime) {
            with(state) {
                return messaging.runMessagingAction("Unable to decline this friend request.") { repository,device,_ ->
                        repository.declineRequest(device.uid,request)
                    }
            }
        }
    }

    fun cancelFriendRequest(request: FriendRequest) {
        with(runtime) {
            with(state) {
                return messaging.runMessagingAction("Unable to cancel this friend request.") { repository,device,_ ->
                        repository.cancelRequest(device.uid,request)
                    }
            }
        }
    }

    fun startConversation(friend: Friend) {
        with(runtime) {
            with(state) {
                return messaging.runMessagingAction("Unable to open this conversation.") { repository,device,token ->
                        val conversation=repository.conversationWith(AccountIdentity(device.uid,coverUsername),friend)
                        if(epoch==token && screen==SessionScreen.Safe && page==SafePage.Messages) conversationActions.openConversation(conversation)
                    }
            }
        }
    }
}
