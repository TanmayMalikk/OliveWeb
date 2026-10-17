package com.olive.app.ui.session

import android.app.Application
import androidx.compose.runtime.*
import com.olive.app.BuildConfig
import com.olive.app.data.*
import com.olive.app.security.*
import com.olive.app.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex

// Shares dependencies, account identity, jobs and cancellation epoch across feature controllers.
internal class SessionRuntime(val application:Application,val scope:CoroutineScope) {
    val state:SessionState=SessionState { canEnterSafe() }
    val localMode = !BuildConfig.FIREBASE_CONFIGURED
    val vault = EncryptedVault(application, if(localMode) "demo" else "firebase")
    val activityVault = EncryptedVault(application, if(localMode) "demo-activity" else "firebase-activity")
    fun phoneSecure() = application.getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure
    var deviceSecure by mutableStateOf(phoneSecure())
    val store = DeviceStore(vault,activityVault,::phoneSecure)
    val coverStore=CoverStateStore(EncryptedVault(application,"cover"))
    val coverSaveMutex=Mutex()
    val foodRevisions=mutableMapOf<String,Long>()
    val newsRevisions=mutableMapOf<String,Long>()
    val accounts: AccountRepository = if(localMode) LocalAccountRepository(vault) else FirebaseAccountRepository()
    val adminRepository = if(localMode) null else AdminRepository()
    val messagingRepository = if(localMode) null else MessagingRepository()
    val messageReadState=application.getSharedPreferences("olive_message_read_state",android.content.Context.MODE_PRIVATE)
    val cloudState=application.getSharedPreferences("olive_cloud_state",android.content.Context.MODE_PRIVATE)
    var enrollment by mutableStateOf<DeviceEnrollment?>(null)
    var pendingIdentity: AccountIdentity? = null
    var pendingProfile: UserProfile? = null
    var operationJob: Job? = null
    var triggerJob: Job? = null
    var historySyncJob: Job? = null
    var idleJob: Job? = null
    var socialSubscription: ChatSubscription? = null
    var conversationSubscription: ChatSubscription? = null
    var messageSubscription: ChatSubscription? = null
    var observedFriendshipIds=emptySet<String>()
    var lastTouchAt=0L
    val historySyncMutex=Mutex()
    var activeTriggerId: String? = null
    var epoch = 0L
    var pinAuthorizedUid:String?=null
    var pinAuthorizedEpoch = -1L
    val screenHistory=mutableListOf<SessionScreen>()
    val safePageHistory=mutableListOf<SafePage>()

    val auth=SessionAuthentication(this)
    val access=SessionAccess(this)
    val entrySettings=SessionEntrySettings(this)
    val deviceAccess=SessionDeviceAccess(this)
    val navigation=SessionNavigation(this)
    val organization=OrganizationAccounts(this)
    val messaging=SessionMessaging(this)
    val friendActions=FriendActions(this)
    val conversationActions=SessionConversations(this)
    val privacy=SessionPrivacy(this)
    val covers=SessionCoverState(this)
    val history=SessionHistory(this)
    val notes=SessionNotes(this)
    val settings=SessionSettings(this)
    val operations=SessionOperations(this)

    val configured get() = enrollment != null
    val coverAccountId get() = enrollment?.uid
    val sensitive get() = state.screen != SessionScreen.Cover || state.checkoutActive
    val setupUsername get() = pendingIdentity?.username.orEmpty()
    val setupProfile get() = pendingProfile
    val usesCover get() = state.accountRole==AccountRole.USER
    val unreadMessageCount: Int get() {
        state.readMessageVersion
        val uid=enrollment?.uid ?: return 0
        return state.conversations.count { conversation ->
            conversation.lastMessageId.isNotBlank() && conversation.lastSenderUid.isNotBlank() &&
                conversation.lastSenderUid!=uid &&
                messageReadState.getString(conversationActions.readMessageKey(uid,conversation.id),null)!=conversation.lastMessageId
        }
    }
    val pendingMessageActionCount get()=state.incomingFriendRequests.size+unreadMessageCount

    // User access requires verified app-PIN or Android authentication for this enrollment and epoch.
    private fun canEnterSafe():Boolean {
        val device=enrollment
        val requiresPin=device?.role==AccountRole.USER || state.accountRole==AccountRole.USER
        return !requiresPin || (device!=null && pinAuthorizedUid==device.uid && pinAuthorizedEpoch==epoch)
    }

}
