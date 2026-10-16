package com.olive.app.ui.session

import androidx.core.content.edit
import com.olive.app.data.*
import com.olive.app.security.*
import com.olive.app.ui.*
import com.olive.app.notification.LocalNotificationEventHandler
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

// Connects account messaging and manages live subscriptions.
internal class SessionMessaging(private val runtime:SessionRuntime) {
    fun ensureMessagingObservation(showFeedback: Boolean=false) {
        with(runtime) {
            with(state) {
                if(socialSubscription!=null || messagingBusy) return
                startMessaging(showFeedback)
            }
        }
    }

    fun startMessaging(showFeedback: Boolean=true) {
        with(runtime) {
            with(state) {
                stopMessaging(clearRows=false)
                val repository=messagingRepository
                val device=enrollment
                if(repository==null || device==null) {
                    if(showFeedback) message="Messaging needs a Firebase-connected build."
                    return
                }
                // A saved local account can outlive Firebase's login; reconnect it before touching private friend data.
                val expectedUsername=InputPolicy.username(coverUsername)
                if(accounts.authenticatedUid()!=device.uid || accounts.authenticatedUsername()!=expectedUsername) {
                    messagingNeedsReconnect=true
                    messagingBusy=false
                    if(showFeedback) message="Enter your account password to reconnect friends and messages."
                    return
                }
                messagingNeedsReconnect=false
                val identity=AccountIdentity(device.uid,coverUsername)
                LocalNotificationEventHandler.pauseForegroundListeners()
                val token=epoch
                messagingBusy=true
                if(showFeedback) message=null
                scope.launch {
                    try {
                        registerMessagingIdentity(identity)
                        if(epoch!=token || screen!=SessionScreen.Safe) return@launch
                        socialSubscription=repository.observeSocial(device.uid,{ social ->
                            if(epoch==token && screen==SessionScreen.Safe) {
                                friends=social.friends
                                incomingFriendRequests=social.incoming
                                outgoingFriendRequests=social.outgoing
                                observeFriendConversations(social.friends,token)
                                messagingBusy=false
                            }
                        },{ error -> if(epoch==token) {
                            messagingBusy=false
                            if(showFeedback || page==SafePage.Messages) message="Unable to load friend information. $error"
                        } })
                    } catch(error: CancellationException) { throw error }
                    catch(error: Exception) {
                        if(epoch==token) {
                            messagingBusy=false
                            if(error is FirebaseFirestoreException && error.code==FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                                messagingNeedsReconnect=true
                                if(showFeedback || page==SafePage.Messages)
                                    message="Firebase needs your account password again before friends and messages can load."
                            } else if(showFeedback || page==SafePage.Messages)
                                message=error.localizedMessage ?: "Unable to start messaging."
                        }
                    }
                }
            }
        }
    }

    fun reconnectMessaging(password: String, onConnected: ()->Unit = {}) {
        with(runtime) {
            with(state) {
                val device=enrollment ?: return
                if(localMode || messagingBusy || password.isBlank() || screen!=SessionScreen.Safe || page!=SafePage.Messages) return
                val token=epoch
                messagingBusy=true;message=null
                scope.launch {
                    var connected=false
                    try {
                        val identity=accounts.authenticate(coverUsername,password,accountRole)
                        check(identity.uid==device.uid) { "This device is linked to an older account. Sign out, then sign in again." }
                        connected=true;messagingNeedsReconnect=false
                    } catch(error: CancellationException) { throw error }
                    catch(error: Exception) {
                        if(epoch==token) {
                            messagingNeedsReconnect=true
                            message=error.localizedMessage ?: "Could not reconnect. Check your password and try again."
                        }
                    } finally { if(epoch==token) messagingBusy=false }
                    if(connected && epoch==token && screen==SessionScreen.Safe && page==SafePage.Messages) {
                        onConnected()
                        startMessaging(showFeedback=true)
                    }
                }
            }
        }
    }

    fun observeFriendConversations(currentFriends: List<Friend>, token: Long) {
        with(runtime) {
            with(state) {
                val ids=currentFriends.map { it.friendshipId }.toSet()
                if(ids==observedFriendshipIds) return
                observedFriendshipIds=ids
                conversationSubscription?.close()
                conversationSubscription=messagingRepository?.observeConversations(ids.toList(),{ rows ->
                    if(epoch==token && screen==SessionScreen.Safe) {
                        conversations=rows
                        activeConversation?.let { active -> rows.firstOrNull { it.id==active.id }?.let { updated ->
                            activeConversation=updated
                            conversationActions.markConversationRead(updated)
                        } }
                    }
                },{ error -> if(epoch==token && page==SafePage.Messages) message="Unable to load messages. $error" })
            }
        }
    }

    fun runMessagingAction(failure: String, block: suspend (MessagingRepository,DeviceEnrollment,Long)->Unit) {
        with(runtime) {
            with(state) {
                val repository=messagingRepository ?: run { message="Messaging needs a Firebase-connected build.";return }
                val device=enrollment ?: return
                if(messagingBusy || messagingNeedsReconnect || screen!=SessionScreen.Safe || page!=SafePage.Messages) return
                val token=epoch
                messagingBusy=true;message=null
                scope.launch {
                    try { block(repository,device,token) }
                    catch(error: CancellationException) { throw error }
                    catch(error: Exception) { if(epoch==token) {
                        message=if(error is FirebaseFirestoreException && error.code==FirebaseFirestoreException.Code.PERMISSION_DENIED)
                            "$failure Firebase denied this action. Refresh the page and try again."
                        else error.localizedMessage ?: failure
                    } }
                    finally { if(epoch==token) messagingBusy=false }
                }
            }
        }
    }

    fun stopMessaging(clearRows: Boolean = true) {
        with(runtime) {
            with(state) {
                socialSubscription?.close();socialSubscription=null
                conversationSubscription?.close();conversationSubscription=null
                observedFriendshipIds=emptySet()
                messageSubscription?.close();messageSubscription=null
                activeConversation=null;chatMessages=emptyList();messagingBusy=false
                messagingNeedsReconnect=false
                if(clearRows) {
                    userSearchResults=emptyList();friends=emptyList();incomingFriendRequests=emptyList();outgoingFriendRequests=emptyList()
                    conversations=emptyList()
                }
            }
        }
    }

    suspend fun registerMessagingIdentity(identity:AccountIdentity) {
        with(runtime) {
            with(state) {
                val marker=messagingIdentityMarker(identity)
                if(!shouldRegisterMessagingIdentity(cloudState.getString("registered_identity",null),identity)) return
                messagingRepository?.registerUser(identity)
                cloudState.edit { putString("registered_identity",marker) }
            }
        }
    }

    fun syncLocalNotificationHandler() {
        with(runtime) {
            with(state) {
                val device=enrollment ?: return
                if(localMode) return
                if(accounts.authenticatedUid()==device.uid)
                    LocalNotificationEventHandler.start(application,coverType,accountRole)
            }
        }
    }
}
