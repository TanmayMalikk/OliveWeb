// Signed-in users: loads and sends chat messages.
package com.olive.app.ui.session

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
    // Session messaging: Reports whether messaging screen active.
    private fun messagingScreenActive() = with(runtime.state) {
        screen == SessionScreen.Safe ||
                (screen == SessionScreen.Portal && accountRole in setOf(AccountRole.STAFF, AccountRole.ADMIN))
    }
    // Starts the account's friend and conversation listeners once the current screen may show messages.
    fun ensureMessagingObservation(showFeedback: Boolean = false) {
        with(runtime) {
            with(state) {
                if (socialSubscription != null || messagingBusy) return
                startMessaging(showFeedback)
            }
        }
    }

    // Starts messaging for session messaging. It waits for the set time and cancels the timer when the feature closes.
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
                    // A start messaging error is handled here instead of closing the app.
                    try {
                        registerMessagingIdentity(identity)
                        if (epoch != token || !messagingScreenActive()) return@launch
                        socialSubscription = repository.observeSocial(device.uid, { social ->
                            // Only continue with start messaging when epoch is token and messaging screen active.
                            if (epoch == token && messagingScreenActive()) {
                                friends = social.friends
                                incomingFriendRequests = social.incoming
                                outgoingFriendRequests = social.outgoing
                                observeFriendConversations(social.friends, token)
                                messagingBusy = false
                            }
                        }, { error ->
                            // start messaging checks whether epoch is token before continuing.
                            if (epoch == token) {
                                messagingBusy = false
                                if (showFeedback || page == SafePage.Messages) message =
                                    runtime.application.getString(
                                        R.string.ui_unable_to_load_friend_information_value,
                                        error
                                    )
                            }
                        })
                        // The live listeners are attached; their first rows can populate after loading ends.
                        if (epoch == token && messagingScreenActive()) messagingBusy = false
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // Only continue with start messaging when epoch is token.
                        if (epoch == token) {
                            messagingBusy = false
                            // error is firebase firestore exception and error code is firebase firestore exception code permission denied is handled here by start messaging.
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

    // Session messaging: returns the reconnect messaging. It checks the chat and uses its secure data before reading or saving a message.
    fun reconnectMessaging(password: String, onConnected: () -> Unit = {}) {
        with(runtime) {
            with(state) {
                val device = enrollment ?: return
                if (localMode || messagingBusy || password.isBlank() || !messagingScreenActive()) return
                val token = epoch
                messagingBusy = true
                message = null
                scope.launch {
                    var connected = false
                    // If reconnect messaging fails, catch the error so the app can keep running and explain the problem.
                    try {
                        val identity = accounts.authenticate(coverUsername, password, accountRole)
                        check(identity.uid == device.uid) { "This device is linked to an older account. Sign out, then sign in again." }
                        connected = true
                        messagingNeedsReconnect = false
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) { // Keep the social screen open and offer account reconnection when messaging setup fails.
                        if (epoch == token) {
                            messagingNeedsReconnect = true
                            message =
                                runtime.application.getString(R.string.ui_could_not_reconnect_check_your_password_and_try_again)
                        }
                    } finally {
                        if (epoch == token) messagingBusy = false
                    }
                    if (connected && epoch == token && messagingScreenActive()) {
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
                // The marker keeps support conversations observable even when this account has no friends.
                val ids = currentFriends.map { it.friendshipId }.toSet() + "__support__"
                if (ids == observedFriendshipIds) return
                observedFriendshipIds = ids
                conversationSubscription?.close()
                conversationSubscription =
                    messagingRepository?.observeConversations(deviceUid(), { rows ->
                        // epoch is token and messaging screen active is handled here by observe friend conversations.
                        if (epoch == token && messagingScreenActive()) {
                            if (conversationLoadFailed) message = null
                            conversationLoadFailed = false
                            conversations = rows
                            activeConversation?.let { active ->
                                rows.firstOrNull { it.id == active.id }?.let { updated ->
                                    if (active.closedAt == 0L && updated.closedAt > 0L) {
                                        conversationActions.closeConversation()
                                    } else if (active.participantIds != updated.participantIds) {
                                        messageSubscription?.close()
                                        messageSubscription = null
                                        activeConversation = updated
                                        conversationActions.openConversation(updated, preserveMessages = true)
                                    } else {
                                        activeConversation = updated
                                        conversationActions.markConversationRead(updated)
                                        if (messageSubscription == null && !messagingBusy &&
                                            active.updatedAt != updated.updatedAt) {
                                            conversationActions.openConversation(updated)
                                        }
                                    }
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

    // Session messaging: Runs messaging action. It reads or saves the matching protected record in Firebase.
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
                if (messagingBusy || messagingNeedsReconnect || !messagingScreenActive()) return
                val token = epoch
                messagingBusy = true
                message = null
                scope.launch {
                    // A run messaging action error is handled here instead of closing the app.
                    try {
                        block(repository, device, token)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // epoch is token is handled here by run messaging action.
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

    // Stops messaging for session messaging. It checks the chat and uses its secure data before reading or saving a message.
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

    // Session messaging: returns the device uid.
    private fun deviceUid() = runtime.enrollment?.uid ?: error("No active account")

    // Registers messaging identity for session messaging.
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

    // Reconnects device notifications to the conversations visible to the current account.
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
