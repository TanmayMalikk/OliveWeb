package com.olive.app.ui.users.shared.walkthrough

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
    fun multiStepTourCoversAllMessagingFeaturesAllSettingsTabsAndAutoOpensSupportMenu() {
        val userSteps = walkthroughTourStepsForRole(AccountRole.USER)
        val contactSteps = walkthroughTourStepsForRole(AccountRole.SAFE_CONTACT)

        assertEquals(29, userSteps.size)
        assertEquals(18, contactSteps.size)

        val supportMenuSteps = userSteps.filter { it.openSupportMenu }
        assertEquals(
            listOf(
                "support_menu_staff",
                "support_menu_staff_escalate",
                "support_menu_emergency",
                "support_menu_emergency_alert",
            ),
            supportMenuSteps.map { it.id },
        )
        assertTrue(supportMenuSteps.all { it.tourPage == SafePage.Home && it.arrow == WalkthroughArrow.SouthWest })

        val messagingSteps = userSteps.filter { it.topicId == "messaging_calls" }
        assertEquals(4, messagingSteps.size)

        val settingsTabs = userSteps.mapNotNull { it.settingsTabTag }
        assertEquals(
            listOf(
                "settings_tab_profile",
                "settings_tab_password",
                "settings_tab_privacy",
                "settings_tab_permissions",
            ),
            settingsTabs,
        )
        assertEquals(
            listOf("settings_tab_profile", "settings_tab_password", "settings_tab_permissions"),
            contactSteps.mapNotNull { it.settingsTabTag },
        )

        assertEquals("home_header_shortcuts", nextWalkthroughTourStepId(userSteps, "quick_exit_fab", 1))
        assertNull(nextWalkthroughTourStepId(userSteps, "quick_exit_fab", -1))
        assertNull(nextWalkthroughTourStepId(userSteps, "settings_permissions", 1))
        assertEquals("settings_privacy_passkey", nextWalkthroughTourStepId(userSteps, "settings_permissions", -1))
        assertEquals("chat_add_friends", firstWalkthroughStepForTopic(AccountRole.USER, "messaging_calls")?.id)
        assertEquals(4, walkthroughStepCountForTopic(AccountRole.USER, "messaging_calls"))
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
