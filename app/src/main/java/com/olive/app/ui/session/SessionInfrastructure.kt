// All app roles: builds the local stores and account-scoped data sources used by a session.
package com.olive.app.ui.session

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.olive.app.data.AccountRepository
import com.olive.app.data.AdminLocationRepository
import com.olive.app.data.AdminRepository
import com.olive.app.data.CoverStateStore
import com.olive.app.data.FirebaseAccountRepository
import com.olive.app.data.LocalAccountRepository
import com.olive.app.data.LocationSharingRepository
import com.olive.app.data.MessagingRepository
import com.olive.app.data.MessageOutboxStore
import com.olive.app.data.ChatCacheStore
import com.olive.app.data.SafetyDocumentRepository
import com.olive.app.data.SafetyDocumentDataSource
import com.olive.app.data.DocumentIndexStore
import com.olive.app.data.DocumentIndexDataSource
import com.olive.app.data.IndexedSafetyDocumentRepository
import com.olive.app.data.SecureChatRepository
import com.olive.app.data.SecureDocumentRepository
import com.olive.app.data.media.MediaRepository
import com.olive.app.data.media.MediaTicketDataSource
import com.olive.app.data.media.OfflineMediaCache
import com.olive.app.security.EncryptedVault
import com.olive.app.security.DeviceStore
import kotlinx.coroutines.sync.Mutex

// Owns the device-only stores. Keeping them together makes their scopes visible at construction.
internal class SessionLocalStorage(application: Application, localMode: Boolean) {
    val vault = EncryptedVault(application, if (localMode) "demo" else "firebase")
    val activityVault = EncryptedVault(
        application,
        if (localMode) "demo-activity" else "firebase-activity",
    )
    val deviceStore = DeviceStore(vault, activityVault)
    val coverStore = CoverStateStore(EncryptedVault(application, "cover"))
    val coverSaveMutex = Mutex()
    val newsRevisions = mutableMapOf<String, Long>()
    val messageReadState: SharedPreferences = application.getSharedPreferences(
        "olive_message_read_state",
        Context.MODE_PRIVATE,
    )
    val cloudState: SharedPreferences = application.getSharedPreferences(
        "olive_cloud_state",
        Context.MODE_PRIVATE,
    )
    val messageOutbox = MessageOutboxStore(application)
    val chatCache = ChatCacheStore(application)
    val documentIndex = DocumentIndexStore(application)
    val offlineMediaCache = OfflineMediaCache(application)
}

// Builds cloud repositories only for a Firebase session. Tests can supply a replacement bundle.
internal class SessionDataSources(
    val accounts: AccountRepository,
    val admin: AdminRepository?,
    val messaging: MessagingRepository?,
    val secureChat: SecureChatRepository?,
    val secureDocument: SecureDocumentRepository?,
    val media: MediaTicketDataSource?,
    val safetyDocuments: SafetyDocumentDataSource?,
    val locations: LocationSharingRepository?,
    val adminLocations: AdminLocationRepository?,
) {
    companion object {
        // Creates the cloud repository bundle used by a signed-in Firebase account.
        fun create(
            application: Application,
            localMode: Boolean,
            vault: EncryptedVault,
            documentIndex: DocumentIndexDataSource,
        ): SessionDataSources {
            val media: MediaTicketDataSource? = if (localMode) null else MediaRepository()
            return SessionDataSources(
                accounts = if (localMode) LocalAccountRepository(vault) else FirebaseAccountRepository(),
                admin = if (localMode) null else AdminRepository(),
                messaging = if (localMode) null else MessagingRepository(),
                secureChat = if (localMode) null else SecureChatRepository(),
                secureDocument = if (localMode) null else SecureDocumentRepository(),
                media = media,
                safetyDocuments = media?.let {
                    IndexedSafetyDocumentRepository(SafetyDocumentRepository(media = it), documentIndex)
                },
                locations = if (localMode) null else LocationSharingRepository(application),
                adminLocations = if (localMode) null else AdminLocationRepository(),
            )
        }
    }
}
