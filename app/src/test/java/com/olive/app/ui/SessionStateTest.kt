package com.olive.app.ui

import android.app.Activity
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.work.impl.WorkManagerImpl
import com.google.firebase.auth.FirebaseAuth
import com.olive.app.data.account.*
import com.olive.app.data.location.AdminLocationRepository
import com.olive.app.data.location.LocationSharingDataSource
import com.olive.app.data.location.MapLocationPoint
import com.olive.app.data.media.MediaTicketDataSource
import com.olive.app.data.messaging.*
import com.olive.app.data.news.NewsArticle
import com.olive.app.data.safety.SafetyDocument
import com.olive.app.data.safety.SafetyDocumentDataSource
import com.olive.app.data.safety.SecureDocumentRepository
import com.olive.app.security.DeviceEnrollment
import com.olive.app.security.SafeEntryMethod
import com.olive.app.support.TestAndroidKeyStore
import com.olive.app.ui.session.*
import com.olive.app.ui.users.shared.navigation.*
import com.olive.app.ui.users.shared.organization.AdminLocationMapController
import com.olive.app.ui.users.shared.organization.AdminLocationMapState
import java.io.File
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionStateTest {
    @Test
    fun privateNavigationStateRequiresAnAuthorizedSession() {
        var authorized = false
        val state = SessionState { authorized }
        state.screen = SessionScreen.Safe
        assertEquals(SessionScreen.Cover, state.navigationState.value.screen)

        authorized = true
        state.screen = SessionScreen.Safe
        assertEquals(SessionScreen.Safe, state.navigationState.value.screen)
    }

    @Test
    fun closingMessagingPublishesClearedPrivateState() {
        val state = SessionState { true }
        state.chatMessages = listOf(ChatMessage("message", "sender", "name", "body", 1L))
        state.messagingBusy = true
        val before = state.messagingState.value

        state.clearMessaging(clearRows = true)

        assertEquals(1, before.chatMessages.size)
        assertTrue(state.messagingState.value.chatMessages.isEmpty())
        assertEquals(false, state.messagingState.value.messagingBusy)
    }

    @Test
    fun sessionStateActivityAndDashboardAccessorsUpdateImmutableSnapshots() {
        val state = SessionState { true }
        state.accessibilityExitVersion = 2
        state.idleCountdownSeconds = 15
        state.accountRole = AccountRole.STAFF
        val settings = listOf(SettingsEvent(area = "Privacy", action = "Updated"))
        val triggers = listOf(TriggerEvent(source = "Cover olive"))
        val shared = PrivateEntry(id = "shared", title = "Title", body = "Body")
        val news = listOf(NewsArticle("Title", link = "https://publisher.example/news"))

        state.settingsHistory = settings
        state.privacyHistory = settings
        state.triggerHistory = triggers
        state.viewedSharedEntry = shared
        state.adminCounts = AccountCounts(users = 4)
        state.setAdminSummary("admin-1", AccountCounts(users = 4, staff = 2))
        state.savedNews = news
        state.coverDataVersion = 3
        state.conversationLoadFailed = true

        assertEquals(2, state.accessibilityExitVersion)
        assertEquals(15, state.idleCountdownSeconds)
        assertEquals(AccountRole.STAFF, state.accountRole)
        assertEquals(settings, state.settingsHistory)
        assertEquals(settings, state.privacyHistory)
        assertEquals(triggers, state.triggerHistory)
        assertEquals(shared, state.viewedSharedEntry)
        assertEquals(2, state.adminCounts.staff)
        assertEquals("admin-1", state.dashboardState.value.adminSummaryOwner)
        assertEquals(news, state.savedNews)
        assertEquals(3, state.coverDataVersion)
        assertTrue(state.conversationLoadFailed)
    }

    @Test
    fun adminLocationMapControllerObservesFeedOnlyForOrganizationRoles() {
        AdminLocationMapController(null).open(AccountRole.ADMIN)

        val repo = mock(AdminLocationRepository::class.java)
        var onUpdate: ((List<MapLocationPoint>) -> Unit)? = null
        var onError: ((String) -> Unit)? = null
        var closed = false
        doAnswer { invocation ->
            onUpdate = invocation.getArgument(0)
            onError = invocation.getArgument(1)
            ChatSubscription { closed = true }
        }.`when`(repo).observe(
            any<(List<MapLocationPoint>) -> Unit>() ?: {},
            any<(String) -> Unit>() ?: {},
        )

        val controller = AdminLocationMapController(repo)
        controller.open(AccountRole.USER)
        verifyNoInteractions(repo)
        assertFalse(controller.state.loading)

        controller.open(AccountRole.ADMIN)
        assertTrue(controller.state.loading)
        assertTrue(controller.stateFlow.value.loading)
        controller.open(AccountRole.STAFF)
        verify(repo, times(1)).observe(
            any<(List<MapLocationPoint>) -> Unit>() ?: {},
            any<(String) -> Unit>() ?: {},
        )

        val point = MapLocationPoint("u1", "Person", 53.5, -113.5, 100L, live = true)
        onUpdate?.invoke(listOf(point))
        assertEquals(listOf(point), controller.state.points)
        assertFalse(controller.state.loading)

        onError?.invoke("Synthetic error")
        assertEquals("Synthetic error", controller.state.error)

        controller.close()
        assertTrue(closed)
        assertEquals(AdminLocationMapState(), controller.state)
    }

    @Test
    fun sessionViewModelDelegatesStateAndLifecycleActionsSafely() {
        TestAndroidKeyStore.install().use {
            val work = mock(WorkManagerImpl::class.java)
            WorkManagerImpl.setDelegate(work)
            val rawApp = RuntimeEnvironment.getApplication()
            val resources = spy(rawApp.resources)
            doReturn("Ok").`when`(resources).getString(anyInt())
            doReturn("Ok").`when`(resources).getString(anyInt(), any())
            val app = spy(rawApp)
            doReturn(resources).`when`(app).resources
            doReturn(app).`when`(app).applicationContext
            val accounts = mock(AccountRepository::class.java)
            val admin = mock(AdminRepository::class.java)
            val messaging = mock(MessagingRepository::class.java)
            val secureChat = mock(SecureChatRepository::class.java)
            val secureDocuments = mock(SecureDocumentRepository::class.java)
            val media = mock(MediaTicketDataSource::class.java)
            val safetyDocuments = mock(SafetyDocumentDataSource::class.java)
            val locations = mock(LocationSharingDataSource::class.java)
            val adminLocations = mock(AdminLocationRepository::class.java)
            val dataSources = SessionDataSources(
                accounts, admin, messaging, secureChat, secureDocuments,
                media, safetyDocuments, locations, adminLocations,
                null, null,
            )
            `when`(accounts.authenticatedUid()).thenReturn("owner")
            `when`(accounts.authenticatedUsername()).thenReturn("fixture_owner")
            val conversation = ChatConversation(
                id = "conv-1",
                friendshipId = "friend-1",
                participantIds = listOf("owner", "peer"),
                participantUsernames = mapOf("owner" to "fixture_owner", "peer" to "peer_user"),
                type = "SUPPORT",
                supportTag = "safety-plan",
                lastMessageId = "m1",
                lastSenderUid = "peer",
            )
            runBlocking {
                `when`(messaging.getConversation("conv-1")).thenReturn(conversation)
                `when`(messaging.getConversation("broken")).thenThrow(RuntimeException("network"))
            }

            val store = ViewModelStore()
            mockStatic(FirebaseAuth::class.java).use { firebaseAuth ->
                val auth = mock(FirebaseAuth::class.java)
                firebaseAuth.`when`<FirebaseAuth> { FirebaseAuth.getInstance() }.thenReturn(auth)
                try {
                    val vm = ViewModelProvider(
                        store,
                        object : ViewModelProvider.Factory {
                            @Suppress("UNCHECKED_CAST")
                            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                                SessionViewModel(app, dataSources, localMode = true) as T
                        },
                    )[SessionViewModel::class.java]
                    runBlocking {
                        val deadline = System.currentTimeMillis() + 5_000L
                        while (vm.busy && System.currentTimeMillis() < deadline) {
                            shadowOf(Looper.getMainLooper()).idle()
                            Thread.sleep(10)
                        }
                        shadowOf(Looper.getMainLooper()).idle()
                    }

                    val runtime = vm.runtime
                    runtime.enrollment = DeviceEnrollment("owner", "", AccountRole.USER)
                    runtime.state.accountRole = AccountRole.USER
                    runtime.pinAuthorizedUid = "owner"
                    runtime.pinAuthorizedEpoch = runtime.epoch
                    runtime.state.screen = SessionScreen.Safe
                    runtime.state.page = SafePage.Messages
                    runtime.state.coverUsername = "fixture_owner"
                    runtime.state.conversations = listOf(conversation)
                    val incomingReq = FriendRequest("r1", "peer", "peer_user", "owner", "fixture_owner", 1L)
                    val outgoingReq = FriendRequest("r2", "owner", "fixture_owner", "third", "third_user", 2L)
                    runtime.state.incomingFriendRequests = listOf(incomingReq)
                    runtime.state.outgoingFriendRequests = listOf(outgoingReq)

                    assertNotNull(vm.navigationState.value)
                    assertNotNull(vm.accountState.value)
                    assertNotNull(vm.activityState.value)
                    assertNotNull(vm.dashboardState.value)
                    assertEquals(SessionScreen.Safe, vm.screen)
                    assertEquals(0, vm.pinChallengeVersion)
                    assertEquals(0, vm.unlockAnimationVersion)
                    assertEquals(SafePage.Messages, vm.page)
                    assertNull(vm.activityCategory)
                    assertFalse(vm.busy)
                    assertNull(vm.deviceLockRequest)
                    assertNull(vm.message)
                    assertNull(vm.profile)
                    assertTrue(vm.attempts.isEmpty())
                    assertTrue(vm.settingsHistory.isEmpty())
                    assertTrue(vm.privacyHistory.isEmpty())
                    assertTrue(vm.triggerHistory.isEmpty())
                    assertTrue(vm.privateEntries.isEmpty())
                    assertFalse(vm.loadingHistory)
                    assertFalse(vm.historyLoadFailed)
                    assertNull(vm.viewedSharedEntry)
                    assertSame(runtime.safety, vm.safetyPlans)
                    assertSame(runtime.staffSupport, vm.staffSupport)
                    assertFalse(vm.pendingSync)
                    assertEquals("", vm.deliveryAddress.city)
                    assertEquals("English", vm.language)
                    assertEquals("", vm.coverDob)
                    assertEquals("", vm.coverFullName)
                    assertEquals("fixture_owner", vm.coverUsername)
                    assertEquals(CoverType.FITNESS, vm.coverType)
                    assertEquals(AccountRole.USER, vm.accountRole)
                    assertTrue(vm.adminAccounts.isEmpty())
                    assertTrue(vm.adminStaffAccounts.isEmpty())
                    assertFalse(vm.adminAccountsLoading)
                    assertFalse(vm.adminStaffLoading)
                    assertFalse(vm.adminAccountsReady)
                    assertFalse(vm.adminStaffReady)
                    assertFalse(vm.showAdminAccountsSkeleton)
                    assertFalse(vm.showAdminStaffSkeleton)
                    assertNull(vm.adminAccountsError)
                    assertNull(vm.adminStaffError)
                    assertTrue(vm.savedNews.isEmpty())
                    assertFalse(vm.privacyBlackout)
                    assertEquals(0, vm.coverReset)
                    assertTrue(vm.localMode)
                    assertEquals("owner", vm.coverAccountId)
                    assertTrue(vm.sensitive)
                    assertTrue(vm.usesCover)
                    assertNull(vm.preparingVoiceConversationId)
                    assertNull(vm.coverEmergencyConfirmation)
                    assertFalse(vm.coverEmergencyInFlight)
                    assertTrue(vm.pendingCameraUploadTags.isEmpty())

                    vm.ensureAdminAccounts()
                    vm.ensureAdminStaff()
                    vm.pauseIdleTimerForCall()
                    vm.resumeIdleTimerAfterCall()
                    vm.reconcilePriorityData()

                    vm.beginExternalFilePicker()
                    assertTrue(vm.externalFilePickerOpen)
                    vm.endExternalFilePicker()
                    assertFalse(vm.externalFilePickerOpen)

                    vm.beginCallPermissionRequest()
                    assertTrue(vm.callPermissionRequestOpen)
                    vm.endCallPermissionRequest()
                    assertFalse(vm.callPermissionRequestOpen)

                    val launchId = vm.beginSafeSystemActivity()
                    assertNotNull(launchId)
                    assertNull(vm.beginSafeSystemActivity())
                    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
                    val view = View(activity)
                    activity.setContentView(view)
                    vm.safeSystemActivityResult(view)
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
                    vm.finishSafeSystemActivity(launchId!!)
                    assertFalse(vm.safeSystemActivityOpen)

                    assertFalse(vm.sendVoiceMessage(File(app.cacheDir, "voice.m4a"), "{}"))
                    vm.shareLiveLocationInChat(60_000L) { false }

                    val messages = vm.messages
                    assertNotNull(messages.state.value)
                    assertTrue(messages.userSearchResults.isEmpty())
                    assertTrue(messages.friends.isEmpty())
                    assertEquals(1, messages.incomingFriendRequests.size)
                    assertEquals(1, messages.outgoingFriendRequests.size)
                    assertEquals(1, messages.conversations.size)
                    assertTrue(messages.archivedConversationIds.isEmpty())
                    assertTrue(messages.restoredClosedConversationIds.isEmpty())
                    assertTrue(messages.privateContactNotes.isEmpty())
                    assertNull(messages.activeConversation)
                    assertFalse(messages.chatQuickExitHintVisible)
                    assertTrue(messages.chatMessages.isEmpty())
                    assertFalse(messages.loadingIndex)
                    assertFalse(messages.loadingConversation)
                    assertFalse(messages.busy)
                    assertFalse(messages.needsReconnect)
                    assertEquals(2, messages.pendingActionCount)
                    assertTrue(messages.isUnread(conversation))
                    assertEquals(1, messages.unreadCountForSupportTag("safety-plan"))
                    assertEquals(1, messages.unreadCountForChatTab(true))
                    assertEquals(1, messages.unreadCountForChatTab(false))
                    assertEquals(FriendRelationship.IncomingRequest, messages.relationshipFor("peer"))
                    messages.ensureMessagingObservation(false)
                    messages.reconnectMessaging("Secret!123")
                    messages.searchUsers("peer")
                    messages.sendFriendRequest(DirectoryUser("u2", "user2"))
                    messages.acceptFriendRequest(incomingReq)
                    messages.declineFriendRequest(incomingReq)
                    messages.cancelFriendRequest(outgoingReq)
                    messages.startConversation(Friend("f1", "peer", "peer_user"))
                    messages.openConversation(conversation)
                    assertNull(messages.observeMembershipEvents("conv-1", "owner") {})
                    runBlocking {
                        assertNull(messages.findRequestedConversation(""))
                        assertEquals(conversation, messages.findRequestedConversation("conv-1"))
                        assertNull(messages.findRequestedConversation("broken"))
                    }
                    mockConstruction(FriendInviteRepository::class.java).use {
                        runBlocking<Unit> {
                            messages.createFriendInvite("owner", false)
                            messages.claimFriendInvite("owner", "CODE")
                            messages.observeFriendInviteUnavailable("owner", "CODE") {}
                        }
                    }
                    messages.closeConversation()
                    messages.setConversationArchived(conversation, true)
                    messages.savePrivateContactNote(DirectoryUser("peer", "peer_user"), "note")
                    messages.sendMessage("hello")
                    val chatMsg = ChatMessage("m1", "owner", "fixture_owner", "hi", 10L)
                    messages.retryMessage(chatMsg)
                    messages.discardFailedMessage(chatMsg)
                    messages.unsendMessage(chatMsg)
                    messages.dismissChatQuickExitHint()
                    val entry = PrivateEntry(id = "e1", title = "Note", body = "Body")
                    messages.sharePrivateEntryInChat(entry)
                    messages.shareSafetyDocumentInChat(
                        SafetyDocument("d1", "owner", "", "Doc.pdf", "application/pdf", listOf("owner")),
                    )
                    messages.shareSafetyPlanInChat()
                    messages.openSharedPrivateEntry("e1")
                    messages.closeSharedPrivateEntry()
                    messages.requestAdminHelp("conv-1")
                    messages.addConversationParticipant("conv-1", "peer")
                    messages.removeConversationParticipant("conv-1", "peer")
                    messages.requestStaffForConversation("conv-1") {}
                    messages.closeSupportChat("conv-1")

                    vm.saveOrganizationProfile("Name", "2000-01-01", "a@b.ca", "")
                    vm.updateOrganizationProfile("Name", "2000-01-01", "a@b.ca", "")
                    vm.refreshAdminAccounts()
                    vm.refreshAdminStaff()
                    vm.createManagedAccount(AdminAccountDraft("staff1", "Secret!123", AccountRole.STAFF))
                    vm.deleteManagedAccount(
                        AdminAccountSummary("u2", "staff1", AccountRole.STAFF, "Name", "a@b.ca", "", true, false),
                    )
                    vm.saveSettings("Name", "", "", "2000-01-01", "", DeliveryAddress(city = "Edmonton"))
                    vm.saveCoverSettings("Cover Name")
                    vm.changeCover(CoverType.NEWS)
                    vm.changeTrigger("trigger word", "1234")
                    vm.saveContacts(emptyList<SafeContact>())
                    vm.sync()
                    vm.syncLocalNotificationHandler()
                    vm.changePassword("old", "NewSecret!123", "NewSecret!123")

                    vm.noteInteraction()
                    assertTrue(vm.idleTimeoutSeconds() > 0)
                    vm.setIdleTimeoutSeconds(60)
                    vm.captureDetected("screenshot")
                    vm.recordingStopped()
                    vm.recordSettingsInteraction("Privacy", "Opened")
                    vm.toggleNewsBookmark(NewsArticle("Title", link = "https://publisher.example/1"))
                    vm.showActivityCategory(ActivityCategory.Settings)
                    vm.activityBack()
                    vm.clearPrivateEntryStatus()
                    vm.savePrivateEntry(null, PrivateEntryKind.Note, "Title", "Body")
                    vm.togglePrivateEntryPin("e1")
                    vm.trashPrivateEntry("e1")
                    vm.restorePrivateEntry("e1")

                    vm.navigation.openSafePage(SafePage.Notes)
                    vm.navigation.back()
                    vm.navigation.background()
                    vm.navigation.quickExit()
                    vm.quickExit()
                    vm.signOut()
                    shadowOf(Looper.getMainLooper()).idle()
                } finally {
                    store.clear()
                    WorkManagerImpl.setDelegate(null)
                }
            }
        }
    }
}
