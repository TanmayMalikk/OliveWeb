package com.olive.app.ui.users.shared.walkthrough

import androidx.compose.ui.geometry.Rect
import com.olive.app.data.account.AccountRole
import com.olive.app.ui.users.shared.navigation.SafePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeWalkthroughTest {
    @Test
    fun userRoleReceivesFullFeatureTableOfContentsAndSafeContactExcludesUserOnlyTopics() {
        val userTopics = walkthroughTopicsForRole(AccountRole.USER)
        val contactTopics = walkthroughTopicsForRole(AccountRole.SAFE_CONTACT)

        assertEquals(11, userTopics.size)
        assertTrue(userTopics.all { it.stepResIds.size == 3 })
        assertEquals(
            setOf(
                SafePage.Home,
                SafePage.Settings,
                SafePage.SafetyPlan,
                SafePage.Notes,
                SafePage.Documents,
                SafePage.Messages,
                SafePage.Location,
                SafePage.StaffHelp,
                SafePage.Hotlines,
                SafePage.Activity,
            ),
            userTopics.map { it.targetPage }.toSet(),
        )

        assertEquals(6, contactTopics.size)
        assertFalse(contactTopics.any { it.userOnly })
        assertEquals(
            listOf("safety_plan", "notes_incidents", "documents_ocr", "messaging_calls", "location_map", "cover_entry"),
            contactTopics.map { it.id },
        )
    }

    @Test
    fun topicGroupsPreserveCategoryOrderAndFilterEmptySectionsForSafeContacts() {
        val userGroups = walkthroughTopicGroups(walkthroughTopicsForRole(AccountRole.USER))
        val contactGroups = walkthroughTopicGroups(walkthroughTopicsForRole(AccountRole.SAFE_CONTACT))

        assertEquals(
            listOf(
                WalkthroughCategory.PRIVACY,
                WalkthroughCategory.TOOLS,
                WalkthroughCategory.SUPPORT,
                WalkthroughCategory.ACCOUNT,
            ),
            userGroups.map { it.category },
        )
        assertEquals(listOf(WalkthroughCategory.PRIVACY, WalkthroughCategory.TOOLS), contactGroups.map { it.category })
        assertEquals(1, contactGroups.first().topics.size)
        assertEquals(5, contactGroups.last().topics.size)
    }

    @Test
    fun multiStepTourCoversAllMessagingFeaturesAllSettingsCardsAndAutoOpensPopups() {
        val userSteps = walkthroughTourStepsForRole(AccountRole.USER)
        val contactSteps = walkthroughTourStepsForRole(AccountRole.SAFE_CONTACT)

        assertEquals(47, userSteps.size)
        assertEquals(20, contactSteps.size)

        val supportMenuSteps = userSteps.filter { it.openSupportMenu }
        assertEquals(
            listOf(
                "support_menu_quick_links",
                "support_menu_staff",
                "support_menu_emergency",
            ),
            supportMenuSteps.map { it.id },
        )
        assertTrue(supportMenuSteps.all { it.tourPage == SafePage.Home && it.avoidId == "support_action_list" })

        val popupSteps = userSteps.filter { it.opensPopup }
        assertEquals(
            listOf(
                "plan_tailor_questionnaire",
                "notes_editor_attachments",
                "docs_upload_popup",
                "docs_private_camera",
                "chat_invite_popup",
                "loc_directory_places_popup",
                "loc_live_sharing_duration",
            ),
            popupSteps.map { it.id },
        )

        val planSteps = userSteps.filter { it.topicId == "safety_plan" }
        assertEquals(6, planSteps.size)
        assertEquals(
            listOf(
                "plan_primary_tab",
                "plan_section_jumps",
                "plan_backup_tab",
                "plan_consultation",
                "plan_share_contacts",
                "plan_tailor_questionnaire",
            ),
            planSteps.map { it.id },
        )

        val notesSteps = userSteps.filter { it.topicId == "notes_incidents" }
        assertEquals(
            listOf(
                "notes_search_filter_sort",
                "notes_editor_incident",
                "notes_editor_attachments",
                "notes_trash_button",
            ),
            notesSteps.map { it.id },
        )

        val messagingSteps = userSteps.filter { it.topicId == "messaging_calls" }
        assertEquals(
            listOf(
                "chat_tabs_dms_tickets",
                "chat_invite_popup",
                "chat_enter_invite_code",
                "chat_in_chat_tools",
                "chat_ticket_tab_archive",
            ),
            messagingSteps.map { it.id },
        )

        // Every topic's steps must appear in a single contiguous block (no split topics).
        listOf(userSteps, contactSteps).forEach { steps ->
            val seenTopics = mutableSetOf<String>()
            var prevTopic: String? = null
            steps.forEach { step ->
                if (step.topicId != prevTopic) {
                    assertTrue("Split topic ${step.topicId}", seenTopics.add(step.topicId))
                    prevTopic = step.topicId
                }
            }
        }
        assertEquals("hotlines_region_location", firstWalkthroughStepForTopic(AccountRole.USER, "hotlines_contacts")?.id)

        val userSettingsSteps = userSteps.filter { it.settingsTabTag != null }
        assertEquals(12, userSettingsSteps.size)
        assertEquals(
            listOf(
                "settings_tab_profile",
                "settings_tab_password",
                "settings_tab_privacy",
                "settings_tab_permissions",
            ),
            userSettingsSteps.mapNotNull { it.settingsTabTag }.distinct(),
        )
        val contactSettingsSteps = contactSteps.filter { it.settingsTabTag != null }
        assertEquals(5, contactSettingsSteps.size)
        assertEquals(
            listOf("settings_tab_profile", "settings_tab_password", "settings_tab_permissions"),
            contactSettingsSteps.mapNotNull { it.settingsTabTag }.distinct(),
        )

        assertEquals("home_profile_activity", nextWalkthroughTourStepId(userSteps, "home_header_shortcuts", 1))
        assertNull(nextWalkthroughTourStepId(userSteps, "home_header_shortcuts", -1))
        assertNull(nextWalkthroughTourStepId(userSteps, "settings_permissions_automatic", 1))
        assertEquals("settings_permissions_features", nextWalkthroughTourStepId(userSteps, "settings_permissions_automatic", -1))
        assertEquals("chat_tabs_dms_tickets", firstWalkthroughStepForTopic(AccountRole.USER, "messaging_calls")?.id)
        assertEquals(5, walkthroughStepCountForTopic(AccountRole.USER, "messaging_calls"))
    }

    @Test
    fun coachmarkPlacementNeverOverlapsHighlightedTargetOrOpenSupportMenu() {
        val width = 1080f
        val height = 2200f
        val cardW = 900f
        val cardH = 480f
        val gap = 24f

        val topRightCamera = Rect(820f, 120f, 1040f, 220f)
        val belowCamera = computeCoachmarkPlacement(
            avoidRect = topRightCamera,
            containerWidth = width,
            containerHeight = height,
            cardWidth = cardW,
            cardHeight = cardH,
            safeTop = 100f,
            safeBottom = 1960f,
            marginPx = 32f,
            gapPx = gap,
        )
        assertTrue("Popup must sit strictly below top camera buttons", belowCamera.topPx >= topRightCamera.bottom)

        val profileButtons = Rect(40f, 520f, 1040f, 640f)
        val belowProfile = computeCoachmarkPlacement(
            avoidRect = profileButtons,
            containerWidth = width,
            containerHeight = height,
            cardWidth = cardW,
            cardHeight = cardH,
            safeTop = 100f,
            safeBottom = 1960f,
            marginPx = 32f,
            gapPx = gap,
        )
        assertTrue("Popup must sit strictly below My profile & Account activity buttons", belowProfile.topPx >= profileButtons.bottom)

        val quickExit = Rect(700f, 2000f, 1048f, 2140f)
        val aboveQuickExit = computeCoachmarkPlacement(
            avoidRect = quickExit,
            containerWidth = width,
            containerHeight = height,
            cardWidth = cardW,
            cardHeight = cardH,
            safeTop = 100f,
            safeBottom = 2160f,
            marginPx = 32f,
            gapPx = gap,
        )
        assertTrue("Popup must sit strictly above Quick exit", aboveQuickExit.topPx + cardH <= quickExit.top)

        val supportMenuList = Rect(32f, 900f, 920f, 1950f)
        val aboveSupportMenu = computeCoachmarkPlacement(
            avoidRect = supportMenuList,
            containerWidth = width,
            containerHeight = height,
            cardWidth = cardW,
            cardHeight = cardH,
            safeTop = 16f,
            safeBottom = 1960f,
            marginPx = 32f,
            gapPx = gap,
        )
        assertTrue("Popup must sit strictly above the open + menu", aboveSupportMenu.topPx + cardH <= supportMenuList.top)
    }

    @Test
    fun stepAndTopicNavigationClampWithinValidBounds() {
        assertEquals(0, clampWalkthroughStep(-3, 3))
        assertEquals(1, clampWalkthroughStep(1, 3))
        assertEquals(2, clampWalkthroughStep(9, 3))
        assertEquals(0, clampWalkthroughStep(4, 0))

        assertEquals(0, nextWalkthroughTopicIndex(0, 11, -1))
        assertEquals(1, nextWalkthroughTopicIndex(0, 11, 1))
        assertEquals(10, nextWalkthroughTopicIndex(10, 11, 1))
        assertEquals(0, nextWalkthroughTopicIndex(0, 1, 1))
    }
}
