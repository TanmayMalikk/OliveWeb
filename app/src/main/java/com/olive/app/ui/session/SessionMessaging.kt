// Contains the session messaging code for this part of Olive.
package com.olive.app.ui.session

import com.olive.app.ui.shared.*

import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.R

import androidx.core.content.edit
import com.olive.app.data.*
import com.olive.app.security.*
import com.olive.app.notification.LocalNotificationEventHandler
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Connects account messaging and manages live subscriptions.
internal class SessionMessaging(private val runtime: SessionRuntime) {
    // Makes sure messaging observation is ready.
    fun ensureMessagingObservation(showFeedback: Boolean = false) {
        with(runtime) {
            with(state) {
                if (socialSubscription != null || messagingBusy) return
                startMessaging(showFeedback)
            }
        }
    }

    // Starts messaging.
    fun startMessaging(showFeedback: Boolean = true) {
        with(runtime) {
            with(state) {
                stopMessaging(clearRows = false)
                val repository = messagingRepository
                val device = enrollment
                if (repository == null || device == null) {
                    if (showFeedback) message =
                        runtime.application.getString(R.string.messaging_needs_a_firebase_connected_build)
                    return
                }
                // A saved local account can outlive Firebase's login; reconnect it before touching private friend data.
                val expectedUsername = InputPolicy.username(coverUsername)
                if (accounts.authenticatedUid() != device.uid || accounts.authenticatedUsername() != expectedUsername) {
                    messagingNeedsReconnect = true
                    messagingBusy = false
                    if (showFeedback) message =
                        runtime.application.getString(R.string.ui_enter_your_account_password_to_reconnect_friends_and_messages)
                    return
                }
                messagingNeedsReconnect = false
                val identity = AccountIdentity(device.uid, coverUsername)
                LocalNotificationEventHandler.pauseForegroundListeners()
                val token = epoch
                messagingBusy = true
                if (showFeedback) message = null
                scope.launch {
                    try {
                        registerMessagingIdentity(identity)
                        if (epoch != token || screen != SessionScreen.Safe) return@launch
                        socialSubscription = repository.observeSocial(device.uid, { social ->
                            if (epoch == token && screen == SessionScreen.Safe) {
                                friends = social.friends
                                incomingFriendRequests = social.incoming
                                outgoingFriendRequests = social.outgoing
                                observeFriendConversations(social.friends, token)
                                messagingBusy = false
                            }
                        }, { error ->
                            if (epoch == token) {
                                messagingBusy = false
                                if (showFeedback || page == SafePage.Messages) message =
                                    runtime.application.getString(
                                        R.string.ui_unable_to_load_friend_information_value,
                                        error
                                    )
                            }
                        })
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (epoch == token) {
                            messagingBusy = false
                            if (error is FirebaseFirestoreException && error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                                messagingNeedsReconnect = true
                                if (showFeedback || page == SafePage.Messages)
                                    message =
                                        runtime.application.getString(R.string.ui_firebase_needs_your_account_password_again_before_friends_and_mes)
                            } else if (showFeedback || page == SafePage.Messages)
                                message =
                                    runtime.application.getString(R.string.ui_unable_to_start_messaging)
                        }
                    }
                }
            }
        }
    }

    fun reconnectMessaging(password: String, onConnected: () -> Unit = {}) {
        with(runtime) {
            with(state) {
                val device = enrollment ?: return
                if (localMode || messagingBusy || password.isBlank() || screen != SessionScreen.Safe || page != SafePage.Messages) return
                val token = epoch
                messagingBusy = true
                message = null
                scope.launch {
                    var connected = false
                    try {
                        val identity = accounts.authenticate(coverUsername, password, accountRole)
                        check(identity.uid == device.uid) { "This device is linked to an older account. Sign out, then sign in again." }
                        connected = true
                        messagingNeedsReconnect = false
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        if (epoch == token) {
                            messagingNeedsReconnect = true
                            message =
                                runtime.application.getString(R.string.ui_could_not_reconnect_check_your_password_and_try_again)
                        }
                    } finally {
                        if (epoch == token) messagingBusy = false
                    }
                    if (connected && epoch == token && screen == SessionScreen.Safe && page == SafePage.Messages) {
                        onConnected()
                        startMessaging(showFeedback = true)
                    }
                }
            }
        }
    }

    // Watches friend conversations for changes.
    fun observeFriendConversations(currentFriends: List<Friend>, token: Long) {
        with(runtime) {
            with(state) {
                val ids = currentFriends.map { it.friendshipId }.toSet()
                if (ids == observedFriendshipIds) return
                observedFriendshipIds = ids
                conversationSubscription?.close()
                conversationSubscription =
                    messagingRepository?.observeConversations(deviceUid(), ids.toList(), { rows ->
                        if (epoch == token && screen == SessionScreen.Safe) {
                            if (conversationLoadFailed) message = null
                            conversationLoadFailed = false
                            conversations = rows
                            activeConversation?.let { active ->
                                rows.firstOrNull { it.id == active.id }?.let { updated ->
                                    activeConversation = updated
                                    conversationActions.markConversationRead(updated)
                                }
                            }
                        }
                    }, { error ->
                        if (epoch == token && page == SafePage.Messages) {
                            conversationLoadFailed = true
                            message = runtime.application.getString(
                                R.string.ui_unable_to_load_messages_value,
                                error
                            )
                        }
                    })
            }
        }
    }

    // Runs messaging action.
    fun runMessagingAction(
        failure: String,
        block: suspend (MessagingRepository, DeviceEnrollment, Long) -> Unit
    ) {
        with(runtime) {
            with(state) {
                val repository = messagingRepository ?: run {
                    message =
                        runtime.application.getString(R.string.messaging_needs_a_firebase_connected_build)
                    return
                }
                val device = enrollment ?: return
                if (messagingBusy || messagingNeedsReconnect || screen != SessionScreen.Safe || page != SafePage.Messages) return
                val token = epoch
                messagingBusy = true
                message = null
                scope.launch {
                    try {
                        block(repository, device, token)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (epoch == token) {
                            message =
                                if (error is FirebaseFirestoreException && error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
                                    "$failure Firebase denied this action. Refresh the page and try again."
                                else error.localizedMessage ?: failure
                        }
                    } finally {
                        if (epoch == token) messagingBusy = false
                    }
                }
            }
        }
    }

    // Stops messaging.
    fun stopMessaging(clearRows: Boolean = true) {
        with(runtime) {
            with(state) {
                socialSubscription?.close()
                socialSubscription = null
                conversationSubscription?.close()
                conversationSubscription = null
                observedFriendshipIds = emptySet()
                conversationLoadFailed = false
                messageSubscription?.close()
                messageSubscription = null
                activeConversation = null
                chatMessages = emptyList()
                messagingBusy = false
                messagingNeedsReconnect = false
                if (clearRows) {
                    userSearchResults = emptyList()
                    friends = emptyList()
                    incomingFriendRequests =
                        emptyList()
                    outgoingFriendRequests = emptyList()
                    conversations = emptyList()
                }
            }
        }
    }

    private fun deviceUid() = runtime.enrollment?.uid ?: error("No active account")

    suspend fun registerMessagingIdentity(identity: AccountIdentity) {
        with(runtime) {
            val publicKey = withContext(Dispatchers.Default) { KeyManager.getOrCreatePublicKey() }
            val marker = messagingIdentityMarker(identity, publicKey.encoded)
            val savedMarker = cloudState.getString("registered_identity", null)
            if (verifiedMessagingIdentityMarker == marker &&
                !shouldRegisterMessagingIdentity(savedMarker, identity, publicKey.encoded)
            ) return
            messagingRepository?.registerUser(identity, publicKey)
            cloudState.edit { putString("registered_identity", marker) }
            verifiedMessagingIdentityMarker = marker
        }
    }

    // Syncs local notification handler.
    fun syncLocalNotificationHandler() {
        with(runtime) {
            with(state) {
                val device = enrollment ?: return
                if (localMode) return
                if (accounts.authenticatedUid() == device.uid)
                    LocalNotificationEventHandler.start(application, coverType, accountRole)
            }
        }
    }
}
