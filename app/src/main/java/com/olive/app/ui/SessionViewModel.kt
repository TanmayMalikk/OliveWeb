package com.olive.app.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import com.olive.app.BuildConfig
import com.olive.app.data.*
import com.olive.app.security.*
import com.olive.app.notification.LocalNotificationEventHandler
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

enum class SessionScreen { Loading, Welcome, NewCredentials, ReturningCredentials, ProfileSetup, TriggerSetup, OrganizationProfileSetup, DevicePin, Cover, Pin, Safe, Portal, StorageError }
enum class SafePage { Home, Notes, Messages, Profile, Contacts, Settings, Activity, Camera }
enum class ActivityCategory { Settings, Triggers, PinAttempts, Checkouts, Privacy }
enum class CredentialFieldError { InvalidUsername, InvalidPassword, UsernameTaken, IncorrectPassword }

internal fun messagingIdentityMarker(identity:AccountIdentity)="${identity.uid}:${identity.username.lowercase()}"
internal fun shouldRegisterMessagingIdentity(savedMarker:String?,identity:AccountIdentity)=
    savedMarker!=messagingIdentityMarker(identity)

// This is the traffic controller: it keeps cover mode, setup, PIN access, and the safe side from stepping on each other.
class SessionViewModel(application: Application) : AndroidViewModel(application) {
    val localMode = !BuildConfig.FIREBASE_CONFIGURED
    private val vault = EncryptedVault(application, if(localMode) "demo" else "firebase")
    private val activityVault = EncryptedVault(application, if(localMode) "demo-activity" else "firebase-activity")
    private val store = DeviceStore(vault,activityVault)
    private val coverStore=CoverStateStore(EncryptedVault(application,"cover"))
    private val coverSaveMutex=Mutex()
    private val foodRevisions=mutableMapOf<String,Long>()
    private val newsRevisions=mutableMapOf<String,Long>()
    private val accounts: AccountRepository = if(localMode) LocalAccountRepository(vault) else FirebaseAccountRepository()
    private val adminRepository = if(localMode) null else AdminRepository()
    private val messagingRepository = if(localMode) null else MessagingRepository()
    private val messageReadState=application.getSharedPreferences("olive_message_read_state",android.content.Context.MODE_PRIVATE)
    private val cloudState=application.getSharedPreferences("olive_cloud_state",android.content.Context.MODE_PRIVATE)
    var screen by mutableStateOf(SessionScreen.Loading); private set
    var page by mutableStateOf(SafePage.Home); private set
    var activityCategory by mutableStateOf<ActivityCategory?>(null); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var profile by mutableStateOf<UserProfile?>(null); private set
    var attempts by mutableStateOf(emptyList<FailedAttempt>()); private set
    var settingsHistory by mutableStateOf(emptyList<SettingsEvent>()); private set
    var privacyHistory by mutableStateOf(emptyList<SettingsEvent>()); private set
    var triggerHistory by mutableStateOf(emptyList<TriggerEvent>()); private set
    var checkoutHistory by mutableStateOf(emptyList<CheckoutEvent>()); private set
    var privateEntries by mutableStateOf(emptyList<PrivateEntry>()); private set
    var userSearchResults by mutableStateOf(emptyList<DirectoryUser>()); private set
    var friends by mutableStateOf(emptyList<Friend>()); private set
    var incomingFriendRequests by mutableStateOf(emptyList<FriendRequest>()); private set
    var outgoingFriendRequests by mutableStateOf(emptyList<FriendRequest>()); private set
    var conversations by mutableStateOf(emptyList<ChatConversation>()); private set
    private var readMessageVersion by mutableIntStateOf(0)
    var activeConversation by mutableStateOf<ChatConversation?>(null); private set
    var chatQuickExitHintVisible by mutableStateOf(false); private set
    var chatMessages by mutableStateOf(emptyList<ChatMessage>()); private set
    var messagingBusy by mutableStateOf(false); private set
    var messagingNeedsReconnect by mutableStateOf(false); private set
    var pendingSync by mutableStateOf(false); private set
    var deliveryAddress by mutableStateOf(DeliveryAddress()); private set
    var addressPrompt by mutableStateOf(false); private set
    var addressSaveVersion by mutableIntStateOf(0); private set
    var language by mutableStateOf(AppLocaleManager.resolveSystemDefaultLanguageName()); private set
    var coverFullName by mutableStateOf(""); private set
    var coverUsername by mutableStateOf(""); private set
    var coverType by mutableStateOf(CoverType.FOOD); private set
    var accountRole by mutableStateOf(AccountRole.USER); private set
    var adminCounts by mutableStateOf(AccountCounts()); private set
    var adminAccounts by mutableStateOf(emptyList<AdminAccountSummary>()); private set
    var foodCart by mutableStateOf<Map<String,Int>>(emptyMap()); private set
    var foodFavorites by mutableStateOf<Set<String>>(emptySet()); private set
    var foodCustomizations by mutableStateOf<Map<String,Set<String>>>(emptyMap()); private set
    var savedNews by mutableStateOf<List<NewsArticle>>(emptyList()); private set
    var coverDataVersion by mutableIntStateOf(0); private set
    var checkoutActive by mutableStateOf(false)
    var idleCountdownSeconds by mutableStateOf<Int?>(null); private set
    var privacyBlackout by mutableStateOf(false); private set
    var credentialFieldError by mutableStateOf<CredentialFieldError?>(null); private set
    var creatingAccount by mutableStateOf(false); private set
    var coverReset by mutableIntStateOf(0); private set
    private var enrollment by mutableStateOf<DeviceEnrollment?>(null)
    private var pendingIdentity: AccountIdentity? = null
    private var pendingProfile: UserProfile? = null
    private var operationJob: Job? = null
    private var triggerJob: Job? = null
    private var historySyncJob: Job? = null
    private var idleJob: Job? = null
    private var socialSubscription: ChatSubscription? = null
    private var conversationSubscription: ChatSubscription? = null
    private var messageSubscription: ChatSubscription? = null
    private var observedFriendshipIds=emptySet<String>()
    private var lastTouchAt=0L
    private val historySyncMutex=Mutex()
    private var activeTriggerId: String? = null
    private var epoch = 0L
    private val screenHistory=mutableListOf<SessionScreen>()
    private val safePageHistory=mutableListOf<SafePage>()
    val configured get() = enrollment != null
    val coverAccountId get() = enrollment?.uid
    val sensitive get() = screen != SessionScreen.Cover || checkoutActive
    val setupUsername get() = pendingIdentity?.username.orEmpty()
    val setupProfile get() = pendingProfile
    val usesCover get() = accountRole==AccountRole.USER
    val unreadMessageCount: Int get() {
        readMessageVersion
        val uid=enrollment?.uid ?: return 0
        return conversations.count { conversation ->
            conversation.lastMessageId.isNotBlank() && conversation.lastSenderUid.isNotBlank() &&
                conversation.lastSenderUid!=uid &&
                messageReadState.getString(readMessageKey(uid,conversation.id),null)!=conversation.lastMessageId
        }
    }
    val pendingMessageActionCount get()=incomingFriendRequests.size+unreadMessageCount

    init { load() }
    fun load() {
        if(busy) return
        busy = true
        screenHistory.clear();safePageHistory.clear()
        screen = SessionScreen.Loading
        viewModelScope.launch {
            try {
                check(BuildConfig.DEBUG || !localMode)
                var restoredSession=withContext(Dispatchers.IO) { store.currentSession() }
                if(restoredSession==null && !localMode) {
                    restoredSession=withContext(Dispatchers.IO) {
                        accounts.authenticatedUid()?.let { uid ->
                            store.restoreActive(uid)
                            store.currentSession()
                        }
                    }
                }
                enrollment=restoredSession?.enrollment
                restoredSession?.let { restored ->
                    val device=restored.enrollment
                    val saved=restored.profile
                    deliveryAddress=saved.address; language=saved.language
                    coverFullName=saved.fullName; coverUsername=saved.username
                    coverType=saved.coverType
                    accountRole=saved.role
                    restoreCoverData(device.uid)
                    checkoutHistory=withContext(Dispatchers.IO) { store.checkoutHistory(device.uid) }
                    if(!localMode) loadCloudHistory(device.uid,saved.username)
                    if(saved.role!=AccountRole.USER) profile=saved
                    if(saved.role==AccountRole.SAFE_CONTACT) withContext(Dispatchers.IO) { store.removePin(device.uid) }
                }
                screen = when(enrollment?.role) {
                    null -> SessionScreen.Welcome
                    AccountRole.USER -> SessionScreen.Cover
                    AccountRole.SAFE_CONTACT -> SessionScreen.Safe
                    AccountRole.STAFF,AccountRole.ADMIN -> SessionScreen.Portal
                }
                if(enrollment?.role==AccountRole.ADMIN && !localMode) queueAdminRefresh()
            } catch(_: Exception) { screen = SessionScreen.StorageError }
            finally { busy = false }
        }
    }
    private fun openScreen(value: SessionScreen) {
        if(value==screen) return
        screenHistory += screen
        screen=value
    }
    fun selectRole(role: AccountRole) { if(!busy) { accountRole=role;message=null;credentialFieldError=null } }
    fun chooseNew() {
        if(busy) return
        if(accountRole==AccountRole.STAFF || accountRole==AccountRole.ADMIN) { message="Staff and Admin accounts are created by an administrator.";return }
        creatingAccount=true;message=null;openScreen(SessionScreen.NewCredentials)
    }
    fun chooseReturning() { if(!busy) { creatingAccount=false;message=null; openScreen(SessionScreen.ReturningCredentials) } }
    fun setup() { if(!busy) { creatingAccount=false;message=null; openScreen(SessionScreen.Welcome) } }
    fun error(text: String) { message = text }
    fun clearCredentialError() { credentialFieldError=null;message=null }

    // Account creation and returning sign-in meet here; Firebase calls live in AccountRepository.
    fun authenticate(username: String, password: String, create: Boolean) {
        if(busy) return
        credentialFieldError=null
        val organizationEmail=accountRole in setOf(AccountRole.STAFF,AccountRole.ADMIN) && '@' in username
        val validUsername=if(create) InputPolicy.validUsername(username) else InputPolicy.validExistingUsername(username)
        if(!organizationEmail && !validUsername) { credentialFieldError=CredentialFieldError.InvalidUsername;message="Invalid username."; return }
        if(create && !InputPolicy.validPassword(password)) { credentialFieldError=CredentialFieldError.InvalidPassword;message="Invalid password."; return }
        if(password.isEmpty()) { credentialFieldError=CredentialFieldError.IncorrectPassword;message="Password required."; return }
        runOperation("Unable to sign in. Check your credentials and connection, then try again.") { token ->
            val identity = try {
                if(create) accounts.create(username,password,accountRole) else accounts.authenticate(username,password,accountRole)
            } catch(error:Exception) {
                val details=error.message.orEmpty()
                val usernameUnavailable=create && (error is FirebaseAuthUserCollisionException ||
                    details.contains("unavailable",ignoreCase=true))
                val incorrectLogin=!create && (error is FirebaseAuthInvalidCredentialsException ||
                    error is FirebaseAuthInvalidUserException || details.contains("sign-in failed",ignoreCase=true))
                if(!usernameUnavailable && !incorrectLogin) throw error
                if(epoch==token) {
                    credentialFieldError=if(usernameUnavailable) CredentialFieldError.UsernameTaken else CredentialFieldError.IncorrectPassword
                    message=if(usernameUnavailable) "Username taken." else "Incorrect password."
                }
                return@runOperation
            }
            val remote = accounts.profile(identity)
            val existing = withContext(Dispatchers.IO) {
                if(store.pendingSync(identity.uid)) store.profile(identity.uid) else remote
            }
            if(epoch == token) {
                if(existing==null && !create && (accountRole==AccountRole.STAFF || accountRole==AccountRole.ADMIN)) {
                    accounts.signOut();message="This ${accountRole.title} account has not been provisioned by an administrator.";return@runOperation
                }
                if(existing!=null && existing.role!=accountRole) {
                    accounts.signOut();message="This account is registered as ${existing.role.title}. Select that account type to sign in.";return@runOperation
                }
                pendingIdentity = identity
                pendingProfile = existing
                if(existing!=null && existing.role==AccountRole.SAFE_CONTACT && AccountPolicy.personalProfileNeedsSetup(existing)) {
                    openScreen(SessionScreen.ProfileSetup)
                } else if(existing!=null && existing.role in setOf(AccountRole.STAFF,AccountRole.ADMIN) && AccountPolicy.organizationProfileNeedsSetup(existing)) {
                    openScreen(SessionScreen.OrganizationProfileSetup)
                } else if(existing!=null && existing.role!=AccountRole.USER) {
                    val saved=withContext(Dispatchers.IO) { store.enrollWithoutPin(identity,existing) }
                    enrollment=saved;profile=existing;coverUsername=existing.username;coverFullName=existing.fullName
                    accountRole=existing.role;language=existing.language;deliveryAddress=existing.address
                    screenHistory.clear();safePageHistory.clear()
                    if(existing.role==AccountRole.SAFE_CONTACT) {
                        page=SafePage.Home;screen=SessionScreen.Safe;refreshHistory(saved.uid)
                    } else {
                        screen=SessionScreen.Portal
                        if(existing.role==AccountRole.ADMIN) queueAdminRefresh()
                    }
                } else openScreen(when {
                    existing==null || AccountPolicy.personalProfileNeedsSetup(existing) -> SessionScreen.ProfileSetup
                    existing.triggerHash.isBlank() -> SessionScreen.TriggerSetup
                    else -> SessionScreen.DevicePin
                })
            }
        }
    }

    // Onboarding saves here; add new fields to UserProfile and ProfileSetup at the same time.
    fun saveSetupProfile(value: UserProfile) {
        val identity = pendingIdentity ?: return
        if(busy) return
        if(value.role!=accountRole) { message="Account type mismatch.";return }
        if(value.fullName.isBlank() || !InputPolicy.validDob(value.dob) || value.language.isBlank() || !value.address.locationValid) {
            message="Full name, a valid date of birth, language and city are required."; return
        }
        runOperation("Your account exists, but the profile was not saved. Retry here, or sign in again to finish setup.") { token ->
            accounts.saveProfile(identity,value)
            if(value.role==AccountRole.SAFE_CONTACT) {
                val saved=withContext(Dispatchers.IO) { store.enrollWithoutPin(identity,value) }
                if(epoch==token) {
                    enrollment=saved;profile=value;coverUsername=value.username;coverFullName=value.fullName
                    accountRole=value.role;language=value.language;deliveryAddress=value.address
                    screenHistory.clear();safePageHistory.clear();page=SafePage.Home;screen=SessionScreen.Safe
                    refreshHistory(saved.uid)
                    if(!localMode) loadCloudHistory(saved.uid,value.username)
                }
            } else {
                withContext(Dispatchers.IO) { store.cacheProfile(identity.uid,value) }
                if(epoch == token) { pendingProfile=value; openScreen(SessionScreen.DevicePin) }
            }
        }
    }

    // Lock this phone with its own PIN, then open the cover chosen during setup.
    fun createDevicePin(pin: String, confirmation: String) {
        if(busy) return
        if(!InputPolicy.validPin(pin) || pin != confirmation) { message="Enter the same 4–6 digit PIN in both fields."; return }
        val identity = pendingIdentity ?: return
        val value = pendingProfile ?: return
        runOperation("Unable to save this device's PIN. Please retry.") { token ->
            val saved = withContext(Dispatchers.IO) { store.enroll(identity,value,pin) }
            enrollment=saved
            if(epoch == token) {
                deliveryAddress=value.address; language=value.language
                coverFullName=value.fullName; coverUsername=value.username
                coverType=value.coverType
                accountRole=value.role
                restoreCoverData(saved.uid)
                quickExit()
                if(!localMode) loadCloudHistory(saved.uid,value.username)
            }
        }
    }

    // Secret search entry lives here; keep comparing the trigger hash instead of plain text.
    fun interceptSearch(value: String): Boolean {
        if(accountRole!=AccountRole.USER) return false
        val device = enrollment ?: return false
        if(value.isBlank() || (InputPolicy.triggerHash(value) != device.triggerHash &&
                InputPolicy.legacyTriggerHash(value) != device.triggerHash)) return false
        if(screen == SessionScreen.Cover && !busy) {
            val event=TriggerEvent(source=if(coverType==CoverType.NEWS) "Daily Digest search" else "Olive search")
            activeTriggerId=event.id
            triggerJob=viewModelScope.launch(Dispatchers.IO) { store.recordTrigger(device.uid,event);queueHistorySync() }
            message=null; screen=SessionScreen.Pin
        }
        return true
    }

    // This controls the PIN screen result and sends attempt five straight back to the cover.
    fun submitPin(pin: String) {
        if(busy || screen != SessionScreen.Pin) return
        val device = enrollment ?: return
        if(!InputPolicy.validPin(pin)) { message="Enter your 4–6 digit PIN."; return }
        runOperation("Unable to check the PIN or record this attempt. Access remains locked.") { token ->
            val result = withContext(Dispatchers.IO) {
                triggerJob?.join()
                store.verify(device.uid,pin).also { outcome ->
                    activeTriggerId?.let { store.recordTriggerAttempt(device.uid,it,outcome is PinResult.Accepted) }
                    queueHistorySync()
                }
            }
            if(epoch != token || screen != SessionScreen.Pin) return@runOperation
            when(result) {
                PinResult.Accepted -> {
                    val saved = withContext(Dispatchers.IO) { store.unlockSnapshot(device.uid) }
                    if(epoch == token && screen == SessionScreen.Pin) {
                        busy=false
                        profile=saved.first; attempts=saved.second; pendingSync=saved.third
                        refreshHistory(device.uid)
                        screenHistory.clear();safePageHistory.clear();page=SafePage.Home;screen=SessionScreen.Safe
                        startIdleTimer()
                    }
                }
                is PinResult.Rejected -> if(result.failures >= 5) quickExit() else {
                    message="Incorrect PIN. ${5-result.failures} attempts remaining."
                }
            }
        }
    }

    // Calculator hands us a 4–6 digit candidate after two equals taps; a match opens the same safe area.
    fun submitCalculatorPin(pin: String) {
        if(busy || screen!=SessionScreen.Cover || coverType!=CoverType.CALCULATOR || !InputPolicy.validPin(pin)) return
        val device=enrollment ?: return
        val newTrigger=if(activeTriggerId==null) TriggerEvent(source="Better Calculator").also { activeTriggerId=it.id } else null
        val triggerId=activeTriggerId ?: return
        runOperation("Unable to check the PIN or record this attempt. Access remains locked.") { token ->
            val result=withContext(Dispatchers.IO) {
                // Reuse one calculator trigger session so a later success can follow its earlier wrong attempts.
                newTrigger?.let { store.recordTrigger(device.uid,it) }
                store.verify(device.uid,pin).also { outcome ->
                    store.recordTriggerAttempt(device.uid,triggerId,outcome is PinResult.Accepted)
                    queueHistorySync()
                }
            }
            if(epoch!=token || screen!=SessionScreen.Cover || coverType!=CoverType.CALCULATOR) return@runOperation
            when(result) {
                PinResult.Accepted -> {
                    val saved=withContext(Dispatchers.IO) { store.unlockSnapshot(device.uid) }
                    if(epoch==token && screen==SessionScreen.Cover) {
                        busy=false
                        profile=saved.first;attempts=saved.second;pendingSync=saved.third
                        refreshHistory(device.uid)
                        screenHistory.clear();safePageHistory.clear();page=SafePage.Home;screen=SessionScreen.Safe
                        startIdleTimer()
                    }
                }
                is PinResult.Rejected -> if(result.failures>=5) quickExit()
            }
        }
    }

    // Quick exit clears private state here; keep background() and MainActivity using this same path.
    fun quickExit() {
        epoch++
        operationJob?.cancel();operationJob=null
        idleJob?.cancel();idleJob=null;idleCountdownSeconds=null
        busy=false;checkoutActive=false;coverReset++;addressPrompt=false
        profile?.coverType?.let { coverType=it }
        if(accountRole==AccountRole.SAFE_CONTACT) {
            page=SafePage.Home;activityCategory=null;screenHistory.clear();safePageHistory.clear()
            message=null;stopMessaging();activeTriggerId=null;triggerJob=null;screen=SessionScreen.Safe
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
        stopMessaging()
        if(accountRole==AccountRole.USER && !localMode) syncLocalNotificationHandler()
        activeTriggerId=null;triggerJob=null
        pendingIdentity=null; pendingProfile=null
    }
    // If the app loses focus, play it safe and lock everything behind the cover again.
    fun background() {
        // Also discard partially entered onboarding fields when leaving the app.
        if(screen == SessionScreen.Loading || screen == SessionScreen.StorageError) return
        if(enrollment==null) {
            epoch++;operationJob?.cancel();operationJob=null;busy=false;pendingIdentity=null;pendingProfile=null
            screenHistory.clear();screen=SessionScreen.Welcome;message=null
        } else if(accountRole==AccountRole.USER) quickExit()
    }
    fun back() {
        if(screen == SessionScreen.Safe && page==SafePage.Messages && activeConversation!=null) closeConversation()
        else if(screen == SessionScreen.Safe && page==SafePage.Activity && activityCategory!=null) { activityCategory=null;message=null }
        else if(screen == SessionScreen.Safe && safePageHistory.isNotEmpty()) {
            page=safePageHistory.removeAt(safePageHistory.lastIndex);message=null
        }
        else if(screen == SessionScreen.Safe && page != SafePage.Home) { page=SafePage.Home;message=null }
        else if((screen == SessionScreen.Safe && accountRole==AccountRole.USER) || screen == SessionScreen.Pin) quickExit()
        else if(screenHistory.isNotEmpty()) { screen=screenHistory.removeAt(screenHistory.lastIndex);message=null }
        // Root cover/welcome/loading screens deliberately consume Back so Android does not open the phone home screen.
    }

    fun saveProvisionedTrigger(trigger:String) {
        val identity=pendingIdentity ?: return
        val existing=pendingProfile ?: return
        if(!InputPolicy.validTrigger(trigger)) { message="Choose a trigger word or phrase of 3–64 characters.";return }
        val updated=existing.copy(triggerHash=InputPolicy.triggerHash(trigger))
        runOperation("The trigger word could not be saved. Retry here.") { token ->
            accounts.saveProfile(identity,updated)
            withContext(Dispatchers.IO) { store.cacheProfile(identity.uid,updated) }
            if(epoch==token) { pendingProfile=updated;openScreen(SessionScreen.DevicePin) }
        }
    }

    fun saveOrganizationProfile(fullName:String,dob:String,email:String,phone:String) {
        val identity=pendingIdentity ?: return
        val repository=adminRepository ?: return
        if(fullName.isBlank() || !InputPolicy.validDob(dob) || !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
            message="Full name, a valid date of birth, and a real email are required.";return
        }
        runOperation("Unable to finish the organization profile. Check the email and try again.") { token ->
            val updated=repository.completeOrganizationProfile(fullName.trim(),dob.trim(),email.trim(),phone.trim())
            val saved=withContext(Dispatchers.IO) { store.enrollWithoutPin(identity,updated) }
            if(epoch==token) {
                enrollment=saved;profile=updated;pendingProfile=updated;accountRole=updated.role
                coverUsername=updated.username;coverFullName=updated.fullName;language=updated.language
                screenHistory.clear();screen=SessionScreen.Portal
                if(updated.role==AccountRole.ADMIN) queueAdminRefresh()
            }
        }
    }

    fun updateOrganizationProfile(fullName:String,dob:String,email:String,phone:String) {
        val device=enrollment ?: return
        val repository=adminRepository ?: return
        if(screen!=SessionScreen.Portal || accountRole !in setOf(AccountRole.STAFF,AccountRole.ADMIN) || busy) return
        if(fullName.isBlank() || !InputPolicy.validDob(dob) || !android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
            message="Full name, a valid date of birth, and a real email are required.";return
        }
        runOperation("Unable to update your Olive Tree profile.") { token ->
            val updated=repository.completeOrganizationProfile(fullName.trim(),dob.trim(),email.trim(),phone.trim())
            withContext(Dispatchers.IO) { store.cacheProfile(device.uid,updated) }
            if(epoch==token && screen==SessionScreen.Portal) {
                profile=updated;pendingProfile=updated;coverFullName=updated.fullName;message="Profile updated."
            }
        }
    }

    fun refreshAdminAccounts() {
        val repository=adminRepository ?: return
        if(accountRole!=AccountRole.ADMIN || busy) return
        runOperation("Unable to load organization accounts.") { token ->
            val (counts,rows)=repository.accounts()
            if(epoch==token && screen==SessionScreen.Portal) { adminCounts=counts;adminAccounts=rows }
        }
    }

    private fun queueAdminRefresh() {
        viewModelScope.launch {
            while(busy) delay(50.milliseconds)
            if(screen==SessionScreen.Portal && accountRole==AccountRole.ADMIN) refreshAdminAccounts()
        }
    }

    fun createManagedAccount(value:AdminAccountDraft) {
        val repository=adminRepository ?: return
        if(accountRole!=AccountRole.ADMIN || busy) return
        if(!InputPolicy.validUsername(value.username)) { message="Use 8–24 letters, numbers or underscores for the username.";return }
        if(!InputPolicy.validPassword(value.password)) { message="Use 8–24 characters with a letter, number and special character.";return }
        runOperation("Unable to create the account. The username or email may already be in use.") { token ->
            repository.create(value)
            val (counts,rows)=repository.accounts()
            if(epoch==token && screen==SessionScreen.Portal) { adminCounts=counts;adminAccounts=rows;message="${value.role.title} account created." }
        }
    }

    fun deleteManagedAccount(value:AdminAccountSummary) {
        val repository=adminRepository ?: return
        if(accountRole!=AccountRole.ADMIN || busy || value.protected) return
        runOperation("Unable to delete this account.") { token ->
            repository.delete(value.uid)
            val (counts,rows)=repository.accounts()
            if(epoch==token && screen==SessionScreen.Portal) { adminCounts=counts;adminAccounts=rows;message="${value.username} was deleted." }
        }
    }
    fun navigate(value: SafePage) {
        if(screen == SessionScreen.Safe) {
            if(value!=page) safePageHistory += page
            page=value; message=null
            if(value==SafePage.Activity) activityCategory=null
            val uid=enrollment?.uid
            if(uid!=null) {
                if(value==SafePage.Settings || value==SafePage.Profile || value==SafePage.Contacts)
                    recordSettingsInteraction("Olive Branch", "Opened ${value.name.lowercase()} page")
                if(value==SafePage.Activity || value==SafePage.Notes) refreshHistory(uid)
                if(value==SafePage.Messages) ensureMessagingObservation(showFeedback=true)
            }
        }
    }

    // Friend and conversation summaries stay live while Olive Branch is unlocked so Home can show pending activity.
    fun ensureMessagingObservation(showFeedback: Boolean=false) {
        if(socialSubscription!=null || messagingBusy) return
        startMessaging(showFeedback)
    }

    private fun startMessaging(showFeedback: Boolean=true) {
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
        viewModelScope.launch {
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

    fun reconnectMessaging(password: String, onConnected: ()->Unit = {}) {
        val device=enrollment ?: return
        if(localMode || messagingBusy || password.isBlank() || screen!=SessionScreen.Safe || page!=SafePage.Messages) return
        val token=epoch
        messagingBusy=true;message=null
        viewModelScope.launch {
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

    private fun observeFriendConversations(currentFriends: List<Friend>, token: Long) {
        val ids=currentFriends.map { it.friendshipId }.toSet()
        if(ids==observedFriendshipIds) return
        observedFriendshipIds=ids
        conversationSubscription?.close()
        conversationSubscription=messagingRepository?.observeConversations(ids.toList(),{ rows ->
            if(epoch==token && screen==SessionScreen.Safe) {
                conversations=rows
                activeConversation?.let { active -> rows.firstOrNull { it.id==active.id }?.let { updated ->
                    activeConversation=updated
                    markConversationRead(updated)
                } }
            }
        },{ error -> if(epoch==token && page==SafePage.Messages) message="Unable to load messages. $error" })
    }

    fun searchUsers(query: String) = runMessagingAction("Unable to search for users.") { repository,device,token ->
        val rows=repository.searchUsers(query,device.uid)
        if(epoch==token) {
            userSearchResults=rows
            message=if(rows.isEmpty()) "No user found for that exact username." else null
        }
    }

    fun relationshipFor(uid: String): FriendRelationship = when {
        friends.any { it.uid==uid } -> FriendRelationship.Friends
        incomingFriendRequests.any { it.senderUid==uid } -> FriendRelationship.IncomingRequest
        outgoingFriendRequests.any { it.recipientUid==uid } -> FriendRelationship.RequestSent
        else -> FriendRelationship.AddFriend
    }

    fun sendFriendRequest(user: DirectoryUser) = runMessagingAction("Unable to send this friend request.") { repository,device,_ ->
        repository.sendFriendRequest(AccountIdentity(device.uid,coverUsername),user)
    }

    fun acceptFriendRequest(request: FriendRequest) = runMessagingAction("Unable to accept this friend request.") { repository,device,_ ->
        repository.acceptRequest(AccountIdentity(device.uid,coverUsername),request)
    }

    fun declineFriendRequest(request: FriendRequest) = runMessagingAction("Unable to decline this friend request.") { repository,device,_ ->
        repository.declineRequest(device.uid,request)
    }

    fun cancelFriendRequest(request: FriendRequest) = runMessagingAction("Unable to cancel this friend request.") { repository,device,_ ->
        repository.cancelRequest(device.uid,request)
    }

    fun startConversation(friend: Friend) = runMessagingAction("Unable to open this conversation.") { repository,device,token ->
        val conversation=repository.conversationWith(AccountIdentity(device.uid,coverUsername),friend)
        if(epoch==token && screen==SessionScreen.Safe && page==SafePage.Messages) openConversation(conversation)
    }

    fun openConversation(conversation: ChatConversation) {
        if(screen!=SessionScreen.Safe || page!=SafePage.Messages) return
        val uid=enrollment?.uid ?: return
        if(uid !in conversation.participantIds) return
        messageSubscription?.close();chatMessages=emptyList();activeConversation=conversation;message=null
        markConversationRead(conversation)
        chatQuickExitHintVisible=accountRole==AccountRole.USER
        val token=epoch
        messageSubscription=messagingRepository?.observeMessages(conversation.id,{ rows ->
            if(epoch==token && activeConversation?.id==conversation.id) chatMessages=rows
        },{ error -> if(epoch==token) message="Unable to load this conversation. $error" })
    }

    fun closeConversation() {
        messageSubscription?.close();messageSubscription=null
        activeConversation=null;chatMessages=emptyList();message=null
    }

    fun sendMessage(text: String, onSent: ()->Unit = {}) {
        val repository=messagingRepository ?: return
        val device=enrollment ?: return
        val conversation=activeConversation ?: return
        if(messagingBusy) return
        val token=epoch
        messagingBusy=true;message=null
        viewModelScope.launch {
            try {
                repository.send(conversation.id,AccountIdentity(device.uid,coverUsername),text)
                if(epoch==token && activeConversation?.id==conversation.id) onSent()
            }
            catch(error: CancellationException) { throw error }
            catch(error: Exception) { if(epoch==token) message=error.localizedMessage ?: "Unable to send the message." }
            finally { if(epoch==token) messagingBusy=false }
        }
    }

    fun unsendMessage(chat: ChatMessage) {
        val conversation=activeConversation ?: return
        runMessagingAction("Unable to unsend this message.") { repository,device,_ ->
            repository.unsend(conversation.id,chat,device.uid)
        }
    }

    private fun runMessagingAction(failure: String, block: suspend (MessagingRepository,DeviceEnrollment,Long)->Unit) {
        val repository=messagingRepository ?: run { message="Messaging needs a Firebase-connected build.";return }
        val device=enrollment ?: return
        if(messagingBusy || messagingNeedsReconnect || screen!=SessionScreen.Safe || page!=SafePage.Messages) return
        val token=epoch
        messagingBusy=true;message=null
        viewModelScope.launch {
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

    private fun stopMessaging(clearRows: Boolean = true) {
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
    fun noteInteraction() {
        if(screen!=SessionScreen.Safe || accountRole!=AccountRole.USER) return
        lastTouchAt=SystemClock.elapsedRealtime()
        idleCountdownSeconds=null
    }
    fun captureDetected(kind: String) {
        if(privacyBlackout) return
        val uid=enrollment?.uid
        if(accountRole==AccountRole.USER) quickExit()
        privacyBlackout=true
        if(kind=="Screenshot") viewModelScope.launch {
            delay(1_500.milliseconds)
            privacyBlackout=false
        }
        if(uid==null) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.recordSettings(uid,SettingsEvent(area="Privacy",action="$kind detected"));queueHistorySync() }
        }
    }
    fun recordingStopped() { privacyBlackout=false }
    private fun startIdleTimer() {
        idleJob?.cancel()
        if(accountRole!=AccountRole.USER) {
            idleCountdownSeconds=null
            return
        }
        noteInteraction()
        idleJob=viewModelScope.launch {
            while(screen==SessionScreen.Safe) {
                delay(250.milliseconds)
                val countdown=IdlePolicy.countdown(SystemClock.elapsedRealtime()-lastTouchAt)
                if(countdown==0) { quickExit();break }
                idleCountdownSeconds=countdown
            }
        }
    }

    // Focus and button taps get a timestamp, while saved changes also get before/after values.
    fun recordSettingsInteraction(area: String, action: String) {
        if(area=="Olive Branch") return
        val uid=enrollment?.uid ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { store.recordSettings(uid,SettingsEvent(area=area,action=action));queueHistorySync() }
        }
    }
    // Cover state is local and account-scoped, so a declined checkout or app close won't empty it.
    private suspend fun restoreCoverData(uid: String) {
        val saved=withContext(Dispatchers.IO) { coverStore.restore(uid) }
        foodCart=saved.first.cart;foodFavorites=saved.first.favorites;foodCustomizations=saved.first.customizations;savedNews=saved.second
        coverDataVersion++
    }
    fun saveFoodCover(cart: Map<String,Int>, favorites: Set<String>, customizations: Map<String,Set<String>>) {
        val uid=enrollment?.uid ?: return
        foodCart=cart;foodFavorites=favorites;foodCustomizations=customizations
        val latest=FoodCoverState(cart,favorites,customizations)
        val revision=(foodRevisions[uid] ?: 0L)+1L
        foodRevisions[uid]=revision
        viewModelScope.launch {
            coverSaveMutex.withLock {
                if(foodRevisions[uid]!=revision) return@withLock
                withContext(Dispatchers.IO) { coverStore.saveFood(uid,latest) }
            }
        }
    }
    fun toggleNewsBookmark(article: NewsArticle) {
        val uid=enrollment?.uid ?: return
        savedNews=if(savedNews.any { it.link==article.link }) savedNews.filterNot { it.link==article.link }
            else (listOf(article)+savedNews).take(100)
        val latest=savedNews
        val revision=(newsRevisions[uid] ?: 0L)+1L
        newsRevisions[uid]=revision
        viewModelScope.launch {
            coverSaveMutex.withLock {
                if(newsRevisions[uid]!=revision) return@withLock
                withContext(Dispatchers.IO) { coverStore.saveNews(uid,latest) }
            }
        }
    }
    private fun refreshHistory(uid: String) {
        viewModelScope.launch {
            val (histories,cleanupNeeded)=withContext(Dispatchers.IO) {
                val purged=store.purgeExpiredPrivateEntries(uid)
                Triple(store.settingsHistory(uid),store.triggerHistory(uid),store.checkoutHistory(uid)) to purged
            }
            val entries=withContext(Dispatchers.IO) { store.privateEntries(uid) }
            if(enrollment?.uid==uid) {
                settingsHistory=histories.first.filter { it.area!="Privacy" && (it.area!="Olive Branch" || it.changes.isNotEmpty()) }
                privacyHistory=histories.first.filter { it.area=="Privacy" }
                triggerHistory=histories.second;checkoutHistory=histories.third;privateEntries=entries
            }
            if(cleanupNeeded) queueHistorySync()
        }
    }
    fun showActivityCategory(value: ActivityCategory) {
        if(screen==SessionScreen.Safe) { activityCategory=value;page=SafePage.Activity;message=null }
    }
    fun activityBack() {
        if(activityCategory!=null) activityCategory=null else back()
    }

    // Opening a note starts with a clean editor instead of carrying over the last Trash notice.
    fun clearPrivateEntryStatus() {
        if(screen==SessionScreen.Safe && page==SafePage.Notes) message=null
    }

    // Private writing is encrypted on this phone first, then the normal account sync uploads one owner-only object.
    fun savePrivateEntry(id: String?, kind: PrivateEntryKind?, title: String, body: String) {
        if(screen!=SessionScreen.Safe || page!=SafePage.Notes || busy) return
        val cleanTitle=title.trim()
        val cleanBody=body.trim()
        if(cleanBody.isBlank()) { message="Write something before saving."; return }
        if(cleanTitle.length>120 || cleanBody.length>10_000) { message="Use up to 120 title characters and 10,000 detail characters."; return }
        val uid=enrollment?.uid ?: return
        val existing=id?.let { wanted -> privateEntries.firstOrNull { it.id==wanted && it.deletedAt==null } }
        val changes=existing?.let { old -> buildList {
            fun changed(field: String, before: String, after: String) { if(before!=after) add(FieldChange(field,before,after)) }
            changed("Tag",old.kind?.name ?: "No tag",kind?.name ?: "No tag")
            changed("Title",old.title,cleanTitle)
            changed("Details",old.body,cleanBody)
        } }.orEmpty()
        if(existing!=null && changes.isEmpty()) { message="No changes to save."; return }
        val now=System.currentTimeMillis()
        val entry=PrivateEntry(id=existing?.id ?: UUID.randomUUID().toString(),timestamp=existing?.timestamp ?: now,
            updatedAt=now,kind=kind,title=cleanTitle,body=cleanBody,
            pinned=existing?.pinned ?: false,
            edits=existing?.edits.orEmpty()+if(existing==null) emptyList() else listOf(PrivateEntryEdit(timestamp=now,changes=changes)))
        runOperation("Unable to save this private entry. Please retry.") { token ->
            withContext(Dispatchers.IO) { store.savePrivateEntry(uid,entry) }
            if(epoch==token && screen==SessionScreen.Safe && page==SafePage.Notes) {
                privateEntries=withContext(Dispatchers.IO) { store.privateEntries(uid) }
                message=if(localMode) "Saved privately on this device." else "Saved privately. Syncing to your account."
            }
            queueHistorySync()
        }
    }
    fun togglePrivateEntryPin(id: String) {
        if(screen!=SessionScreen.Safe || page!=SafePage.Notes || busy) return
        val uid=enrollment?.uid ?: return
        val existing=privateEntries.firstOrNull { it.id==id && it.deletedAt==null } ?: return
        val now=System.currentTimeMillis()
        val pinned=!existing.pinned
        // Pinning changes placement only, so it never creates a content revision in the edit log.
        val entry=existing.copy(updatedAt=now,pinned=pinned)
        runOperation("Unable to update this private entry. Please retry.") { token ->
            withContext(Dispatchers.IO) { store.savePrivateEntry(uid,entry) }
            if(epoch==token && screen==SessionScreen.Safe && page==SafePage.Notes)
                privateEntries=withContext(Dispatchers.IO) { store.privateEntries(uid) }
            queueHistorySync()
        }
    }
    fun trashPrivateEntry(id: String) {
        val existing=privateEntries.firstOrNull { it.id==id && it.deletedAt==null } ?: return
        val now=System.currentTimeMillis()
        updatePrivateEntry(existing.copy(updatedAt=now,pinned=false,deletedAt=now),
            "Unable to move this entry to Trash. Please retry.",
            if(PRIVATE_ENTRY_TRASH_RETENTION_MS<60_000L) "Moved to Trash for 10 seconds." else "Moved to Trash for 30 days.")
        val uid=enrollment?.uid ?: return
        viewModelScope.launch {
            delay((PRIVATE_ENTRY_TRASH_RETENTION_MS+100L).milliseconds)
            val purged=withContext(Dispatchers.IO) { store.purgeExpiredPrivateEntries(uid) }
            if(purged && enrollment?.uid==uid) {
                privateEntries=withContext(Dispatchers.IO) { store.privateEntries(uid) }
                queueHistorySync()
            }
        }
    }

    fun dismissChatQuickExitHint() {
        chatQuickExitHintVisible=false
    }
    private fun readMessageKey(uid:String,conversationId:String)="read_message_${uid}_$conversationId"
    private fun markConversationRead(conversation:ChatConversation) {
        val uid=enrollment?.uid ?: return
        if(conversation.lastMessageId.isBlank()) return
        val key=readMessageKey(uid,conversation.id)
        if(messageReadState.getString(key,null)==conversation.lastMessageId) return
        messageReadState.edit { putString(key,conversation.lastMessageId) }
        readMessageVersion++
    }
    fun restorePrivateEntry(id: String) {
        val existing=privateEntries.firstOrNull { it.id==id && it.deletedAt!=null } ?: return
        updatePrivateEntry(existing.copy(updatedAt=System.currentTimeMillis(),deletedAt=null),
            "Unable to restore this entry. Please retry.","Restored from Trash.")
    }
    private fun updatePrivateEntry(entry: PrivateEntry, failure: String, success: String) {
        if(screen!=SessionScreen.Safe || page!=SafePage.Notes || busy) return
        val uid=enrollment?.uid ?: return
        runOperation(failure) { token ->
            withContext(Dispatchers.IO) { store.savePrivateEntry(uid,entry) }
            if(epoch==token && screen==SessionScreen.Safe && page==SafePage.Notes) {
                privateEntries=withContext(Dispatchers.IO) { store.privateEntries(uid) }
                message=success
            }
            queueHistorySync()
        }
    }
    private fun loadCloudHistory(uid: String, username: String) {
        viewModelScope.launch {
            // Paint the local cover first; cloud reconciliation does not belong on the launch critical path.
            delay(1_000.milliseconds)
            val identity=AccountIdentity(uid,username)
            // Messaging registration failing must never block the existing profile/history sync path.
            runCatching { registerMessagingIdentity(identity) }
            runCatching {
                accounts.profile(identity) // Reading once upgrades the existing profile object to readable JSON.
                val records=accounts.historyRecords(identity)
                withContext(Dispatchers.IO) { store.mergeHistory(uid,records) }
                refreshHistory(uid)
            }
            queueHistorySync()
        }
    }
    private fun queueHistorySync() {
        if(localMode || enrollment==null) return
        historySyncJob?.cancel()
        val uid=enrollment!!.uid
        val username=coverUsername
        historySyncJob=viewModelScope.launch {
            delay(750.milliseconds)
            historySyncMutex.withLock {
                while(enrollment?.uid==uid) {
                    val pending=withContext(Dispatchers.IO) { store.pendingHistory(uid) }
                    if(pending.isEmpty()) break
                    for(record in pending) {
                        try {
                            accounts.saveHistoryRecord(AccountIdentity(uid,username),record)
                            withContext(Dispatchers.IO) { store.markHistorySynced(uid,record) }
                        } catch(error: CancellationException) { throw error }
                        catch(_: Exception) { return@withLock }
                    }
                }
            }
        }
    }
    // Keep only the foods, time, total, and displayed error from a declined food checkout.
    suspend fun recordCheckout(foods: List<OrderLine>, totalCents: Int, error: String) {
        val uid=enrollment?.uid ?: return
        withContext(Dispatchers.IO) {
            store.recordCheckout(uid,CheckoutEvent(foods=foods,totalCents=totalCents,error=error))
        }
        checkoutHistory=withContext(Dispatchers.IO) { store.checkoutHistory(uid) }
        queueHistorySync()
    }

    fun dismissAddressPrompt() { addressPrompt=false }
    // Address saving and popup auto-close happen here; edit the fields in CheckoutScreens.
    fun saveAddress(address: DeliveryAddress) {
        val device=enrollment ?: return
        recordSettingsInteraction("Cover","Pressed save delivery address")
        if(busy || !address.valid) { message="Enter a complete delivery address."; return }
        runOperation("Unable to save the address. Please retry.") { token ->
            val updated=withContext(Dispatchers.IO) {
                val before=store.profile(device.uid)
                val after=before.copy(address=address,region=address.generalLocation)
                store.cacheProfile(device.uid,after,pending=!localMode,
                    audit=SettingsEvent(area="Cover",action="Saved delivery address",changes=profileChanges(before,after)))
                after
            }
            if(epoch==token) {
                deliveryAddress=address; profile=profile?.copy(address=address)
                addressPrompt=false; addressSaveVersion++; message="Address saved."
            }
            queueHistorySync()
            if(!localMode) syncProfile(device.uid,updated,token)
        }
    }
    // Full private profile settings save here; edit their form in SessionScreens.SettingsEditor.
    fun saveSettings(fullName: String, affected: String, abuserName: String, dob: String, selectedLanguage: String, situation: String, address: DeliveryAddress) {
        if(screen!=SessionScreen.Safe || busy) return
        recordSettingsInteraction("Olive Branch","Pressed save profile settings")
        if(fullName.isBlank() || !InputPolicy.validDob(dob) || selectedLanguage.isBlank() || !address.locationValid) { message="Full name, a valid date of birth, language and city are required."; return }
        val updated=profile?.copy(fullName=fullName.trim(),affectedNames=affected.trim(),abuserName=abuserName.trim(),dob=dob,language=selectedLanguage,situation=situation.trim(),region=address.generalLocation,address=address) ?: return
        saveSafeProfile(updated,"Saved profile settings")
    }
    // Cover settings save only normal food-app details; edit their form in OliveApp.Account.
    fun saveCoverSettings(fullName: String, selectedLanguage: String) {
        if(screen!=SessionScreen.Cover || busy) return
        recordSettingsInteraction("Cover","Pressed save account settings")
        val supportedLangs = setOf("English","French","Spanish","Arabic","Tagalog","Chinese","Cree","en","fr","es","ar","tl","zh","cr")
        if(fullName.isBlank() || selectedLanguage !in supportedLangs) {
            message="Enter your full name and preferred language."; return
        }
        val device=enrollment ?: return
        runOperation("Unable to save account settings.") { token ->
            val updated=withContext(Dispatchers.IO) {
                val before=store.profile(device.uid)
                val after=before.copy(fullName=fullName.trim(),language=selectedLanguage)
                store.cacheProfile(device.uid,after,pending=!localMode,
                    audit=SettingsEvent(area="Cover",action="Saved account settings",changes=profileChanges(before,after)))
                after
            }
            if(epoch==token && screen==SessionScreen.Cover) {
                coverFullName=updated.fullName; language=updated.language
                pendingSync=!localMode; message="Account settings saved."
            }
            queueHistorySync()
            if(!localMode) syncProfile(device.uid,updated,token)
        }
    }
    private fun saveSafeProfile(updated: UserProfile, action: String) {
        val device=enrollment ?: return
        val before=profile ?: return
        runOperation("Unable to save profile changes.") { token ->
            withContext(Dispatchers.IO) { store.cacheProfile(device.uid,updated,pending=!localMode,
                audit=SettingsEvent(area="Olive Branch",action=action,changes=profileChanges(before,updated))) }
            if(epoch==token) { profile=updated; coverFullName=updated.fullName; language=updated.language; deliveryAddress=updated.address; pendingSync=!localMode; message="Changes saved." }
            queueHistorySync()
            if(!localMode) syncProfile(device.uid,updated,token)
        }
    }
    // Safe settings can swap covers; the game stays unavailable until it has a real screen.
    fun changeCover(type: CoverType) {
        if(screen != SessionScreen.Safe || busy || accountRole!=AccountRole.USER) return
        if(type == CoverType.GAME) { message="In development"; return }
        val before=profile ?: return
        if(type==before.coverType) return
        val updated=before.copy(coverType=type)
        val device=enrollment ?: return
        // Show the chosen cover right away; saving keeps going even if Quick exit is tapped immediately.
        val token=epoch
        profile=updated;coverType=type;busy=true;message=null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.cacheProfile(device.uid,updated,pending=!localMode,
                    audit=SettingsEvent(area="Olive Branch",action="Changed cover app",changes=profileChanges(before,updated))) }
                if(epoch==token) { pendingSync=!localMode;message="Cover app changed to ${type.title}." }
                queueHistorySync()
                if(!localMode) syncProfile(device.uid,updated,token)
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { if(epoch==token) message="Unable to change the cover app." }
            finally { if(epoch==token) busy=false }
        }
    }

    private suspend fun registerMessagingIdentity(identity:AccountIdentity) {
        val marker=messagingIdentityMarker(identity)
        if(!shouldRegisterMessagingIdentity(cloudState.getString("registered_identity",null),identity)) return
        messagingRepository?.registerUser(identity)
        cloudState.edit { putString("registered_identity",marker) }
    }

    // The phone watches small conversation markers locally and schedules a cover-safe notification when one changes.
    fun syncLocalNotificationHandler() {
        val device=enrollment ?: return
        if(localMode) return
        if(accounts.authenticatedUid()==device.uid)
            LocalNotificationEventHandler.start(getApplication(),coverType,accountRole)
    }
    // Trigger changes require the current PIN here; keep the new trigger stored as a hash.
    fun changeTrigger(trigger: String, pin: String) {
        if(screen!=SessionScreen.Safe || busy || accountRole!=AccountRole.USER) return
        recordSettingsInteraction("Olive Branch","Pressed change trigger word")
        val device=enrollment ?: return
        if(!InputPolicy.validTrigger(trigger) || !InputPolicy.validPin(pin)) { message="Enter a trigger of 3–64 characters and your 4–6 digit PIN."; return }
        runOperation("Unable to change the trigger. Please retry.") { token ->
            val result=withContext(Dispatchers.IO) { store.verify(device.uid,pin) }
            if(epoch!=token || screen!=SessionScreen.Safe) return@runOperation
            when(result) {
                PinResult.Accepted -> {
                    val before=profile!!
                    val updated=before.copy(triggerHash=InputPolicy.triggerHash(trigger))
                    withContext(Dispatchers.IO) { store.updateTrigger(device.uid,updated,!localMode,
                        SettingsEvent(area="Olive Branch",action="Changed trigger word",changes=profileChanges(before,updated))) }
                    enrollment=device.copy(triggerHash=updated.triggerHash)
                    if(epoch==token) { profile=updated; message="Trigger word changed."; pendingSync=!localMode }
                    queueHistorySync()
                    if(!localMode) syncProfile(device.uid,updated,token)
                }
                is PinResult.Rejected -> {
                    attempts=withContext(Dispatchers.IO) { store.attempts(device.uid) }
                    if(result.failures>=5) quickExit() else message="Incorrect PIN. ${5-result.failures} attempts remaining."
                }
            }
        }
    }

    // Contact changes save here; edit the contact form in SessionScreens.ContactEditor.
    fun saveContacts(value: List<SafeContact>) {
        if(screen != SessionScreen.Safe || busy) return
        recordSettingsInteraction("Olive Branch","Pressed save safe contacts")
        val device = enrollment ?: return
        val before = profile ?: return
        val updated = before.copy(contacts=value)
        runOperation("Unable to save contacts on this device. Please retry.") { token ->
            withContext(Dispatchers.IO) { store.cacheProfile(device.uid,updated,pending=!localMode,
                audit=SettingsEvent(area="Olive Branch",action="Saved safe contacts",changes=profileChanges(before,updated))) }
            if(epoch == token) { profile=updated; pendingSync=!localMode; message="Contacts saved on this device." }
            queueHistorySync()
            if(!localMode) syncProfile(device.uid,updated,token)
        }
    }
    fun sync() {
        val device=enrollment ?: return
        val value=profile ?: return
        if(screen != SessionScreen.Safe || busy || localMode) return
        recordSettingsInteraction("Olive Branch","Pressed sync changes")
        runOperation("Changes are saved on this device. Cloud sync is unavailable; retry when connected.") { token ->
            syncProfile(device.uid,value,token)
            queueHistorySync()
        }
    }
    private suspend fun syncProfile(uid: String, value: UserProfile, token: Long) {
        try {
            withTimeout(15_000.milliseconds) { accounts.saveProfile(AccountIdentity(uid,value.username),value) }
            withContext(Dispatchers.IO) { store.markSynced(uid) }
            if(epoch == token) { pendingSync=false; message="Changes synced." }
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) {
            if(epoch == token) { pendingSync=true; message="Saved on this device. Cloud sync failed; retry when connected." }
        }
    }
    // Sign out lives only inside Olive Branch, then forget this phone's account link.
    fun signOut() {
        if((screen != SessionScreen.Safe && screen != SessionScreen.Portal) || busy || enrollment == null) return
        val uid=enrollment!!.uid
        LocalNotificationEventHandler.cancel(getApplication())
        quickExit()
        historySyncJob?.cancel();historySyncJob=null
        busy=true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    store.recordSettings(uid,SettingsEvent(area="Olive Branch",action="Signed out on this device"))
                    store.forgetDevice()
                }
                if(!localMode) runCatching {
                    withTimeoutOrNull(2_000.milliseconds) {
                        store.pendingHistory(uid).forEach { record ->
                            accounts.saveHistoryRecord(AccountIdentity(uid,coverUsername),record)
                            withContext(Dispatchers.IO) { store.markHistorySynced(uid,record) }
                        }
                    }
                }
                accounts.signOut(); enrollment=null; deliveryAddress=DeliveryAddress(); language="English"
                coverFullName=""; coverUsername=""; coverType=CoverType.FOOD;accountRole=AccountRole.USER;profile=null
                foodCart=emptyMap();foodFavorites=emptySet();foodCustomizations=emptyMap();savedNews=emptyList();coverDataVersion++
                screen=SessionScreen.Welcome
            } catch(_: Exception) { /* Fail closed: the interface remains locked. */ }
            finally { busy=false }
        }
    }
    private fun runOperation(failure: String, block: suspend (Long) -> Unit) {
        val token=epoch
        busy=true; message=null
        operationJob=viewModelScope.launch {
            val runningJob=coroutineContext.job
            var finalFailure: String? = null
            try { withTimeout(25_000.milliseconds) { block(token) } }
            catch(_: TimeoutCancellationException) { if(epoch == token) finalFailure="The operation timed out. Check your connection and retry; your account may already exist." }
            catch(e: CancellationException) { throw e }
            catch(_: Exception) { if(epoch == token) finalFailure=failure }
            finally {
                if(operationJob===runningJob) {
                    operationJob=null;busy=false
                    finalFailure?.let { message=it }
                }
            }
        }
    }

    // Password changes verify the current password first; usernames stay permanent.
    fun changePassword(currentPassword: String, newPassword: String, confirmation: String) {
        if(screen !in setOf(SessionScreen.Safe,SessionScreen.Portal) || busy) return
        val area=if(screen==SessionScreen.Portal) "Olive Tree" else "Olive Branch"
        if(screen==SessionScreen.Safe) recordSettingsInteraction(area,"Pressed change account password")
        val device=enrollment ?: return
        val username=profile?.username ?: return
        if(currentPassword.isEmpty()) { message="Enter your current password."; return }
        if(!InputPolicy.validPassword(newPassword)) { message="Use 8–24 characters with a letter, number and special character."; return }
        if(newPassword!=confirmation) { message="The new passwords do not match."; return }
        runOperation("Unable to change the password. Check your current password and connection.") { token ->
            accounts.changePassword(AccountIdentity(device.uid,username),currentPassword,newPassword)
            withContext(Dispatchers.IO) { store.recordSettings(device.uid,SettingsEvent(area=area,
                action="Changed account password",changes=listOf(FieldChange("Password","Hidden","Changed")))) }
            queueHistorySync()
            if(epoch==token && screen in setOf(SessionScreen.Safe,SessionScreen.Portal)) message="Password changed."
        }
    }

    // Device PIN changes use the same failed-attempt log and fifth-try quick exit.
    fun changePin(currentPin: String, newPin: String, confirmation: String) {
        if(screen!=SessionScreen.Safe || busy) return
        recordSettingsInteraction("Olive Branch","Pressed change device PIN")
        val device=enrollment ?: return
        if(!InputPolicy.validPin(currentPin) || !InputPolicy.validPin(newPin)) { message="Enter 4–6 digits for both PINs."; return }
        if(newPin!=confirmation) { message="The new PINs do not match."; return }
        runOperation("Unable to change the PIN.") { token ->
            when(val result=withContext(Dispatchers.IO) { store.changePin(device.uid,currentPin,newPin) }) {
                PinResult.Accepted -> {
                    withContext(Dispatchers.IO) { store.recordSettings(device.uid,SettingsEvent(area="Olive Branch",
                        action="Changed device PIN",changes=listOf(FieldChange("Device PIN","Hidden","Changed")))) }
                    queueHistorySync()
                    if(epoch==token && screen==SessionScreen.Safe) message="Device PIN changed."
                }
                is PinResult.Rejected -> {
                    attempts=withContext(Dispatchers.IO) { store.attempts(device.uid) }
                    if(result.failures>=5) quickExit() else message="Incorrect PIN. ${5-result.failures} attempts remaining."
                }
            }
        }
    }

    override fun onCleared() {
        stopMessaging()
    }
}

