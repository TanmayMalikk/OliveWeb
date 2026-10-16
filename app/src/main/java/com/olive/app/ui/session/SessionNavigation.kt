package com.olive.app.ui.session

import com.olive.app.data.*
import com.olive.app.ui.*

// Owns navigation history and background locking; quick exit clears PIN authorization and invalidates pending operations.
internal class SessionNavigation(private val runtime:SessionRuntime) {
    fun openScreen(value: SessionScreen) {
        with(runtime) {
            with(state) {
                if(value==screen) return
                screenHistory += screen
                screen=value
            }
        }
    }

    // User exits also advance the cover-focus event so TalkBack leaves the old private screen.
    fun quickExit() {
        with(runtime) {
            with(state) {
                enrollment?.let { accountRole=it.role }
                if(accountRole==AccountRole.USER && screen in setOf(SessionScreen.Safe,SessionScreen.Pin)) accessibilityExitVersion++
                epoch++
                pinAuthorizedUid=null;pinAuthorizedEpoch=-1L
                operationJob?.cancel();operationJob=null
                idleJob?.cancel();idleJob=null;idleCountdownSeconds=null
                busy=false;checkoutActive=false;coverReset++;addressPrompt=false
                profile?.coverType?.let { coverType=it }
                if(accountRole==AccountRole.SAFE_CONTACT) {
                    page=SafePage.Home;activityCategory=null;screenHistory.clear();safePageHistory.clear()
                    message=null;messaging.stopMessaging();activeTriggerId=null;triggerJob=null;screen=SessionScreen.Safe
                    return
                }
                screen=when(accountRole) {
                    AccountRole.USER -> SessionScreen.Cover
                    AccountRole.SAFE_CONTACT -> SessionScreen.Safe
                    AccountRole.STAFF,AccountRole.ADMIN -> SessionScreen.Portal
                }
                page=SafePage.Home;activityCategory=null
                screenHistory.clear();safePageHistory.clear()
                profile=null; attempts=emptyList(); message=null; pendingSync=false
                settingsHistory=emptyList();privacyHistory=emptyList();triggerHistory=emptyList();privateEntries=emptyList()
                messaging.stopMessaging()
                if(accountRole==AccountRole.USER && !localMode) messaging.syncLocalNotificationHandler()
                activeTriggerId=null;triggerJob=null
                pendingIdentity=null; pendingProfile=null
            }
        }
    }

    fun background() {
        with(runtime) {
            with(state) {
                // Also discard partially entered onboarding fields when leaving the app.
                if(screen == SessionScreen.Loading || screen == SessionScreen.StorageError) return
                if(enrollment==null) {
                    epoch++;operationJob?.cancel();operationJob=null;busy=false;pendingIdentity=null;pendingProfile=null
                    screenHistory.clear();screen=SessionScreen.Welcome;message=null
                } else if(accountRole==AccountRole.USER) quickExit()
            }
        }
    }

    fun back() {
        with(runtime) {
            with(state) {
                if(screen == SessionScreen.Safe && page==SafePage.Messages && activeConversation!=null) conversationActions.closeConversation()
                else if(screen == SessionScreen.Safe && page==SafePage.Activity && activityCategory!=null) { activityCategory=null;message=null }
                else if(screen == SessionScreen.Safe && safePageHistory.isNotEmpty()) {
                    page=safePageHistory.removeAt(safePageHistory.lastIndex);message=null
                }
                else if(screen == SessionScreen.Safe && page != SafePage.Home) { page=SafePage.Home;message=null }
                else if((screen == SessionScreen.Safe && accountRole==AccountRole.USER) || screen == SessionScreen.Pin) quickExit()
                else if(screenHistory.isNotEmpty()) { screen=screenHistory.removeAt(screenHistory.lastIndex);message=null }
                // Root cover/welcome/loading screens deliberately consume Back so Android does not open the phone home screen.
            }
        }
    }

    fun navigate(value: SafePage) {
        with(runtime) {
            with(state) {
                if(screen == SessionScreen.Safe) {
                    if(value!=page) safePageHistory += page
                    page=value; message=null
                    if(value==SafePage.Activity) activityCategory=null
                    val uid=enrollment?.uid
                    if(uid!=null) {
                        if(value==SafePage.Settings || value==SafePage.Profile || value==SafePage.Contacts)
                            history.recordSettingsInteraction("Olive Branch", "Opened ${value.name.lowercase()} page")
                        if(value==SafePage.Activity || value==SafePage.Notes) history.refreshHistory(uid)
                        if(value==SafePage.Messages) messaging.ensureMessagingObservation(showFeedback=true)
                    }
                }
            }
        }
    }
}
