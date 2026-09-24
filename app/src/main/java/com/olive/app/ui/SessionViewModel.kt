package com.olive.app.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.olive.app.BuildConfig
import com.olive.app.data.*
import com.olive.app.security.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

enum class SessionScreen { Loading, Welcome, NewCredentials, ReturningCredentials, ProfileSetup, DevicePin, Cover, Pin, Safe, StorageError }
enum class SafePage { Home, Profile, Contacts, Attempts, Settings, Activity }

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
    var screen by mutableStateOf(SessionScreen.Loading); private set
    var page by mutableStateOf(SafePage.Home); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null); private set
    var profile by mutableStateOf<UserProfile?>(null); private set
    var attempts by mutableStateOf(emptyList<FailedAttempt>()); private set
    var settingsHistory by mutableStateOf(emptyList<SettingsEvent>()); private set
    var privacyHistory by mutableStateOf(emptyList<SettingsEvent>()); private set
    var triggerHistory by mutableStateOf(emptyList<TriggerEvent>()); private set
    var checkoutHistory by mutableStateOf(emptyList<CheckoutEvent>()); private set
    var pendingSync by mutableStateOf(false); private set
    var deliveryAddress by mutableStateOf(DeliveryAddress()); private set
    var addressPrompt by mutableStateOf(false); private set
    var addressSaveVersion by mutableStateOf(0); private set
    var language by mutableStateOf("English"); private set
    var coverFullName by mutableStateOf(""); private set
    var coverUsername by mutableStateOf(""); private set
    var coverType by mutableStateOf(CoverType.FOOD); private set
    var foodCart by mutableStateOf<Map<String,Int>>(emptyMap()); private set
    var foodFavorites by mutableStateOf<Set<String>>(emptySet()); private set
    var savedNews by mutableStateOf<List<NewsArticle>>(emptyList()); private set
    var coverDataVersion by mutableStateOf(0); private set
    var checkoutActive by mutableStateOf(false)
    var idleCountdownSeconds by mutableStateOf<Int?>(null); private set
    var privacyBlackout by mutableStateOf(false); private set
    var coverReset by mutableStateOf(0); private set
    private var enrollment by mutableStateOf<DeviceEnrollment?>(null)
    private var pendingIdentity: AccountIdentity? = null
    private var pendingProfile: UserProfile? = null
    private var operationJob: Job? = null
    private var triggerJob: Job? = null
    private var historySyncJob: Job? = null
    private var idleJob: Job? = null
    private var lastTouchAt=0L
    private val historySyncMutex=Mutex()
    private var activeTriggerId: String? = null
    private var epoch = 0L
    val configured get() = enrollment != null
    val coverAccountId get() = enrollment?.uid
    val sensitive get() = screen != SessionScreen.Cover || checkoutActive
    val setupUsername get() = pendingIdentity?.username.orEmpty()

    init { load() }
    fun load() {
        if(busy) return
        busy = true
        screen = SessionScreen.Loading
        viewModelScope.launch {
            try {
                check(BuildConfig.DEBUG || !localMode)
                enrollment = withContext(Dispatchers.IO) { store.current() }
                enrollment?.let { device ->
                    val saved = withContext(Dispatchers.IO) { store.profile(device.uid) }
                    deliveryAddress=saved.address; language=saved.language
                    coverFullName=saved.fullName; coverUsername=saved.username
                    coverType=saved.coverType
                    restoreCoverData(device.uid)
                    checkoutHistory=withContext(Dispatchers.IO) { store.checkoutHistory(device.uid) }
                    if(!localMode) loadCloudHistory(device.uid,saved.username)
                }
                screen = if(enrollment == null) SessionScreen.Welcome else SessionScreen.Cover
            } catch(_: Exception) { screen = SessionScreen.StorageError }
            finally { busy = false }
        }
    }
    fun chooseNew() { if(!busy) { message=null; screen=SessionScreen.NewCredentials } }
    fun chooseReturning() { if(!busy) { message=null; screen=SessionScreen.ReturningCredentials } }
    fun setup() { if(!busy) { message=null; screen=SessionScreen.Welcome } }
    fun error(text: String) { message = text }

    // Account creation and returning sign-in meet here; Firebase calls live in AccountRepository.
    fun authenticate(username: String, password: String, create: Boolean) {
        if(busy) return
        if(!InputPolicy.validUsername(username)) { message="Use 4–24 letters, numbers or underscores for your username."; return }
        if(create && !InputPolicy.validPassword(password)) { message="Use a password of 8–24 characters."; return }
        if(password.isEmpty()) { message="Enter your password."; return }
        runOperation("Unable to sign in. Check your credentials and connection, then try again.") { token ->
            val identity = if(create) accounts.create(username,password) else accounts.authenticate(username,password)
            val remote = accounts.profile(identity)
            val existing = withContext(Dispatchers.IO) {
                if(store.pendingSync(identity.uid)) store.profile(identity.uid) else remote
            }
            if(epoch == token) {
                pendingIdentity = identity
                pendingProfile = existing
                screen = if(existing == null) SessionScreen.ProfileSetup else SessionScreen.DevicePin
            }
        }
    }

    // Onboarding saves here; add new fields to UserProfile and ProfileSetup at the same time.
    fun saveSetupProfile(value: UserProfile) {
        val identity = pendingIdentity ?: return
        if(busy) return
        if(value.fullName.isBlank() || !InputPolicy.validDob(value.dob) || value.language.isBlank() || !value.address.locationValid) {
            message="Full name, a valid date of birth, language and city are required."; return
        }
        runOperation("Your account exists, but the profile was not saved. Retry here, or sign in again to finish setup.") { token ->
            accounts.saveProfile(identity,value)
            withContext(Dispatchers.IO) { store.cacheProfile(identity.uid,value) }
            if(epoch == token) { pendingProfile=value; screen=SessionScreen.DevicePin }
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
                restoreCoverData(saved.uid)
                quickExit()
                if(!localMode) loadCloudHistory(saved.uid,value.username)
            }
        }
    }

    // Secret search entry lives here; keep comparing the trigger hash instead of plain text.
    fun interceptSearch(value: String): Boolean {
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
                        page=SafePage.Home; screen=SessionScreen.Safe
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
        val newTrigger=if(activeTriggerId==null) TriggerEvent(source="Simple Calculator").also { activeTriggerId=it.id } else null
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
                        page=SafePage.Home;screen=SessionScreen.Safe
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
        screen=SessionScreen.Cover
        page=SafePage.Home
        profile=null; attempts=emptyList(); message=null; pendingSync=false
        settingsHistory=emptyList();privacyHistory=emptyList();triggerHistory=emptyList()
        activeTriggerId=null;triggerJob=null
        pendingIdentity=null; pendingProfile=null
    }
    // If the app loses focus, play it safe and lock everything behind the cover again.
    fun background() {
        // Also discard partially entered onboarding fields when leaving the app.
        if(screen != SessionScreen.Loading && screen != SessionScreen.StorageError) quickExit()
    }
    fun back() {
        if(screen == SessionScreen.Safe && page != SafePage.Home) { page=SafePage.Home; message=null }
        else quickExit()
    }
    fun navigate(value: SafePage) {
        if(screen == SessionScreen.Safe) {
            page=value; message=null
            val uid=enrollment?.uid
            if(uid!=null) {
                if(value==SafePage.Settings || value==SafePage.Profile || value==SafePage.Contacts)
                    recordSettingsInteraction("Olive Branch", "Opened ${value.name.lowercase()} page")
                if(value==SafePage.Activity) refreshHistory(uid)
            }
        }
    }
    fun noteInteraction() {
        if(screen!=SessionScreen.Safe) return
        lastTouchAt=SystemClock.elapsedRealtime()
        idleCountdownSeconds=null
    }
    fun captureDetected(kind: String) {
        if(privacyBlackout) return
        val uid=enrollment?.uid
        quickExit()
        privacyBlackout=true
        if(kind=="Screenshot") viewModelScope.launch {
            delay(1_500)
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
        noteInteraction()
        idleJob=viewModelScope.launch {
            while(screen==SessionScreen.Safe) {
                delay(250)
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
        val saved=withContext(Dispatchers.IO) { coverStore.food(uid) to coverStore.news(uid) }
        foodCart=saved.first.cart;foodFavorites=saved.first.favorites;savedNews=saved.second
        coverDataVersion++
    }
    fun saveFoodCover(cart: Map<String,Int>, favorites: Set<String>) {
        val uid=enrollment?.uid ?: return
        foodCart=cart;foodFavorites=favorites
        val latest=FoodCoverState(cart,favorites)
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
            val histories=withContext(Dispatchers.IO) {
                Triple(store.settingsHistory(uid),store.triggerHistory(uid),store.checkoutHistory(uid))
            }
            if(enrollment?.uid==uid) {
                settingsHistory=histories.first.filter { it.area!="Privacy" && (it.area!="Olive Branch" || it.changes.isNotEmpty()) }
                privacyHistory=histories.first.filter { it.area=="Privacy" }
                triggerHistory=histories.second;checkoutHistory=histories.third
            }
        }
    }
    private fun loadCloudHistory(uid: String, username: String) {
        viewModelScope.launch {
            runCatching {
                val records=accounts.historyRecords(AccountIdentity(uid,username))
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
            delay(750)
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
        if(fullName.isBlank() || selectedLanguage !in setOf("English","French")) {
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
        if(screen != SessionScreen.Safe || busy) return
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
    // Trigger changes require the current PIN here; keep the new trigger stored as a hash.
    fun changeTrigger(trigger: String, pin: String) {
        if(screen!=SessionScreen.Safe || busy) return
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
            withTimeout(15_000) { accounts.saveProfile(AccountIdentity(uid,value.username),value) }
            withContext(Dispatchers.IO) { store.markSynced(uid) }
            if(epoch == token) { pendingSync=false; message="Changes synced." }
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) {
            if(epoch == token) { pendingSync=true; message="Saved on this device. Cloud sync failed; retry when connected." }
        }
    }
    // Sign out lives only inside Olive Branch, then forget this phone's account link.
    fun signOut() {
        if(screen != SessionScreen.Safe || busy || enrollment == null) return
        val uid=enrollment!!.uid
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
                    withTimeoutOrNull(2_000) {
                        store.pendingHistory(uid).forEach { record ->
                            accounts.saveHistoryRecord(AccountIdentity(uid,coverUsername),record)
                            withContext(Dispatchers.IO) { store.markHistorySynced(uid,record) }
                        }
                    }
                }
                accounts.signOut(); enrollment=null; deliveryAddress=DeliveryAddress(); language="English"
                coverFullName=""; coverUsername=""; coverType=CoverType.FOOD
                foodCart=emptyMap();foodFavorites=emptySet();savedNews=emptyList();coverDataVersion++
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
            try { withTimeout(25_000) { block(token) } }
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
        if(screen!=SessionScreen.Safe || busy) return
        recordSettingsInteraction("Olive Branch","Pressed change account password")
        val device=enrollment ?: return
        val username=profile?.username ?: return
        if(currentPassword.isEmpty()) { message="Enter your current password."; return }
        if(!InputPolicy.validPassword(newPassword)) { message="Use a new password of 8–24 characters."; return }
        if(newPassword!=confirmation) { message="The new passwords do not match."; return }
        runOperation("Unable to change the password. Check your current password and connection.") { token ->
            accounts.changePassword(AccountIdentity(device.uid,username),currentPassword,newPassword)
            withContext(Dispatchers.IO) { store.recordSettings(device.uid,SettingsEvent(area="Olive Branch",
                action="Changed account password",changes=listOf(FieldChange("Password","Hidden","Changed")))) }
            queueHistorySync()
            if(epoch==token && screen==SessionScreen.Safe) message="Password changed."
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
}

