// All app roles: connects screens to the session workflows that own each feature.
package com.olive.app.ui.users.shared.navigation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.olive.app.ui.session.SessionRuntime
import com.olive.app.ui.users.user.documents.MediaTransferController
import com.olive.app.ui.users.user.documents.SafetyDocumentsController
import com.olive.app.ui.users.user.location.LocationMapController
import com.olive.app.ui.users.admin.AdminLocationMapController

// Android lifecycle adapter. Feature controllers own workflows; screens observe read-only state.
internal class SessionViewModel(application: Application) : AndroidViewModel(application),
    SessionCoverNotesActivityActions,
    SessionAccountManagementActions {
    override val runtime = SessionRuntime(application, viewModelScope)
    val account = SessionAccountEntry(runtime)
    val messages = SessionMessages(runtime)
    val navigation = SessionScreenNavigation(runtime)

    // Document and map screens keep their own feature controllers.
    internal val media = runtime.mediaRepository?.let {
        MediaTransferController(application, viewModelScope, it)
    }
    internal val documents =
        SafetyDocumentsController(application, viewModelScope, runtime.safetyDocumentRepository, media)
    internal val locationMap =
        LocationMapController(application, viewModelScope, runtime.locationSharingRepository)
    internal val adminLocationMap = AdminLocationMapController(runtime.dataSources.adminLocations)

    // Root navigation, account entry, and device lock state.
    val screen get() = runtime.state.screen
    // These versions tell Compose to redraw after an entry or cover event.
    val pinChallengeVersion get() = runtime.state.pinChallengeVersion
    val unlockAnimationVersion get() = runtime.state.unlockAnimationVersion
    val accessibilityExitVersion get() = runtime.state.accessibilityExitVersion
    val page get() = runtime.state.page
    val activityCategory get() = runtime.state.activityCategory
    val busy get() = runtime.state.busy
    val deviceLockRequest get() = runtime.state.deviceLockRequest
    // Signed-in account, privacy, and activity state.
    val message get() = runtime.state.message
    val profile get() = runtime.state.profile
    val attempts get() = runtime.state.attempts
    val settingsHistory get() = runtime.state.settingsHistory
    val privacyHistory get() = runtime.state.privacyHistory
    val triggerHistory get() = runtime.state.triggerHistory
    val checkoutHistory get() = runtime.state.checkoutHistory
    val privateEntries get() = runtime.state.privateEntries
    val viewedSharedEntry get() = runtime.state.viewedSharedEntry
    internal val safetyPlans get() = runtime.safety
    internal val staffSupport get() = runtime.staffSupport

    // Cover, organization account, and sync state.
    val pendingSync get() = runtime.state.pendingSync
    val deliveryAddress get() = runtime.state.deliveryAddress
    val addressPrompt get() = runtime.state.addressPrompt
    val addressSaveVersion get() = runtime.state.addressSaveVersion
    val language get() = runtime.state.language
    val coverDob get() = runtime.state.coverDob
    val coverFullName get() = runtime.state.coverFullName
    val coverUsername get() = runtime.state.coverUsername
    val coverType get() = runtime.state.coverType
    val accountRole get() = runtime.state.accountRole
    val adminCounts get() = runtime.state.adminCounts
    val adminAccounts get() = runtime.state.adminAccounts
    val foodCart get() = runtime.state.foodCart
    val foodFavorites get() = runtime.state.foodFavorites
    val foodCustomizations get() = runtime.state.foodCustomizations
    val savedNews get() = runtime.state.savedNews
    val coverDataVersion get() = runtime.state.coverDataVersion
    var checkoutActive: Boolean
        get() = runtime.state.checkoutActive
        set(value) {
            runtime.state.checkoutActive = value
        }
    val idleCountdownSeconds get() = runtime.state.idleCountdownSeconds
    val privacyBlackout get() = runtime.state.privacyBlackout
    val coverReset get() = runtime.state.coverReset
    val localMode get() = runtime.localMode
    val coverAccountId get() = runtime.coverAccountId
    val sensitive get() = runtime.sensitive
    val usesCover get() = runtime.usesCover

    @Volatile
    var externalFilePickerOpen = false
        private set

    init {
        runtime.accountSession.load()
    }

    // Opens Android's file picker for a document attachment.
    fun beginExternalFilePicker() {
        externalFilePickerOpen = true
    }

    // Clears the pending file-picker request after Android returns a result.
    fun endExternalFilePicker() {
        externalFilePickerOpen = false
    }

    // Shares the user's current live-location session with a chat.
    fun shareLiveLocationInChat(durationMillis: Long, requestPermission: suspend () -> Boolean) =
        runtime.secureSharing.shareLiveLocation(durationMillis, locationMap, requestPermission)

    // Closing this session also clears document and map data held by their screen controllers.
    fun signOut() {
        documents.clear()
        locationMap.clear()
        adminLocationMap.close()
        runtime.accountSession.signOut()
    }

    // Stops account listeners and background work when the session view model is removed.
    override fun onCleared() {
        documents.clear()
        locationMap.clear()
        adminLocationMap.close()
        runtime.safety.close()
        runtime.messaging.stopMessaging()
    }
}
