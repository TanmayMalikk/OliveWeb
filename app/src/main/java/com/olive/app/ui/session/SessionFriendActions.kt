// Signed-in users: sends and accepts friend requests.
package com.olive.app.ui.session

import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.R

import com.olive.app.data.*

// Searches the directory and manages friend requests.
internal class FriendActions(private val runtime: SessionRuntime) {
    // Finds users for the signed-in session.
    fun searchUsers(query: String) {
        with(runtime) {
            with(state) {
                return messaging.runMessagingAction("Unable to search for users.") { repository, device, token ->
                    val rows = repository.searchUsers(query, device.uid)
                    if (epoch == token) {
                        userSearchResults = rows
                        message =
                            if (rows.isEmpty()) runtime.application.getString(R.string.no_user_found_for_that_exact_username) else null
                    }
                }
            }
        }
    }

    // Loads the current friend relationship before changing it.
    fun relationshipFor(uid: String): FriendRelationship {
        with(runtime) {
            with(state) {
                return when {
                    friends.any { it.uid == uid } -> FriendRelationship.Friends
                    incomingFriendRequests.any { it.senderUid == uid } -> FriendRelationship.IncomingRequest
                    outgoingFriendRequests.any { it.recipientUid == uid } -> FriendRelationship.RequestSent
                    else -> FriendRelationship.AddFriend
                }
            }
        }
    }

    // Sends a request to the selected directory account and refreshes the social list on success.
    fun sendFriendRequest(user: DirectoryUser) {
        with(runtime) {
            with(state) {
                return messaging.runMessagingAction("Unable to send this friend request.") { repository, device, _ ->
                    repository.sendFriendRequest(AccountIdentity(device.uid, coverUsername), user)
                }
            }
        }
    }

    // Session friend actions: Accepts friend request. It runs the repository request through the shared messaging error handler.
    fun acceptFriendRequest(request: FriendRequest) {
        with(runtime) {
            with(state) {
                return messaging.runMessagingAction("Unable to accept this friend request.") { repository, device, _ ->
                    repository.acceptRequest(AccountIdentity(device.uid, coverUsername), request)
                }
            }
        }
    }

    // Session friend actions: Declines friend request. It runs the repository request through the shared messaging error handler.
    fun declineFriendRequest(request: FriendRequest) {
        with(runtime) {
            return messaging.runMessagingAction("Unable to decline this friend request.") { repository, device, _ ->
                repository.declineRequest(device.uid, request)
            }
        }
    }

    // Cancels friend request.
    fun cancelFriendRequest(request: FriendRequest) {
        with(runtime) {
            return messaging.runMessagingAction("Unable to cancel this friend request.") { repository, device, _ ->
                repository.cancelRequest(device.uid, request)
            }
        }
    }

    // Creates or opens a direct message after confirming the friend relationship.
    fun startConversation(friend: Friend) {
        with(runtime) {
            with(state) {
                return messaging.runMessagingAction("Unable to open this conversation.") { repository, device, token ->
                    val conversation = repository.conversationWith(
                        AccountIdentity(device.uid, coverUsername),
                        friend
                    )
                    if (epoch == token && screen == SessionScreen.Safe && page == SafePage.Messages) conversationActions.openConversation(
                        conversation
                    )
                }
            }
        }
    }
}
