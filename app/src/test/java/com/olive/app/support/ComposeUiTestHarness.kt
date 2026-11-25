package com.olive.app.support

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.olive.app.data.account.AccountKeysetRepository
import com.olive.app.data.account.AccountRepository
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.AdminRepository
import com.olive.app.data.account.OpaqueRecoveryRepository
import com.olive.app.data.location.AdminLocationRepository
import com.olive.app.data.location.LocationSharingRepository
import com.olive.app.data.media.MediaRepository
import com.olive.app.data.messaging.MessagingRepository
import com.olive.app.data.messaging.SecureChatRepository
import com.olive.app.data.safety.SafetyDocumentDataSource
import com.olive.app.data.safety.SecureDocumentRepository
import com.olive.app.data.sync.PrioritySyncCoordinator
import com.olive.app.security.DeviceEnrollment
import com.olive.app.ui.session.SessionDataSources
import com.olive.app.ui.shared.LocalWalkthroughDirective
import com.olive.app.ui.shared.LocalWalkthroughTargets
import com.olive.app.ui.shared.OlivePrivateDesign
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.shared.OliveUndoOverlay
import com.olive.app.ui.shared.WalkthroughDirective
import com.olive.app.ui.shared.WalkthroughTargetRegistry
import com.olive.app.ui.users.shared.navigation.LocalQuickExit
import com.olive.app.ui.users.shared.navigation.SafePage
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import com.olive.app.ui.users.user.dashboard.LocalBackgroundLocationPermissionRequest
import com.olive.app.ui.users.user.dashboard.LocalHotlineLocationLookup
import com.olive.app.ui.users.user.dashboard.LocalLocationPermissionRequest
import java.security.Security
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RuntimeEnvironment

internal class UiSessionHandle(
    val session: SessionViewModel,
    val accounts: AccountRepository,
    val admin: AdminRepository,
    val messaging: MessagingRepository,
    val secureChat: SecureChatRepository,
    val secureDocument: SecureDocumentRepository,
    val media: MediaRepository,
    val safetyDocuments: SafetyDocumentDataSource?,
    val locations: LocationSharingRepository,
    val adminLocations: AdminLocationRepository,
    private val keyStoreHandle: AutoCloseable?,
    private val viewModelStore: ViewModelStore,
) : AutoCloseable {
    override fun close() {
        runCatching<Unit> { session.runtime.messaging.stopMessaging() }
        runCatching<Unit> { session.locationMap.clear() }
        runCatching<Unit> { session.adminLocationMap.close() }
        runCatching<Unit> { viewModelStore.clear() }
        runCatching<Unit> { keyStoreHandle?.close() }
    }
}

internal fun createUiTestSession(
    role: AccountRole = AccountRole.USER,
    screen: SessionScreen = if (role in setOf(AccountRole.ADMIN, AccountRole.STAFF)) SessionScreen.Portal else SessionScreen.Safe,
    page: SafePage = SafePage.Home,
    uid: String = "owner",
    username: String = "test_user",
    fullName: String = "Test User",
    localMode: Boolean = true,
    documentsOverride: SafetyDocumentDataSource? = null,
    accountKeysetsOverride: AccountKeysetRepository? = null,
    opaqueRecoveryOverride: OpaqueRecoveryRepository? = null,
): UiSessionHandle {
    val keyStoreHandle = if (Security.getProvider("AndroidKeyStore") == null) TestAndroidKeyStore.install() else null
    val app: Application = RuntimeEnvironment.getApplication()
    if (com.google.firebase.FirebaseApp.getApps(app).isEmpty()) {
        com.google.firebase.FirebaseApp.initializeApp(
            app,
            com.google.firebase.FirebaseOptions.Builder()
                .setProjectId("olive-test")
                .setApplicationId("1:1234567890:android:olive")
                .setApiKey("AIzaSyTestOnlyKey000000000000000000")
                .setDatabaseUrl("https://olive-test-default-rtdb.firebaseio.com")
                .build(),
        )
    }
    if (runCatching { androidx.work.WorkManager.getInstance(app) }.isFailure) {
        androidx.work.WorkManager.initialize(
            app,
            androidx.work.Configuration.Builder().build(),
        )
    }
    val accounts = mock(AccountRepository::class.java)
    val admin = mock(AdminRepository::class.java)
    val messaging = mock(MessagingRepository::class.java)
    val secureChat = mock(SecureChatRepository::class.java)
    val secureDocument = mock(SecureDocumentRepository::class.java)
    val media = mock(MediaRepository::class.java)
    val safetyDocuments = documentsOverride ?: mock(SafetyDocumentDataSource::class.java)
    val locations = mock(LocationSharingRepository::class.java)
    val adminLocations = mock(AdminLocationRepository::class.java)
    `when`(accounts.authenticatedUid()).thenReturn(uid)
    `when`(accounts.authenticatedUsername()).thenReturn(username)

    val dataSources = SessionDataSources(
        accounts = accounts,
        admin = admin,
        messaging = messaging,
        secureChat = secureChat,
        secureDocument = secureDocument,
        media = media,
        safetyDocuments = safetyDocuments,
        locations = locations,
        adminLocations = adminLocations,
        accountKeysets = accountKeysetsOverride,
        opaqueRecovery = opaqueRecoveryOverride,
    )
    val store = ViewModelStore()
    val session = ViewModelProvider(
        store,
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SessionViewModel(app, dataSourcesOverride = dataSources, localMode = localMode) as T
        },
    )[SessionViewModel::class.java]
    // Let SessionViewModel.init's async AccountSessionController.load() finish before seeding test state.
    val loadDeadline = System.nanoTime() + 5_000_000_000L
    while (session.busy && System.nanoTime() < loadDeadline) {
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        Thread.sleep(5)
    }
    org.robolectric.shadows.ShadowLooper.idleMainLooper()
    // Register in a ViewModelStore so close() cancels viewModelScope via onCleared() without reflection.
    @Suppress("UNUSED_VARIABLE")
    val syncCoord = PrioritySyncCoordinator(app, null)
    session.runtime.enrollment = DeviceEnrollment(uid, "", role)
    session.runtime.state.accountRole = role
    session.runtime.state.coverUsername = username
    session.runtime.state.coverFullName = fullName
    session.runtime.pinAuthorizedUid = uid
    session.runtime.pinAuthorizedEpoch = session.runtime.epoch
    session.runtime.state.screen = screen
    session.runtime.state.page = page

    return UiSessionHandle(
        session = session,
        accounts = accounts,
        admin = admin,
        messaging = messaging,
        secureChat = secureChat,
        secureDocument = secureDocument,
        media = media,
        safetyDocuments = safetyDocuments,
        locations = locations,
        adminLocations = adminLocations,
        keyStoreHandle = keyStoreHandle,
        viewModelStore = store,
    )
}

@Composable
internal fun OliveUiTestHost(
    privateDesign: Boolean = true,
    locationGranted: Boolean = false,
    walkthroughTargets: WalkthroughTargetRegistry? = null,
    walkthroughDirective: WalkthroughDirective? = null,
    onQuickExit: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val hotlineLookup: suspend (Context) -> com.olive.app.data.account.DeliveryAddress? = { null }
    val permRequest: suspend () -> Boolean = { locationGranted }
    CompositionLocalProvider(
        LocalQuickExit provides onQuickExit,
        LocalLocationPermissionRequest provides permRequest,
        LocalBackgroundLocationPermissionRequest provides (if (locationGranted) null else permRequest),
        LocalHotlineLocationLookup provides hotlineLookup,
        LocalWalkthroughTargets provides walkthroughTargets,
        LocalWalkthroughDirective provides walkthroughDirective,
    ) {
        OliveTheme {
            if (privateDesign) {
                OlivePrivateDesign {
                    OliveUndoOverlay(scopeKey = "ui-test", enabled = true) {
                        content()
                    }
                }
            } else {
                content()
            }
        }
    }
}
