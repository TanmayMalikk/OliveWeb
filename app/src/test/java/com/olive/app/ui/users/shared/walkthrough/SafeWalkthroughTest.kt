package com.olive.app.ui.users.shared.walkthrough

import com.olive.app.data.account.AccountRole
import com.olive.app.ui.users.shared.navigation.SafePage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        assertEquals(5, contactTopics.size)
        assertFalse(contactTopics.any { it.userOnly })
        assertEquals(
            listOf("safety_plan", "notes_incidents", "documents_ocr", "messaging_calls", "location_map"),
            contactTopics.map { it.id },
        )
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
