package com.olive.app.ui.session

import androidx.compose.runtime.*
import com.olive.app.data.*
import com.olive.app.ui.*

// Observable screen state exposed through SessionViewModel; the screen setter also enforces the User PIN gate.
internal class SessionState(private val canEnterSafe:()->Boolean) {
    private var screenState by mutableStateOf(SessionScreen.Loading)
    var screen:SessionScreen
        get()=screenState
        set(value) { screenState=if(value==SessionScreen.Safe && !canEnterSafe()) SessionScreen.Cover else value }
    var pinChallengeVersion by mutableIntStateOf(0)
    var accessibilityExitVersion by mutableIntStateOf(0)
    var page by mutableStateOf(SafePage.Home)
    var activityCategory by mutableStateOf<ActivityCategory?>(null)
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var profile by mutableStateOf<UserProfile?>(null)
    var attempts by mutableStateOf(emptyList<FailedAttempt>())
    var settingsHistory by mutableStateOf(emptyList<SettingsEvent>())
    var privacyHistory by mutableStateOf(emptyList<SettingsEvent>())
    var triggerHistory by mutableStateOf(emptyList<TriggerEvent>())
    var checkoutHistory by mutableStateOf(emptyList<CheckoutEvent>())
    var privateEntries by mutableStateOf(emptyList<PrivateEntry>())
    var userSearchResults by mutableStateOf(emptyList<DirectoryUser>())
    var friends by mutableStateOf(emptyList<Friend>())
    var incomingFriendRequests by mutableStateOf(emptyList<FriendRequest>())
    var outgoingFriendRequests by mutableStateOf(emptyList<FriendRequest>())
    var conversations by mutableStateOf(emptyList<ChatConversation>())
    var readMessageVersion by mutableIntStateOf(0)
    var activeConversation by mutableStateOf<ChatConversation?>(null)
    var chatQuickExitHintVisible by mutableStateOf(false)
    var chatMessages by mutableStateOf(emptyList<ChatMessage>())
    var messagingBusy by mutableStateOf(false)
    var messagingNeedsReconnect by mutableStateOf(false)
    var pendingSync by mutableStateOf(false)
    var deliveryAddress by mutableStateOf(DeliveryAddress())
    var addressPrompt by mutableStateOf(false)
    var addressSaveVersion by mutableIntStateOf(0)
    var language by mutableStateOf(AppLocaleManager.resolveSystemDefaultLanguageName())
    var coverFullName by mutableStateOf("")
    var coverUsername by mutableStateOf("")
    var coverType by mutableStateOf(CoverType.FOOD)
    var accountRole by mutableStateOf(AccountRole.USER)
    var adminCounts by mutableStateOf(AccountCounts())
    var adminAccounts by mutableStateOf(emptyList<AdminAccountSummary>())
    var foodCart by mutableStateOf<Map<String,Int>>(emptyMap())
    var foodFavorites by mutableStateOf<Set<String>>(emptySet())
    var foodCustomizations by mutableStateOf<Map<String,Set<String>>>(emptyMap())
    var savedNews by mutableStateOf<List<NewsArticle>>(emptyList())
    var coverDataVersion by mutableIntStateOf(0)
    var checkoutActive by mutableStateOf(false)
    var idleCountdownSeconds by mutableStateOf<Int?>(null)
    var privacyBlackout by mutableStateOf(false)
    var credentialFieldError by mutableStateOf<CredentialFieldError?>(null)
    var creatingAccount by mutableStateOf(false)
    var coverReset by mutableIntStateOf(0)
}
