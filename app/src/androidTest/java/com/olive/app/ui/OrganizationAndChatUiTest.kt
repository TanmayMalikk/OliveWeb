package com.olive.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.olive.app.BuildConfig
import com.olive.app.R
import com.olive.app.data.account.AccountCounts
import com.olive.app.data.account.AccountRole
import com.olive.app.data.account.UserProfile
import com.olive.app.data.account.AdminAccountSummary
import com.olive.app.data.messaging.ChatConversation
import com.olive.app.data.messaging.ChatMessage
import com.olive.app.data.messaging.Friend
import com.olive.app.data.messaging.FriendRequest
import com.olive.app.data.messaging.DirectoryUser
import com.olive.app.data.messaging.SUPPORT_TAG_NEEDS_ADMIN
import com.olive.app.data.messaging.SUPPORT_TAG_PLAN_CONSULT
import com.olive.app.data.messaging.SUPPORT_TAG_SUPPORT
import com.olive.app.security.DeviceEnrollment
import com.olive.app.data.messaging.MessageDeliveryStatus
import com.olive.app.ui.shared.OliveTheme
import com.olive.app.ui.session.EmergencyAlert
import com.olive.app.ui.users.admin.AdminAccounts
import com.olive.app.ui.users.admin.AdminPortal
import com.olive.app.ui.users.admin.EmergencyResponseScreen
import com.olive.app.ui.users.staff.StaffPortal
import com.olive.app.ui.users.shared.navigation.SessionScreen
import com.olive.app.ui.users.admin.CreateAccountDialog
import com.olive.app.ui.users.shared.messaging.ChatInfoDialog
import com.olive.app.ui.users.shared.messaging.ChatPane
import com.olive.app.ui.users.shared.messaging.MessagesScreen
import com.olive.app.ui.users.shared.navigation.LocalQuickExit
import com.olive.app.ui.users.shared.navigation.SessionViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OrganizationAndChatUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun text(id: Int) = context.getString(id)

    private fun session(
        role: AccountRole,
        initialize: SessionViewModel.() -> Unit,
    ): SessionViewModel {
        check(!BuildConfig.FIREBASE_CONFIGURED)
        val activity = compose.activity
        lateinit var session: SessionViewModel
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            session = ViewModelProvider(activity)[SessionViewModel::class.java]
        }
        compose.waitUntil(15_000) {
            var ready = false
            instrumentation.runOnMainSync { ready = !session.busy }
            ready
        }
        instrumentation.runOnMainSync {
            session.runtime.state.accountRole = role
            session.initialize()
        }
        return session
    }

    private fun conversation(type: String, closedAt: Long = 0L) = ChatConversation(
        id = "chat", friendshipId = "friendship",
        participantIds = listOf("owner", "member", "staff", "admin"),
        participantUsernames = mapOf(
            "owner" to "Private owner", "member" to "Private friend",
            "staff" to "Private staff", "admin" to "Private admin",
        ),
        type = type, ownerUid = "owner", staffUid = "staff", adminUid = "admin",
        ticketSequence = 520, closedAt = closedAt,
    )

    private fun scrollDialogTo(matcher: SemanticsMatcher) {
        val scroll = hasScrollToIndexAction()
        compose.onNode(scroll).performScrollToNode(matcher)
    }

    @Test
    fun ticketInfoAnonymizesPersonalMembersForStaffAndHidesMembershipActions() {
        val session = session(AccountRole.STAFF) {
            runtime.state.activeConversation = conversation("SUPPORT")
        }
        compose.setContent { OliveTheme { Box(Modifier.fillMaxSize()) { ChatInfoDialog(session, "staff", {}, { _, _ -> }) } } }
        compose.onNodeWithText("Support Ticket #520").assertExists()
        compose.onNodeWithText("Ticket #520").assertIsDisplayed()
        scrollDialogTo(hasTestTag("chat_member_member"))
        compose.onNodeWithText("Friend", ignoreCase = false).assertIsDisplayed()
        scrollDialogTo(hasTestTag("chat_member_admin"))
        compose.onNodeWithText(text(R.string.support_admin_chat_name)).assertIsDisplayed()
        compose.onNodeWithText("Private owner").assertDoesNotExist()
        compose.onNodeWithText("Private friend").assertDoesNotExist()
        scrollDialogTo(hasText(text(R.string.chat_no_shared_items)))
        compose.onNodeWithText(text(R.string.chat_no_shared_items)).assertIsDisplayed()
        compose.onNodeWithTag("chat_add_people").assertDoesNotExist()
        compose.onNodeWithTag("chat_request_staff").assertDoesNotExist()
    }

    @Test
    fun groupInfoOffersEligibleFriendsAndListsOnlyActiveUniqueSharedItems() {
        val session = session(AccountRole.USER) {
            runtime.state.activeConversation = conversation("GROUP")
            runtime.state.friends = listOf(Friend("old", "member", "Private friend"), Friend("new", "new", "Available friend"))
            runtime.state.chatMessages = listOf(
                ChatMessage("plain", "owner", "Owner", "Normal chat", 1),
                ChatMessage("note", "owner", "Owner", "SHARED_NOTE:note-1:Shared rehearsal", 2),
                ChatMessage("duplicate", "owner", "Owner", "SHARED_NOTE:note-1:Duplicate share", 3),
                ChatMessage("removed", "member", "Member", "SHARED_NOTE:note-2:Removed note", 4, unsent = true),
                ChatMessage("location", "member", "Member", "SHARED_LOCATION:map", 5),
                ChatMessage("incident", "member", "Member", "SHARED_INCIDENT:incident", 6),
                ChatMessage("plan", "member", "Member", "SHARED_SAFETY_PLAN:plan", 7),
            )
        }
        var closes = 0
        compose.setContent {
            OliveTheme {
                CompositionLocalProvider(LocalQuickExit provides {}) {
                    Box(Modifier.fillMaxSize()) { ChatInfoDialog(session, "owner", { closes++ }, { _, _ -> }) }
                }
            }
        }
        scrollDialogTo(hasTestTag("chat_remove_member"))
        compose.onNodeWithTag("chat_remove_member").assertIsEnabled()
        scrollDialogTo(hasTestTag("chat_add_people"))
        compose.onNodeWithTag("chat_add_people").performClick()
        compose.onNodeWithTag("chat_add_new").assertIsDisplayed()
        compose.onNodeWithTag("chat_add_member").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        scrollDialogTo(hasTestTag("chat_request_staff"))
        compose.onNodeWithTag("chat_request_staff").assertIsEnabled()
        scrollDialogTo(hasText("Shared rehearsal"))
        compose.onNodeWithText("Shared rehearsal").assertIsDisplayed()
        compose.onNodeWithText("Duplicate share").assertDoesNotExist()
        compose.onNodeWithText("Removed note").assertDoesNotExist()
        compose.onNodeWithText("Normal chat").assertDoesNotExist()
        scrollDialogTo(hasText(text(R.string.shared_incident_report)))
        compose.onNodeWithText(text(R.string.shared_incident_report)).assertIsDisplayed()
        scrollDialogTo(hasText(text(R.string.shared_safety_plan)))
        compose.onNodeWithText(text(R.string.shared_safety_plan)).assertIsDisplayed()
        compose.onNodeWithTag("chat_info_back").performClick()
        compose.runOnIdle { assertEquals(1, closes) }
    }

    @Test
    fun staffChatSearchSkipsUnsentMessagesAndDeliveredStatusUpdatesInPlace() {
        val incoming = ChatMessage("incoming", "owner", "Private owner", "Check the appointment time", 1)
        val unsent = ChatMessage("removed", "owner", "Private owner", "Hidden content", 2, unsent = true)
        val draft = ChatMessage("reply", "staff", "Private staff", "I can help with that", 3,
            deliveryStatus = MessageDeliveryStatus.QUEUED)
        val session = session(AccountRole.STAFF) {
            runtime.state.activeConversation = conversation("SUPPORT").copy(adminUid = "")
            runtime.state.chatMessages = listOf(incoming, unsent, draft)
        }
        compose.setContent { OliveTheme { ChatPane(session, "staff", showBack = true, exit = null) } }
        compose.onNodeWithTag("request_admin_help").assertIsDisplayed()
        compose.onNodeWithTag("close_support_chat").assertIsDisplayed()
        compose.onNodeWithTag("message_status_reply").assertExists()
        compose.runOnIdle {
            session.runtime.state.chatMessages = listOf(incoming, unsent, draft.copy(deliveryStatus = MessageDeliveryStatus.SENT))
        }
        compose.onNodeWithTag("message_status_reply").assertDoesNotExist()
        compose.onNodeWithText("I can help with that").assertExists()

        compose.onNodeWithTag("chat_search_toggle").performClick()
        val query = compose.onNode(hasSetTextAction() and hasText(text(R.string.chat_search_messages)))
        query.performTextReplacement("appointment")
        compose.onNodeWithText("Check the appointment time").assertExists()
        compose.onNodeWithText("I can help with that").assertDoesNotExist()
        query.performTextReplacement("Hidden content")
        compose.onNodeWithText(text(R.string.chat_no_matching_messages)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.clear_search)).performClick()
        compose.onNodeWithText("I can help with that").assertExists()
        compose.onNodeWithText("Private owner").assertDoesNotExist()
    }

    @Test
    fun personalMessageListsSeparateRequestsUnreadConversationsAndArchivedTickets() {
        val session = session(AccountRole.USER) {
            runtime.enrollment = DeviceEnrollment("fixture-owner", "")
            runtime.state.incomingFriendRequests = listOf(
                FriendRequest("ask", "incoming", "New friend", "fixture-owner", "Owner", 1),
            )
            runtime.state.outgoingFriendRequests = listOf(
                FriendRequest("pending", "fixture-owner", "Owner", "other", "Pending friend", 1),
            )
            runtime.state.friends = listOf(Friend("saved-friend", "member", "Trusted friend"))
            runtime.state.userSearchResults = listOf(
                DirectoryUser("member", "Trusted friend"),
                DirectoryUser("incoming", "New friend"),
                DirectoryUser("other", "Pending friend"),
                DirectoryUser("new", "Available friend"),
            )
            val dm = conversation("DIRECT").copy(
                participantIds = listOf("fixture-owner", "member"),
                participantUsernames = mapOf("fixture-owner" to "Owner", "member" to "Trusted friend"),
            )
            runtime.state.conversations = listOf(
                dm.copy(id = "unread", lastMessageId = "fixture-message", lastSenderUid = "member"),
                dm.copy(id = "read"),
                dm.copy(id = "closed", type = "SUPPORT", closedAt = 12),
                dm.copy(id = "unrelated", type = "SUPPORT", participantIds = listOf("unrelated")),
            )
        }
        compose.setContent { OliveTheme { MessagesScreen(session) } }
        val list = compose.onNode(hasScrollToIndexAction())
        fun goTo(textId: Int) {
            val label = text(textId)
            list.performScrollToNode(hasText(label))
            compose.onNodeWithText(label).performClick()
        }
        list.performScrollToNode(hasText(text(R.string.add_friend)))
        compose.onNodeWithText(text(R.string.add_friend)).assertIsEnabled()
        goTo(R.string.incoming_friend_requests)
        list.performScrollToNode(hasTestTag("accept_friend_ask"))
        compose.onNodeWithTag("accept_friend_ask").assertIsEnabled()
        compose.onNodeWithTag("decline_friend_ask").assertIsEnabled()
        goTo(R.string.outgoing_friend_requests)
        list.performScrollToNode(hasTestTag("cancel_friend_pending"))
        compose.onNodeWithTag("cancel_friend_pending").assertIsEnabled()
        list.performScrollToNode(hasTestTag("conversation_read"))
        compose.onNodeWithTag("conversation_read").assertIsDisplayed()
        goTo(R.string.chat_unread_only)
        list.performScrollToNode(hasTestTag("conversation_unread"))
        compose.onNodeWithTag("conversation_unread").assertIsDisplayed()
        compose.onNodeWithTag("conversation_read").assertDoesNotExist()
        goTo(R.string.chat_unread_only)
        list.performScrollToNode(hasTestTag("messages_tickets_tab"))
        compose.onNodeWithTag("messages_tickets_tab").performClick()
        compose.onNodeWithTag("message_username").assertDoesNotExist()
        list.performScrollToNode(hasTestTag("archived_chats"))
        compose.onNodeWithTag("archived_chats").performClick()
        list.performScrollToNode(hasTestTag("conversation_closed"))
        compose.onNodeWithTag("conversation_closed").assertIsDisplayed()
        compose.onNodeWithTag("conversation_unrelated").assertDoesNotExist()
    }

    @Test
    fun staffInboxFiltersItsAssignedTicketsByCategory() {
        val session = session(AccountRole.STAFF) {
            runtime.enrollment = DeviceEnrollment("staff", "", AccountRole.STAFF)
            val ticket = conversation("SUPPORT")
            runtime.state.conversations = listOf(
                ticket.copy(id = "consult", supportTag = SUPPORT_TAG_PLAN_CONSULT, supportOrigin = SUPPORT_TAG_PLAN_CONSULT),
                ticket.copy(id = "support", supportTag = SUPPORT_TAG_SUPPORT),
                ticket.copy(id = "escalated", supportTag = SUPPORT_TAG_NEEDS_ADMIN, closedAt = 25),
                ticket.copy(id = "unassigned", supportTag = SUPPORT_TAG_SUPPORT, participantIds = listOf("owner", "different-staff")),
            )
        }
        compose.setContent { OliveTheme { MessagesScreen(session) } }
        compose.onNodeWithTag("conversation_consult").assertIsDisplayed()
        compose.onNodeWithTag("support_filter_$SUPPORT_TAG_SUPPORT").performClick()
        compose.onNodeWithTag("conversation_support").assertIsDisplayed()
        compose.onNodeWithTag("conversation_consult").assertDoesNotExist()
        compose.onNodeWithTag("conversation_unassigned").assertDoesNotExist()
        compose.onNodeWithTag("support_filter_$SUPPORT_TAG_NEEDS_ADMIN").performClick()
        compose.onNodeWithTag("archived_chats").performClick()
        compose.onNodeWithTag("conversation_escalated").assertIsDisplayed()
        compose.onNodeWithTag("conversation_support").assertDoesNotExist()
    }

    @Test
    fun adminDashboardLinksToStaffWorkloadsAndEmergencyInbox() {
        val session = session(AccountRole.ADMIN) {
            runtime.enrollment = DeviceEnrollment("fixture-admin", "", AccountRole.ADMIN)
            runtime.state.screen = SessionScreen.Portal
            runtime.state.adminCounts = AccountCounts(openTickets = 12, closedTickets = 7)
            runtime.state.adminAccounts = listOf(
                AdminAccountSummary("staff", "workload_staff", AccountRole.STAFF, "Support person", "", "",
                    true, false, safetyPlans = 6, completedSafetyPlans = 2, supportChats = 9, closedSupportChats = 3),
            )
        }
        compose.setContent { OliveTheme { AdminPortal(session) } }
        compose.onNodeWithContentDescription("12 ${text(R.string.admin_open_tickets)}").assertExists()
        compose.onNodeWithContentDescription("7 ${text(R.string.admin_closed_tickets)}").assertExists()
        val emergencyCard = text(R.string.admin_emergency_response) + ". " + text(R.string.admin_emergency_response_help)
        compose.onNodeWithContentDescription(emergencyCard).assertHasClickAction().performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.emergency_response_empty)).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNodeWithText(text(R.string.admin_staff_management)).performScrollTo().performClick()
        val workload = context.resources.getQuantityString(
            R.plurals.admin_staff_workload_accessibility, 6, "workload_staff", 6, 2, 9, 3,
        )
        compose.onNodeWithContentDescription(workload).assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.ui_back)).performClick()
        compose.onNodeWithText(text(R.string.admin_panel)).assertIsDisplayed()
    }

    @Test
    fun staffSettingsKeepProfileAndPasswordInputsInTheirOwnTabs() {
        val session = session(AccountRole.STAFF) {
            runtime.enrollment = DeviceEnrollment("fixture-profile", "", AccountRole.STAFF)
            runtime.state.screen = SessionScreen.Portal
            runtime.state.profile = UserProfile(
                username = "staff_fixture", fullName = "Fixture Name", affectedNames = "", abuserName = "",
                dob = "1990-05-07", language = "English", situation = "", region = "", triggerHash = "",
                role = AccountRole.STAFF, email = "staff@example.invalid",
            )
        }
        compose.setContent { OliveTheme { StaffPortal(session) } }
        compose.onNodeWithText(text(R.string.safety_inbox)).performClick()
        compose.onNodeWithTag("safety_staff_available").assertIsDisplayed().assertIsToggleable()
            .assertContentDescriptionEquals(text(R.string.safety_available))
        compose.onNodeWithText(text(R.string.safety_available)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.safety_back)).performClick()
        compose.onNodeWithContentDescription(text(R.string.account_settings)).performClick()
        compose.onNodeWithTag("organization_settings_name").assertTextContains("Fixture Name")
        compose.onNodeWithText(text(R.string.password), useUnmergedTree = true).performClick()
        compose.onNodeWithTag("organization_settings_name").assertDoesNotExist()
        compose.onNodeWithTag("organization_current_password").assertExists()
        compose.onNodeWithTag("organization_new_password").assertExists()
        compose.onNodeWithTag("organization_confirm_password").assertExists()
        compose.onNodeWithTag("settings_tab_permissions").performClick()
        compose.onNodeWithTag("settings_install_permission").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ui_profile), useUnmergedTree = true).performClick()
        compose.onNodeWithTag("organization_settings_name").assertTextContains("Fixture Name")
        compose.onNodeWithTag("organization_current_password").assertDoesNotExist()
    }

    @Test
    fun emergencyInboxShowsTrackingActionsAndSeparatesResolvedRequests() {
        val session = session(AccountRole.ADMIN) {}
        val alerts = listOf(
            EmergencyAlert("active", "active-user", "Active test account", 100, ""),
            EmergencyAlert("resolved", "resolved-user", "Resolved test account", 10, "report", resolvedAt = 20),
        )
        var tracked: String? = null
        compose.setContent {
            OliveTheme { EmergencyResponseScreen(session, alerts, null, {}, { tracked = it }) }
        }
        compose.onNodeWithText("Active test account").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.emergency_response_waiting_pdf)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.emergency_response_resolve)).assertIsEnabled()
        compose.onNodeWithText("Resolved test account").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.emergency_response_track)).performClick()
        compose.runOnIdle { assertEquals("active-user", tracked) }
        compose.onNodeWithText(text(R.string.emergency_response_resolved)).performClick()
        compose.onNodeWithText("Resolved test account").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.emergency_response_reopen)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.emergency_response_pdf)).assertIsEnabled()
        compose.onNodeWithText("Active test account").assertDoesNotExist()
    }

    @Test
    fun accountSearchAndSetupStatusFilterShowOnlyMatchingAccounts() {
        val session = session(AccountRole.ADMIN) {
            runtime.state.adminAccounts = listOf(
                AdminAccountSummary("pending", "pending_user", AccountRole.USER, "", "", "", false, false),
                AdminAccountSummary("complete", "complete_user", AccountRole.USER, "Complete person", "", "", true, false),
                AdminAccountSummary("staff", "team_account", AccountRole.STAFF, "Support member", "team@example.invalid", "", true, true, assignedUsers = 4),
            )
        }
        var selected: String? = null
        compose.setContent {
            OliveTheme {
                Box(Modifier.fillMaxSize()) { AdminAccounts(session, {}, {}, { selected = it.uid }) }
            }
        }
        val list = compose.onNode(hasScrollToIndexAction())
        val pending = hasContentDescription("pending_user", substring = true)
        val complete = hasContentDescription("complete_user", substring = true)
        val team = hasContentDescription("team_account", substring = true)
        list.performScrollToNode(team)
        compose.onNode(team).assertIsDisplayed()
        list.performScrollToNode(hasText(text(R.string.admin_search_accounts)))
        compose.onNode(hasSetTextAction()).performTextReplacement("pending_user")
        compose.onNodeWithText(text(R.string.ui_setup_complete)).performClick()
        compose.onNode(pending).assertDoesNotExist()
        compose.onNode(complete).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.ui_setup_required)).performClick()
        list.performScrollToNode(pending)
        compose.onNode(pending).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.ui_delete)).performClick()
        compose.runOnIdle { assertEquals("pending", selected) }
    }

    @Test
    fun managedAccountFormShowsDifferentFieldsForStaff() {
        val session = session(AccountRole.ADMIN) {}
        var dismissed = false
        compose.setContent { OliveTheme { CreateAccountDialog(session) { dismissed = true } } }
        compose.onNodeWithTag("admin_city").assertExists()
        compose.onNodeWithText(text(R.string.account_type)).performClick()
        compose.onNodeWithText("Staff", ignoreCase = true).performClick()
        compose.onNodeWithTag("admin_city").assertDoesNotExist()
        compose.onNodeWithTag("admin_email").assertExists()
        compose.onNodeWithTag("admin_phone").assertExists()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.runOnIdle { assertEquals(true, dismissed) }
    }

    @Test
    fun organizationProfileSetupValidatesContactDetailsAndRetainsDraftWhenGoingBack() {
        val session = session(AccountRole.STAFF) {
            runtime.state.screen = SessionScreen.OrganizationProfileSetup
            runtime.pendingProfile = UserProfile("staff_fixture", "Synthetic staff", "", "", "1995-04-18",
                "English", "", "", "", role = AccountRole.STAFF)
        }
        compose.setContent { OliveTheme { com.olive.app.ui.users.shared.organization.OrganizationProfileSetup(session) } }
        compose.onNodeWithTag("organization_full_name").performTextReplacement("Updated synthetic staff")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("onboarding_next").performScrollTo().performClick()
        val email = compose.onNodeWithTag("organization_email")
        email.performTextInput("invalid")
        compose.onNodeWithTag("organization_profile_submit").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.ui_invalid_email)).assertExists()
        email.performTextReplacement("staff@example.test")
        compose.onNodeWithTag("organization_phone").performTextInput("555-0100")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("onboarding_back").performScrollTo().performClick()
        compose.onNodeWithTag("organization_full_name").assertTextContains("Updated synthetic staff")
        compose.onNodeWithTag("onboarding_next").performScrollTo().performClick()
        email.assertTextContains("staff@example.test")
        compose.onNodeWithTag("organization_phone").assertTextContains("555-0100")
    }

    @Test
    fun organizationMapOpensFullScreenAndBackReturnsToThePortal() {
        val session = session(AccountRole.ADMIN) {
            runtime.state.screen = SessionScreen.Portal
            runtime.enrollment = DeviceEnrollment("map-admin-fixture", "", AccountRole.ADMIN)
        }
        var backed = false
        compose.setContent { OliveTheme {
            com.olive.app.ui.users.shared.organization.OrganizationMap(session, { backed = true })
        } }
        compose.onNodeWithTag("organization_location_map").assertIsDisplayed()
        compose.onNodeWithTag("organization_map_back").performClick()
        compose.runOnIdle { org.junit.Assert.assertTrue(backed) }
    }
}
