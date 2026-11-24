// Safe-app users and safe contacts: walkthrough catalog metadata, in-app tour anchors, and navigation helpers.
package com.olive.app.ui.users.shared.walkthrough

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.ui.graphics.vector.ImageVector
import com.olive.app.R
import com.olive.app.data.account.AccountRole
import com.olive.app.ui.users.shared.navigation.SafePage

/**
 * Placement of an in-app walkthrough coachmark card over the live target screen.
 */
internal enum class WalkthroughAnchor {
    BottomEnd,
    BottomStart,
    TopEnd,
    TopCenter,
    BottomCenter,
}

/**
 * Directional arrow displayed inside the coachmark card to point toward the target control.
 */
internal enum class WalkthroughArrow {
    SouthEast,
    SouthWest,
    NorthEast,
    North,
    South,
}

/**
 * Top-level Table of Contents groupings for walkthrough features.
 *
 * @property labelRes Localized section heading resource ID.
 */
internal enum class WalkthroughCategory(@param:StringRes val labelRes: Int) {
    PRIVACY(R.string.walkthrough_cat_privacy),
    TOOLS(R.string.walkthrough_cat_tools),
    SUPPORT(R.string.walkthrough_cat_support),
    ACCOUNT(R.string.walkthrough_cat_account),
}

/**
 * Metadata describing a single Olive Branch feature covered in the live guided Walkthrough.
 *
 * @property category Grouping section in the Table of Contents.
 * @property id Stable identifier used for test tags, deep links, and tour progression.
 * @property titleRes Localized feature title resource ID.
 * @property summaryRes Localized short summary shown in Table of Contents cards and first-visit popups.
 * @property stepResIds Ordered three-step instructions; step 1 is shown in the floating coachmark popup.
 * @property targetPage Primary [SafePage] associated with this feature (used by first-visit triggers).
 * @property targetLabelRes Localized name of [targetPage].
 * @property icon Vector icon representing the feature.
 * @property userOnly Whether the topic is hidden from safe-contact accounts.
 * @property tourPage Live destination page opened during the in-app coachmark tour.
 * @property anchor Screen placement for the floating coachmark card.
 * @property arrow Arrow direction inside the floating coachmark pointing toward the real UI element.
 */
internal data class WalkthroughTopic(
    val category: WalkthroughCategory,
    val id: String,
    @param:StringRes val titleRes: Int,
    @param:StringRes val summaryRes: Int,
    val stepResIds: List<Int>,
    val targetPage: SafePage,
    @param:StringRes val targetLabelRes: Int,
    val icon: ImageVector,
    val userOnly: Boolean = false,
    val tourPage: SafePage = targetPage,
    val anchor: WalkthroughAnchor = WalkthroughAnchor.BottomCenter,
    val arrow: WalkthroughArrow = WalkthroughArrow.North,
)

/**
 * Grouped section returned for rendering the Table of Contents.
 *
 * @property category Section category metadata.
 * @property topics Ordered topics within [category] available to the active role.
 */
internal data class WalkthroughTopicGroup(
    val category: WalkthroughCategory,
    val topics: List<WalkthroughTopic>,
)

internal val allWalkthroughTopics: List<WalkthroughTopic> = listOf(
    WalkthroughTopic(
        category = WalkthroughCategory.PRIVACY,
        id = "cover_entry",
        titleRes = R.string.walkthrough_topic_cover_title,
        summaryRes = R.string.walkthrough_topic_cover_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_cover_step_1,
            R.string.walkthrough_topic_cover_step_2,
            R.string.walkthrough_topic_cover_step_3,
        ),
        targetPage = SafePage.Settings,
        targetLabelRes = R.string.settings,
        icon = Icons.Outlined.Lock,
        userOnly = true,
        tourPage = SafePage.Settings,
        anchor = WalkthroughAnchor.BottomCenter,
        arrow = WalkthroughArrow.North,
    ),
    WalkthroughTopic(
        category = WalkthroughCategory.PRIVACY,
        id = "quick_exit",
        titleRes = R.string.walkthrough_topic_exit_title,
        summaryRes = R.string.walkthrough_topic_exit_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_exit_step_1,
            R.string.walkthrough_topic_exit_step_2,
            R.string.walkthrough_topic_exit_step_3,
        ),
        targetPage = SafePage.Settings,
        targetLabelRes = R.string.settings,
        icon = Icons.AutoMirrored.Outlined.ExitToApp,
        userOnly = true,
        tourPage = SafePage.Home,
        anchor = WalkthroughAnchor.BottomEnd,
        arrow = WalkthroughArrow.SouthEast,
    ),
    WalkthroughTopic(
        category = WalkthroughCategory.TOOLS,
        id = "safety_plan",
        titleRes = R.string.walkthrough_topic_plan_title,
        summaryRes = R.string.walkthrough_topic_plan_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_plan_step_1,
            R.string.walkthrough_topic_plan_step_2,
            R.string.walkthrough_topic_plan_step_3,
        ),
        targetPage = SafePage.SafetyPlan,
        targetLabelRes = R.string.support_safety_plan,
        icon = Icons.Outlined.HealthAndSafety,
        tourPage = SafePage.SafetyPlan,
        anchor = WalkthroughAnchor.BottomCenter,
        arrow = WalkthroughArrow.North,
    ),
    WalkthroughTopic(
        category = WalkthroughCategory.TOOLS,
        id = "notes_incidents",
        titleRes = R.string.walkthrough_topic_notes_title,
        summaryRes = R.string.walkthrough_topic_notes_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_notes_step_1,
            R.string.walkthrough_topic_notes_step_2,
            R.string.walkthrough_topic_notes_step_3,
        ),
        targetPage = SafePage.Notes,
        targetLabelRes = R.string.notes_incidents,
        icon = Icons.Outlined.Description,
        tourPage = SafePage.Notes,
        anchor = WalkthroughAnchor.BottomCenter,
        arrow = WalkthroughArrow.North,
    ),
    WalkthroughTopic(
        category = WalkthroughCategory.TOOLS,
        id = "documents_ocr",
        titleRes = R.string.walkthrough_topic_docs_title,
        summaryRes = R.string.walkthrough_topic_docs_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_docs_step_1,
            R.string.walkthrough_topic_docs_step_2,
            R.string.walkthrough_topic_docs_step_3,
        ),
        targetPage = SafePage.Documents,
        targetLabelRes = R.string.support_documents,
        icon = Icons.Outlined.FolderOpen,
        tourPage = SafePage.Documents,
        anchor = WalkthroughAnchor.BottomCenter,
        arrow = WalkthroughArrow.North,
    ),
    WalkthroughTopic(
        category = WalkthroughCategory.TOOLS,
        id = "messaging_calls",
        titleRes = R.string.walkthrough_topic_chat_title,
        summaryRes = R.string.walkthrough_topic_chat_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_chat_step_1,
            R.string.walkthrough_topic_chat_step_2,
            R.string.walkthrough_topic_chat_step_3,
        ),
        targetPage = SafePage.Messages,
        targetLabelRes = R.string.messages,
        icon = Icons.Outlined.ChatBubbleOutline,
        tourPage = SafePage.Messages,
        anchor = WalkthroughAnchor.BottomCenter,
        arrow = WalkthroughArrow.North,
    ),
    WalkthroughTopic(
        category = WalkthroughCategory.TOOLS,
        id = "location_map",
        titleRes = R.string.walkthrough_topic_location_title,
        summaryRes = R.string.walkthrough_topic_location_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_location_step_1,
            R.string.walkthrough_topic_location_step_2,
            R.string.walkthrough_topic_location_step_3,
        ),
        targetPage = SafePage.Location,
        targetLabelRes = R.string.location_map,
        icon = Icons.Outlined.LocationOn,
        tourPage = SafePage.Location,
        anchor = WalkthroughAnchor.TopCenter,
        arrow = WalkthroughArrow.South,
    ),
    WalkthroughTopic(
        category = WalkthroughCategory.SUPPORT,
        id = "staff_help",
        titleRes = R.string.walkthrough_topic_staff_title,
        summaryRes = R.string.walkthrough_topic_staff_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_staff_step_1,
            R.string.walkthrough_topic_staff_step_2,
            R.string.walkthrough_topic_staff_step_3,
        ),
        targetPage = SafePage.StaffHelp,
        targetLabelRes = R.string.support_staff_help,
        icon = Icons.Outlined.SupportAgent,
        userOnly = true,
        tourPage = SafePage.Home,
        anchor = WalkthroughAnchor.BottomStart,
        arrow = WalkthroughArrow.SouthWest,
    ),
    WalkthroughTopic(
        category = WalkthroughCategory.SUPPORT,
        id = "emergency_report",
        titleRes = R.string.walkthrough_topic_emergency_title,
        summaryRes = R.string.walkthrough_topic_emergency_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_emergency_step_1,
            R.string.walkthrough_topic_emergency_step_2,
            R.string.walkthrough_topic_emergency_step_3,
        ),
        targetPage = SafePage.Documents,
        targetLabelRes = R.string.support_documents,
        icon = Icons.Outlined.WarningAmber,
        userOnly = true,
        tourPage = SafePage.Home,
        anchor = WalkthroughAnchor.BottomStart,
        arrow = WalkthroughArrow.SouthWest,
    ),
    WalkthroughTopic(
        category = WalkthroughCategory.SUPPORT,
        id = "hotlines_contacts",
        titleRes = R.string.walkthrough_topic_hotlines_title,
        summaryRes = R.string.walkthrough_topic_hotlines_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_hotlines_step_1,
            R.string.walkthrough_topic_hotlines_step_2,
            R.string.walkthrough_topic_hotlines_step_3,
        ),
        targetPage = SafePage.Hotlines,
        targetLabelRes = R.string.support_hotlines,
        icon = Icons.Outlined.PhoneInTalk,
        userOnly = true,
        tourPage = SafePage.Hotlines,
        anchor = WalkthroughAnchor.BottomCenter,
        arrow = WalkthroughArrow.North,
    ),
    WalkthroughTopic(
        category = WalkthroughCategory.ACCOUNT,
        id = "activity_settings",
        titleRes = R.string.walkthrough_topic_activity_title,
        summaryRes = R.string.walkthrough_topic_activity_summary,
        stepResIds = listOf(
            R.string.walkthrough_topic_activity_step_1,
            R.string.walkthrough_topic_activity_step_2,
            R.string.walkthrough_topic_activity_step_3,
        ),
        targetPage = SafePage.Activity,
        targetLabelRes = R.string.account_activity,
        icon = Icons.Outlined.History,
        userOnly = true,
        tourPage = SafePage.Activity,
        anchor = WalkthroughAnchor.BottomCenter,
        arrow = WalkthroughArrow.North,
    ),
)

/**
 * Filters the walkthrough table of contents to the features supported by the given [role].
 *
 * @param role Account role viewing the walkthrough.
 * @return Ordered list of [WalkthroughTopic] items for that role.
 */
internal fun walkthroughTopicsForRole(role: AccountRole): List<WalkthroughTopic> =
    if (role == AccountRole.SAFE_CONTACT) allWalkthroughTopics.filterNot(WalkthroughTopic::userOnly)
    else allWalkthroughTopics

/**
 * Groups the given [topics] into ordered Table of Contents sections, omitting empty categories.
 *
 * @param topics Flat list of role-filtered walkthrough topics.
 * @return Ordered list of non-empty [WalkthroughTopicGroup] sections.
 */
internal fun walkthroughTopicGroups(topics: List<WalkthroughTopic>): List<WalkthroughTopicGroup> =
    WalkthroughCategory.entries.mapNotNull { category ->
        val groupTopics = topics.filter { it.category == category }
        if (groupTopics.isEmpty()) null else WalkthroughTopicGroup(category, groupTopics)
    }

/**
 * Clamps a requested step index into the valid step range `[0, totalSteps - 1]`.
 *
 * @param stepIndex Zero-based step index requested by the user.
 * @param totalSteps Total number of steps in the topic.
 * @return Valid zero-based step index.
 */
internal fun clampWalkthroughStep(stepIndex: Int, totalSteps: Int): Int =
    if (totalSteps <= 1) 0 else stepIndex.coerceIn(0, totalSteps - 1)

/**
 * Computes the next topic index when moving forward or backward through the table of contents.
 *
 * @param currentIndex Current zero-based topic index.
 * @param totalTopics Total number of available topics.
 * @param delta Step direction (positive for next, negative for previous).
 * @return Clamped zero-based topic index.
 */
internal fun nextWalkthroughTopicIndex(currentIndex: Int, totalTopics: Int, delta: Int): Int =
    if (totalTopics <= 1) 0 else (currentIndex + delta).coerceIn(0, totalTopics - 1)

/**
 * Returns the next topic identifier during a live in-app walkthrough tour, or `null` when moving
 * past the end or before the beginning of [topics].
 *
 * @param topics Ordered list of role-filtered walkthrough topics.
 * @param currentId Currently shown coachmark topic ID.
 * @param delta Direction to advance (`+1` for Next, `-1` for Back).
 * @return Target topic ID, or `null` when out of bounds.
 */
internal fun nextWalkthroughTourTopicId(
    topics: List<WalkthroughTopic>,
    currentId: String,
    delta: Int,
): String? {
    val idx = topics.indexOfFirst { it.id == currentId }
    if (idx < 0) return null
    val next = idx + delta
    return if (next in topics.indices) topics[next].id else null
}
