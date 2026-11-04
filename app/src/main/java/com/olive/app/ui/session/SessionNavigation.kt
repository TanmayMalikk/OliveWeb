// Signed-in users: moves between cover, login, and safe-app screens.
package com.olive.app.ui.session

import com.olive.app.ui.users.shared.navigation.*

import com.olive.app.data.*

// Owns navigation history and background locking; quick exit clears PIN authorization and invalidates pending operations.
internal class SessionNavigation(private val runtime: SessionRuntime) {
    // Opens a Safe App screen and remembers where Back should return.
    fun openScreen(value: SessionScreen) {
        with(runtime) {
            with(state) {
                if (value == screen) return
                screenHistory += screen
                screen = value
            }
        }
    }

    // User exits also advance the cover-focus event so TalkBack leaves the old private screen.
    fun quickExit() {
        with(runtime) {
            with(state) {
                enrollment?.let { accountRole = it.role }
                if (accountRole == AccountRole.USER && screen in setOf(
                        SessionScreen.Safe,
                        SessionScreen.Pin
                    )
                ) accessibilityExitVersion++
                safety.close()
                epoch++
                pinAuthorizedUid = null
                pinAuthorizedEpoch = -1L
                deviceLockRequest?.complete(null)
                deviceLockRequest = null
                operationJob?.cancel()
                operationJob = null
                idleJob?.cancel()
                idleJob = null
                idlePausedForCall = false
                idleCountdownSeconds = null
                busy = false
                checkoutActive = false
                coverReset++
                addressPrompt = false
                viewedSharedEntry = null
                profile?.coverType?.let { coverType = it }
                // Safe contacts return to account entry because they do not use a cover app.
                if (accountRole == AccountRole.SAFE_CONTACT) {
                    page = SafePage.Home
                    activityCategory =
                        null
                    screenHistory.clear()
                    safePageHistory.clear()
                    message = null
                    messaging.stopMessaging()
                    activeTriggerId = null
                    triggerJob =
                        null
                    screen = SessionScreen.Safe
                    return
                }
                screen = when (accountRole) {
                    AccountRole.USER -> SessionScreen.Cover
                    AccountRole.SAFE_CONTACT -> SessionScreen.Safe
                    AccountRole.STAFF, AccountRole.ADMIN -> SessionScreen.Portal
                }
                page = SafePage.Home
                activityCategory = null
                screenHistory.clear()
                safePageHistory.clear()
                profile = null
                attempts = emptyList()
                message = null
                pendingSync = false
                settingsHistory = emptyList()
                privacyHistory = emptyList()
                triggerHistory =
                    emptyList()
                privateEntries = emptyList()
                messaging.stopMessaging()
                if (accountRole == AccountRole.USER && !localMode) messaging.syncLocalNotificationHandler()
                activeTriggerId = null
                triggerJob = null
                pendingIdentity = null
                pendingProfile = null
            }
        }
    }

    // Moves the app into its background state for session navigation.
    fun background() {
        with(runtime) {
            with(state) {
                // Also discard partially entered onboarding fields when leaving the app.
                if (screen == SessionScreen.Loading || screen == SessionScreen.StorageError) return
                // Without a saved enrollment, private entry must return to account setup.
                if (enrollment == null) {
                    epoch++
                    operationJob?.cancel()
                    operationJob = null
                    busy =
                        false
                    pendingIdentity = null
                    pendingProfile = null
                    accountRole = AccountRole.USER
                    coverType = CoverType.FOOD
                    screenHistory.clear()
                    screen = SessionScreen.Cover
                    message = null
                } else if (accountRole == AccountRole.USER) quickExit()
            }
        }
    }

    // Returns to the previous screen for session navigation.
    fun back() {
        with(runtime) {
            with(state) {
                if (screen == SessionScreen.Safe && page == SafePage.Messages && activeConversation != null) conversationActions.closeConversation()
                else if (screen == SessionScreen.Safe && page == SafePage.Activity && activityCategory != null) {
                    activityCategory = null
                    message = null
                } else if (screen == SessionScreen.Safe && safePageHistory.isNotEmpty()) {
                    page = safePageHistory.removeAt(safePageHistory.lastIndex)
                    message = null
                } else if (screen == SessionScreen.Safe && page != SafePage.Home) {
                    page = SafePage.Home
                    message = null
                } else if ((screen == SessionScreen.Safe && accountRole == AccountRole.USER) || screen == SessionScreen.Pin) quickExit()
                else if (screenHistory.isNotEmpty()) {
                    val previousScreen = screenHistory.removeAt(screenHistory.lastIndex)
                    if (enrollment == null && previousScreen == SessionScreen.Cover) {
                        accountRole = AccountRole.USER
                        coverType = CoverType.FOOD
                    }
                    screen = previousScreen
                    message = null
                }
                // Root cover/welcome/loading screens deliberately consume Back so Android does not open the phone home screen.
            }
        }
    }

    // Session navigation: Opens the requested screen.
    fun navigate(value: SafePage) {
        with(runtime) {
            with(state) {
                // Safe App pages use their own back stack before leaving the private area.
                if (screen == SessionScreen.Safe) {
                    if (value != page) safePageHistory += page
                    page = value
                    message = null
                    if (value == SafePage.Activity) activityCategory = null
                    val uid = enrollment?.uid
                    if (uid != null) {
                        if (value == SafePage.Settings || value == SafePage.Profile || value == SafePage.Contacts)
                            history.recordSettingsInteraction(
                                "Olive Branch",
                                "Opened ${value.name.lowercase()} page"
                            )
                        if (value == SafePage.Activity || value == SafePage.Notes) history.refreshHistory(
                            uid
                        )
                        if (value == SafePage.Messages) messaging.ensureMessagingObservation(
                            showFeedback = true
                        )
                    }
                }
            }
        }
    }
}
